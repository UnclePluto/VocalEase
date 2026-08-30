package com.vocaease.patient.feature.upload

import com.qiniu.android.common.FixedZone
import com.qiniu.android.storage.Configuration
import com.qiniu.android.storage.FileRecorder
import com.qiniu.android.storage.UploadManager
import com.qiniu.android.storage.UploadOptions
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

object QiniuV2Configuration {
    fun create(recorderDirectory: File, uploadUrl: String): Configuration {
        require(recorderDirectory.isDirectory && !Files.isSymbolicLink(recorderDirectory.toPath()))
        val host = validatedHost(uploadUrl)
        return Configuration.Builder()
            .useHttps(true)
            .accelerateUploading(false)
            .allowBackupHost(false)
            .zone(FixedZone(arrayOf(host)))
            .recorder(FileRecorder(recorderDirectory.absolutePath))
            .buildV2()
    }

    fun validatedHost(value: String): String {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("上传地址无效") }
        require(
            uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null &&
                uri.path in setOf("", "/") && uri.port in setOf(-1, 443) &&
                !uri.host.isNullOrBlank() && HOST.matches(uri.host) && !IP_LITERAL.matches(uri.host),
        ) { "上传地址无效" }
        return "https://${uri.host.lowercase()}"
    }

    private val HOST = Regex("(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}")
    private val IP_LITERAL = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
}

object QiniuRecorderDirectory {
    fun create(root: File, accountScopeHash: String, draftId: String): File {
        require(accountScopeHash.matches(Regex("[0-9a-f]{64}")))
        require(draftId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(!Files.isSymbolicLink(root.toPath())) { "断点目录不可为符号链接" }
        root.mkdirs()
        require(root.isDirectory)
        val rootCanonical = root.canonicalFile
        val opaqueJob = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$accountScopeHash\u0000$draftId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = File(rootCanonical, opaqueJob).canonicalFile
        require(directory.path.startsWith(rootCanonical.path + File.separator))
        require(!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS) || !Files.isSymbolicLink(directory.toPath()))
        directory.mkdirs()
        require(directory.isDirectory)
        setOwnerOnly(directory, directory = true)
        return directory
    }
}

class QiniuV2Uploader(
    private val recorderDirectory: File,
) : QiniuUploader {
    private val cancelled = AtomicBoolean(false)

    override suspend fun upload(request: QiniuUploadRequest, onProgress: (Int) -> Unit): QiniuUploadResult {
        cancelled.set(false)
        require(request.file.isFile && !Files.isSymbolicLink(request.file.toPath()))
        require(request.file.length() == request.grant.binding.sizeBytes)
        require(request.mimeType == request.grant.binding.mimeType)
        val manager = UploadManager(QiniuV2Configuration.create(recorderDirectory, request.grant.uploadUrl))
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancelled.set(true) }
            val options = UploadOptions(
                emptyMap(),
                request.mimeType,
                false,
                { _, percent -> onProgress((percent * 100).toInt().coerceIn(0, 100)) },
                { cancelled.get() || !continuation.isActive },
            )
            manager.put(
                request.file,
                request.grant.binding.objectKey,
                request.grant.uploadToken,
                { key, info, response ->
                    if (!continuation.isActive) return@put
                    when {
                        info.isCancelled || cancelled.get() -> continuation.resume(QiniuUploadResult.Cancelled)
                        !info.isOK -> continuation.resumeWithException(QiniuUploadException())
                        key != request.grant.binding.objectKey -> continuation.resumeWithException(QiniuUploadException())
                        response != null && response.has("key") && response.optString("key") != request.grant.binding.objectKey ->
                            continuation.resumeWithException(QiniuUploadException())
                        else -> continuation.resume(QiniuUploadResult.Completed(request.grant.binding.objectKey))
                    }
                },
                options,
            )
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }
}

class QiniuUploadException internal constructor() : java.io.IOException("对象上传失败")

internal fun setOwnerOnly(file: File, directory: Boolean) {
    val permissions = if (directory) {
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    } else {
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }
    runCatching { Files.setPosixFilePermissions(file.toPath(), permissions) }.getOrElse {
        check(file.setReadable(false, false) && file.setWritable(false, false))
        check(file.setReadable(true, true) && file.setWritable(true, true))
        if (directory) check(file.setExecutable(true, true))
    }
}
