package com.vocaease.patient.core.media

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

data class RecordingStagingIdentity(
    val accountScopeHash: String,
    val draftId: String,
    val sessionId: String,
    val creationKey: String,
) {
    init {
        require(accountScopeHash.matches(Regex("[0-9a-f]{64}")))
        listOf(draftId, sessionId, creationKey).forEach {
            require(it.isNotBlank() && it.length <= 128 && '/' !in it && '\\' !in it && '\u0000' !in it)
        }
    }
}

data class RecoverableRecording(
    val identity: RecordingStagingIdentity,
    val video: File,
    val metadata: File,
    val createdAtMillis: Long,
)

class PrivateRecordingTempFiles internal constructor(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val orphanMaxAgeMillis: Long = DEFAULT_ORPHAN_MAX_AGE_MILLIS,
) : RecordingTempFiles {
    private val directory = File(context.filesDir, "recordings/plaintext")
    private val random = SecureRandom()

    init {
        require(orphanMaxAgeMillis >= 0)
        ensureDirectory()
    }

    override fun createVideo(): File = create("video-")

    override fun createVideo(identity: RecordingStagingIdentity): File {
        val name = "video-${randomHex(16)}"
        val video = createExact("$name$RECORDING_SUFFIX")
        val pending = createExact("$name$PENDING_SUFFIX")
        try {
            FileOutputStream(pending).use { raw ->
                DataOutputStream(raw).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeLong(nowMillis())
                    output.writeUTF(video.name)
                    output.writeUTF(identity.accountScopeHash)
                    output.writeUTF(identity.draftId)
                    output.writeUTF(identity.sessionId)
                    output.writeUTF(identity.creationKey)
                    output.flush()
                    raw.fd.sync()
                }
            }
            val metadata = File(directory, "$name$METADATA_SUFFIX")
            if (!pending.renameTo(metadata)) throw IOException("无法发布录制恢复元数据")
            Os.chmod(metadata.absolutePath, OWNER_FILE_MODE)
            syncDirectory()
            return video
        } catch (error: Exception) {
            pending.delete()
            video.delete()
            throw error
        }
    }

    override fun createAudio(): File = create("audio-")

    override fun cleanup(vararg files: File) {
        files.forEach { file ->
            if (!isDirectChild(file)) return@forEach
            runCatching {
                file.delete()
                if (file.name.startsWith("video-") && file.name.endsWith(RECORDING_SUFFIX)) {
                    val base = file.name.removeSuffix(RECORDING_SUFFIX)
                    File(directory, "$base$METADATA_SUFFIX").delete()
                    File(directory, "$base$PENDING_SUFFIX").delete()
                }
            }
        }
    }

    fun cleanup(recording: RecoverableRecording) {
        cleanup(recording.video)
        if (isDirectChild(recording.metadata)) recording.metadata.delete()
    }

    fun listRecoverable(accountScopeHash: String): List<RecoverableRecording> {
        require(accountScopeHash.matches(Regex("[0-9a-f]{64}")))
        ensureDirectory()
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && METADATA_NAME.matches(it.name) }
            .mapNotNull { metadata -> readMetadata(metadata) }
            .filter { it.identity.accountScopeHash == accountScopeHash }
            .sortedBy { it.createdAtMillis }
            .toList()
    }

    /**
     * 只清理没有恢复元数据配对的陈旧明文。配对录制由账户绑定的恢复扫描决定去留，
     * 因而应用启动时不会再像旧实现一样立即销毁可恢复录制。
     */
    fun cleanupOrphans() {
        ensureDirectory()
        val now = nowMillis()
        directory.listFiles().orEmpty()
            .filter { it.isFile && METADATA_NAME.matches(it.name) && isOlderThan(it.lastModified(), now) }
            .filter { readMetadata(it) == null }
            .forEach { metadata ->
                val base = metadata.name.removeSuffix(METADATA_SUFFIX)
                metadata.delete()
                File(directory, "$base$RECORDING_SUFFIX").delete()
            }
        directory.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || !file.name.endsWith(RECORDING_SUFFIX)) return@forEach
            val base = file.name.removeSuffix(RECORDING_SUFFIX)
            val paired = File(directory, "$base$METADATA_SUFFIX").isFile
            if (!paired && isOlderThan(file.lastModified(), now)) file.delete()
        }
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(PENDING_SUFFIX) && isOlderThan(it.lastModified(), now) }
            .forEach(File::delete)
    }

    private fun readMetadata(metadata: File): RecoverableRecording? = try {
        DataInputStream(FileInputStream(metadata)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("元数据版本无效")
            val createdAtMillis = input.readLong()
            val videoName = input.readUTF()
            val expectedBase = metadata.name.removeSuffix(METADATA_SUFFIX)
            if (videoName != "$expectedBase$RECORDING_SUFFIX") throw IOException("录制路径不匹配")
            val identity = RecordingStagingIdentity(
                accountScopeHash = input.readUTF(),
                draftId = input.readUTF(),
                sessionId = input.readUTF(),
                creationKey = input.readUTF(),
            )
            if (input.read() != -1) throw IOException("元数据存在尾随内容")
            val video = File(directory, videoName)
            if (!isDirectChild(video) || !video.isFile) throw IOException("录制文件不存在")
            RecoverableRecording(identity, video, metadata, createdAtMillis)
        }
    } catch (_: Exception) { null }

    private fun isOlderThan(lastModified: Long, now: Long): Boolean =
        lastModified in 0..now && now - lastModified > orphanMaxAgeMillis

    private fun create(prefix: String): File = createExact("$prefix${randomHex(16)}$RECORDING_SUFFIX")

    private fun createExact(name: String): File {
        ensureDirectory()
        val file = File(directory, name)
        if (!file.createNewFile()) throw IOException("无法创建录制临时文件")
        Os.chmod(file.absolutePath, OWNER_FILE_MODE)
        return file
    }

    private fun randomHex(byteCount: Int): String = ByteArray(byteCount).also(random::nextBytes)
        .joinToString("") { "%02x".format(it) }

    private fun isDirectChild(file: File): Boolean = runCatching {
        file.parentFile?.canonicalFile == directory.canonicalFile
    }.getOrDefault(false)

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("无法创建录制临时目录")
        }
        Os.chmod(directory.absolutePath, OWNER_DIRECTORY_MODE)
    }

    private fun syncDirectory() {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private companion object {
        const val RECORDING_SUFFIX = ".recording"
        const val METADATA_SUFFIX = ".recovery"
        const val PENDING_SUFFIX = ".recovery.pending"
        const val DEFAULT_ORPHAN_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        const val MAGIC = 0x56525331 // VRS1
        const val VERSION = 1
        val METADATA_NAME = Regex("video-[0-9a-f]{32}\\.recovery")
        val OWNER_FILE_MODE = OsConstants.S_IRUSR or OsConstants.S_IWUSR
        val OWNER_DIRECTORY_MODE = OWNER_FILE_MODE or OsConstants.S_IXUSR
    }
}
