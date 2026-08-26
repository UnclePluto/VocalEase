package com.vocaease.patient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vocaease.patient.ui.VocaEaseApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VocaEaseApp(container = AppContainer.unavailable())
        }
    }
}
