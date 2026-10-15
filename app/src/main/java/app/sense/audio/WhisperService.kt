package app.sense.audio

import android.content.Context
import android.media.*
import app.sense.core.*
import app.sense.data.Backend
import app.sense.radar.RadarService
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteAudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import livekit.org.webrtc.AudioTrackSink

interface SpatialRenderer : AutoCloseable {
    fun attach(id: String, track: RemoteAudioTrack)

    fun detach(id: String)
}

/** Receives decoded PCM per friend. Platform AudioTrack supplies true stereo panning. */
class StereoRenderer(private val radar: RadarService, private val capabilities: CapabilityService) :
    SpatialRenderer {
    private val outputs = mutableMapOf<String, Output>()

    private inner class Output(val id: String, val remote: RemoteAudioTrack) : AudioTrackSink {
        private val queue = ArrayBlockingQueue<Pair<Int, ShortArray>>(12)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var player: android.media.AudioTrack? = null
        private var current = SpatialGains(.707f, .707f)
        @Volatile private var closed = false

        private val route = capabilities.headsetDevice
        private val playerLock = Any()

        init {
            remote.setVolume(0.0)
            try {
                remote.addSink(this)
            } catch (e: Exception) {
                runCatching { remote.setVolume(1.0) }
                scope.cancel()
                throw e
            }
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    check(route != null) { "Use SDK speaker output" }
                    var rate = 0
                    while (isActive) {
                        val block =
                            queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        check(!closed && capabilities.headsetDevice?.id == route.id) {
                            "Audio route changed"
                        }
                        if (rate != block.first) {
                            synchronized(playerLock) {
                                player?.release()
                                player = null
                            }
                            rate = block.first
                            player =
                                android.media.AudioTrack.Builder()
                                    .setAudioAttributes(
                                        AudioAttributes.Builder()
                                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                            .build()
                                    )
                                    .setAudioFormat(
                                        AudioFormat.Builder()
                                            .setSampleRate(rate)
                                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                            .build()
                                    )
                                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                                    .setBufferSizeInBytes(
                                        maxOf(
                                            android.media.AudioTrack.getMinBufferSize(
                                                rate,
                                                AudioFormat.CHANNEL_OUT_STEREO,
                                                AudioFormat.ENCODING_PCM_16BIT,
                                            ),
                                            rate / 10 * 4,
                                        )
                                    )
                                    .build()
                            synchronized(playerLock) {
                                check(!closed)
                                val track = player!!
                                check(track.setPreferredDevice(route)) {
                                    "Headphone route unavailable"
                                }
                                track.play()
                                track.write(
                                    ShortArray(480),
                                    0,
                                    480,
                                    android.media.AudioTrack.WRITE_NON_BLOCKING,
                                )
                            }
                            var routeWait = 0
                            while (player?.routedDevice?.id != route.id && routeWait++ < 20) {
                                ensureActive()
                                delay(10)
                            }
                            check(player?.routedDevice?.id == route.id) {
                                "Headphone route unavailable"
                            }
                            val routedPlayer = player!!
                            routedPlayer.addOnRoutingChangedListener(
                                { changed -> if (changed.routedDevice?.id != route.id) close() },
                                android.os.Handler(android.os.Looper.getMainLooper()),
                            )
                        }
                        val target =
                            StereoSpatializer.gains(
                                radar.measurements.value[id],
                                System.currentTimeMillis(),
                            )
                        val mono = block.second
                        val stereo = ShortArray(mono.size * 2)
                        for (i in mono.indices) {
                            current =
                                SpatialGains(
                                    current.left + .003f * (target.left - current.left),
                                    current.right + .003f * (target.right - current.right),
                                )
                            stereo[i * 2] =
                                (mono[i] * current.left).toInt().coerceIn(-30000, 30000).toShort()
                            stereo[i * 2 + 1] =
                                (mono[i] * current.right).toInt().coerceIn(-30000, 30000).toShort()
                        }
                        var written = 0
                        val deadline = android.os.SystemClock.elapsedRealtime() + 1000
                        while (written < stereo.size) {
                            ensureActive()
                            synchronized(playerLock) {
                                check(!closed && player?.routedDevice?.id == route.id) {
                                    "Audio route changed"
                                }
                                val n =
                                    player!!.write(
                                        stereo,
                                        written,
                                        stereo.size - written,
                                        android.media.AudioTrack.WRITE_NON_BLOCKING,
                                    )
                                check(
                                    n >= 0 && android.os.SystemClock.elapsedRealtime() < deadline
                                ) {
                                    "Spatial output stalled"
                                }
                                written += n
                            }
                            if (written < stereo.size) delay(2)
                        }
                    }
                } catch (_: Exception) {
                    // Release custom output before restoring SDK playback, including route failure.
                } finally {
                    closed = true
                    runCatching { remote.removeSink(this@Output) }
                    synchronized(playerLock) {
                        runCatching {
                            player?.pause()
                            player?.flush()
                            player?.release()
                        }
                        player = null
                    }
                    queue.clear()
                    runCatching { remote.setVolume(1.0) }
                    scope.cancel()
                }
            }
        }

        override fun onData(
            audioData: ByteBuffer?,
            bitsPerSample: Int,
            sampleRate: Int,
            numberOfChannels: Int,
            numberOfFrames: Int,
            absoluteCaptureTimestampMs: Long,
        ) {
            if (closed) return
            if (
                audioData == null ||
                    bitsPerSample != 16 ||
                    numberOfChannels !in 1..8 ||
                    sampleRate !in 8000..96000 ||
                    numberOfFrames !in 1..9600
            ) {
                close()
                return
            }
            val b = audioData.duplicate().order(ByteOrder.nativeOrder())
            val count = minOf(numberOfFrames, b.remaining() / 2 / numberOfChannels)
            val mono = ShortArray(count)
            for (i in 0 until count) {
                var sum = 0
                repeat(numberOfChannels) { sum += b.short }
                mono[i] = (sum / numberOfChannels).toShort()
            }
            if (!queue.offer(sampleRate to mono)) {
                queue.poll()
                queue.offer(sampleRate to mono)
            }
        }

        fun close() {
            closed = true
            synchronized(playerLock) {
                runCatching {
                    player?.pause()
                    player?.flush()
                }
            }
            scope.cancel()
            queue.clear()
        }
    }

    override fun attach(id: String, track: RemoteAudioTrack) {
        if (outputs[id]?.remote === track) return
        detach(id)
        if (capabilities.headsetDevice != null)
            runCatching { outputs[id] = Output(id, track) }
                .onFailure { runCatching { track.setVolume(1.0) } }
    }

    override fun detach(id: String) {
        outputs.remove(id)?.close()
    }

    override fun close() {
        outputs.values.forEach { it.close() }
        outputs.clear()
    }
}

class WhisperService(
    private val context: Context,
    private val capabilities: CapabilityService,
    private val backend: Backend,
    private val radar: RadarService,
    private val hear: HearEngine,
    private val parent: CoroutineScope,
) {
    val state = MutableStateFlow(VoiceState())
    private var room: Room? = null
    private var events: Job? = null
    private var renderer: SpatialRenderer? = null
    private var connecting: Job? = null
    private var generation = 0

    suspend fun connect(id: String) {
        check(state.value.phase != VoicePhase.CONNECTING) { "Voice is already connecting" }
        disconnect()
        val gen = generation
        connecting = currentCoroutineContext()[Job]
        check(capabilities.state.value.microphonePermission) { "Allow microphone access first" }
        state.value = VoiceState(VoicePhase.CONNECTING, id)
        try {
            hear.stopAndJoin()
            currentCoroutineContext().ensureActive()
            backend.sessionAction(id, "join")
            val credentials = backend.sessionAction(id, "token")
            val r =
                LiveKit.create(
                    context,
                    overrides =
                        LiveKitOverrides(audioOptions = AudioOptions(disableAudioPrewarming = true)),
                )
            room = r
            renderer = StereoRenderer(radar, capabilities)
            events =
                parent.launch {
                    r.events.collect { event ->
                        if (gen != generation) return@collect
                        when (event) {
                            is RoomEvent.TrackSubscribed -> {
                                val track = event.track
                                if (track is RemoteAudioTrack)
                                    renderer?.attach(event.participant.identity?.value ?: "", track)
                            }
                            is RoomEvent.TrackUnsubscribed ->
                                renderer?.detach(event.participant.identity?.value ?: "")
                            is RoomEvent.Reconnecting ->
                                state.value = state.value.copy(phase = VoicePhase.RECONNECTING)
                            is RoomEvent.Reconnected ->
                                state.value = state.value.copy(phase = VoicePhase.CONNECTED)
                            is RoomEvent.Disconnected -> {
                                disconnect()
                                state.value =
                                    VoiceState(VoicePhase.FAILED, error = "Voice disconnected")
                            }
                            else -> Unit
                        }
                        state.value =
                            state.value.copy(
                                participants =
                                    r.remoteParticipants.values.map {
                                        it.name ?: it.identity?.value ?: "Friend"
                                    }
                            )
                    }
                }
            r.connect(credentials.getString("url"), credentials.getString("token"))
            currentCoroutineContext().ensureActive()
            check(gen == generation) { "Voice connection cancelled" }
            r.localParticipant.setMicrophoneEnabled(true)
            state.value = state.value.copy(phase = VoicePhase.CONNECTED, muted = false)
        } catch (e: Exception) {
            if (gen == generation) {
                connecting = null
                disconnect()
                if (e !is CancellationException)
                    state.value =
                        VoiceState(VoicePhase.FAILED, error = e.message ?: "Unable to connect")
            }
            throw e
        } finally {
            if (gen == generation) connecting = null
        }
    }

    suspend fun mute() {
        val r = room ?: return
        val muted = !state.value.muted
        r.localParticipant.setMicrophoneEnabled(!muted)
        state.value = state.value.copy(muted = muted)
    }

    fun disconnect() {
        generation++
        connecting?.cancel()
        connecting = null
        events?.cancel()
        events = null
        renderer?.close()
        renderer = null
        room?.disconnect()
        room?.release()
        room = null
        state.value = VoiceState()
    }
}
