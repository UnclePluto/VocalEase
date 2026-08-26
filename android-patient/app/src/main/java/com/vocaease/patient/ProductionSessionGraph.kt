package com.vocaease.patient

import com.vocaease.patient.core.network.NetworkDiagnostic
import com.vocaease.patient.core.network.NetworkModule
import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.RawAuthApi
import com.vocaease.patient.core.network.RefreshCoordinator
import com.vocaease.patient.core.network.RefreshingPatientApi
import com.vocaease.patient.core.network.SessionLifecycleEvent
import com.vocaease.patient.core.security.TokenVault
import com.vocaease.patient.feature.auth.AuthRepository
import com.vocaease.patient.feature.auth.VocaEaseAuthRemoteDataSource
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient

internal class ProductionSessionGraph(
    val authRepository: AuthRepository,
    val patientApi: PatientApi,
    val sessionEvents: SharedFlow<SessionLifecycleEvent>,
)

internal fun createProductionSessionGraph(
    baseUrl: String,
    tokenVault: TokenVault,
    diagnosticSink: ((NetworkDiagnostic) -> Unit)? = null,
    clientOverride: OkHttpClient? = null,
): ProductionSessionGraph {
    val client = clientOverride
        ?: diagnosticSink?.let { NetworkModule.createAuthenticatedHttpClient(tokenVault, it) }
        ?: NetworkModule.createAuthenticatedHttpClient(tokenVault)
    val rawApi = NetworkModule.createApi(baseUrl, client)
    val authRemote = VocaEaseAuthRemoteDataSource(RawAuthApi(rawApi))
    val coordinator = RefreshCoordinator(tokenVault, authRemote)
    return ProductionSessionGraph(
        authRepository = AuthRepository(tokenVault, authRemote, coordinator),
        patientApi = RefreshingPatientApi(rawApi, coordinator),
        sessionEvents = coordinator.events,
    )
}
