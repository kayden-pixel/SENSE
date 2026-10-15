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
import kotlinx.coroutines.flow.first
import org.json.*

@Composable
internal fun YouScreen(
    vm: SenseViewModel,
    profile: Profile,
    c: Capabilities,
    permission: (String) -> Unit,
) {
    var name by remember(profile.name) { mutableStateOf(profile.name) }
    var url by remember { mutableStateOf(app.sense.BuildConfig.BACKEND_URL) }
    var username by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(true) }
    var legacy by remember { mutableStateOf(false) }
    val savedUsername by remember { vm.g.store.text("username") }.collectAsStateWithLifecycle("")
    val recoveryKey by vm.recoveryKey.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.recoveryKey.value = null } }
    val busy by vm.busy.collectAsStateWithLifecycle()
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
                TextButton(onClick = { vm.action { vm.g.backend.signOut() } }) { Text("Sign out") }
                TextButton(onClick = { vm.showRecoveryKey() }) { Text("Show private recovery key") }
            } else {
                LaunchedEffect(Unit) {
                    val saved = vm.g.store.text("backend").first()
                    if (saved.isNotBlank()) url = saved
                }
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text("SENSE server · https://") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Toggle("Choose a new username", register) {
                    register = it
                    key = ""
                    legacy = false
                }
                if (!register)
                    Toggle("Use an older SENSE ID", legacy) {
                        legacy = it
                        username = ""
                        key = ""
                    }
                OutlinedTextField(
                    username,
                    { username = it.take(if (legacy) 64 else 24) },
                    label = {
                        Text(
                            if (legacy) "Existing SENSE ID"
                            else "Username · 3–24 letters, numbers or hyphens"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!register)
                    OutlinedTextField(
                        key,
                        { key = it.take(128) },
                        label = {
                            Text(
                                if (legacy) "Original password"
                                else "Recovery key · leave empty on a saved phone"
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                Action(
                    if (busy) "Connecting…" else if (register) "Use this username" else "Sign in",
                    !busy &&
                        Regex(if (legacy) "[a-zA-Z0-9-]{1,64}" else "[a-zA-Z0-9][a-zA-Z0-9-]{2,23}")
                            .matches(username.trim()) &&
                        url.startsWith("https://"),
                ) {
                    vm.connect(url, username, key, register, legacy)
                    key = ""
                }
                Text(
                    "Your phone stores a private key to protect your username. New identities are created only when you choose. Keep the recovery key to sign in on another phone.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
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
            "SENSE 0.1.0 beta · Apache-2.0",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    recoveryKey?.let { secret ->
        AlertDialog(
            onDismissRequest = { vm.recoveryKey.value = null },
            properties =
                androidx.compose.ui.window.DialogProperties(
                    securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn
                ),
            title = { Text("Keep this private") },
            text = {
                Column {
                    Text(
                        "This recovery key gives access to your identity. Save it somewhere private. It is never included in the shared APK."
                    )
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(secret) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.recoveryKey.value = null }) { Text("Done") }
            },
        )
    }
}
