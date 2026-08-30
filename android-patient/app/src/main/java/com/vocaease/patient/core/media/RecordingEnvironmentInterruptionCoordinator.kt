package com.vocaease.patient.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.vocaease.patient.feature.training.RecordingInterruption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

interface RecordingAudioFocus : AutoCloseable {
    fun start(onLost: () -> Unit)
}

/** 将 Activity 停止和音频焦点事件收敛到单一顺序队列，关闭后不再投递。 */
class RecordingEnvironmentInterruptionCoordinator(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val audioFocus: RecordingAudioFocus,
    private val interrupt: suspend (RecordingInterruption) -> Unit,
) : AutoCloseable {
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val events = Channel<EnvironmentCommand>(Channel.UNLIMITED)
    private val actor: Job = scope.launch(dispatcher) {
        for (command in events) {
            when (command) {
                is EnvironmentCommand.Interrupt -> if (!closed.get()) interrupt(command.reason)
                is EnvironmentCommand.Ready -> command.ack.complete(Unit)
            }
        }
    }

    /**
     * 在返回前处理监听启用前后已经排队的宿主/焦点中断，调用方随后才可启动录制。
     */
    suspend fun startAndAwaitReady() {
        if (closed.get()) return
        if (started.compareAndSet(false, true)) audioFocus.start(::onAudioFocusLost)
        val ready = CompletableDeferred<Unit>()
        if (!events.trySend(EnvironmentCommand.Ready(ready)).isSuccess) return
        ready.await()
    }

    fun onHostStopped() {
        if (!closed.get()) events.trySend(EnvironmentCommand.Interrupt(RecordingInterruption.CAMERA))
    }

    private fun onAudioFocusLost() {
        if (!closed.get()) events.trySend(EnvironmentCommand.Interrupt(RecordingInterruption.AUDIO))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        audioFocus.close()
        events.close()
        actor.cancel()
    }
}

private sealed interface EnvironmentCommand {
    data class Interrupt(val reason: RecordingInterruption) : EnvironmentCommand
    data class Ready(val ack: CompletableDeferred<Unit>) : EnvironmentCommand
}

class AndroidRecordingAudioFocus(context: Context) : RecordingAudioFocus {
    private val audioManager = requireNotNull(context.applicationContext.getSystemService(AudioManager::class.java))
    private var request: AudioFocusRequest? = null

    override fun start(onLost: () -> Unit) {
        if (request != null) return
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (
                change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                onLost()
            }
        }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener)
            .build()
        request = focusRequest
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onLost()
        }
    }

    override fun close() {
        request?.let(audioManager::abandonAudioFocusRequest)
        request = null
    }
}
