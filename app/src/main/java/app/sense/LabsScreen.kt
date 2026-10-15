package app.sense

import android.os.*
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
internal fun LabsScreen(vm: SenseViewModel, c: Capabilities) {
    val ranges by vm.g.radar.measurements.collectAsStateWithLifecycle()
    val hear by vm.g.hear.state.collectAsStateWithLifecycle()
    val installed by vm.g.packs.installed.collectAsStateWithLifecycle()
    val catalog by vm.g.packs.catalog.collectAsStateWithLifecycle()
    val download by vm.g.packs.download.collectAsStateWithLifecycle()
    var simulation by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(vm.g.backend.origin) }
    val signedIn by vm.g.backend.signedIn.collectAsStateWithLifecycle()
    val accountError by vm.error.collectAsStateWithLifecycle()
    Page {
        Title(
            "Labs",
            "At the edge.",
            "Experimental capabilities and device details. Nothing here switches on automatically.",
        )
        if (BuildConfig.DEBUG)
            Section("Developer server", "Developer builds only") {
                OutlinedTextField(
                    serverUrl,
                    { serverUrl = it },
                    label = { Text("HTTPS backend URL") },
                    singleLine = true,
                    enabled = !signedIn,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (signedIn) Text("Sign out before changing the developer server.", color = Muted)
                else {
                    Action(
                        "Use developer server",
                        serverUrl.startsWith("https://") || serverUrl.isBlank(),
                    ) {
                        vm.changeDeveloperServer(serverUrl)
                    }
                    TextButton(onClick = { vm.openOfflineDeveloperPreview() }) {
                        Text("Open offline developer preview")
                    }
                }
                accountError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        Section("Hardware") {
            Detail("UWB", if (c.uwb) "Supported" else "Unavailable")
            Detail("Android Ranging", if (c.ranging) "Available" else "Unavailable")
            Detail("Current source", ranges.values.firstOrNull()?.source?.name ?: "Off")
            Detail("Audio route", c.route)
            Detail("Sample rate", hear.sampleRate.toString() + " Hz")
            Detail("PCM buffer estimate", hear.bufferMs.toString() + " ms")
            Detail("Measured total latency", "Not measured")
            Detail("Noise suppression", if (hear.noiseReduction) "Active" else "Off or unavailable")
        }
        Section(
            "Developer Simulation Mode",
            "Explicit synthetic Radar data. Never used outside this screen.",
        ) {
            Toggle("Simulate a participant", simulation) {
                simulation = it
                vm.g.radar.simulate(it)
            }
            if (simulation) {
                Text("DEVELOPER SIMULATION", color = Accent)
                RadarGraphic(ranges.values.toList(), Modifier.fillMaxWidth().height(180.dp))
            }
        }
        DisposableEffect(Unit) { onDispose { if (simulation) vm.g.radar.stop() } }
        Section(
            "Capability packs",
            "Use a trusted HTTPS catalog. Downloads are verified before installation and again before loading.",
        ) {
            OutlinedTextField(
                url,
                { url = it },
                label = { Text("Catalog URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Action("Load catalog", url.startsWith("https://")) {
                vm.action { vm.g.packs.loadCatalog(url) }
            }
            catalog.forEach { pack ->
                Text(pack.id + " · " + pack.version)
                Text(
                    pack.license +
                        " · " +
                        pack.runtime +
                        " · " +
                        (pack.downloadSize / 1024 / 1024) +
                        " MB",
                    color = Muted,
                )
                TextButton(onClick = { vm.g.packs.install(pack) }) {
                    Text(
                        if (download?.id == pack.id && download?.error != null) "Retry"
                        else "Install"
                    )
                }
            }
            download?.let { d ->
                Text(d.error ?: "Downloading " + d.id, color = Accent)
                LinearProgressIndicator(
                    progress = { d.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { vm.g.packs.cancel() }) { Text("Cancel") }
            }
            if (installed.isEmpty()) Text("No optional packs installed", color = Muted)
            installed.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.id, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            vm.action {
                                vm.g.packs.verifiedFile(p.id)
                                vm.localNotice.value = "Pack integrity verified"
                            }
                        }
                    ) {
                        Text("Verify")
                    }
                    TextButton(onClick = { vm.action { vm.g.packs.remove(p.id) } }) {
                        Text("Remove")
                    }
                }
            }
        }
        Section(
            "Hear Labs",
            "Audiovisual target-speaker separation needs a validated local model. No separation model is bundled.",
        ) {}
        Section(
            "Moment Labs",
            "Reconstruction has an extension interface. This beta does not generate 4D scenes or bullet-time video.",
        ) {}
        if (c.uwb && !c.ranging)
            Text(
                "Standalone UWB on Android 15 and earlier is planned. This beta uses Bluetooth on those versions.",
                color = Muted,
            )
    }
}
