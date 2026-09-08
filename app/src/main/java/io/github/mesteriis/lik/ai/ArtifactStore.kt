package io.github.mesteriis.lik.ai

import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ArtifactStore(private val root: File) {
    val artifacts = File(root, "artifacts")
    val staging = File(root, "staging")
    fun file(digest: String) = File(artifacts, digest)
    fun installed(digest: String, size: Long) = file(digest).let { it.isFile && it.length() == size && sha256(it) == digest }
    fun availableBytes(): Long = StatFs(root.absolutePath).availableBytes
    fun reserve(required: Long) { root.mkdirs(); require(required >= 0 && availableBytes() >= required) { "LOW_SPACE" } }
    fun operation(id: String) = File(staging, id).also(File::mkdirs)
    fun publish(part: File, spec: ArtifactSpec): File {
        require(part.length() == spec.size && sha256(part) == spec.sha256) { "HASH_MISMATCH" }
        artifacts.mkdirs()
        val target = file(spec.sha256)
        if (target.exists()) {
            require(installed(spec.sha256, spec.size)) { "ARTIFACT_COLLISION" }
            check(part.delete())
        } else check(part.renameTo(target)) { "PUBLISH_FAILED" }
        return target
    }
    fun cleanup(retained: Set<String>): Long = artifacts.listFiles().orEmpty().filter { it.name !in retained }.sumOf { file ->
        val size = file.length(); if (file.delete()) size else 0
    }
    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input -> val buffer = ByteArray(1024 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
