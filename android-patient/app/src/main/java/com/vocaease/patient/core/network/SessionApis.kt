package com.vocaease.patient.core.network

import com.vocaease.patient.core.network.dto.AuthTokensDto
import com.vocaease.patient.core.network.dto.ChangePasswordRequestDto
import com.vocaease.patient.core.network.dto.ConfirmSessionMediaRequestDto
import com.vocaease.patient.core.network.dto.CreateSessionRequestDto
import com.vocaease.patient.core.network.dto.LoginRequestDto
import com.vocaease.patient.core.network.dto.LogoutRequestDto
import com.vocaease.patient.core.network.dto.PatientMeDto
import com.vocaease.patient.core.network.dto.PatientMediaUploadGrantDto
import com.vocaease.patient.core.network.dto.PatientMediaUploadGrantRequestDto
import com.vocaease.patient.core.network.dto.PrivateUrlDto
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.network.dto.SessionMutationDto
import com.vocaease.patient.core.network.dto.SessionPageDto
import com.vocaease.patient.core.network.dto.SessionStatus
import com.vocaease.patient.core.network.dto.SessionUploadGrantDto
import com.vocaease.patient.core.network.dto.SessionUploadGrantRequestDto
import com.vocaease.patient.core.network.dto.SingingSessionDto
import com.vocaease.patient.core.network.dto.SongDto
import com.vocaease.patient.core.network.dto.SongPageDto

/** 认证端点永不进入 401 自动刷新，change/logout 仅由 AuthInterceptor 附加当前 access。 */
interface AuthApi {
    suspend fun login(request: LoginRequestDto): ApiEnvelope<AuthTokensDto>
    suspend fun refresh(request: RefreshRequestDto): ApiEnvelope<AuthTokensDto>
    suspend fun changePassword(request: ChangePasswordRequestDto): ApiEnvelope<EmptyDataDto>
    suspend fun logout(request: LogoutRequestDto): ApiEnvelope<EmptyDataDto>
}

/** 患者业务仓库唯一可依赖的受保护 API；所有方法统一执行一次 401 刷新/重试。 */
interface PatientApi {
    suspend fun patientMe(): ApiEnvelope<PatientMeDto>
    suspend fun songs(page: Int? = null, pageSize: Int? = null, keyword: String? = null, sort: String? = null): ApiEnvelope<SongPageDto>
    suspend fun song(songId: String): ApiEnvelope<SongDto>
    suspend fun previewSong(songId: String): ApiEnvelope<PrivateUrlDto>
    suspend fun sessions(
        page: Int? = null,
        pageSize: Int? = null,
        status: SessionStatus? = null,
        createdFrom: String? = null,
        createdTo: String? = null,
    ): ApiEnvelope<SessionPageDto>
    suspend fun createSession(idempotencyKey: String, request: CreateSessionRequestDto): ApiEnvelope<SingingSessionDto>
    suspend fun session(sessionId: String): ApiEnvelope<SingingSessionDto>
    suspend fun sessionUploadGrant(
        sessionId: String,
        idempotencyKey: String? = null,
        request: SessionUploadGrantRequestDto,
    ): ApiEnvelope<SessionUploadGrantDto>
    suspend fun patientMediaUploadGrant(request: PatientMediaUploadGrantRequestDto): ApiEnvelope<PatientMediaUploadGrantDto>
    suspend fun confirmSessionMedia(sessionId: String, request: ConfirmSessionMediaRequestDto): ApiEnvelope<SingingSessionDto>
    suspend fun submitSession(sessionId: String, idempotencyKey: String): ApiEnvelope<SessionMutationDto>
    suspend fun cancelSession(sessionId: String): ApiEnvelope<SingingSessionDto>
    suspend fun retrySession(sessionId: String, idempotencyKey: String): ApiEnvelope<SessionMutationDto>
    suspend fun patientMediaPrivateUrl(assetId: String): ApiEnvelope<PrivateUrlDto>
}

internal class RawAuthApi(
    private val raw: VocaEaseApi,
) : AuthApi {
    override suspend fun login(request: LoginRequestDto) = raw.login(request)
    override suspend fun refresh(request: RefreshRequestDto) = raw.refresh(request)
    override suspend fun changePassword(request: ChangePasswordRequestDto) = raw.changePassword(request)
    override suspend fun logout(request: LogoutRequestDto) = raw.logout(request)
}

internal class RefreshingPatientApi(
    private val raw: VocaEaseApi,
    private val refreshCoordinator: RefreshCoordinator,
) : PatientApi {
    private suspend fun <T> protectedCall(call: suspend VocaEaseApi.() -> T): T =
        refreshCoordinator.executeAuthenticated { raw.call() }

    override suspend fun patientMe() = protectedCall { patientMe() }
    override suspend fun songs(page: Int?, pageSize: Int?, keyword: String?, sort: String?) =
        protectedCall { songs(page, pageSize, keyword, sort) }
    override suspend fun song(songId: String) = protectedCall { song(songId) }
    override suspend fun previewSong(songId: String) = protectedCall { previewSong(songId) }
    override suspend fun sessions(
        page: Int?,
        pageSize: Int?,
        status: SessionStatus?,
        createdFrom: String?,
        createdTo: String?,
    ) = protectedCall { sessions(page, pageSize, status, createdFrom, createdTo) }
    override suspend fun createSession(idempotencyKey: String, request: CreateSessionRequestDto) =
        protectedCall { createSession(idempotencyKey, request) }
    override suspend fun session(sessionId: String) = protectedCall { session(sessionId) }
    override suspend fun sessionUploadGrant(
        sessionId: String,
        idempotencyKey: String?,
        request: SessionUploadGrantRequestDto,
    ) = protectedCall { sessionUploadGrant(sessionId, idempotencyKey, request) }
    override suspend fun patientMediaUploadGrant(request: PatientMediaUploadGrantRequestDto) =
        protectedCall { patientMediaUploadGrant(request) }
    override suspend fun confirmSessionMedia(sessionId: String, request: ConfirmSessionMediaRequestDto) =
        protectedCall { confirmSessionMedia(sessionId, request) }
    override suspend fun submitSession(sessionId: String, idempotencyKey: String) =
        protectedCall { submitSession(sessionId, idempotencyKey) }
    override suspend fun cancelSession(sessionId: String) = protectedCall { cancelSession(sessionId) }
    override suspend fun retrySession(sessionId: String, idempotencyKey: String) =
        protectedCall { retrySession(sessionId, idempotencyKey) }
    override suspend fun patientMediaPrivateUrl(assetId: String) = protectedCall { patientMediaPrivateUrl(assetId) }
}
