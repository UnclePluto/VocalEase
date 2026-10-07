package com.vocaease.patient.core.media

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RecordingPlaybackHandoff(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val leaseTimeoutMillis: Long = 30_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val sessions = ConcurrentHashMap<String, PendingHandoff>()

    fun offer(draftId: String, session: PreviewSession) {
        require(draftId.isNotBlank())
        require(leaseTimeoutMillis > 0)
        val handoff = PendingHandoff(session)
        val previous = sessions.putIfAbsent(draftId, handoff)
        check(previous == null || previous.session === session) { "录制播放交接已存在" }
        if (previous != null) return
        handoff.expiry = scope.launch {
            delay(leaseTimeoutMillis)
            if (sessions.remove(draftId, handoff)) session.release()
        }
    }

    fun take(draftId: String): RecordingPlayback? = sessions.remove(draftId)?.let { handoff ->
        handoff.expiry?.cancel()
        PreviewSessionRecordingPlayback(handoff.session, dispatcher)
    }

    fun discard(draftId: String) {
        sessions.remove(draftId)?.let { handoff ->
            handoff.expiry?.cancel()
            handoff.session.release()
        }
    }

    fun discardAll() {
        sessions.entries.forEach { (draftId, handoff) ->
            if (sessions.remove(draftId, handoff)) {
                handoff.expiry?.cancel()
                handoff.session.release()
            }
        }
    }

    private class PendingHandoff(val session: PreviewSession) {
        @Volatile var expiry: Job? = null
    }
}

private class PreviewSessionRecordingPlayback(
    private val session: PreviewSession,
    dispatcher: CoroutineDispatcher,
) : RecordingPlayback {
    private val stopped = AtomicBoolean()
    private val endedDelivered = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile private var endedListener: (() -> Unit)? = null
    override val currentPositionMillis: Long get() = session.currentPositionMillis
    override val playbackBinding get() = session.playbackBinding
    override val activeMode get() = session.activeMode
    override val playbackState get() = session.state
    override suspend fun switchMode(mode: SongPlaybackMode) = !stopped.get() && session.switchMode(mode)
    override suspend fun pause() = !stopped.get() && session.pause()
    override suspend fun resume() = !stopped.get() && session.play()

    init {
        scope.launch {
            session.state.collectLatest { state ->
                if (state is PreviewState.Ended) notifyEnded()
            }
        }
    }

    override fun play() {
        if (!stopped.get()) scope.launch { session.play() }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        session.release()
        scope.cancel()
    }

    override fun setOnEnded(listener: () -> Unit) {
        endedListener = listener
        if (session.state.value is PreviewState.Ended) notifyEnded()
    }

    private fun notifyEnded() {
        val listener = endedListener ?: return
        if (!stopped.get() && endedDelivered.compareAndSet(false, true)) listener()
    }
}
