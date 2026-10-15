package app.sense.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.NoiseSuppressor
import app.sense.core.CapabilityService
import app.sense.core.MonitorDsp
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

fun interface KeywordDetector {
    fun process(pcm: ShortArray, sampleRate: Int): String?
}

fun interface TargetSpeakerProcessor {
    fun process(pcm: ShortArray, sampleRate: Int): ShortArray
}

fun interface AudioPreprocessor {
    fun process(pcm: ShortArray): ShortArray
}

data class HearState(
    val active: Boolean = false,
    val level: Float = 0f,
    val sampleRate: Int = 48000,
    val bufferMs: Int = 0,
    val noiseReduction: Boolean = false,
    val route: String = "",
    val error: String? = null,
)

class HearEngine(
    private val context: Context,
    private val capabilities: CapabilityService,
    private val parent: CoroutineScope,
) {
    val state = MutableStateFlow(HearState())
    private var job: Job? = null
    @Volatile private var generation = 0
    @Volatile var gain = .35f
    @Volatile var focus = false
    var keywordDetector: KeywordDetector? = null
    var targetSpeakerProcessor: TargetSpeakerProcessor? = null
    var preprocessor: AudioPreprocessor? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val previousJob = job
        stop()
        val gen = generation
        check(capabilities.state.value.canHear) { "Connect headphones and allow microphone access" }
        job =
            parent.launch(Dispatchers.IO) {
                previousJob?.join()
                var input: AudioRecord? = null
                var output: android.media.AudioTrack? = null
                var suppressor: NoiseSuppressor? = null
                val manager = context.getSystemService(AudioManager::class.java)
                var focusRequest: AudioFocusRequest? = null
                try {
                    ensureActive()
                    val attributes =
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    focusRequest =
                        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attributes)
                            .setOnAudioFocusChangeListener {
                                if (gen == generation && it != AudioManager.AUDIOFOCUS_GAIN) stop()
                            }
                            .build()
                    check(
                        manager.requestAudioFocus(focusRequest) ==
                            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    ) {
                        "Audio focus unavailable"
                    }
                    val rate = 48000
                    val n =
                        maxOf(
                            AudioRecord.getMinBufferSize(
                                rate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                            ),
                            1920,
                        )
                    input =
                        AudioRecord.Builder()
                            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(rate)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build()
                            )
                            .setBufferSizeInBytes(n * 2)
                            .build()
                    check(input.state == AudioRecord.STATE_INITIALIZED) {
                        "Microphone initialization failed"
                    }
                    val route = capabilities.headsetDevice ?: error("Headphone route unavailable")
                    output =
                        android.media.AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(rate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build()
                            )
                            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(
                                maxOf(
                                    n * 2,
                                    android.media.AudioTrack.getMinBufferSize(
                                        rate,
                                        AudioFormat.CHANNEL_OUT_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                    ),
                                )
                            )
                            .setPerformanceMode(
                                android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                            )
                            .build()
                    check(
                        output.state == android.media.AudioTrack.STATE_INITIALIZED &&
                            output.setPreferredDevice(route)
                    ) {
                        "Cannot select headphones safely"
                    }
                    val routedOutput = output
                    output.addOnRoutingChangedListener(
                        { changed ->
                            if (gen == generation && changed.routedDevice?.id != route.id) {
                                runCatching {
                                    routedOutput.pause()
                                    routedOutput.flush()
                                }
                                stop()
                            }
                        },
                        android.os.Handler(android.os.Looper.getMainLooper()),
                    )
                    if (NoiseSuppressor.isAvailable())
                        suppressor = NoiseSuppressor.create(input.audioSessionId)
                    suppressor?.let { runCatching { it.enabled = true } }
                    input.startRecording()
                    output.play()
                    state.value =
                        HearState(
                            true,
                            sampleRate = rate,
                            bufferMs = n * 1000 / (rate * 2),
                            noiseReduction = suppressor?.enabled == true,
                            route = capabilities.state.value.route,
                        )
                    val pcm = ShortArray(480)
                    val dsp = MonitorDsp()
                    var lastLevel = 0L
                    // Prime with silence, then verify the actual route before emitting microphone
                    // PCM.
                    check(
                        output.write(
                            ShortArray(480),
                            0,
                            480,
                            android.media.AudioTrack.WRITE_NON_BLOCKING,
                        ) > 0
                    )
                    var routeWait = 0
                    while (output.routedDevice?.id != route.id && routeWait++ < 20) {
                        ensureActive()
                        delay(10)
                    }
                    check(output.routedDevice?.id == route.id) {
                        "Headphones were not selected; monitoring stopped"
                    }
                    while (isActive) {
                        check(
                            capabilities.headsetDevice?.id == route.id &&
                                output.routedDevice?.id == route.id
                        ) {
                            "Headphones disconnected; monitoring stopped"
                        }
                        val count = input.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0) { "Microphone read failed" }
                        if (count == 0) {
                            delay(5)
                            continue
                        }
                        var chunk = pcm.copyOf(count)
                        keywordDetector?.process(chunk, rate)
                        chunk = preprocessor?.process(chunk) ?: chunk
                        chunk = targetSpeakerProcessor?.process(chunk, rate) ?: chunk
                        require(chunk.size in 1..4800) { "Audio processor returned invalid buffer" }
                        val energy = chunk.sumOf { it.toDouble() * it }
                        chunk = dsp.process(chunk, gain, focus)
                        check(output.routedDevice?.id == route.id) { "Audio route changed" }
                        var written = 0
                        val deadline = android.os.SystemClock.elapsedRealtime() + 500
                        while (written < chunk.size) {
                            ensureActive()
                            check(output.routedDevice?.id == route.id) { "Audio route changed" }
                            val nwrite =
                                output.write(
                                    chunk,
                                    written,
                                    chunk.size - written,
                                    android.media.AudioTrack.WRITE_BLOCKING,
                                )
                            check(
                                nwrite >= 0 && android.os.SystemClock.elapsedRealtime() < deadline
                            ) {
                                "Headphone output stalled"
                            }
                            written += nwrite
                            if (written < chunk.size) delay(2)
                        }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastLevel >= 50) {
                            lastLevel = now
                            state.value =
                                state.value.copy(
                                    level =
                                        (sqrt(energy / chunk.size) / 32768)
                                            .toFloat()
                                            .coerceIn(0f, 1f)
                                )
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException)
                        state.value = state.value.copy(error = e.message ?: "Audio unavailable")
                } finally {
                    runCatching { input?.stop() }
                    runCatching { input?.release() }
                    runCatching { output?.stop() }
                    runCatching { output?.release() }
                    runCatching { suppressor?.release() }
                    runCatching { focusRequest?.let { manager.abandonAudioFocusRequest(it) } }
                    state.value = state.value.copy(active = false, level = 0f)
                }
            }
    }

    suspend fun stopAndJoin() {
        val previous = job
        stop()
        previous?.join()
    }

    fun stop() {
        generation++
        job?.cancel()
        // Retain the cancelled job until the next start/join finishes releasing the microphone.
    }
}
