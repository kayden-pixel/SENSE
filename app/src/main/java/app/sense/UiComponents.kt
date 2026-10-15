package app.sense

import android.content.Intent
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import app.sense.camera.*
import app.sense.core.*
import app.sense.data.*
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.*

internal val Ink = Color(0xFF080A0B)
internal val Surface = Color(0xFF15181A)
internal val Accent = Color(0xFFB9E6CE)
internal val Muted = Color(0xFF9CA5A3)

@Composable
internal fun SenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Accent,
                onPrimary = Ink,
                background = Ink,
                surface = Surface,
                surfaceVariant = Color(0xFF202526),
                onSurface = Color(0xFFE9EEEB),
                onSurfaceVariant = Muted,
                outline = Color(0xFF333A38),
            ),
        typography = Typography(),
        content = content,
    )
}

@Composable
internal fun SensorPill(label: String) {
    Row(
        Modifier.padding(start = 8.dp)
            .background(Surface, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(Accent, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Accent)
    }
}

@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
internal fun Title(kicker: String, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            kicker.uppercase(),
            color = Accent,
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 2.sp,
        )
        Text(title, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium)
        Text(body, color = Muted, lineHeight = 23.sp)
    }
}

@Composable
internal fun Section(
    title: String,
    body: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(24.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body != null) Text(body, color = Muted, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
internal fun FeatureRow(
    name: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(23.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(name, fontSize = 19.sp)
            Text(description, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
    }
}

@Composable
internal fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
    ) {
        Text(text)
    }
}

@Composable
internal fun Setup(title: String, body: String, button: String, onClick: () -> Unit) {
    Section(title, body) { Action(button, onClick = onClick) }
}

@Composable
internal fun RadarGraphic(measurements: List<Measurement>, modifier: Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val r = minOf(size.width, size.height) * .37f
        repeat(3) { i ->
            drawCircle(
                Accent.copy(alpha = .09f + i * .025f),
                r * (i + 1) / 3,
                center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
            )
        }
        drawLine(
            Accent.copy(alpha = .08f),
            Offset(center.x - r, center.y),
            Offset(center.x + r, center.y),
        )
        drawLine(
            Accent.copy(alpha = .08f),
            Offset(center.x, center.y - r),
            Offset(center.x, center.y + r),
        )
        drawCircle(Accent, 4.dp.toPx(), center)
        measurements
            .filter { it.azimuth != null && !it.stale(System.currentTimeMillis()) }
            .forEach { m ->
                val radius = (m.distance / 10).coerceIn(.15, 1.0).toFloat() * r
                val a = m.azimuth!!
                val point =
                    Offset(
                        center.x + sin(a).toFloat() * radius,
                        center.y - cos(a).toFloat() * radius,
                    )
                drawCircle(Accent.copy(alpha = .15f), 13.dp.toPx(), point)
                drawCircle(Accent, 5.dp.toPx(), point)
            }
    }
}

@Composable
internal fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

internal fun privacyLabel(p: PrivacyState) =
    when (p) {
        PrivacyState.SAFE -> "Single viewer · content visible"
        PrivacyState.EXTRA_VIEWER -> "Extra face · content hidden"
        PrivacyState.OWNER_ABSENT -> "Viewer absent · content hidden"
        PrivacyState.POSSIBLE_VIEWER -> "Uncertain view · content hidden"
        PrivacyState.CHECKING -> "Checking privacy · content hidden"
    }

@Composable
internal fun PrivacyCover(state: PrivacyState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Icon(Icons.Outlined.VisibilityOff, null, tint = Accent, modifier = Modifier.size(36.dp))
            Text("A moment of privacy.", fontSize = 24.sp)
            Text(privacyLabel(state), color = Muted)
            Text(
                "Face the phone alone to restore content. Manage protection from Home → Veil.",
                color = Muted,
                lineHeight = 22.sp,
            )
        }
    }
}

@Composable
internal fun ClipRow(vm: SenseViewModel, clip: Clip, session: String) {
    val ctx = LocalContext.current
    Section(
        "Captured clip",
        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(clip.startedAt)),
    ) {
        Text("Saved on this phone · " + (clip.file.length() / 1024) + " KB", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    val uri =
                        androidx.core.content.FileProvider.getUriForFile(
                            ctx,
                            "app.sense.files",
                            clip.file,
                        )
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("video/mp4")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Share clip",
                        )
                    )
                }
            ) {
                Text("Share")
            }
            TextButton(
                onClick = { vm.action { vm.g.moment.transfer(session, clip) } },
                enabled = session.isNotBlank() && vm.g.backend.authenticated,
            ) {
                Text("Transfer to session")
            }
            TextButton(
                onClick = {
                    vm.action {
                        clip.file.delete()
                        vm.g.store.records.delete("history", clip.file.name)
                        vm.g.moment.clips.value =
                            vm.g.moment.clips.value.filter { it.file != clip.file }
                    }
                }
            ) {
                Text("Delete")
            }
        }
    }
}

@Composable
internal fun Avatar(name: String, data: String?) {
    var bitmap by remember(data) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(data) {
        bitmap =
            withContext(Dispatchers.IO) {
                runCatching {
                        if (data == null) null
                        else {
                            val bytes =
                                android.util.Base64.decode(
                                    data.substringAfter(','),
                                    android.util.Base64.DEFAULT,
                                )
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?.asImageBitmap()
                        }
                    }
                    .getOrNull()
            }
    }
    Box(
        Modifier.size(46.dp).background(Surface, CircleShape).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null)
            Image(
                bitmap!!,
                contentDescription = "Avatar",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        else Text(name.take(1).uppercase(), color = Accent, fontSize = 20.sp)
    }
}

@Composable
internal fun Detail(label: String, value: String) {
    Row {
        Text(label, color = Muted, modifier = Modifier.weight(1f))
        Text(value)
    }
}

@Composable
internal fun PermissionRow(
    label: String,
    granted: Boolean,
    key: String,
    permission: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        if (granted) Text("Allowed", color = Accent)
        else TextButton(onClick = { permission(key) }) { Text("Enable") }
    }
}
