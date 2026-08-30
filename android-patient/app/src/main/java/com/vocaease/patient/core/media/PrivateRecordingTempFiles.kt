package com.vocaease.patient.core.media

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File

class PrivateRecordingTempFiles internal constructor(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val orphanMaxAgeMillis: Long = DEFAULT_ORPHAN_MAX_AGE_MILLIS,
) : RecordingTempFiles {
    private val directory = File(context.filesDir, "recordings/plaintext")

    init {
        require(orphanMaxAgeMillis >= 0)
        ensureDirectory()
    }

    override fun createVideo(): File = create("video-")
    override fun createAudio(): File = create("audio-")

    override fun cleanup(vararg files: File) {
        files.forEach { file ->
            runCatching {
                if (file.parentFile?.canonicalFile == directory.canonicalFile && file.name.endsWith(SUFFIX)) {
                    file.delete()
                }
            }
        }
    }

    fun cleanupOrphans() {
        ensureDirectory()
        val threshold = nowMillis() - orphanMaxAgeMillis
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(SUFFIX) &&
                (orphanMaxAgeMillis == 0L || file.lastModified() <= threshold)
            ) file.delete()
        }
    }

    private fun create(prefix: String): File {
        ensureDirectory()
        return File.createTempFile(prefix, SUFFIX, directory).also { file ->
            Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw java.io.IOException("无法创建录制临时目录")
        }
        Os.chmod(
            directory.absolutePath,
            OsConstants.S_IRUSR or OsConstants.S_IWUSR or OsConstants.S_IXUSR,
        )
    }

    private companion object {
        const val SUFFIX = ".recording"
        const val DEFAULT_ORPHAN_MAX_AGE_MILLIS = 0L
    }
}
