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
internal fun Onboarding(vm: SenseViewModel, profile: Profile) {
    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(32.dp))
        RadarGraphic(emptyList(), Modifier.fillMaxWidth().height(100.dp))
        Spacer(Modifier.height(24.dp))
        Title("SENSE", "A little more aware.", "Choose your username. Find your people.")
        Spacer(Modifier.height(16.dp))
        AccountForm(vm, profile)
        Spacer(Modifier.height(20.dp))
        Text(
            "Camera and mouth sensing stay on your phone. You choose permissions when you use each feature.",
            color = Muted,
        )
        if (BuildConfig.DEBUG)
            TextButton(onClick = { vm.navigate(Destination.LABS) }) { Text("Developer settings") }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
internal fun Home(vm: SenseViewModel, c: Capabilities, permission: (String) -> Unit) {
    val people by vm.g.backend.people.collectAsStateWithLifecycle()
    val near = people.count { it.online && it.relationship == Relationship.ACCEPTED }
    Page {
        Title(
            "Your space",
            "Stay in tune.",
            when {
                !c.cameraPermission && !c.microphonePermission && !c.nearbyPermission ->
                    "Start with what you want to sense."
                c.headset -> "Your headphones are connected. Listen closer."
                near > 0 -> "Your people are available. Find a little connection."
                else -> "Small signals. More connection."
            },
        )
        Box(
            Modifier.fillMaxWidth()
                .height(230.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.radialGradient(listOf(Color(0xFF1A2923), Ink)))
        ) {
            RadarGraphic(emptyList(), Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text(
                    if (near > 0) "$near friends available" else "Your circle, closer",
                    fontSize = 20.sp,
                )
                Text(
                    "Nearby sensing starts when you open Radar",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!c.nearbyPermission)
            Setup(
                "Find nearby friends",
                "Radar needs Nearby devices and precise location permission for Bluetooth distance estimates. It does not request GPS coordinates.",
                "Allow nearby access",
            ) {
                permission("nearby")
            }
        if (c.headset) {
            FeatureRow("Hear", "Bring your surroundings into focus", Icons.Outlined.Headphones) {
                vm.open(Feature.HEAR)
            }
            FeatureRow("Whisper", "A private room for your people", Icons.Outlined.GraphicEq) {
                vm.open(Feature.WHISPER)
            }
        } else {
            FeatureRow("Radar", "Know who is nearby", Icons.Outlined.Radar) {
                vm.open(Feature.RADAR)
            }
            FeatureRow("Whisper", "Talk in a temporary voice room", Icons.Outlined.GraphicEq) {
                vm.open(Feature.WHISPER)
            }
        }
        HorizontalDivider(color = Surface)
        FeatureRow("Silent", "Commands made with a quiet gesture", Icons.Outlined.Face) {
            vm.open(Feature.SILENT)
        }
        FeatureRow("Veil", "A little privacy in a public place", Icons.Outlined.VisibilityOff) {
            vm.open(Feature.VEIL)
        }
        if (!c.headset)
            FeatureRow("Hear", "Ambient listening with headphones", Icons.Outlined.Headphones) {
                vm.open(Feature.HEAR)
            }
    }
}
