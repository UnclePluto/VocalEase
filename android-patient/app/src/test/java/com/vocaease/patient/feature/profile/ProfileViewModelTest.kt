package com.vocaease.patient.feature.profile

import com.vocaease.patient.feature.catalog.PatientRemoteDataSource
import com.vocaease.patient.feature.catalog.PatientRepository
import com.vocaease.patient.feature.catalog.profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileViewModelTest {
    @Test
    fun `我的统计只取患者接口终身汇总并读取待上传数量`() = runBlocking {
        val patientRepository = PatientRepository(PatientRemoteDataSource { profile() })
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
}
