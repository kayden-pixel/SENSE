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
internal fun VeilScreen(vm: SenseViewModel, c: Capabilities, permission: (String) -> Unit) {
    val enabled by vm.veilEnabled.collectAsStateWithLifecycle()
    val people by vm.protectPeople.collectAsStateWithLifecycle()
    val you by vm.protectYou.collectAsStateWithLifecycle()
    val vision by vm.g.camera.state.collectAsStateWithLifecycle()
    Page {
        Title(
            "Veil",
            "Keep it personal.",
            "Your front camera checks for extra faces, an absent viewer, or an uncertain view. Frames never leave the phone.",
        )
        if (!c.frontCamera)
            Text("Veil is unavailable: this device has no front camera.", color = Muted)
        else if (!c.canFaceCamera)
            Setup(
                "Veil needs camera access",
                "A visible camera indicator stays on while protection is active.",
                "Allow camera",
            ) {
                permission("camera")
            }
        else {
            CameraSurface(vm, CameraMode.VEIL, Modifier.fillMaxWidth().height(220.dp))
            Text(privacyLabel(vision.privacy), color = Accent)
        }
        Section("Protection") {
            Toggle("Enable Veil", enabled) { vm.action { vm.g.store.preference("veil", it) } }
            Toggle("Protect People", people) {
                vm.action { vm.g.store.preference("protect_people", it) }
            }
            Toggle("Protect You", you) { vm.action { vm.g.store.preference("protect_you", it) } }
        }
        Text(
            "Veil assumes the single centered viewer is you. It does not identify your face or promise gaze detection. Selected screens are hidden until the view is safe. It protects SENSE screens only.",
            color = Muted,
            lineHeight = 22.sp,
        )
    }
}
