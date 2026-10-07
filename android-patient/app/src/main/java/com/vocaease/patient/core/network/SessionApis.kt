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
    suspend fun previewSong(songId: String, track: String): ApiEnvelope<PrivateUrlDto> = previewSong(songId)
    suspend fun sessionSongPlayback(sessionId: String, track: String): ApiEnvelope<com.vocaease.patient.core.network.dto.SongPlaybackGrantDto> = error("会话播放未实现")
    suspend fun songLyrics(songId: String): ApiEnvelope<com.vocaease.patient.core.network.dto.SongLyricsDto> = error("歌词未实现")
    suspend fun referencePitch(songId: String, version: String?): ApiEnvelope<com.vocaease.patient.core.network.dto.ReferencePitchDto> = error("参考音高未实现")
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
    suspend fun submitSession(sessionId:String,idempotencyKey:String,metadata:com.vocaease.patient.core.media.PlaybackMetadata?):ApiEnvelope<SessionMutationDto> = submitSession(sessionId,idempotencyKey)
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
    private suspend fun <T> protectedCall(call: suspend VocaEaseApi.(AuthRequestContext) -> T): T =
        refreshCoordinator.executeAuthenticated { authContext -> raw.call(authContext) }

    override suspend fun patientMe() = protectedCall { authContext -> patientMe(authContext) }
    override suspend fun songs(page: Int?, pageSize: Int?, keyword: String?, sort: String?) =
        protectedCall { authContext -> songs(page, pageSize, keyword, sort, authContext) }
    override suspend fun song(songId: String) = protectedCall { authContext -> song(songId, authContext) }
    override suspend fun previewSong(songId: String, track: String) = protectedCall { context -> previewSong(songId, context, track) }
    override suspend fun sessionSongPlayback(sessionId: String, track: String) = protectedCall { context -> sessionSongPlayback(sessionId, track, context) }
    override suspend fun songLyrics(songId: String) = protectedCall { context -> songLyrics(songId, context) }
    override suspend fun referencePitch(songId: String, version: String?) = protectedCall { context -> referencePitch(songId, version, context) }
    override suspend fun previewSong(songId: String) =
        protectedCall { authContext -> previewSong(songId, authContext) }
    override suspend fun sessions(
        page: Int?,
        pageSize: Int?,
        status: SessionStatus?,
        createdFrom: String?,
        createdTo: String?,
    ) = protectedCall { authContext -> sessions(page, pageSize, status, createdFrom, createdTo, authContext) }
    override suspend fun createSession(idempotencyKey: String, request: CreateSessionRequestDto) =
        protectedCall { authContext -> createSession(idempotencyKey, request, authContext) }
    override suspend fun session(sessionId: String) = protectedCall { authContext -> session(sessionId, authContext) }
    override suspend fun sessionUploadGrant(
        sessionId: String,
        idempotencyKey: String?,
        request: SessionUploadGrantRequestDto,
    ) = protectedCall { authContext -> sessionUploadGrant(sessionId, idempotencyKey, request, authContext) }
    override suspend fun patientMediaUploadGrant(request: PatientMediaUploadGrantRequestDto) =
        protectedCall { authContext -> patientMediaUploadGrant(request, authContext) }
    override suspend fun confirmSessionMedia(sessionId: String, request: ConfirmSessionMediaRequestDto) =
        protectedCall { authContext -> confirmSessionMedia(sessionId, request, authContext) }
    override suspend fun submitSession(sessionId:String,idempotencyKey:String,metadata:com.vocaease.patient.core.media.PlaybackMetadata?) = protectedCall { context -> submitSession(sessionId,idempotencyKey,context,com.vocaease.patient.core.network.dto.SubmitSessionRequestDto(metadata)) }
    override suspend fun submitSession(sessionId: String, idempotencyKey: String) =
        protectedCall { authContext -> submitSession(sessionId, idempotencyKey, authContext) }
    override suspend fun cancelSession(sessionId: String) =
        protectedCall { authContext -> cancelSession(sessionId, authContext) }
    override suspend fun retrySession(sessionId: String, idempotencyKey: String) =
        protectedCall { authContext -> retrySession(sessionId, idempotencyKey, authContext) }
    override suspend fun patientMediaPrivateUrl(assetId: String) =
        protectedCall { authContext -> patientMediaPrivateUrl(assetId, authContext) }
}
