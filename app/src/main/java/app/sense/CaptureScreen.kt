package app.sense

import android.os.*
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sense.camera.*
import app.sense.core.*
import app.sense.data.*
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.*

@Composable
internal fun CaptureScreen(vm: SenseViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    Page {
        Title(
            "Capture",
            "Same moment. Every angle.",
            "Bring your phones together for a synchronized four-second clip.",
        )
        FeatureRow(
            "Moment",
            "Invite friends, get ready, capture together",
            Icons.Outlined.CameraAlt,
        ) {
            vm.open(Feature.MOMENT)
        }
        HorizontalDivider(color = Surface)
        Text("Saved moments", style = MaterialTheme.typography.titleMedium)
        if (history.isEmpty()) Text("Your first capture will appear here.", color = Muted)
        history.forEach { r ->
            val j = runCatching { JSONObject(r.payload) }.getOrNull()
            if (j != null)
                ClipRow(
                    vm,
                    Clip(
                        java.io.File(j.getString("file")),
                        j.getLong("startedAt"),
                        j.optLong("requestedAt"),
                        j.optLong("uncertainty"),
                        j.optString("session"),
                    ),
                    j.optString("session"),
                )
        }
    }
}

@Composable
internal fun MomentScreen(vm: SenseViewModel, c: Capabilities, permission: (String) -> Unit) {
    val sessions by vm.g.backend.sessions.collectAsStateWithLifecycle()
    val vision by vm.g.camera.state.collectAsStateWithLifecycle()
    val state by vm.g.moment.state.collectAsStateWithLifecycle()
    val clips by vm.g.moment.clips.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var ready by remember(selected) { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(selected, ready, vision.active) {
        val target = selected
        if (target != null && ready && vision.active)
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    while (true) {
                        try {
                            vm.g.backend.sessionAction(
                                target,
                                "ready",
                                JSONObject().put("ready", true),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            vm.g.moment.state.value =
                                vm.g.moment.state.value.copy(
                                    error = "Readiness connection lost. Reconnecting…"
                                )
                        }
                        delay(2000)
                    }
                } finally {
                    withContext(NonCancellable) {
                        runCatching {
                            withTimeout(5000) {
                                vm.g.backend.sessionAction(
                                    target,
                                    "ready",
                                    JSONObject().put("ready", false),
                                )
                            }
                        }
                    }
                }
            }
    }
    val s = sessions.firstOrNull { it.getString("id") == selected }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val joined =
        s?.getJSONArray("participants")?.let { a ->
            (0 until a.length()).any { a.getString(it) == profile.id }
        } == true
    LaunchedEffect(s?.optLong("captureAt"), vision.active, ready, joined) {
        if (s != null && !s.isNull("captureAt") && vision.active && ready && joined)
            vm.g.moment.arm(s.getString("id"), s.getLong("captureAt"))
    }
    DisposableEffect(selected) {
        val old = selected
        onDispose { if (old != null) vm.action { vm.g.backend.sessionAction(old, "leave") } }
    }
    Page {
        Title(
            "Moment",
            "One instant, together.",
            "Capture a four-second clip on each phone. Shared timestamps help align the angles later.",
        )
        if (!c.cameraPermission)
            Setup(
                "Capture with your camera",
                "Clips are saved locally. Transfer is a separate action.",
                "Allow camera",
            ) {
                permission("camera")
            }
        if (c.canCamera)
            CameraSurface(vm, CameraMode.MOMENT, Modifier.fillMaxWidth().height(240.dp))
        Action("Create capture session", vm.g.backend.authenticated) { vm.create("moment") }
        sessions
            .filter { it.getString("kind") == "moment" }
            .forEach { session ->
                val id = session.getString("id")
                OutlinedButton(
                    onClick = {
                        if (selected != id) vm.g.moment.stop()
                        selected = id
                        vm.action { vm.g.backend.sessionAction(id, "join") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.recording,
                ) {
                    Text(
                        if (selected == id) "Current session · " + id.take(8)
                        else "Join · " + id.take(8)
                    )
                }
            }
        if (s != null)
            Section("Get everyone ready") {
                val participants = s.getJSONArray("participants")
                val readyPeople = s.getJSONArray("ready")
                Text(
                    readyPeople.length().toString() +
                        " of " +
                        participants.length() +
                        " phones ready",
                    color = Muted,
                )
                val shared = s.getJSONArray("clips")
                if (shared.length() > 0) {
                    Text("Shared angles", fontWeight = FontWeight.Medium)
                    repeat(shared.length()) { i ->
                        val clip = shared.getJSONObject(i)
                        TextButton(onClick = { vm.downloadClip(s.getString("id"), clip) }) {
                            Text("Save angle · " + clip.getString("user").take(8))
                        }
                    }
                }
                FriendInvites(vm, s.getString("id"))
                Action(
                    if (state.recording) "Capturing…"
                    else if (ready) "Camera ready · tap to pause" else "My camera is ready",
                    vision.active && joined && !state.recording,
                ) {
                    if (vm.g.moment.state.value.recording) return@Action
                    val countdownActive = vm.g.moment.state.value.countdown != null
                    ready = !ready
                    if (!ready) {
                        vm.g.moment.stop()
                        if (countdownActive)
                            vm.localNotice.value =
                                "Countdown cancelled. Create a new session to capture again."
                    }
                }
                if (s.getString("host") == profile.id)
                    Action(
                        "Start synchronized countdown",
                        vision.active &&
                            s.isNull("captureAt") &&
                            readyPeople.length() > 0 &&
                            readyPeople.length() == participants.length(),
                    ) {
                        vm.action { vm.g.backend.sessionAction(s.getString("id"), "schedule") }
                    }
                if (state.countdown != null)
                    Text(state.countdown.toString(), fontSize = 48.sp, color = Accent)
                if (state.recording) Text("Capturing…", color = Accent)
                Text(
                    "Timing is best-effort. Network uncertainty: " +
                        (if (vm.g.backend.clockUncertainty == Long.MAX_VALUE) "unmeasured"
                        else vm.g.backend.clockUncertainty.toString() + " ms") +
                        ". The camera Start-event timestamp is saved; it is not a sensor-frame timestamp.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        if (state.error != null) Text(state.error!!, color = Accent)
        if (state.progress != null)
            LinearProgressIndicator(
                progress = { state.progress!! },
                modifier = Modifier.fillMaxWidth(),
            )
        clips.forEach { ClipRow(vm, it, it.sessionId) }
        Text(
            "Advanced reconstruction and bullet-time generation are in Moment Labs. This beta captures and preserves the source clips.",
            color = Muted,
            lineHeight = 22.sp,
        )
        if (!vm.g.backend.authenticated)
            TextButton(onClick = { vm.navigate(Destination.YOU) }) { Text("Set up your account") }
    }
}
