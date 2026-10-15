package app.sense.camera

import app.sense.core.*
import app.sense.data.LocalStore
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SilentService(
    private val camera: CameraPipeline,
    private val store: LocalStore,
    private val parent: CoroutineScope,
) {
    val commands = store.commands.stateIn(parent, SharingStarted.Eagerly, emptyList())
    val recording = MutableStateFlow(false)
    val examples = MutableStateFlow<List<List<FloatArray>>>(emptyList())
    val recognized = MutableStateFlow<Recognition?>(null)
    val status = MutableStateFlow("Train a command with three examples")
    var threshold = .78
    var confirmation = true
    private var collector: Job? = null
    private val gate = RecognitionGate()
    private val buffer = MouthWindow()
    private var owner: Any? = null
    private val lock = Any()
    @Volatile private var generation = 0

    fun listen(live: Boolean, owner: Any = this) {
        this.owner = owner
        collector?.cancel()
        val gen = ++generation
        synchronized(lock) {
            recording.value = false
            buffer.clear()
        }
        collector =
            parent.launch(Dispatchers.Default) {
                camera.frames.collect { frame ->
                    val sequence =
                        synchronized(lock) {
                            if (gen != generation) return@synchronized emptyList<FloatArray>()
                            if (recording.value || live) {
                                val age = android.os.SystemClock.elapsedRealtime() - frame.elapsedMs
                                buffer.add(
                                    if (age in 0..500) frame else MouthFrame(null, frame.elapsedMs)
                                )
                            }
                            buffer.snapshot()
                        }
                    if (live && !recording.value && sequence.size >= 20) {
                        val motion =
                            sequence.zipWithNext().sumOf { (a, b) ->
                                a.indices.sumOf { i -> kotlin.math.abs(a[i] - b[i]).toDouble() } /
                                    a.size
                            } / (sequence.size - 1)
                        if (motion > .008) {
                            val candidate = MouthMatcher.recognize(sequence, commands.value)
                            withContext(Dispatchers.Main.immediate) {
                                if (
                                    gen != generation ||
                                        android.os.SystemClock.elapsedRealtime() -
                                            frame.elapsedMs !in 0..500
                                )
                                    return@withContext
                                val match =
                                    gate.accept(candidate, android.os.SystemClock.elapsedRealtime())
                                if (match != null) {
                                    recognized.value = match
                                    status.value =
                                        match.command.name +
                                            " · " +
                                            (match.confidence * 100).toInt() +
                                            "% similarity"
                                    synchronized(lock) { buffer.clear() }
                                }
                            }
                        }
                    }
                }
            }
    }

    suspend fun example() {
        if (recording.value || collector?.isActive != true) return
        val gen = generation
        synchronized(lock) {
            buffer.clear()
            recording.value = true
        }
        status.value = "Mouth the command now"
        try {
            delay(2400)
            if (gen != generation) return
            val sample = synchronized(lock) { buffer.snapshot().also { buffer.clear() } }
            if (sample.size < 12) {
                status.value = "No continuous mouth track. Face the camera and try again"
                return
            }
            if (examples.value.size < 6) examples.value = examples.value + listOf(sample)
            status.value = examples.value.size.toString() + " examples recorded"
        } finally {
            if (gen == generation)
                synchronized(lock) {
                    recording.value = false
                    buffer.clear()
                }
        }
    }

    suspend fun save(name: String, action: String) {
        val gen = generation
        require(name.trim().length in 1..32)
        check(examples.value.size >= 3) { "Record at least three examples" }
        store.command(
            SilentCommand(
                UUID.randomUUID().toString(),
                name.trim(),
                action,
                examples.value,
                threshold,
                confirmation,
            )
        )
        if (gen == generation) {
            examples.value = emptyList()
            status.value = "Command saved on this phone"
        }
    }

    fun clearRecognition() {
        recognized.value = null
    }

    fun stop(owner: Any) {
        if (this.owner === owner) stop()
    }

    fun stop() {
        owner = null
        generation++
        collector?.cancel()
        collector = null
        synchronized(lock) {
            recording.value = false
            buffer.clear()
        }
        examples.value = emptyList()
        recognized.value = null
    }
}
