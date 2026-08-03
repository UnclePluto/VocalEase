package com.unclepluto.vocaease

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HealthScreen(
                        presenter = HealthScreenPresenter(
                            HttpHealthGateway("http://10.0.2.2:8000/api/v1/health"),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthScreen(presenter: HealthScreenPresenter) {
    var state by remember { mutableStateOf<HealthScreenState?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(presenter) {
        runCatching {
            withContext(Dispatchers.IO) { presenter.load() }
        }.onSuccess {
            state = it
        }.onFailure {
            errorMessage = "无法连接服务，请稍后重试"
        }
    }

    Column(
        modifier = Modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            errorMessage != null -> Text(errorMessage!!)
            state == null -> CircularProgressIndicator()
            else -> HealthContent(state!!)
        }
    }
}

@Composable
private fun HealthContent(state: HealthScreenState) {
    Text(
        text = state.title,
        style = MaterialTheme.typography.headlineMedium,
    )
    state.dependencies.forEach { dependency ->
        Text("${if (dependency.available) "✓" else "×"} ${dependency.label}")
    }
}
