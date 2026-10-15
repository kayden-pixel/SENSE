package app.sense

import android.content.Intent
import android.os.*
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
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
internal fun RadarScreen(vm: SenseViewModel, c: Capabilities, permission: (String) -> Unit) {
    val map by vm.g.radar.measurements.collectAsStateWithLifecycle()
    val status by vm.g.radar.status.collectAsStateWithLifecycle()
    val people by vm.g.backend.people.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(c.canRadar) {
        if (c.canRadar) vm.g.radar.start(vm.profile.value.id) else vm.g.radar.stop()
    }
    Page {
        Title("Nearby", "Feel the distance.", status)
        if (!c.ranging)
            Text(
                "This Android version/device uses approximate Bluetooth distance. Direction is unavailable.",
                color = Muted,
            )
        RadarGraphic(map.values.toList(), Modifier.fillMaxWidth().height(280.dp))
        if (!c.nearbyPermission)
            Setup(
                "Nearby permission",
                "Radar uses Nearby devices and precise location permission to estimate friend distance from Bluetooth signals. No GPS coordinates are requested.",
                "Allow nearby",
            ) {
                permission("nearby")
            }
        else if (!c.locationEnabled) {
            val ctx = LocalContext.current
            Setup(
                "Location services are off",
                "Android requires location services for Bluetooth distance scanning.",
                "Open location settings",
            ) {
                ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        } else if (!c.bluetooth) {
            val ctx = LocalContext.current
            Setup(
                "Bluetooth is off",
                "Enable Bluetooth to find nearby phones.",
                "Open Bluetooth settings",
            ) {
                ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }
        if (!vm.g.backend.authenticated)
            Setup(
                "Connect with your circle",
                "Sign in to recognize your friends' nearby phones.",
                "Set up account",
            ) {
                vm.navigate(Destination.YOU)
            }
        if (map.isEmpty())
            Text("No nearby measurements yet. Both phones should have Radar open.", color = Muted)
        map.values.forEach { m ->
            Section(
                people.firstOrNull { it.id == m.id }?.name
                    ?: if (m.source == Technology.SIMULATION) "Simulation participant" else "Friend"
            ) {
                Row {
                    Text(
                        if (m.stale(now)) "Last seen"
                        else if (m.source == Technology.BLE_RSSI)
                            "~" + "%.1f".format(m.distance) + " m"
                        else "%.2f".format(m.distance) + " m",
                        fontSize = 28.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (m.azimuth == null) "Distance only" else "Direction available",
                        color = Muted,
                    )
                }
                Text(
                    when (m.source) {
                        Technology.BLE_RSSI ->
                            "Bluetooth estimate · affected by walls, bodies and signal strength"
                        Technology.UWB -> "UWB · precise ranging"
                        Technology.ANDROID_RANGING -> "Android Ranging"
                        Technology.SIMULATION -> "DEVELOPER SIMULATION"
                    },
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (m.stale(now)) Text("Measurement is stale", color = Accent)
            }
        }
        Text(
            "Direction appears only when measured by the hardware. Keep SENSE open while sensing.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
