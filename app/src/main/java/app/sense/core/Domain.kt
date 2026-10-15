package app.sense.core

import kotlin.math.*

data class Capabilities(
    val camera: Boolean = false,
    val microphone: Boolean = false,
    val bluetooth: Boolean = false,
    val headset: Boolean = false,
    val uwb: Boolean = false,
    val ranging: Boolean = false,
    val nearbyPermission: Boolean = false,
    val cameraPermission: Boolean = false,
    val microphonePermission: Boolean = false,
    val notificationPermission: Boolean = false,
    val rangingPermission: Boolean = false,
    val online: Boolean = false,
    val route: String = "Speaker",
    val frontCamera: Boolean = false,
    val bluetoothSupported: Boolean = false,
    val locationEnabled: Boolean = false,
) {
    val canRadar
        get() = bluetoothSupported && bluetooth && nearbyPermission && locationEnabled

    val canCamera
        get() = camera && cameraPermission

    val canFaceCamera
        get() = canCamera && frontCamera

    val canHear
        get() = microphone && microphonePermission && headset
}

enum class Relationship {
    INCOMING,
    OUTGOING,
    ACCEPTED,
    BLOCKED,
}

data class Person(
    val id: String,
    val name: String,
    val relationship: Relationship,
    val online: Boolean = false,
    val radarTokens: List<String> = emptyList(),
    val avatar: String? = null,
    val ranging: Boolean = false,
)

data class Profile(val id: String, val name: String, val avatar: String? = null)

enum class Technology {
    ANDROID_RANGING,
    UWB,
    BLE_RSSI,
    SIMULATION,
}

data class Measurement(
    val id: String,
    val distance: Double,
    val azimuth: Double? = null,
    val elevation: Double? = null,
    val source: Technology,
    val quality: Double,
    val timestamp: Long,
) {
    fun stale(now: Long) = now < timestamp || now - timestamp > 6000
}

object ProviderSelection {
    fun choose(c: Capabilities, negotiated: Set<Technology>): Technology? =
        when {
            c.ranging && c.rangingPermission && Technology.ANDROID_RANGING in negotiated ->
                Technology.ANDROID_RANGING
            c.uwb && c.rangingPermission && Technology.UWB in negotiated -> Technology.UWB
            c.canRadar && Technology.BLE_RSSI in negotiated -> Technology.BLE_RSSI
            else -> null
        }
}

class RangeFilter {
    private val last = mutableMapOf<String, Measurement>()

    fun accept(raw: Measurement): Measurement? {
        val m =
            raw.copy(
                azimuth = raw.azimuth?.takeIf { it.isFinite() },
                elevation = raw.elevation?.takeIf { it.isFinite() },
            )
        if (!m.distance.isFinite() || m.distance < 0 || m.quality !in 0.0..1.0) return null
        val old = last[m.id]
        if (old != null && m.timestamp <= old.timestamp) return null
        val alpha =
            if (old == null || old.source != m.source || m.timestamp - old.timestamp > 6000) 1.0
            else 0.3
        val d = old?.let { it.distance + alpha * (m.distance - it.distance) } ?: m.distance
        val a =
            if (m.azimuth == null) null
            else if (old?.azimuth == null || alpha == 1.0) m.azimuth
            else {
                val delta = atan2(sin(m.azimuth - old.azimuth), cos(m.azimuth - old.azimuth))
                old.azimuth + alpha * delta
            }
        return m.copy(distance = d, azimuth = a).also { last[m.id] = it }
    }

    fun clear() = last.clear()
}

enum class VoicePhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

data class VoiceState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val sessionId: String? = null,
    val muted: Boolean = true,
    val participants: List<String> = emptyList(),
    val error: String? = null,
)

enum class PrivacyState {
    CHECKING,
    SAFE,
    EXTRA_VIEWER,
    POSSIBLE_VIEWER,
    OWNER_ABSENT,
}

class PrivacyGate {
    private var safeFrames = 0

    fun update(faces: Int, centered: Boolean, reliable: Boolean): PrivacyState {
        if (!reliable) {
            safeFrames = 0
            return PrivacyState.CHECKING
        }
        if (faces == 0) {
            safeFrames = 0
            return PrivacyState.OWNER_ABSENT
        }
        if (faces > 1) {
            safeFrames = 0
            return PrivacyState.EXTRA_VIEWER
        }
        if (!centered) {
            safeFrames = 0
            return PrivacyState.POSSIBLE_VIEWER
        }
        safeFrames++
        return if (safeFrames >= 8) PrivacyState.SAFE else PrivacyState.CHECKING
    }
}

data class SilentCommand(
    val id: String,
    val name: String,
    val action: String,
    val samples: List<List<FloatArray>>,
    val threshold: Double = .78,
    val confirm: Boolean = true,
)

data class Recognition(val command: SilentCommand, val confidence: Double)

object MouthMatcher {
    fun distance(a: List<FloatArray>, b: List<FloatArray>): Double {
        if (
            a.size !in 8..128 ||
                b.size !in 8..128 ||
                (a.firstOrNull()?.size ?: 0) !in 1..256 ||
                a.any { f -> f.size != a[0].size || f.any { !it.isFinite() } } ||
                b.any { f -> f.size != a[0].size || f.any { !it.isFinite() } }
        )
            return Double.POSITIVE_INFINITY
        var prev = DoubleArray(b.size + 1) { Double.POSITIVE_INFINITY }
        prev[0] = 0.0
        for (x in a) {
            val next = DoubleArray(b.size + 1) { Double.POSITIVE_INFINITY }
            for (j in b.indices) {
                val y = b[j]
                if (x.size != y.size) return Double.POSITIVE_INFINITY
                val cost = sqrt(x.indices.sumOf { (x[it] - y[it]).toDouble().pow(2) } / x.size)
                next[j + 1] = cost + minOf(prev[j + 1], next[j], prev[j])
            }
            prev = next
        }
        return prev.last() / (a.size + b.size)
    }

    fun recognize(sequence: List<FloatArray>, commands: List<SilentCommand>): Recognition? {
        val scores =
            commands
                .filter {
                    it.samples.size in 3..6 && it.threshold.isFinite() && it.threshold in 0.0..1.0
                }
                .map { c ->
                    Recognition(
                        c,
                        exp(
                            -12 *
                                (c.samples.minOfOrNull { distance(sequence, it) }
                                    ?: Double.POSITIVE_INFINITY)
                        ),
                    )
                }
                .sortedByDescending { it.confidence }
        val best = scores.firstOrNull() ?: return null
        if (
            !best.confidence.isFinite() ||
                best.confidence <= 0 ||
                best.confidence < best.command.threshold ||
                (scores.size > 1 && best.confidence - scores[1].confidence < .06)
        )
            return null
        return best
    }
}

class RecognitionGate(private val cooldownMs: Long = 3000) {
    private var lastAt = Long.MIN_VALUE / 2

    fun accept(recognition: Recognition?, now: Long): Recognition? {
        if (recognition == null || now - lastAt < cooldownMs) return null
        lastAt = now
        return recognition
    }
}

data class CaptureSession(
    val id: String,
    val host: String,
    val participants: List<String>,
    val ready: List<String>,
    val captureAt: Long? = null,
    val expiresAt: Long,
    val clips: List<String> = emptyList(),
) {
    val allReady
        get() = participants.isNotEmpty() && participants.all { it in ready }

    fun canSchedule(user: String, now: Long) =
        user == host && allReady && expiresAt > now && captureAt == null
}

data class SpatialGains(val left: Float, val right: Float)

object StereoSpatializer {
    fun gains(m: Measurement?, now: Long): SpatialGains {
        if (m == null || m.stale(now) || m.azimuth == null || m.quality < .5)
            return SpatialGains(.707f, .707f)
        val pan = sin(m.azimuth).coerceIn(-1.0, 1.0)
        val attenuation = 1.0 / (1.0 + .12 * m.distance.coerceAtLeast(0.0))
        return SpatialGains(
            (cos((pan + 1) * PI / 4) * attenuation).toFloat(),
            (sin((pan + 1) * PI / 4) * attenuation).toFloat(),
        )
    }
}

object PackIntegrity {
    fun valid(expected: String, actual: String) =
        expected.matches(Regex("[a-fA-F0-9]{64}")) && expected.equals(actual, true)
}
