package app.sense

import android.Manifest
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import app.sense.camera.*
import app.sense.core.*
import app.sense.data.*
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.*

class MainActivity : ComponentActivity() {
    private val vm: SenseViewModel by viewModels()
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.g.capabilities.refresh()
            if (it.values.any { granted -> !granted })
                vm.localNotice.value = "Permission declined. You can enable it in Android settings."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        setContent { SenseTheme { SenseApp(vm) { kind -> requestPermission(kind) } } }
    }

    private fun requestPermission(kind: String) {
        val list =
            when (kind) {
                "camera" -> arrayOf(Manifest.permission.CAMERA)
                "microphone" -> arrayOf(Manifest.permission.RECORD_AUDIO)
                "nearby" ->
                    if (Build.VERSION.SDK_INT >= 36)
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            "android.permission.RANGING",
                        )
                    else if (Build.VERSION.SDK_INT >= 31)
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                        )
                    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                "notifications" ->
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                    else emptyArray()
                else -> emptyArray()
            }
        if (list.isNotEmpty()) permissions.launch(list)
    }

    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        vm.stop()
        super.onStop()
    }
}
