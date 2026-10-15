package app.sense

import android.os.*
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.viewinterop.AndroidView
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
internal fun CameraSurface(vm: SenseViewModel, mode: CameraMode, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view =
        remember(mode) {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
    val c by vm.g.capabilities.state.collectAsStateWithLifecycle()
    val available = if (mode == CameraMode.MOMENT) c.canCamera else c.canFaceCamera
    LaunchedEffect(mode, available, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (available)
                try {
                    vm.g.camera.bind(owner, view, mode)
                    awaitCancellation()
                } catch (e: Exception) {
                    if (e !is CancellationException)
                        vm.error.value = "Camera unavailable: " + e.message
                } finally {
                    vm.g.camera.close(view)
                }
        }
    }
    DisposableEffect(mode) { onDispose { vm.g.camera.close(view) } }
    AndroidView(factory = { view }, modifier = modifier.clip(RoundedCornerShape(24.dp)))
}
