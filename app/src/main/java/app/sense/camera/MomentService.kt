package app.sense.camera

import app.sense.core.UserFailures
import app.sense.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

interface ReconstructionEngine {
    suspend fun reconstruct(clips: List<Clip>): java.io.File
}

data class MomentState(
    val sessionId: String? = null,
    val countdown: Int? = null,
    val recording: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

class MomentService(
    private val camera: CameraPipeline,
    private val backend: Backend,
    private val store: LocalStore,
    private val parent: CoroutineScope,
) {
    val state = MutableStateFlow(MomentState())
    val clips = MutableStateFlow<List<Clip>>(emptyList())
    private var waiting: Job? = null
    private var capturedSession: String? = null

    fun arm(id: String, at: Long) {
        if (capturedSession == id || waiting?.isActive == true) return
        capturedSession = id
        waiting =
            parent.launch {
                state.value = MomentState(id)
                val clock = backend.clock.snapshot(android.os.SystemClock.elapsedRealtime())
                if (clock == null) {
                    state.value =
                        MomentState(
                            id,
                            error =
                                "Clock estimate unavailable or stale. Reconnect and create a new session",
                        )
                    return@launch
                }
                val target = at - clock.offsetFromElapsed
                if (target - android.os.SystemClock.elapsedRealtime() < -500) {
                    state.value =
                        state.value.copy(error = "Capture time passed. Create a new session")
                    return@launch
                }
                while (isActive) {
                    val remaining = target - android.os.SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    state.value = state.value.copy(countdown = ((remaining + 999) / 1000).toInt())
                    delay(minOf(remaining, 100))
                }
                if (
                    !clock.usable(android.os.SystemClock.elapsedRealtime()) ||
                        android.os.SystemClock.elapsedRealtime() - target > 500
                ) {
                    state.value =
                        MomentState(
                            id,
                            error = "Capture missed its timing window. Create a new session",
                        )
                    return@launch
                }
                state.value = state.value.copy(countdown = null, recording = true)
                try {
                    camera.record(
                        id,
                        at,
                        clock,
                        { clip ->
                            clips.value = clips.value + clip
                            state.value = state.value.copy(recording = false)
                            parent.launch {
                                store.history(
                                    clip.file.name,
                                    JSONObject()
                                        .put("file", clip.file.absolutePath)
                                        .put("session", id)
                                        .put("startedAt", clip.startedAt)
                                        .put("requestedAt", clip.requestedAt)
                                        .put("uncertainty", clip.clockUncertainty),
                                )
                            }
                        },
                        { error ->
                            state.value = state.value.copy(recording = false, error = error)
                        },
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    state.value =
                        state.value.copy(
                            recording = false,
                            error = "Camera capture unavailable. Please try again.",
                        )
                }
            }
    }

    suspend fun transfer(id: String, clip: Clip) {
        state.value = state.value.copy(progress = 0f)
        try {
            require(id == clip.sessionId && id.isNotBlank()) {
                "Clip belongs to a different capture session"
            }
            backend.upload(id, clip.file, clip.startedAt, clip.clockUncertainty) {
                state.value = state.value.copy(progress = it)
            }
            backend.sync()
            state.value = state.value.copy(progress = null)
        } catch (e: Exception) {
            state.value =
                state.value.copy(
                    progress = null,
                    error = if (e is CancellationException) null else UserFailures.message(e),
                )
            if (e is CancellationException) throw e
        }
    }

    fun stop() {
        waiting?.cancel()
        waiting = null
        state.value = MomentState()
    }
}
