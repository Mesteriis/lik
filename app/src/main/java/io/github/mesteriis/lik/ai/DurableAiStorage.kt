package io.github.mesteriis.lik.ai

import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock

object DurableAiFiles {
    fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}-${System.nanoTime()}.tmp")
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        check(temporary.renameTo(target)) { "ATOMIC_WRITE_FAILED" }
        syncDirectory(target.parentFile!!)
    }

    fun syncDirectory(directory: File) {
        runCatching {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }

    fun replaceDirectory(staged: File, target: File) {
        require(staged.isDirectory)
        val parent = requireNotNull(target.parentFile).also(File::mkdirs)
        syncDirectory(staged)
        val previous = File(parent, ".${target.name}-${System.nanoTime()}.previous")
        if (target.exists()) {
            check(target.renameTo(previous)) { "INDEX_PREVIOUS_MOVE_FAILED" }
            syncDirectory(parent)
        }
        try {
            check(staged.renameTo(target)) { "INDEX_PUBLISH_FAILED" }
            syncDirectory(parent)
        } catch (error: Throwable) {
            if (!target.exists() && previous.exists()) previous.renameTo(target)
            syncDirectory(parent)
            throw error
        }
        if (previous.exists()) previous.deleteRecursively()
        syncDirectory(parent)
    }
}

/** Serializes all profile transfers in-process; the file lock covers crash/restart and future processes. */
object DownloadCoordinator {
    private val lock = ReentrantLock(true)
    fun <T> run(root: File, block: () -> T): T {
        lock.lockInterruptibly()
        return try {
            root.mkdirs()
            RandomAccessFile(File(root, "download-coordinator.lock"), "rw").channel.use { channel ->
                channel.lock().use { block() }
            }
        } finally { lock.unlock() }
    }
}

/** Durable reservation and shared-digest ownership. Existing partial bytes are already charged by StatFs. */
class DownloadReservationLedger(private val root: File) {
    private val file = File(root, "download-reservations-v1.json")
    private val reservationDirectory = File(root, "staging/reservations")

    fun acquire(operation: String, files: List<Pair<ArtifactSpec, Long>>, availableBytes: Long, safetyMargin: Long): DownloadReservationPlan {
        val plan = DownloadReservationPlan.create(operation, files, safetyMargin)
        val state = read()
        val owners = state.first.toMutableMap()
        val reservations = state.second.toMutableMap()
        reservations.remove(operation)
        plan.remainingByDigest.keys.forEach { digest ->
            val previous = owners.put(digest, operation)
            if (previous != null && previous != operation) {
                reservations[previous] = reservations[previous].orEmpty() - digest
            }
        }
        reservations[operation] = plan.remainingByDigest + (MARGIN to safetyMargin)
        val currentReservation = reservationFile(operation).takeIf(File::isFile)?.length() ?: 0L
        require(availableBytes >= (plan.requiredBytes - currentReservation).coerceAtLeast(0)) { "LOW_SPACE" }
        write(owners, reservations.filterValues { it.isNotEmpty() })
        reservations.keys.forEach { resizeReservation(it, reservations.getValue(it).values.sum()) }
        return plan
    }

    fun update(operation: String, digest: String, remaining: Long) {
        require(remaining >= 0)
        val state = read(); val reservations = state.second.toMutableMap()
        val files = reservations[operation].orEmpty().toMutableMap()
        if (remaining == 0L) files.remove(digest) else files[digest] = remaining
        reservations[operation] = files
        write(state.first, reservations.filterValues { it.isNotEmpty() })
        resizeReservation(operation, files.values.sum())
    }

    fun release(operation: String) {
        val state = read()
        val owners = state.first.filterValues { it != operation }
        val reservations = state.second - operation
        write(owners, reservations)
        reservationFile(operation).delete()
        DurableAiFiles.syncDirectory(reservationDirectory)
    }

    fun owner(digest: String): String? = read().first[digest]

    fun reservedBytes(operation: String): Long = reservationFile(operation).takeIf(File::isFile)?.length() ?: 0

    private fun reservationFile(operation: String): File {
        require(operation.matches(Regex("[A-Za-z0-9._-]+")))
        return File(reservationDirectory, "$operation.reserve")
    }

    private fun resizeReservation(operation: String, bytes: Long) {
        reservationDirectory.mkdirs()
        val target = reservationFile(operation)
        if (bytes == 0L) { target.delete(); DurableAiFiles.syncDirectory(reservationDirectory); return }
        RandomAccessFile(target, "rw").use { reservation ->
            if (reservation.length() < bytes) Os.posix_fallocate(reservation.fd, 0, bytes)
            reservation.setLength(bytes)
            reservation.fd.sync()
        }
        val stat = Os.stat(target.absolutePath)
        require(stat.st_size == bytes && stat.st_blocks * 512 >= bytes) { "SPACE_RESERVATION_NOT_ALLOCATED" }
        DurableAiFiles.syncDirectory(reservationDirectory)
    }

    private fun read(): Pair<Map<String, String>, Map<String, Map<String, Long>>> = runCatching {
        if (!file.isFile) return@runCatching emptyMap<String, String>() to emptyMap()
        val root = JSONObject(file.readText())
        require(root.getInt("schema") == 1)
        val ownersObject = root.getJSONObject("owners")
        val owners = ownersObject.keys().asSequence().associateWith { ownersObject.getString(it) }
        val reservationsObject = root.getJSONObject("reservations")
        val reservations = reservationsObject.keys().asSequence().associateWith { operation ->
            val entries = reservationsObject.getJSONObject(operation)
            entries.keys().asSequence().associateWith { entries.getLong(it) }
        }
        owners to reservations
    }.getOrElse {
        file.takeIf(File::exists)?.let {
            it.renameTo(File(root, "download-reservations-corrupt-${System.currentTimeMillis()}.json"))
            DurableAiFiles.syncDirectory(root)
        }
        emptyMap<String, String>() to emptyMap()
    }

    private fun write(owners: Map<String, String>, reservations: Map<String, Map<String, Long>>) {
        val value = JSONObject().put("schema", 1)
            .put("owners", JSONObject().apply { owners.forEach(::put) })
            .put("reservations", JSONObject().apply { reservations.forEach { (operation, files) ->
                put(operation, JSONObject().apply { files.forEach(::put) })
            } })
        DurableAiFiles.atomicWrite(file, value.toString().toByteArray())
    }

    companion object { private const val MARGIN = "@safety-margin" }
}

class GenerationRemovalJournal(private val root: File) {
    private val directory = File(root, "generation-removals")
    fun begin(profile: ProfileId, ids: Set<String>) {
        if (ids.isEmpty()) return
        val value = JSONObject().put("schema", 1).put("profile", profile.wire)
            .put("ids", org.json.JSONArray(ids.sorted()))
        DurableAiFiles.atomicWrite(File(directory, "${profile.wire}.json"), value.toString().toByteArray())
    }
    fun pending(): List<Pair<ProfileId, Set<String>>> = directory.listFiles().orEmpty().map { file ->
        val value = JSONObject(file.readText()); require(value.getInt("schema") == 1)
        val array = value.getJSONArray("ids")
        ProfileId.fromWire(value.getString("profile")) to List(array.length()) { array.getString(it) }.toSet()
    }
    fun ids(): Set<String> = pending().flatMap { it.second }.toSet()
    fun finish(profile: ProfileId) {
        File(directory, "${profile.wire}.json").delete()
        DurableAiFiles.syncDirectory(directory)
    }
}
