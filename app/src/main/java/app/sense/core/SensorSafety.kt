package app.sense.core

import kotlin.math.*

data class MouthFrame(val features: FloatArray?, val elapsedMs: Long)

/** A discontinuity or lost face invalidates the entire temporal window. */
class MouthWindow(private val capacity: Int = 32) {
    private val frames = ArrayDeque<FloatArray>()
    private var lastAt: Long? = null

    fun add(frame: MouthFrame): List<FloatArray> {
        val f = frame.features
        val valid = f != null && f.isNotEmpty() && f.all { it.isFinite() }
        if (
            !valid ||
                lastAt?.let { frame.elapsedMs <= it || frame.elapsedMs - it > 300 } == true ||
                frames.firstOrNull()?.size?.let { it != f?.size } == true
        )
            clear()
        if (valid) {
            frames.addLast(f!!.copyOf())
            lastAt = frame.elapsedMs
            while (frames.size > capacity) frames.removeFirst()
        }
        return snapshot()
    }

    fun snapshot() = frames.map { it.copyOf() }

    fun clear() {
        frames.clear()
        lastAt = null
    }
}

data class ClockSnapshot(
    val offsetFromElapsed: Long,
    val uncertaintyMs: Long,
    val sampledAtElapsed: Long,
) {
    fun serverTime(elapsed: Long) = Math.addExact(elapsed, offsetFromElapsed)

    fun usable(elapsed: Long) =
        elapsed >= sampledAtElapsed &&
            elapsed - sampledAtElapsed <= 30000 &&
            uncertaintyMs in 0..500
}

class SessionClock {
    private var value: ClockSnapshot? = null

    @Synchronized
    fun observe(serverAtResponse: Long, sentElapsed: Long, receivedElapsed: Long) {
        if (
            serverAtResponse <= 0 ||
                receivedElapsed < sentElapsed ||
                receivedElapsed - sentElapsed > 30000
        )
            return
        val sample =
            ClockSnapshot(
                serverAtResponse - (sentElapsed + (receivedElapsed - sentElapsed) / 2),
                (receivedElapsed - sentElapsed + 1) / 2,
                receivedElapsed,
            )
        val old = value
        if (
            old == null || !old.usable(receivedElapsed) || sample.uncertaintyMs <= old.uncertaintyMs
        )
            value = sample
    }

    @Synchronized fun snapshot(elapsed: Long): ClockSnapshot? = value?.takeIf { it.usable(elapsed) }

    @Synchronized fun latest(): ClockSnapshot? = value

    @Synchronized
    fun reset() {
        value = null
    }
}

/** Deterministic DSP shared with tests; hooks always precede gain and limiting. */
class MonitorDsp {
    private var previous = 0f
    private var high = 0f
    private var wasFocused = false

    fun process(input: ShortArray, gain: Float, focus: Boolean): ShortArray {
        if (focus != wasFocused) {
            previous = 0f
            high = 0f
            wasFocused = focus
        }
        val safeGain = if (gain.isFinite()) gain.coerceIn(0f, 1f) else 0f
        return ShortArray(input.size) { i ->
            var x = input[i].toFloat()
            if (focus) {
                high = .92f * (high + x - previous)
                previous = x
                x = high
            }
            (24000f * tanh(x * safeGain / 24000f)).toInt().coerceIn(-24000, 24000).toShort()
        }
    }
}
