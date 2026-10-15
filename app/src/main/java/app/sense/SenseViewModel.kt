package app.sense

import android.app.Application
import androidx.datastore.preferences.core.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sense.camera.*
import app.sense.core.*
import app.sense.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

enum class Destination {
    HOME,
    PEOPLE,
    CAPTURE,
    LABS,
    YOU,
}

enum class Feature {
    RADAR,
    WHISPER,
    SILENT,
    VEIL,
    HEAR,
    MOMENT,
}

class SenseViewModel(app: Application) : AndroidViewModel(app) {
    val g = (app as SenseApplication).graph
    val destination = MutableStateFlow(Destination.HOME)
    val feature = MutableStateFlow<Feature?>(null)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val profile = g.store.profile.stateIn(viewModelScope, SharingStarted.Eagerly, Profile("", ""))
    val onboarding =
        g.store.flag("onboarded").stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val veilEnabled = g.store.flag("veil").stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val protectPeople =
        g.store.flag("protect_people").stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val protectYou =
        g.store.flag("protect_you").stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val history =
        g.store.records
            .observe("history")
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val localNotice = MutableStateFlow<String?>(null)
    val recoveryKey = MutableStateFlow<String?>(null)
    private var polling: Job? = null

    private val initialized =
        viewModelScope.async {
            try {
                withContext(Dispatchers.IO) { g.store.vault.token() }
                g.store.ensureProfile()
                val url = g.store.text("backend").first()
                if (url.isNotBlank()) g.backend.configure(url)
                g.backend.restoreCache()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error.value = e.message
            }
        }

    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e !is CancellationException) error.value = e.message ?: "Action unavailable"
            }
        }
    }

    fun start() {
        g.capabilities.refresh()
        if (feature.value == Feature.RADAR || feature.value == Feature.WHISPER)
            g.radar.start(profile.value.id)
        if (polling != null) return
        polling =
            viewModelScope.launch {
                initialized.await()
                while (isActive) {
                    runCatching {
                            val c = g.capabilities.state.value
                            g.backend.rangingAvailable =
                                c.ranging &&
                                    c.rangingPermission &&
                                    (feature.value == Feature.RADAR ||
                                        feature.value == Feature.WHISPER)
                            g.backend.sync()
                        }
                        .onFailure { g.backend.offline() }
                    delay(2000)
                }
            }
    }

    fun stop() {
        recoveryKey.value = null
        polling?.cancel()
        polling = null
        g.radar.stop()
        g.whisper.disconnect()
        g.hear.stop()
        g.silent.stop()
        g.moment.stop()
        g.camera.close()
    }

    fun navigate(to: Destination) {
        closeFeature()
        destination.value = to
    }

    fun open(f: Feature) {
        closeFeature()
        feature.value = f
        when (f) {
            Feature.RADAR -> g.radar.start(profile.value.id)
            Feature.WHISPER ->
                if (g.capabilities.state.value.canRadar) g.radar.start(profile.value.id)
            else -> Unit
        }
    }

    fun closeFeature() {
        g.radar.stop()
        g.whisper.disconnect()
        g.hear.stop()
        g.silent.stop()
        g.moment.stop()
        g.camera.close()
        feature.value = null
    }

    fun onboard(name: String) {
        action {
            g.store.profile(profile.value.id, name)
            g.store.preference("onboarded", true)
        }
    }

    fun saveProfile(name: String) {
        action {
            if (g.backend.authenticated)
                g.backend.request("/profile", JSONObject().put("name", name))
            g.store.profile(profile.value.id, name)
        }
    }

    fun avatar(uri: android.net.Uri) {
        action {
            val image =
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val bounds =
                        android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                    }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a valid image" }
                    val options =
                        android.graphics.BitmapFactory.Options().apply {
                            inSampleSize =
                                maxOf(bounds.outWidth, bounds.outHeight).div(192).coerceAtLeast(1)
                        }
                    val source =
                        resolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, options)
                        } ?: error("Image unavailable")
                    val bitmap = android.graphics.Bitmap.createScaledBitmap(source, 96, 96, true)
                    val out = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                    if (bitmap != source) source.recycle()
                    bitmap.recycle()
                    "data:image/jpeg;base64," +
                        android.util.Base64.encodeToString(
                            out.toByteArray(),
                            android.util.Base64.NO_WRAP,
                        )
                }
            if (g.backend.authenticated)
                g.backend.request(
                    "/profile",
                    JSONObject().put("name", profile.value.name).put("avatar", image),
                )
            g.store.text("avatar", image)
        }
    }

    fun clearAvatar() {
        action {
            if (g.backend.authenticated)
                g.backend.request(
                    "/profile",
                    JSONObject().put("name", profile.value.name).put("avatar", JSONObject.NULL),
                )
            g.store.text("avatar", "")
        }
    }

    fun connect(
        url: String,
        username: String,
        key: String?,
        register: Boolean,
        legacy: Boolean = false,
    ) {
        action {
            initialized.await()
            busy.value = true
            try {
                g.backend.configure(url)
                val origin = g.backend.origin
                g.store.text("backend", origin)
                val normalized =
                    if (legacy) username.trim()
                    else username.trim().lowercase(java.util.Locale.ROOT)
                require(
                    (legacy && !register && Regex("[a-zA-Z0-9-]{1,64}").matches(normalized)) ||
                        (!legacy && Regex("[a-z0-9][a-z0-9-]{2,23}").matches(normalized))
                ) {
                    "Username needs 3–24 letters, numbers or hyphens"
                }
                val saved =
                    withContext(Dispatchers.IO) { g.store.vault.identity(origin) }
                        ?.takeIf { !legacy && it.username == normalized }
                val identity =
                    if (register)
                        saved
                            ?: DeviceIdentity(
                                java.util.UUID.randomUUID().toString(),
                                normalized,
                                android.util.Base64.encodeToString(
                                    ByteArray(32).also {
                                        java.security.SecureRandom().nextBytes(it)
                                    },
                                    android.util.Base64.NO_WRAP or
                                        android.util.Base64.URL_SAFE or
                                        android.util.Base64.NO_PADDING,
                                ),
                            )
                    else
                        DeviceIdentity(
                            if (legacy) normalized else saved?.id ?: profile.value.id,
                            normalized,
                            key?.takeIf { it.isNotBlank() }
                                ?: saved?.recoveryKey
                                ?: error("Enter your private recovery key on a new phone"),
                        )
                if (register)
                    withContext(Dispatchers.IO) { g.store.vault.identity(origin, identity) }
                g.backend.authenticate(
                    identity.recoveryKey,
                    register,
                    profile.value.copy(id = identity.id),
                    if (legacy) null else normalized,
                )
                if (register) recoveryKey.value = identity.recoveryKey
            } finally {
                busy.value = false
            }
        }
    }

    fun showRecoveryKey() = action {
        val origin =
            g.store.text("backend").first().ifBlank { BuildConfig.BACKEND_URL }.trimEnd('/')
        recoveryKey.value =
            withContext(Dispatchers.IO) { g.store.vault.identity(origin)?.recoveryKey }
        if (recoveryKey.value == null) error("This legacy identity uses your original password")
    }

    fun create(kind: String) {
        action {
            val s = g.backend.create(kind, emptyList())
            feature.value = if (kind == "voice") Feature.WHISPER else Feature.MOMENT
            localNotice.value = "Session created. Invite friends below"
        }
    }

    fun downloadClip(session: String, clip: JSONObject) {
        action {
            val file =
                java.io.File(
                    getApplication<Application>().filesDir,
                    "clips/shared-" + java.util.UUID.randomUUID() + ".mp4",
                )
            g.moment.state.value = g.moment.state.value.copy(progress = 0f)
            try {
                g.backend.downloadToFile(session, clip.getString("id"), file) { p ->
                    g.moment.state.value = g.moment.state.value.copy(progress = p)
                }
                g.store.history(
                    file.name,
                    JSONObject()
                        .put("file", file.absolutePath)
                        .put("session", session)
                        .put("startedAt", clip.getLong("captured_at"))
                        .put("uncertainty", clip.getLong("uncertainty")),
                )
                localNotice.value = "Clip saved in Capture"
            } finally {
                g.moment.state.value = g.moment.state.value.copy(progress = null)
            }
        }
    }

    fun execute(r: Recognition) {
        g.silent.clearRecognition()
        when (r.command.action) {
            "Radar" -> open(Feature.RADAR)
            "Whisper" -> open(Feature.WHISPER)
            "Camera" -> open(Feature.MOMENT)
            "Mute" -> action { g.whisper.mute() }
            "Yes",
            "No" -> localNotice.value = r.command.action
            else -> localNotice.value = r.command.name
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
