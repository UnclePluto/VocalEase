package com.vocaease.patient.feature.upload

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

fun interface UploadPlaintextSource {
    suspend fun copy(kind: UploadMediaKind, destination: File): Long
}

class PlaintextUploadLeaseManager(
    private val root: File,
    private val opaqueJobId: String,
    private val source: UploadPlaintextSource,
    private val nowEpochMillis: () -> Long,
) : PlaintextUploadLeaseProvider {
    init {
        require(opaqueJobId.matches(OPAQUE_JOB))
    }

    override suspend fun open(kind: UploadMediaKind, media: UploadMedia): PlaintextUploadLease {
        val directory = privateJobDirectory()
        val staging = File(directory, randomName())
        var stable: File? = null
        require(staging.canonicalFile.parentFile == directory.canonicalFile)
        try {
            check(staging.createNewFile())
            setOwnerOnly(staging, directory = false)
            val copied = source.copy(kind, staging)
            check(copied == media.sizeBytes && staging.length() == media.sizeBytes) { "解密媒体长度不一致" }
            val contentSha256 = sha256(staging)
            val generation = sha256("$opaqueJobId\u0000${kind.name}\u0000${media.mimeType}\u0000${media.sizeBytes}\u0000$contentSha256")
            stable = File(directory, "${kind.name.lowercase()}-$generation.upload")
            require(stable.canonicalFile.parentFile == directory.canonicalFile)
            if (Files.exists(stable.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(stable.toPath()) && stable.isFile) { "上传明文目标无效" }
                check(stable.delete()) { "无法替换遗留上传明文" }
            }
            check(staging.renameTo(stable)) { "无法建立稳定上传明文" }
            val stableMtime = STABLE_MTIME_BASE_MILLIS
            check(stable.setLastModified(stableMtime) && stable.lastModified() == stableMtime) { "无法设置稳定上传源时间" }
            setOwnerOnly(stable, directory = false)
            return Lease(stable, directory)
        } catch (error: Exception) {
            staging.delete()
            stable?.delete()
            deleteIfEmpty(directory)
            throw error
        }
    }

    private fun privateJobDirectory(): File {
        require(!Files.isSymbolicLink(root.toPath()))
        root.mkdirs()
        require(root.isDirectory)
        setOwnerOnly(root, directory = true)
        val rootCanonical = root.canonicalFile
        val directory = File(rootCanonical, opaqueJobId)
        require(!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS) || !Files.isSymbolicLink(directory.toPath()))
        directory.mkdirs()
        require(directory.isDirectory && directory.canonicalFile.parentFile == rootCanonical)
        setOwnerOnly(directory, directory = true)
        return directory
    }

    private fun randomName(): String = ByteArray(16).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) } + ".staging"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private class Lease(
        override val file: File,
        private val directory: File,
    ) : PlaintextUploadLease {
        @Volatile private var closed = false
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            if (file.exists()) check(file.delete()) { "无法清理上传临时文件" }
            deleteIfEmpty(directory)
        }
    }

    companion object {
        private val OPAQUE_JOB = Regex("[0-9a-f]{64}")
        private const val ORPHAN_AGE_MILLIS = 60L * 60 * 1_000
        private const val STABLE_MTIME_BASE_MILLIS = 946_684_800_000L

        fun cleanupOrphans(root: File, nowEpochMillis: Long, activeOpaqueJobIds: Set<String> = emptySet()) {
            require(nowEpochMillis >= 0)
            if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root.toPath()) || !root.isDirectory) return
            val rootCanonical = root.canonicalFile
            root.listFiles().orEmpty().forEach { candidate ->
                if (!OPAQUE_JOB.matches(candidate.name) || Files.isSymbolicLink(candidate.toPath()) ||
                    !candidate.isDirectory || candidate.canonicalFile.parentFile != rootCanonical
                ) return@forEach
                val children = candidate.listFiles().orEmpty()
                if (children.all { it.isFile && !Files.isSymbolicLink(it.toPath()) }) {
                    cleanupJobDirectory(candidate, nowEpochMillis, activeOpaqueJobIds)
                } else if (children.all {
                        it.isDirectory && !Files.isSymbolicLink(it.toPath()) &&
                            OPAQUE_JOB.matches(it.name) && it.canonicalFile.parentFile == candidate.canonicalFile
                    }
                ) {
                    children.forEach { cleanupJobDirectory(it, nowEpochMillis, activeOpaqueJobIds) }
                    deleteIfEmpty(candidate)
                }
            }
        }

        private fun cleanupJobDirectory(
            candidate: File,
            nowEpochMillis: Long,
            activeOpaqueJobIds: Set<String>,
        ) {
            if (candidate.name in activeOpaqueJobIds) return
            val children = candidate.listFiles().orEmpty()
            if (children.any { Files.isSymbolicLink(it.toPath()) || !it.isFile }) return
            // 文件 mtime 必须稳定以匹配七牛的跨进程 sourceId；遗留年龄因此以 job 目录更新时间为准。
            if (candidate.lastModified() <= nowEpochMillis - ORPHAN_AGE_MILLIS) {
                children.forEach { check(it.delete()) { "无法清理上传明文孤儿" } }
                deleteIfEmpty(candidate)
            }
        }

        private fun deleteIfEmpty(directory: File) {
            if (directory.isDirectory && directory.listFiles().orEmpty().isEmpty()) directory.delete()
        }
    }
}

class RevocablePlaintextLeaseProvider(
    private val delegate: PlaintextUploadLeaseProvider,
) : PlaintextUploadLeaseProvider {
    private val lock = Any()
    private val active = mutableSetOf<TrackedLease>()
    private var revoked = false

    override suspend fun open(kind: UploadMediaKind, media: UploadMedia): PlaintextUploadLease {
        synchronized(lock) { if (revoked) throw CancellationException("上传明文源已撤销") }
        val opened = delegate.open(kind, media)
        val tracked = TrackedLease(opened)
        synchronized(lock) {
            if (revoked) {
                opened.close()
                throw CancellationException("上传明文源已撤销")
            }
            active += tracked
        }
        return tracked
    }

    fun revokeAll() {
        val snapshot = synchronized(lock) {
            revoked = true
            active.toList().also { active.clear() }
        }
        snapshot.forEach(TrackedLease::close)
    }

    private inner class TrackedLease(
        private val delegate: PlaintextUploadLease,
    ) : PlaintextUploadLease {
        override val file: File get() = delegate.file
        @Volatile private var closed = false

        @Synchronized override fun close() {
            if (closed) return
            closed = true
            synchronized(lock) { active -= this }
            delegate.close()
        }
    }
}
