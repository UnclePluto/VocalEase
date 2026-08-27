package com.vocaease.patient

import com.vocaease.patient.core.network.NetworkDiagnostic
import com.vocaease.patient.core.network.NetworkModule
import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.RawAuthApi
import com.vocaease.patient.core.network.RefreshCoordinator
import com.vocaease.patient.core.network.RefreshingPatientApi
import com.vocaease.patient.core.network.SessionLifecycleEvent
import com.vocaease.patient.core.security.SessionInvalidation
import com.vocaease.patient.core.security.TokenVault
import com.vocaease.patient.feature.auth.AuthRepository
import com.vocaease.patient.feature.auth.VocaEasePatientIdentityRemoteDataSource
import com.vocaease.patient.feature.auth.VocaEaseAuthRemoteDataSource
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

internal class ProductionSessionGraph(
    val authRepository: AuthRepository,
    val patientApi: PatientApi,
    val sessionEvents: Flow<SessionLifecycleEvent>,
)

internal fun createProductionSessionGraph(
    baseUrl: String,
    tokenVault: TokenVault,
    diagnosticSink: ((NetworkDiagnostic) -> Unit)? = null,
    clientOverride: OkHttpClient? = null,
    beforeInvalidationPublish: suspend (SessionInvalidation) -> Unit = {},
): ProductionSessionGraph {
    val client = clientOverride
        ?: diagnosticSink?.let { NetworkModule.createAuthenticatedHttpClient(tokenVault, it) }
        ?: NetworkModule.createAuthenticatedHttpClient(tokenVault)
    val rawApi = NetworkModule.createApi(baseUrl, client)
    val authRemote = VocaEaseAuthRemoteDataSource(RawAuthApi(rawApi))
    val coordinator = RefreshCoordinator(
        tokenVault = tokenVault,
        remote = authRemote,
        beforeInvalidationPublish = beforeInvalidationPublish,
    )
    val patientApi = RefreshingPatientApi(rawApi, coordinator)
    return ProductionSessionGraph(
        authRepository = AuthRepository(
            tokenVault,
            authRemote,
            coordinator,
            VocaEasePatientIdentityRemoteDataSource(patientApi),
        ),
        patientApi = patientApi,
        sessionEvents = coordinator.events,
    )
}
