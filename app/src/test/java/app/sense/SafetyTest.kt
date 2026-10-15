package app.sense

import app.sense.core.*
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SafetyTest {
    @Test
    fun temporalWindowsRejectLostFacesGapsAndMalformedVectors() {
        val w = MouthWindow()
        repeat(20) { w.add(MouthFrame(floatArrayOf(it.toFloat()), it * 80L)) }
        assertEquals(20, w.snapshot().size)
        assertTrue(w.add(MouthFrame(null, 1600)).isEmpty())
        w.add(MouthFrame(floatArrayOf(1f), 1680))
        assertEquals(1, w.add(MouthFrame(floatArrayOf(2f), 2100)).size)
        assertTrue(w.add(MouthFrame(floatArrayOf(Float.NaN), 2180)).isEmpty())
        w.add(MouthFrame(floatArrayOf(1f), 2260))
        assertEquals(1, w.add(MouthFrame(floatArrayOf(1f, 2f), 2340)).size)
        assertEquals(1, w.add(MouthFrame(floatArrayOf(3f), 2200)).size)
    }

    @Test
    fun windowsOwnCopiesAndBoundMemory() {
        val w = MouthWindow(3)
        val input = floatArrayOf(1f)
        repeat(8) { w.add(MouthFrame(input, it * 80L)) }
        input[0] = 99f
        assertEquals(3, w.snapshot().size)
        val returned = w.snapshot()
        returned[0][0] = 88f
        assertEquals(1f, w.snapshot()[0][0])
    }

    @Test
    fun malformedMouthSamplesNeverBecomeRecognitions() {
        val valid = List(24) { floatArrayOf(0f, 1f) }
        val command = SilentCommand("a", "Yes", "Yes", List(3) { valid })
        for (bad in
            listOf(
                List(24) { floatArrayOf(Float.NaN, 1f) },
                List(24) { floatArrayOf() },
                emptyList(),
                List(129) { floatArrayOf(0f, 1f) },
            )) {
            assertNull(MouthMatcher.recognize(bad, listOf(command)))
        }
        assertNull(MouthMatcher.recognize(valid, listOf(command.copy(threshold = Double.NaN))))
    }

    @Test
    fun clockUsesMonotonicTimeRejectsStaleAndHighLatency() {
        val clock = SessionClock()
        assertNull(clock.snapshot(10))
        clock.observe(1_000_000, 100, 140)
        val sample = clock.snapshot(140)!!
        assertEquals(20, sample.uncertaintyMs)
        assertEquals(1_000_120, sample.serverTime(240))
        // A clock snapshot never reads the adjustable device wall clock.
        assertEquals(1_000_220, sample.serverTime(340))
        clock.observe(9_000_000, 150, 1150)
        assertEquals(sample, clock.snapshot(1150))
        assertNull(clock.snapshot(30141))
        clock.observe(2_000_000, 40000, 42000)
        assertNull(clock.snapshot(42000))
        clock.observe(2_000_000, 43000, 43010)
        assertNotNull(clock.snapshot(43010))
        clock.reset()
        assertNull(clock.snapshot(43010))
    }

    @Test
    fun noiseLimiterContainsFullScaleAndInvalidGain() {
        val dsp = MonitorDsp()
        val full = ShortArray(1000) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        for (focus in listOf(false, true)) {
            val result = dsp.process(full, 10f, focus)
            assertTrue(result.all { it.toInt() in -24000..24000 })
            assertTrue(dsp.process(full, Float.NaN, focus).all { it == 0.toShort() })
            assertTrue(dsp.process(full, -1f, focus).all { it == 0.toShort() })
        }
        val dc = dsp.process(ShortArray(1000) { 10000 }, .5f, true)
        assertTrue(kotlin.math.abs(dc.last().toInt()) < 10)
    }

    @Test
    fun rangeNonfiniteAnglesCannotPoisonSpatialAudio() {
        val f = RangeFilter()
        val m =
            Measurement("a", 2.0, Double.NaN, Double.POSITIVE_INFINITY, Technology.UWB, .9, 1000)
        val filtered = f.accept(m)!!
        assertNull(filtered.azimuth)
        assertNull(filtered.elevation)
        val gains = StereoSpatializer.gains(filtered, 1001)
        assertTrue(gains.left.isFinite() && gains.right.isFinite())
        assertTrue(m.stale(999))
    }

    @Test
    fun privacyInterruptionResetsStableFrameCount() {
        val gate = PrivacyGate()
        repeat(8) { gate.update(1, true, true) }
        assertEquals(PrivacyState.CHECKING, gate.update(1, true, false))
        repeat(7) { assertEquals(PrivacyState.CHECKING, gate.update(1, true, true)) }
        assertEquals(PrivacyState.SAFE, gate.update(1, true, true))
    }

    @Test
    fun atomicPackFailurePreservesPreviousArtifact() {
        val folder = Files.createTempDirectory("sense-pack").toFile()
        fun sha(data: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        try {
            val original = "original".toByteArray()
            val next = "replacement".toByteArray()
            val old =
                java.io.File(folder, PackFiles.name("face", "1", sha(original))).apply {
                    writeBytes(original)
                }
            val staged = java.io.File(folder, "download.part").apply { writeBytes(next) }
            val destination = java.io.File(folder, PackFiles.name("face", "1", sha(next)))
            assertThrows(IllegalStateException::class.java) {
                PackFiles.commit(staged, destination, next.size.toLong(), "0".repeat(64))
            }
            assertArrayEquals(
                original,
                PackFiles.verify(old, original.size.toLong(), sha(original)).readBytes(),
            )
            assertFalse(destination.exists())
            PackFiles.commit(staged, destination, next.size.toLong(), sha(next))
            assertFalse(staged.exists())
            assertArrayEquals(
                next,
                PackFiles.verify(destination, next.size.toLong(), sha(next)).readBytes(),
            )
            destination.writeText("tampered")
            assertThrows(RuntimeException::class.java) {
                PackFiles.verify(destination, next.size.toLong(), sha(next))
            }
            assertThrows(IllegalArgumentException::class.java) {
                PackFiles.name("../escape", "1", sha(original))
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}
