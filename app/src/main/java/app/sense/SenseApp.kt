package app.sense

import android.os.*
import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sense.camera.*
import app.sense.core.*
import app.sense.data.*
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.*

@Composable
internal fun SenseApp(vm: SenseViewModel, permission: (String) -> Unit) {
    val ready by vm.ready.collectAsStateWithLifecycle()
    val signedIn by vm.g.backend.signedIn.collectAsStateWithLifecycle()
    val developerOffline by vm.developerOffline.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val destination by vm.destination.collectAsStateWithLifecycle()
    val feature by vm.feature.collectAsStateWithLifecycle()
    val cap by vm.g.capabilities.state.collectAsStateWithLifecycle()
    val vision by vm.g.camera.state.collectAsStateWithLifecycle()
    val voice by vm.g.whisper.state.collectAsStateWithLifecycle()
    val hear by vm.g.hear.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.localNotice.collectAsStateWithLifecycle()
    val veil by vm.veilEnabled.collectAsStateWithLifecycle()
    val peopleProtected by vm.protectPeople.collectAsStateWithLifecycle()
    val youProtected by vm.protectYou.collectAsStateWithLifecycle()
    val protect =
        veil &&
            feature == null &&
            (destination == Destination.PEOPLE && peopleProtected ||
                destination == Destination.YOU && youProtected)
    val activity = androidx.activity.compose.LocalActivity.current
    LaunchedEffect(ready, signedIn) { if (ready && !signedIn) vm.closeFeature() }
    DisposableEffect(protect) {
        if (protect) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    BackHandler(feature != null) { vm.closeFeature() }
    RecoveryKeyDialog(vm)
    val entry = AccountRules.entry(ready, signedIn, BuildConfig.DEBUG, developerOffline)
    if (entry == AccountRules.Entry.LOADING) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent)
        }
        return
    }
    if (entry == AccountRules.Entry.SIGN_IN) {
        if (BuildConfig.DEBUG && destination == Destination.LABS) {
            BackHandler { vm.navigate(Destination.HOME) }
            Column(Modifier.fillMaxSize().background(Ink)) {
                TextButton(onClick = { vm.navigate(Destination.HOME) }) { Text("Back to sign in") }
                LabsScreen(vm, cap)
            }
        } else Onboarding(vm, profile)
        return
    }
    Scaffold(
        containerColor = Ink,
        bottomBar = {
            if (feature == null)
                NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                    Destination.entries.forEach { d ->
                        val icon =
                            when (d) {
                                Destination.HOME -> Icons.Outlined.Home
                                Destination.PEOPLE -> Icons.Outlined.PeopleOutline
                                Destination.CAPTURE -> Icons.Outlined.CameraAlt
                                Destination.LABS -> Icons.Outlined.Science
                                Destination.YOU -> Icons.Outlined.PersonOutline
                            }
                        NavigationBarItem(
                            selected = destination == d,
                            onClick = { vm.navigate(d) },
                            icon = { Icon(icon, null) },
                            label = {
                                Text(d.name.lowercase().replaceFirstChar { it.uppercase() })
                            },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    indicatorColor = Surface,
                                    selectedIconColor = Accent,
                                    unselectedIconColor = Muted,
                                ),
                        )
                    }
                }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (feature != null)
                    IconButton(onClick = { vm.closeFeature() }) {
                        Icon(Icons.Outlined.ArrowBack, "Back")
                    }
                if (feature == null)
                    Image(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_sense_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(42.dp).padding(end = 6.dp),
                    )
                Text(
                    if (feature == null) "SENSE"
                    else feature!!.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = if (feature == null) 5.sp else 0.sp,
                    modifier = Modifier.weight(1f),
                )
                if (vision.active) SensorPill("Camera")
                if (voice.phase == VoicePhase.CONNECTED && !voice.muted || hear.active)
                    SensorPill("Mic")
            }
            if (protect) {
                CameraSurface(vm, CameraMode.VEIL, Modifier.size(1.dp))
                if (!cap.frontCamera)
                    Text("Veil cannot restore content without a front camera.", color = Muted)
                else if (!cap.canFaceCamera) {
                    Setup(
                        "Veil needs front-camera access",
                        "Frames stay on this phone.",
                        "Allow camera",
                    ) {
                        permission("camera")
                    }
                }
            }
            val hidden =
                protect &&
                    (!cap.canFaceCamera || vision.privacy != PrivacyState.SAFE || !vision.active)
            AnimatedContent(
                targetState = Pair(destination, feature),
                label = "navigation",
                modifier = Modifier.weight(1f),
            ) { (d, f) ->
                // Dispose outgoing sensor/content branches as soon as navigation changes.
                // Protected content must not remain visible during its exit animation.
                if (d != destination || f != feature) Box(Modifier.fillMaxSize())
                else if (hidden) PrivacyCover(vision.privacy)
                else if (f != null) FeatureScreen(vm, f, cap, permission)
                else
                    when (d) {
                        Destination.HOME -> Home(vm, cap, permission)
                        Destination.PEOPLE -> PeopleScreen(vm)
                        Destination.CAPTURE -> CaptureScreen(vm)
                        Destination.LABS -> LabsScreen(vm, cap)
                        Destination.YOU -> YouScreen(vm, profile, cap, permission)
                    }
            }
        }
    }
    if (error != null)
        AlertDialog(
            onDismissRequest = { vm.error.value = null },
            title = { Text("Unable to continue") },
            text = { Text(error!!) },
            confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text("Got it") } },
        )
    if (notice != null)
        AlertDialog(
            onDismissRequest = { vm.localNotice.value = null },
            text = { Text(notice!!) },
            confirmButton = { TextButton(onClick = { vm.localNotice.value = null }) { Text("OK") } },
        )
}

@Composable
internal fun FeatureScreen(
    vm: SenseViewModel,
    f: Feature,
    c: Capabilities,
    permission: (String) -> Unit,
) {
    val missing =
        when {
            f in setOf(Feature.SILENT, Feature.VEIL) && !c.frontCamera ->
                "A front camera is required on this device."
            f == Feature.MOMENT && !c.camera -> "This device has no camera."
            f in setOf(Feature.HEAR, Feature.WHISPER) && !c.microphone ->
                "This device has no microphone."
            f == Feature.RADAR && !c.bluetoothSupported ->
                "Bluetooth Low Energy is unavailable on this device."
            else -> null
        }
    if (missing != null) {
        Page { Title("Device capability", "Unavailable here.", missing) }
        return
    }
    when (f) {
        Feature.RADAR -> RadarScreen(vm, c, permission)
        Feature.WHISPER -> WhisperScreen(vm, c, permission)
        Feature.SILENT -> SilentScreen(vm, c, permission)
        Feature.VEIL -> VeilScreen(vm, c, permission)
        Feature.HEAR -> HearScreen(vm, c, permission)
        Feature.MOMENT -> MomentScreen(vm, c, permission)
    }
}
