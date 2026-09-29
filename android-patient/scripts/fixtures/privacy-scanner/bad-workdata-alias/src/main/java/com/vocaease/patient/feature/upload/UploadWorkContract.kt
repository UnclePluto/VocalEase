package com.vocaease.patient.feature.upload

import androidx.work.Data as Payload

fun leak(uploadToken: String) = Payload.Builder().putString("token", uploadToken).build()
