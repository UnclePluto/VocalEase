package com.vocaease.patient.feature.upload

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
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
        val file = File(directory, randomName())
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        try {
            check(file.createNewFile())
            setOwnerOnly(file, directory = false)
            val copied = source.copy(kind, file)
            check(copied == media.sizeBytes && file.length() == media.sizeBytes) { "解密媒体长度不一致" }
            setOwnerOnly(file, directory = false)
            return Lease(file, directory)
        } catch (error: Exception) {
            file.delete()
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
        .joinToString("") { "%02x".format(it) } + ".upload"

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

        fun cleanupOrphans(root: File, nowEpochMillis: Long, activeOpaqueJobIds: Set<String> = emptySet()) {
            require(nowEpochMillis >= 0)
            if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root.toPath()) || !root.isDirectory) return
            val rootCanonical = root.canonicalFile
            root.listFiles().orEmpty().forEach { candidate ->
                if (candidate.name in activeOpaqueJobIds || !OPAQUE_JOB.matches(candidate.name) ||
                    Files.isSymbolicLink(candidate.toPath()) || !candidate.isDirectory || candidate.canonicalFile.parentFile != rootCanonical
                ) return@forEach
                val children = candidate.listFiles().orEmpty()
                if (children.any { Files.isSymbolicLink(it.toPath()) || !it.isFile }) return@forEach
                val newest = children.maxOfOrNull(File::lastModified) ?: candidate.lastModified()
                if (newest <= nowEpochMillis - ORPHAN_AGE_MILLIS) {
                    children.forEach { it.delete() }
                    deleteIfEmpty(candidate)
                }
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
