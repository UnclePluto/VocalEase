package com.vocaease.patient.feature.profile

import com.vocaease.patient.feature.catalog.PatientRemoteDataSource
import com.vocaease.patient.feature.catalog.PatientRepository
import com.vocaease.patient.feature.catalog.profile
import com.vocaease.patient.feature.catalog.TestAuthenticatedAccountSession
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileViewModelTest {
    @Test
    fun `我的统计只取患者接口终身汇总并读取待上传数量`() = runBlocking {
        val session = TestAuthenticatedAccountSession().apply {
            authenticate("10000000-0000-4000-8000-000000000001", UUID.randomUUID().toString())
        }
        val patientRepository = PatientRepository(PatientRemoteDataSource { profile() }, session)
        val viewModel = ProfileViewModel(
            patientRepository = patientRepository,
            pendingUploadCounter = PendingUploadCounter { 2 },
            dispatcher = Dispatchers.Unconfined,
        )

        viewModel.refresh()

        assertEquals("Voca", viewModel.state.value.patientName)
        assertEquals(28, viewModel.state.value.lifetimeCompletedSongs)
        assertEquals(8_640, viewModel.state.value.lifetimeDurationSeconds)
        assertEquals(2, viewModel.state.value.pendingUploadCount)
        assertTrue(viewModel.state.value.hasActiveTreatmentPlan)
    }

    @Test
    fun `我的首帧和无缓存失败显示明确状态而非空统计`() = runBlocking {
        val session = TestAuthenticatedAccountSession().apply {
            authenticate("10000000-0000-4000-8000-000000000001", UUID.randomUUID().toString())
        }
        val repository = PatientRepository(PatientRemoteDataSource { throw java.io.IOException("offline") }, session)
        val viewModel = ProfileViewModel(repository, PendingUploadCounter { 0 }, Dispatchers.Unconfined)

        assertEquals(ProfilePatientStatus.INITIAL, viewModel.state.value.patientStatus)
        assertNull(viewModel.state.value.patientName.takeIf { it.isNotEmpty() })
        viewModel.refresh()

        assertEquals(ProfilePatientStatus.ERROR, viewModel.state.value.patientStatus)
        assertEquals("患者信息加载失败，请重试", viewModel.state.value.errorMessage)
    }
}
