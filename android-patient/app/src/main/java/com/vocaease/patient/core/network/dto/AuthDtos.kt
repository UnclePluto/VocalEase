package com.vocaease.patient.core.network.dto

import com.vocaease.patient.core.network.NetworkContractException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ClientKind {
    @SerialName("web") WEB,
    @SerialName("android") ANDROID,
}

@Serializable
enum class AccountRole {
    @SerialName("system_admin") SYSTEM_ADMIN,
    @SerialName("doctor") DOCTOR,
    @SerialName("patient") PATIENT,
}

@Serializable
data class LoginRequestDto(
    @SerialName("login_id") val loginId: String,
    val password: String,
    @SerialName("client_kind") val clientKind: ClientKind,
    @SerialName("remember_me") val rememberMe: Boolean = false,
)

@Serializable
data class RefreshRequestDto(
    @SerialName("client_kind") val clientKind: ClientKind,
    val refresh: String? = null,
)

@Serializable
data class ChangePasswordRequestDto(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class LogoutRequestDto(
    @SerialName("client_kind") val clientKind: ClientKind,
    val refresh: String? = null,
)

@Serializable
data class AccountSnapshotDto(
    @SerialName("login_id") val loginId: String,
    val role: AccountRole,
    @SerialName("must_change_password") val mustChangePassword: Boolean,
)

@Serializable
data class AuthTokensDto(
    val access: String,
    val refresh: String? = null,
    @SerialName("refresh_expires_at") val refreshExpiresAt: String,
    val user: AccountSnapshotDto,
)

data class AuthSession(
    val access: String,
    val refresh: String?,
    val refreshExpiresAt: Instant,
    val loginId: String,
    val role: AccountRole,
    val mustChangePassword: Boolean,
)

fun AuthTokensDto.toDomain(): AuthSession = AuthSession(
    access = access.requireNotBlank("access"),
    refresh = refresh?.requireNotBlank("refresh"),
    refreshExpiresAt = refreshExpiresAt.asInstant("refresh_expires_at"),
    loginId = user.loginId.requireNotBlank("user.login_id"),
    role = user.role,
    mustChangePassword = user.mustChangePassword,
)

internal fun String.asUuid(field: String): UUID = try {
    UUID.fromString(this)
} catch (error: IllegalArgumentException) {
    throw NetworkContractException("$field 不是有效 UUID", error)
}

internal fun String.asInstant(field: String): Instant = try {
    // 旧版 Android 的 Instant.parse 不接受服务端 isoformat() 输出的 +00:00。
    OffsetDateTime.parse(this).toInstant()
} catch (error: RuntimeException) {
    throw NetworkContractException("$field 不是有效 ISO-8601 时间", error)
}

internal fun String.asLocalDate(field: String): LocalDate = try {
    LocalDate.parse(this)
} catch (error: RuntimeException) {
    throw NetworkContractException("$field 不是有效 ISO-8601 日期", error)
}

internal fun String.requireNotBlank(field: String): String = also {
    if (isBlank()) throw NetworkContractException("$field 不能为空")
}
