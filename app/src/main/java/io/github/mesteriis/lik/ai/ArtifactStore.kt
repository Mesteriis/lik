package io.github.mesteriis.lik.ai

import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.json.JSONObject
import android.system.Os

class VerifiedStaging internal constructor(internal val file: File, internal val sha256: String,
    internal val size: Long, internal val device: Long, internal val inode: Long,
    internal val mtimeSec: Long, internal val mtimeNsec: Long,
    internal val ctimeSec: Long, internal val ctimeNsec: Long)

class ArtifactStore(val root: File) {
    private data class FileIdentity(val device: Long, val inode: Long, val size: Long,
                                    val mtimeSec: Long, val mtimeNsec: Long,
                                    val ctimeSec: Long, val ctimeNsec: Long)
    val artifacts = File(root, "artifacts")
    val staging = File(root, "staging")
    private val shared = File(staging, "shared")
    private val operations = File(staging, "operations")
    private val receipts = File(root, "verification")
    private val knownInvalid = mutableMapOf<String, FileIdentity>()
    fun file(digest: String) = File(artifacts, digest)
    @Synchronized fun installed(digest: String, size: Long, checkpoint: () -> Unit = {}): Boolean {
        val target = file(digest)
        if (!target.isFile) return false
        val identity = identity(target)
        if (identity.size != size) { knownInvalid[digest] = identity; return false }
        if (receiptMatches(target, digest, size)) { knownInvalid.remove(digest); return true }
        if (sha256(target, checkpoint) != digest) { knownInvalid[digest] = identity; return false }
        writeReceipt(target, digest, size)
        knownInvalid.remove(digest)
        return true
    }
    fun availableBytes(): Long { root.mkdirs(); return StatFs(root.absolutePath).availableBytes }
    fun reserve(required: Long) { root.mkdirs(); require(required >= 0 && availableBytes() >= required) { "LOW_SPACE" } }
    fun operation(id: String) = File(operations, id).also(File::mkdirs)
    fun sharedPart(digest: String) = File(shared.also(File::mkdirs), "$digest.part")
    fun sharedJournal(digest: String) = File(shared.also(File::mkdirs), "$digest.json")
    fun prepareDownloadMetadata(operation: String, specs: List<ArtifactSpec>, catalogState: File) {
        listOf(root, artifacts, staging, shared, operations, File(operations, operation), receipts,
            File(root, "quarantine"), File(staging, "reservations")).forEach(File::mkdirs)
        PreallocatedMetadata.prepare(catalogState)
        PreallocatedMetadata.prepare(File(operations, operation).resolve("control"))
        specs.forEach { spec ->
            PreallocatedMetadata.prepare(sharedJournal(spec.sha256))
            PreallocatedMetadata.prepare(receipt(spec.sha256))
        }
        DownloadReservationLedger(root).prepareMetadata(operation)
        listOf(root, artifacts, staging, shared, operations, File(operations, operation), receipts,
            File(root, "quarantine"), File(staging, "reservations")).forEach(DurableAiFiles::syncDirectory)
    }
    fun prepareMissingTransferEntries(specs: List<ArtifactSpec>) {
        specs.forEach { spec ->
            listOf(sharedPart(spec.sha256), file(spec.sha256)).forEach { target ->
                if (!target.exists()) FileOutputStream(target).use { it.fd.sync() }
            }
        }
        DurableAiFiles.syncDirectory(shared)
        DurableAiFiles.syncDirectory(artifacts)
    }
    fun writeSharedJournal(spec: ArtifactSpec, stage: DownloadJournalStage, bytes: Long) {
        val value = JSONObject().put("schema", 1).put("path", spec.path).put("size", spec.size)
            .put("sha256", spec.sha256).put("url", spec.url.toString()).put("stage", stage.name).put("bytes", bytes)
        PreallocatedMetadata.write(sharedJournal(spec.sha256), value.toString().toByteArray())
    }
    @Synchronized fun repair(spec: ArtifactSpec, checkpoint: () -> Unit = {}): Boolean {
        val target = file(spec.sha256)
        if (!target.exists()) return false
        val current = identity(target)
        if (knownInvalid[spec.sha256] != current && installed(spec.sha256, spec.size, checkpoint)) return false
        val quarantine = File(root, "quarantine").also(File::mkdirs)
        val rejected = File(quarantine, "${spec.sha256}-${System.currentTimeMillis()}.corrupt")
        check(target.renameTo(rejected)) { "CORRUPT_ARTIFACT_QUARANTINE_FAILED" }
        receipt(spec.sha256).delete()
        knownInvalid.remove(spec.sha256)
        DurableAiFiles.syncDirectory(artifacts)
        DurableAiFiles.syncDirectory(quarantine)
        return true
    }
    fun verifyStaging(part: File, spec: ArtifactSpec, checkpoint: () -> Unit = {}): VerifiedStaging {
        require(part.length() == spec.size && sha256(part, checkpoint) == spec.sha256) { "HASH_MISMATCH" }
        val stat = Os.stat(part.absolutePath)
        return VerifiedStaging(part, spec.sha256, spec.size, stat.st_dev, stat.st_ino,
            stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec, stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec)
    }

    fun publish(verified: VerifiedStaging, spec: ArtifactSpec): File {
        require(verified.sha256 == spec.sha256 && verified.size == spec.size)
        val part = verified.file; val stat = Os.stat(part.absolutePath)
        require(stat.st_dev == verified.device && stat.st_ino == verified.inode && stat.st_size == verified.size &&
            stat.st_mtim.tv_sec == verified.mtimeSec && stat.st_mtim.tv_nsec == verified.mtimeNsec &&
            stat.st_ctim.tv_sec == verified.ctimeSec && stat.st_ctim.tv_nsec == verified.ctimeNsec) { "VERIFIED_STAGING_CHANGED" }
        artifacts.mkdirs()
        val target = file(spec.sha256)
        if (target.exists() && target.length() > 0) {
            require(installed(spec.sha256, spec.size)) { "ARTIFACT_COLLISION" }
            check(part.delete())
        } else Os.rename(part.absolutePath, target.absolutePath)
        DurableAiFiles.syncDirectory(artifacts)
        writeReceipt(target, spec.sha256, spec.size)
        knownInvalid.remove(spec.sha256)
        return target
    }
    fun cleanup(retained: Set<String>): Long {
        val artifactBytes = artifacts.listFiles().orEmpty().filter { it.name !in retained }.sumOf { file ->
        val size = file.length(); if (file.delete()) { receipt(file.name).delete(); size } else 0
        }
        val quarantineBytes = File(root, "quarantine").listFiles().orEmpty().sumOf { file ->
            val size = file.length(); if (file.delete()) size else 0
        }
        DurableAiFiles.syncDirectory(artifacts)
        DurableAiFiles.syncDirectory(receipts)
        DurableAiFiles.syncDirectory(File(root, "quarantine"))
        return artifactBytes + quarantineBytes
    }
    private fun receipt(digest: String) = File(receipts, "$digest.json")
    private fun identity(target: File) = Os.stat(target.absolutePath).let { stat -> FileIdentity(
        stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec,
        stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec) }
    private fun receiptMatches(target: File, digest: String, size: Long): Boolean = runCatching {
        val stat = Os.stat(target.absolutePath)
        val value = JSONObject(PreallocatedMetadata.read(receipt(digest)).toString(Charsets.UTF_8))
        value.getInt("schema") == 1 && value.getString("sha256") == digest && value.getLong("size") == size &&
            value.getLong("device") == stat.st_dev && value.getLong("inode") == stat.st_ino &&
            value.getLong("mtimeSec") == stat.st_mtim.tv_sec && value.getLong("mtimeNsec") == stat.st_mtim.tv_nsec &&
            value.getLong("ctimeSec") == stat.st_ctim.tv_sec && value.getLong("ctimeNsec") == stat.st_ctim.tv_nsec &&
            value.getString("sampleSha256") == sampleSha256(target)
    }.getOrDefault(false)

    private fun writeReceipt(target: File, digest: String, size: Long) {
        val stat = Os.stat(target.absolutePath)
        val value = JSONObject().put("schema", 1).put("sha256", digest).put("size", size)
            .put("device", stat.st_dev).put("inode", stat.st_ino)
            .put("mtimeSec", stat.st_mtim.tv_sec).put("mtimeNsec", stat.st_mtim.tv_nsec)
            .put("ctimeSec", stat.st_ctim.tv_sec).put("ctimeNsec", stat.st_ctim.tv_nsec)
            .put("sampleSha256", sampleSha256(target))
        PreallocatedMetadata.write(receipt(digest), value.toString().toByteArray())
    }

    /** Detects rapid same-size replacement on filesystems whose stat timestamps are coarse. */
    private fun sampleSha256(target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(target, "r").use { input ->
            val length = input.length()
            val positions = longArrayOf(0, (length / 2 - SAMPLE_BYTES / 2).coerceAtLeast(0),
                (length - SAMPLE_BYTES).coerceAtLeast(0)).distinct()
            val buffer = ByteArray(SAMPLE_BYTES)
            positions.forEach { position ->
                input.seek(position)
                val count = input.read(buffer).coerceAtLeast(0)
                digest.update(java.nio.ByteBuffer.allocate(12).putLong(position).putInt(count).array())
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun abandonShared(digests: Set<String>, operation: String) {
        val ledger = DownloadReservationLedger(root)
        digests.filter { ledger.owner(it).let { owner -> owner == null || owner == operation } }.forEach { digest ->
            sharedPart(digest).delete(); sharedJournal(digest).delete()
        }
        File(operations, operation).deleteRecursively()
        ledger.release(operation)
        DurableAiFiles.syncDirectory(shared)
        DurableAiFiles.syncDirectory(operations)
        DurableAiFiles.syncDirectory(staging)
    }
    companion object {
        private const val SAMPLE_BYTES = 4096
        fun sha256(file: File, checkpoint: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input -> val buffer = ByteArray(1024 * 1024); while (true) {
                checkpoint(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
            } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
