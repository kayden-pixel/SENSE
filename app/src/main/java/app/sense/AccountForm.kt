package app.sense

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sense.core.*

@Composable
internal fun AccountForm(vm: SenseViewModel, profile: Profile) {
    val savedUsername by remember { vm.g.store.text("username") }.collectAsStateWithLifecycle("")
    var username by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(true) }
    var legacy by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val cap by vm.g.capabilities.state.collectAsStateWithLifecycle()
    LaunchedEffect(savedUsername) {
        if (username.isBlank() && savedUsername.isNotBlank()) {
            username = savedUsername
            register = false
        }
    }
    Toggle("Create a new username", register) {
        register = it
        legacy = false
        key = ""
        vm.error.value = null
    }
    if (!register)
        Toggle("Use an older SENSE ID", legacy) {
            legacy = it
            username = ""
            key = ""
            vm.error.value = null
        }
    OutlinedTextField(
        username,
        {
            username = it.take(if (legacy) 64 else 24)
            vm.error.value = null
        },
        label = { Text(if (legacy) "Existing SENSE ID" else "Your username") },
        supportingText = {
            Text(
                if (legacy) "Use your original ID and password."
                else "3–24 letters, numbers or hyphens"
            )
        },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!register)
        OutlinedTextField(
            key,
            {
                key = it.take(128)
                vm.error.value = null
            },
            label = { Text(if (legacy) "Original password" else "Recovery key") },
            supportingText = {
                if (!legacy) Text("Leave empty to use the identity saved on this phone.")
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    if (!cap.online)
        Text("No connection. Connect to Wi-Fi or mobile data to continue.", color = Muted)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Action(
        if (busy) "Connecting…" else if (register) "Create my username" else "Sign in",
        !busy &&
            cap.online &&
            (if (legacy) Regex("[a-zA-Z0-9-]{1,64}").matches(username.trim())
            else AccountRules.username(username) != null),
    ) {
        vm.error.value = null
        vm.connect(username, key, register, legacy, profile.name.ifBlank { username.trim() })
        key = ""
    }
    Text(
        "Your phone keeps a private recovery key. Save it to use your username on another phone. Friends create their own identities.",
        color = Muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun RecoveryKeyDialog(vm: SenseViewModel) {
    val recoveryKey by vm.recoveryKey.collectAsStateWithLifecycle()
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
