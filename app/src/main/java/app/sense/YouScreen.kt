package app.sense

import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
internal fun YouScreen(
    vm: SenseViewModel,
    profile: Profile,
    c: Capabilities,
    permission: (String) -> Unit,
) {
    var name by remember(profile.name) { mutableStateOf(profile.name) }
    val savedUsername by remember { vm.g.store.text("username") }.collectAsStateWithLifecycle("")
    val status by vm.g.backend.status.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val avatarPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { vm.avatar(it) }
        }
    Page {
        Title("You", "Make space for you.", "Your profile, your permissions, your choice.")
        Section("Profile") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(profile.name, profile.avatar)
                TextButton(onClick = { avatarPicker.launch("image/*") }) { Text("Choose avatar") }
                if (!profile.avatar.isNullOrBlank())
                    TextButton(onClick = { vm.clearAvatar() }) { Text("Remove") }
            }
            Text(
                "An optional avatar is shared with accepted friends when signed in.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                name,
                { name = it.take(40) },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { vm.saveProfile(name) }, enabled = name.isNotBlank()) {
                Text("Save name")
            }
            Text(if (savedUsername.isBlank()) "Your SENSE ID" else "Your username", color = Muted)
            Text(
                if (savedUsername.isBlank()) profile.id else "@" + savedUsername,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    clipboard.setText(
                        androidx.compose.ui.text.AnnotatedString(
                            if (savedUsername.isBlank()) profile.id else "@" + savedUsername
                        )
                    )
                    vm.localNotice.value = "Invite address copied"
                }
            ) {
                Text("Copy invite address")
            }
        }
        Section("Your identity", status) {
            if (vm.g.backend.authenticated) {
                Text("Signed in. Friends and sessions sync while SENSE is open.", color = Muted)
                TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
                TextButton(onClick = { vm.showRecoveryKey() }) { Text("Show private recovery key") }
            } else {
                AccountForm(vm, profile)
            }
        }
        Section("Sensor permissions") {
            PermissionRow("Camera", c.cameraPermission, "camera", permission)
            PermissionRow("Microphone", c.microphonePermission, "microphone", permission)
            PermissionRow("Nearby devices", c.nearbyPermission, "nearby", permission)
            TextButton(
                onClick = {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + ctx.packageName),
                        )
                    )
                }
            ) {
                Text("Android app settings")
            }
        }
        Section("Privacy") {
            Text(
                "Mouth samples are encrypted with Android Keystore. Veil frames are discarded. Ambient audio stays local. Clips transfer only when you choose. Blocking removes session authorization.",
                color = Muted,
                lineHeight = 22.sp,
            )
            TextButton(onClick = { vm.open(Feature.VEIL) }) { Text("Manage Veil") }
        }
        Text(
            "SENSE " + BuildConfig.VERSION_NAME + " · Apache-2.0",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
