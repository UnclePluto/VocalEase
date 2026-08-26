package com.vocaease.patient.core.network.dto

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Gender {
    @SerialName("male") MALE,
    @SerialName("female") FEMALE,
}

@Serializable
enum class TreatmentPlanStatus {
    @SerialName("pending") PENDING,
    @SerialName("active") ACTIVE,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class PrimaryDoctorDto(
    val id: String,
    val name: String,
)

@Serializable
data class TreatmentPlanDto(
    val id: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("cycle_weeks") val cycleWeeks: Int,
    @SerialName("target_session_count") val targetSessionCount: Int,
    val status: TreatmentPlanStatus? = null,
)

@Serializable
data class TreatmentProgressDto(
    @SerialName("completed_session_count") val completedSessionCount: Int,
    @SerialName("target_session_count") val targetSessionCount: Int,
    @SerialName("progress_percent") val progressPercent: String?,
    @SerialName("current_week") val currentWeek: Int,
)

@Serializable
data class SingingSummaryDto(
    @SerialName("completed_session_count") val completedSessionCount: Int,
    @SerialName("total_duration_seconds") val totalDurationSeconds: Int,
)

@Serializable
data class PatientMeDto(
    val id: String,
    @SerialName("medical_record_no") val medicalRecordNo: String,
    val name: String,
    val gender: Gender,
    @SerialName("enrollment_age") val enrollmentAge: Int,
    val phone: String,
    val notes: String,
    @SerialName("primary_doctor") val primaryDoctor: PrimaryDoctorDto,
    @SerialName("active_treatment_plan") val activeTreatmentPlan: TreatmentPlanDto?,
    @SerialName("treatment_progress") val treatmentProgress: TreatmentProgressDto?,
    @SerialName("singing_summary") val singingSummary: SingingSummaryDto,
)

data class PrimaryDoctor(
    val id: UUID,
    val name: String,
)

data class TreatmentPlan(
    val id: UUID,
    val startDate: LocalDate,
    val cycleWeeks: Int,
    val targetSessionCount: Int,
    val status: TreatmentPlanStatus?,
)

data class TreatmentProgress(
    val completedSessionCount: Int,
    val targetSessionCount: Int,
    val progressPercent: String?,
    val currentWeek: Int,
)

data class SingingSummary(
    val completedSessionCount: Int,
    val totalDurationSeconds: Int,
)

data class PatientProfile(
    val id: UUID,
    val medicalRecordNo: String,
    val name: String,
    val gender: Gender,
    val enrollmentAge: Int,
    val phone: String,
    val notes: String,
    val primaryDoctor: PrimaryDoctor,
    val activeTreatmentPlan: TreatmentPlan?,
    val treatmentProgress: TreatmentProgress?,
    val singingSummary: SingingSummary,
)

fun PatientMeDto.toDomain(): PatientProfile = PatientProfile(
    id = id.asUuid("patient.id"),
    medicalRecordNo = medicalRecordNo.requireNotBlank("patient.medical_record_no"),
    name = name.requireNotBlank("patient.name"),
    gender = gender,
    enrollmentAge = enrollmentAge,
    phone = phone,
    notes = notes,
    primaryDoctor = PrimaryDoctor(
        id = primaryDoctor.id.asUuid("patient.primary_doctor.id"),
        name = primaryDoctor.name.requireNotBlank("patient.primary_doctor.name"),
    ),
    activeTreatmentPlan = activeTreatmentPlan?.let {
        TreatmentPlan(
            id = it.id.asUuid("patient.active_treatment_plan.id"),
            startDate = it.startDate.asLocalDate("patient.active_treatment_plan.start_date"),
            cycleWeeks = it.cycleWeeks,
            targetSessionCount = it.targetSessionCount,
            status = it.status,
        )
    },
    treatmentProgress = treatmentProgress?.let {
        TreatmentProgress(
            completedSessionCount = it.completedSessionCount,
            targetSessionCount = it.targetSessionCount,
            progressPercent = it.progressPercent,
            currentWeek = it.currentWeek,
        )
    },
    singingSummary = SingingSummary(
        completedSessionCount = singingSummary.completedSessionCount,
        totalDurationSeconds = singingSummary.totalDurationSeconds,
    ),
)
