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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

fun interface UploadHostAllowlist {
    fun allows(host: String): Boolean
}

object OfficialQiniuUploadHostAllowlist : UploadHostAllowlist {
    private val OFFICIAL = Regex("(?:upload|up)(?:-[a-z0-9-]+)?\\.qiniup\\.com")
    override fun allows(host: String): Boolean = OFFICIAL.matches(host)
}

object QiniuV2Configuration {
    fun create(
        recorderDirectory: File,
        uploadUrl: String,
        allowlist: UploadHostAllowlist = OfficialQiniuUploadHostAllowlist,
    ): Configuration {
        require(recorderDirectory.isDirectory && !Files.isSymbolicLink(recorderDirectory.toPath()))
        val host = validatedHost(uploadUrl, allowlist)
        return Configuration.Builder()
            .useHttps(true)
            .accelerateUploading(false)
            .allowBackupHost(false)
            .zone(FixedZone(arrayOf(host)))
            .recorder(FileRecorder(recorderDirectory.absolutePath))
            .buildV2()
    }

    fun validatedHost(
        value: String,
        allowlist: UploadHostAllowlist = OfficialQiniuUploadHostAllowlist,
    ): String {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("上传地址无效") }
        val host = uri.host?.lowercase()
        require(
            uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null &&
                uri.path in setOf("", "/") && uri.port in setOf(-1, 443) &&
                !host.isNullOrBlank() && HOST.matches(host) && !IP_LITERAL.matches(host) && allowlist.allows(host),
        ) { "上传地址无效" }
        return "https://$host"
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

    fun forMedia(jobDirectory: File, kind: UploadMediaKind, sourceId: String): File {
        require(jobDirectory.isDirectory && !Files.isSymbolicLink(jobDirectory.toPath()))
        require(sourceId.isNotBlank())
        val jobCanonical = jobDirectory.canonicalFile
        val generation = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${kind.name}\u0000$sourceId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = File(jobCanonical, "${kind.name.lowercase()}-$generation").canonicalFile
        require(directory.parentFile == jobCanonical)
        require(!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS) || !Files.isSymbolicLink(directory.toPath()))
        directory.mkdirs()
        require(directory.isDirectory)
        setOwnerOnly(directory, directory = true)
        return directory
    }
}

internal sealed interface QiniuSdkResult {
    data class Completed(val key: String?, val responseKey: String?) : QiniuSdkResult
    data object Cancelled : QiniuSdkResult
    data object Failed : QiniuSdkResult
}

internal fun interface QiniuSdkEngine {
    fun put(
        request: QiniuUploadRequest,
        recorderDirectory: File,
        validatedUploadHost: String,
        onProgress: (Int) -> Unit,
        shouldCancel: () -> Boolean,
        callback: (QiniuSdkResult) -> Unit,
    )
}

private object RealQiniuSdkEngine : QiniuSdkEngine {
    override fun put(
        request: QiniuUploadRequest,
        recorderDirectory: File,
        validatedUploadHost: String,
        onProgress: (Int) -> Unit,
        shouldCancel: () -> Boolean,
        callback: (QiniuSdkResult) -> Unit,
    ) {
        val configuration = QiniuV2Configuration.create(
            recorderDirectory,
            validatedUploadHost,
            UploadHostAllowlist { true },
        )
        val options = UploadOptions(
            emptyMap(),
            request.mimeType,
            false,
            { _, percent -> onProgress((percent * 100).toInt().coerceIn(0, 100)) },
            shouldCancel,
        )
        UploadManager(configuration).put(
            request.file,
            request.grant.binding.objectKey,
            request.grant.uploadToken,
            { key, info, response ->
                callback(
                    when {
                        info.isCancelled -> QiniuSdkResult.Cancelled
                        !info.isOK -> QiniuSdkResult.Failed
                        else -> QiniuSdkResult.Completed(
                            key,
                            response?.takeIf { it.has("key") }?.optString("key"),
                        )
                    },
                )
            },
            options,
        )
    }
}

class QiniuV2Uploader internal constructor(
    private val recorderDirectory: File,
    private val allowlist: UploadHostAllowlist,
    private val sdkEngine: QiniuSdkEngine,
) : QiniuUploader {
    constructor(
        recorderDirectory: File,
        allowlist: UploadHostAllowlist = OfficialQiniuUploadHostAllowlist,
    ) : this(recorderDirectory, allowlist, RealQiniuSdkEngine)

    private val activeCancel = AtomicReference<(() -> Unit)?>(null)

    override suspend fun upload(request: QiniuUploadRequest, onProgress: (Int) -> Unit): QiniuUploadResult {
        require(request.file.isFile && !Files.isSymbolicLink(request.file.toPath()))
        require(request.file.length() == request.grant.binding.sizeBytes)
        require(request.mimeType == request.grant.binding.mimeType)
        val sourceId = "${request.file.name}_${request.file.lastModified()}"
        val mediaRecorderDirectory = QiniuRecorderDirectory.forMedia(recorderDirectory, request.kind, sourceId)
        val validatedUploadHost = QiniuV2Configuration.validatedHost(request.grant.uploadUrl, allowlist)
        return suspendCancellableCoroutine { continuation ->
            val cancelled = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            lateinit var cancelAction: () -> Unit
            fun complete(result: QiniuSdkResult) {
                if (!completed.compareAndSet(false, true)) return
                activeCancel.compareAndSet(cancelAction, null)
                if (!continuation.isActive) return
                when (result) {
                    QiniuSdkResult.Cancelled -> continuation.resume(QiniuUploadResult.Cancelled)
                    QiniuSdkResult.Failed -> continuation.resumeWithException(QiniuUploadException())
                    is QiniuSdkResult.Completed -> {
                        val expected = request.grant.binding.objectKey
                        if (result.key != expected || result.responseKey?.let { it != expected } == true) {
                            continuation.resumeWithException(QiniuUploadException())
                        } else {
                            continuation.resume(QiniuUploadResult.Completed(expected))
                        }
                    }
                }
            }
            cancelAction = {
                cancelled.set(true)
                complete(QiniuSdkResult.Cancelled)
            }
            check(activeCancel.compareAndSet(null, cancelAction)) { "同一上传器不能并发执行" }
            continuation.invokeOnCancellation {
                cancelled.set(true)
                activeCancel.compareAndSet(cancelAction, null)
            }
            sdkEngine.put(
                request,
                mediaRecorderDirectory,
                validatedUploadHost,
                { progress ->
                    if (!cancelled.get() && !completed.get() && continuation.isActive) onProgress(progress)
                },
                { cancelled.get() || completed.get() || !continuation.isActive },
                ::complete,
            )
        }
    }

    override fun cancel() {
        activeCancel.getAndSet(null)?.invoke()
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
