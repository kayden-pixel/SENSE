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
internal fun PeopleScreen(vm: SenseViewModel) {
    val people by vm.g.backend.people.collectAsStateWithLifecycle()
    val sessions by vm.g.backend.sessions.collectAsStateWithLifecycle()
    var id by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Person?>(null) }
    Page {
        Title(
            "People",
            "Your circle.",
            "Connections begin with an invitation. Nearby presence is shared with accepted friends.",
        )
        if (!vm.g.backend.authenticated)
            Setup(
                "Connect your account",
                "Friends and room invitations need your SENSE server.",
                "Set up account",
            ) {
                vm.navigate(Destination.YOU)
            }
        else
            Section("Invite someone") {
                OutlinedTextField(
                    id,
                    { id = it.take(64) },
                    label = { Text("Their @username or SENSE ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Action("Send friend request", id.isNotBlank()) {
                    vm.action {
                        vm.g.backend.friend(id.trim(), "request")
                        id = ""
                    }
                }
            }
        if (people.isEmpty())
            Text(
                "Your circle is empty. Share your username from You to get started.",
                color = Muted,
            )
        people.forEach { p ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { selected = p }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p.name, p.avatar)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(p.name, fontWeight = FontWeight.Medium)
                    Text(
                        when (p.relationship) {
                            Relationship.INCOMING -> "Wants to connect"
                            Relationship.OUTGOING -> "Invitation sent"
                            Relationship.BLOCKED -> "Blocked"
                            else -> if (p.online) "Available" else "Offline"
                        },
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (p.relationship == Relationship.INCOMING)
                    TextButton(onClick = { vm.action { vm.g.backend.friend(p.id, "accept") } }) {
                        Text("Accept")
                    }
                else Icon(Icons.Outlined.MoreHoriz, "Manage friend", tint = Muted)
            }
        }
        if (sessions.isNotEmpty())
            Section("Invitations") {
                sessions.forEach { s ->
                    FeatureRow(
                        if (s.getString("kind") == "voice") "Whisper room" else "Moment session",
                        "Temporary · tap to open",
                        if (s.getString("kind") == "voice") Icons.Outlined.GraphicEq
                        else Icons.Outlined.CameraAlt,
                    ) {
                        vm.open(
                            if (s.getString("kind") == "voice") Feature.WHISPER else Feature.MOMENT
                        )
                    }
                }
            }
    }
    selected?.let { p ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(p.name) },
            text = {
                Column {
                    Text(p.id, color = Muted)
                    if (p.relationship == Relationship.BLOCKED)
                        TextButton(
                            onClick = {
                                vm.action { vm.g.backend.friend(p.id, "unblock") }
                                selected = null
                            }
                        ) {
                            Text("Unblock")
                        }
                    else {
                        TextButton(
                            onClick = {
                                vm.action { vm.g.backend.friend(p.id, "remove") }
                                selected = null
                            }
                        ) {
                            Text(
                                if (p.relationship == Relationship.ACCEPTED) "Remove friend"
                                else "Dismiss invitation"
                            )
                        }
                        TextButton(
                            onClick = {
                                vm.action { vm.g.backend.friend(p.id, "block") }
                                selected = null
                            }
                        ) {
                            Text("Block")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Done") } },
        )
    }
}

@Composable
internal fun FriendInvites(vm: SenseViewModel, id: String) {
    val people by vm.g.backend.people.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("Invite a friend") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            people
                .filter { it.relationship == Relationship.ACCEPTED }
                .forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = {
                            expanded = false
                            vm.action {
                                vm.g.backend.sessionAction(
                                    id,
                                    "invite",
                                    JSONObject().put("id", p.id),
                                )
                            }
                        },
                    )
                }
            if (people.none { it.relationship == Relationship.ACCEPTED })
                DropdownMenuItem(
                    text = { Text("Add friends in People first") },
                    onClick = { expanded = false },
                )
        }
    }
}
