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
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

internal interface VocaEaseApi {
    @POST("api/v1/auth/login/")
    suspend fun login(@Body request: LoginRequestDto): ApiEnvelope<AuthTokensDto>

    @POST("api/v1/auth/refresh/")
    suspend fun refresh(@Body request: RefreshRequestDto): ApiEnvelope<AuthTokensDto>

    @POST("api/v1/auth/change-password/")
    suspend fun changePassword(@Body request: ChangePasswordRequestDto): ApiEnvelope<EmptyDataDto>

    @POST("api/v1/auth/logout/")
    suspend fun logout(@Body request: LogoutRequestDto): ApiEnvelope<EmptyDataDto>

    @GET("api/v1/patient/me/")
    suspend fun patientMe(
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<PatientMeDto>

    @GET("api/v1/patient/songs/")
    suspend fun songs(
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null,
        @Query("keyword") keyword: String? = null,
        @Query("sort") sort: String? = null,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<SongPageDto>

    @GET("api/v1/patient/songs/{song_id}/")
    suspend fun song(
        @Path("song_id") songId: String,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<SongDto>

    @POST("api/v1/patient/songs/{song_id}/preview/")
    suspend fun previewSong(
        @Path("song_id") songId: String,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
        @Query("track") track: String = "source",
    ): ApiEnvelope<PrivateUrlDto>

    @POST("api/v1/patient/singing-sessions/{session_id}/song-playback/")
    suspend fun sessionSongPlayback(@Path("session_id") sessionId: String, @Query("track") track: String,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current): ApiEnvelope<com.vocaease.patient.core.network.dto.SongPlaybackGrantDto>

    @GET("api/v1/patient/songs/{song_id}/reference-pitch/")
    suspend fun referencePitch(@Path("song_id") songId: String, @Query("version") version: String?,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current): ApiEnvelope<com.vocaease.patient.core.network.dto.ReferencePitchDto>

    @GET("api/v1/patient/singing-sessions/")
    suspend fun sessions(
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null,
        @Query("status") status: SessionStatus? = null,
        @Query("created_from") createdFrom: String? = null,
        @Query("created_to") createdTo: String? = null,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<SessionPageDto>

    @POST("api/v1/patient/singing-sessions/")
    suspend fun createSession(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateSessionRequestDto,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<SingingSessionDto>

    @GET("api/v1/patient/singing-sessions/{session_id}/")
    suspend fun session(
        @Path("session_id") sessionId: String,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<SingingSessionDto>

    @POST("api/v1/patient/singing-sessions/{session_id}/upload-grants/")
    suspend fun sessionUploadGrant(
        @Path("session_id") sessionId: String,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
        @Body request: SessionUploadGrantRequestDto,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<SessionUploadGrantDto>

    @POST("api/v1/patient/media/upload-grants/")
    suspend fun patientMediaUploadGrant(
        @Body request: PatientMediaUploadGrantRequestDto,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<PatientMediaUploadGrantDto>

    @POST("api/v1/patient/singing-sessions/{session_id}/confirm-upload/")
    suspend fun confirmSessionMedia(
        @Path("session_id") sessionId: String,
        @Body request: ConfirmSessionMediaRequestDto,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<SingingSessionDto>

    @POST("api/v1/patient/singing-sessions/{session_id}/submit/")
    suspend fun submitSession(
        @Path("session_id") sessionId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
        @Body request: com.vocaease.patient.core.network.dto.SubmitSessionRequestDto = com.vocaease.patient.core.network.dto.SubmitSessionRequestDto(),
    ): ApiEnvelope<SessionMutationDto>

    @POST("api/v1/patient/singing-sessions/{session_id}/cancel/")
    suspend fun cancelSession(
        @Path("session_id") sessionId: String,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<SingingSessionDto>

    @POST("api/v1/patient/singing-sessions/{session_id}/retry/")
    suspend fun retrySession(
        @Path("session_id") sessionId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<SessionMutationDto>

    @POST("api/v1/patient/media/{asset_id}/private-url/")
    suspend fun patientMediaPrivateUrl(
        @Path("asset_id") assetId: String,
        @Tag authContext: AuthRequestContext = AuthRequestContext.Current,
    ): ApiEnvelope<PrivateUrlDto>
}
