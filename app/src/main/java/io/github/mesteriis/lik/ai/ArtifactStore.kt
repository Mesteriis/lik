package io.github.mesteriis.lik.ai

import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ArtifactStore(val root: File) {
    val artifacts = File(root, "artifacts")
    val staging = File(root, "staging")
    private val shared = File(staging, "shared")
    private val operations = File(staging, "operations")
    fun file(digest: String) = File(artifacts, digest)
    fun installed(digest: String, size: Long, checkpoint: () -> Unit = {}) =
        file(digest).let { it.isFile && it.length() == size && sha256(it, checkpoint) == digest }
    fun availableBytes(): Long { root.mkdirs(); return StatFs(root.absolutePath).availableBytes }
    fun reserve(required: Long) { root.mkdirs(); require(required >= 0 && availableBytes() >= required) { "LOW_SPACE" } }
    fun operation(id: String) = File(operations, id).also(File::mkdirs)
    fun sharedPart(digest: String) = File(shared.also(File::mkdirs), "$digest.part")
    fun sharedJournal(digest: String) = File(shared.also(File::mkdirs), "$digest.json")
    fun repair(spec: ArtifactSpec, checkpoint: () -> Unit = {}): Boolean {
        val target = file(spec.sha256)
        if (!target.exists() || installed(spec.sha256, spec.size, checkpoint)) return false
        val quarantine = File(root, "quarantine").also(File::mkdirs)
        val rejected = File(quarantine, "${spec.sha256}-${System.currentTimeMillis()}.corrupt")
        check(target.renameTo(rejected)) { "CORRUPT_ARTIFACT_QUARANTINE_FAILED" }
        DurableAiFiles.syncDirectory(artifacts)
        DurableAiFiles.syncDirectory(quarantine)
        return true
    }
    fun publish(part: File, spec: ArtifactSpec, checkpoint: () -> Unit = {}): File {
        require(part.length() == spec.size && sha256(part, checkpoint) == spec.sha256) { "HASH_MISMATCH" }
        artifacts.mkdirs()
        val target = file(spec.sha256)
        if (target.exists()) {
            require(installed(spec.sha256, spec.size)) { "ARTIFACT_COLLISION" }
            check(part.delete())
        } else check(part.renameTo(target)) { "PUBLISH_FAILED" }
        DurableAiFiles.syncDirectory(artifacts)
        return target
    }
    fun cleanup(retained: Set<String>): Long {
        val artifactBytes = artifacts.listFiles().orEmpty().filter { it.name !in retained }.sumOf { file ->
        val size = file.length(); if (file.delete()) size else 0
        }
        val quarantineBytes = File(root, "quarantine").listFiles().orEmpty().sumOf { file ->
            val size = file.length(); if (file.delete()) size else 0
        }
        DurableAiFiles.syncDirectory(artifacts)
        DurableAiFiles.syncDirectory(File(root, "quarantine"))
        return artifactBytes + quarantineBytes
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
        fun sha256(file: File, checkpoint: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input -> val buffer = ByteArray(1024 * 1024); while (true) {
                checkpoint(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
            } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
