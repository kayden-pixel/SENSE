package app.sense

import app.sense.core.*
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test
    fun permissionsGateSensors() {
        val c =
            Capabilities(
                camera = true,
                microphone = true,
                bluetooth = true,
                headset = true,
                bluetoothSupported = true,
                locationEnabled = true,
            )
        assertFalse(c.canCamera)
        assertFalse(c.canRadar)
        assertFalse(c.canHear)
        assertTrue(c.copy(cameraPermission = true).canCamera)
        assertFalse(c.copy(cameraPermission = true).canFaceCamera)
        assertTrue(c.copy(cameraPermission = true, frontCamera = true).canFaceCamera)
        assertTrue(c.copy(nearbyPermission = true).canRadar)
        assertTrue(c.copy(microphonePermission = true).canHear)
        assertFalse(c.copy(microphonePermission = true, headset = false).canHear)
        assertFalse(c.copy(nearbyPermission = true, locationEnabled = false).canRadar)
        assertFalse(c.copy(nearbyPermission = true, bluetoothSupported = false).canRadar)
    }

    @Test
    fun providerPriorityRequiresNegotiationAndPermission() {
        val c =
            Capabilities(
                bluetooth = true,
                bluetoothSupported = true,
                locationEnabled = true,
                nearbyPermission = true,
                ranging = true,
                uwb = true,
                rangingPermission = true,
            )
        assertEquals(
            Technology.ANDROID_RANGING,
            ProviderSelection.choose(c, Technology.entries.toSet()),
        )
        assertEquals(
            Technology.UWB,
            ProviderSelection.choose(c, setOf(Technology.UWB, Technology.BLE_RSSI)),
        )
        assertEquals(
            Technology.BLE_RSSI,
            ProviderSelection.choose(c.copy(rangingPermission = false), Technology.entries.toSet()),
        )
        assertNull(ProviderSelection.choose(c, emptySet()))
    }

    @Test
    fun rangeRejectsInvalidAndOutOfOrderValues() {
        val filter = RangeFilter()
        val m = Measurement("a", 2.0, source = Technology.BLE_RSSI, quality = .25, timestamp = 1000)
        assertNotNull(filter.accept(m))
        assertNull(filter.accept(m.copy(timestamp = 900)))
        assertNull(filter.accept(m.copy(distance = Double.NaN, timestamp = 2000)))
        assertNull(filter.accept(m.copy(distance = -1.0, timestamp = 2000)))
    }

    @Test
    fun smoothingDoesNotManufactureDirectionOrHideStaleData() {
        val filter = RangeFilter()
        val m = Measurement("a", 2.0, source = Technology.BLE_RSSI, quality = .25, timestamp = 1000)
        filter.accept(m)
        val next = filter.accept(m.copy(distance = 10.0, timestamp = 2000))!!
        assertTrue(next.distance in 2.0..10.0)
        assertNull(next.azimuth)
        assertTrue(next.stale(9000))
        val reset = filter.accept(m.copy(distance = 20.0, timestamp = 10000))!!
        assertEquals(20.0, reset.distance, .001)
    }

    @Test
    fun directionSmoothingCrossesWrapByShortestArc() {
        val f = RangeFilter()
        val m =
            Measurement(
                "a",
                2.0,
                Math.toRadians(179.0),
                source = Technology.UWB,
                quality = .9,
                timestamp = 1,
            )
        f.accept(m)
        val next = f.accept(m.copy(azimuth = Math.toRadians(-179.0), timestamp = 2))!!
        assertTrue(Math.toDegrees(next.azimuth!!) > 179)
    }

    @Test
    fun staleOrUncertainSpatialAudioStaysCentered() {
        val m =
            Measurement(
                "a",
                2.0,
                Math.PI / 2,
                source = Technology.UWB,
                quality = .9,
                timestamp = 1000,
            )
        val right = StereoSpatializer.gains(m, 1001)
        assertTrue(right.right > right.left)
        val stale = StereoSpatializer.gains(m, 9000)
        assertEquals(stale.left, stale.right)
        val distanceOnly = StereoSpatializer.gains(m.copy(azimuth = null), 1001)
        assertEquals(distanceOnly.left, distanceOnly.right)
    }

    @Test
    fun voiceInitialStateCannotPublishBeforeConnection() {
        val s = VoiceState()
        assertEquals(VoicePhase.IDLE, s.phase)
        assertTrue(s.muted)
        assertNull(s.sessionId)
    }

    @Test
    fun privacyFailsClosedAndRestoresOnlyAfterStableView() {
        val gate = PrivacyGate()
        assertEquals(PrivacyState.EXTRA_VIEWER, gate.update(2, true, true))
        repeat(7) { assertEquals(PrivacyState.CHECKING, gate.update(1, true, true)) }
        assertEquals(PrivacyState.SAFE, gate.update(1, true, true))
        assertEquals(PrivacyState.OWNER_ABSENT, gate.update(0, true, true))
        assertEquals(PrivacyState.POSSIBLE_VIEWER, gate.update(1, false, true))
        assertEquals(PrivacyState.CHECKING, gate.update(1, true, false))
    }

    @Test
    fun packHashRequiresExactValidDigest() {
        val digest = "a".repeat(64)
        assertTrue(PackIntegrity.valid(digest, digest.uppercase()))
        assertFalse(PackIntegrity.valid(digest, "b".repeat(64)))
        assertFalse(PackIntegrity.valid("a", "a"))
    }

    private fun sample(shift: Float = 0f) =
        (0 until 24).map { i ->
            floatArrayOf(
                kotlin.math.sin(i * .3).toFloat() + shift,
                kotlin.math.cos(i * .2).toFloat(),
            )
        }

    @Test
    fun silentRecognizerRejectsUntrainedAndAmbiguousCommands() {
        val a = SilentCommand("a", "Radar", "Radar", listOf(sample(), sample(), sample()))
        assertEquals("a", MouthMatcher.recognize(sample(), listOf(a))!!.command.id)
        assertNull(MouthMatcher.recognize(sample(), listOf(a.copy(samples = listOf(sample())))))
        assertNull(MouthMatcher.recognize(sample(), listOf(a, a.copy(id = "b"))))
        assertNull(MouthMatcher.recognize(sample(3f), listOf(a)))
    }

    @Test
    fun recognitionCooldownSuppressesDuplicates() {
        val r = Recognition(SilentCommand("a", "Yes", "Yes", emptyList()), .9)
        val g = RecognitionGate()
        assertNotNull(g.accept(r, 1000))
        assertNull(g.accept(r, 1500))
        assertNotNull(g.accept(r, 4001))
    }

    @Test
    fun momentRequiresAllReadyHostAndUnexpiredSession() {
        val s = CaptureSession("s", "a", listOf("a", "b"), listOf("a"), expiresAt = 10000)
        assertFalse(s.canSchedule("a", 100))
        assertTrue(s.copy(ready = listOf("a", "b")).canSchedule("a", 100))
        assertFalse(s.copy(ready = listOf("a", "b")).canSchedule("b", 100))
        assertFalse(s.copy(ready = listOf("a", "b")).canSchedule("a", 20000))
        assertFalse(s.copy(ready = listOf("a", "b"), captureAt = 500).canSchedule("a", 100))
    }
}
