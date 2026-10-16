package app.sense.data

import app.sense.BuildConfig
import app.sense.core.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*

class Backend(private val store: LocalStore) {
    private val client = SenseHttp.client
    private val syncLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var generation = 0L
    val clock = SessionClock()
    @Volatile private var base = BuildConfig.BACKEND_URL.trimEnd('/')
    val origin
        get() = base

    val configured
        get() = base.startsWith("https://")

    val authenticated
        get() = signedIn.value

    private val _signedIn = MutableStateFlow(false)
    val signedIn = _signedIn.asStateFlow()

    val people = MutableStateFlow<List<Person>>(emptyList())
    val sessions = MutableStateFlow<List<JSONObject>>(emptyList())
    val status = MutableStateFlow("Offline mode")
    var radarToken: String? = null
        private set

    val clockUncertainty
        get() = clock.latest()?.uncertaintyMs ?: Long.MAX_VALUE

    fun configure(url: String) {
        check(BuildConfig.DEBUG || url.trimEnd('/') == BuildConfig.BACKEND_URL.trimEnd('/')) {
            "Custom servers are available only in developer builds"
        }
        val parsed = url.trimEnd('/').takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()
        require(
            url.isEmpty() ||
                (parsed != null &&
                    parsed.isHttps &&
                    parsed.username.isEmpty() &&
                    parsed.password.isEmpty() &&
                    parsed.query == null &&
                    parsed.fragment == null)
        ) {
            "Use an HTTPS backend URL without credentials, query or fragment"
        }
        val next = parsed?.toString()?.trimEnd('/') ?: ""
        if (base != next) {
            generation++
            base = next
            _signedIn.value = false
            clock.reset()
            lastRangingReport = null
            people.value = emptyList()
            sessions.value = emptyList()
            radarToken = null
        }
    }

    suspend fun request(route: String, body: JSONObject? = null): JSONObject {
        val epoch = generation
        val origin = base
        val token = store.vault.tokenFor(origin)
        return withContext(Dispatchers.IO) {
            if (!origin.startsWith("https://")) throw ServiceFailure(FailureKind.NOT_CONFIGURED)
            if (epoch != generation) throw CancellationException("Account changed")
            val builder = Request.Builder().url(origin + route).header("Accept", "application/json")
            token?.let { builder.header("Authorization", "Bearer $it") }
            if (body != null)
                builder.post(body.toString().toRequestBody("application/json".toMediaType()))
            client.newCall(builder.build()).awaitBody { response ->
                if (epoch != generation) throw CancellationException("Account changed")
                val text =
                    response.body?.byteStream()?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            require(out.size() + n <= 4 * 1024 * 1024) {
                                "Server response too large"
                            }
                            out.write(chunk, 0, n)
                        }
                        val bytes = out.toByteArray()
                        require(bytes.size <= 4 * 1024 * 1024) { "Server response too large" }
                        bytes.toString(Charsets.UTF_8)
                    } ?: "{}"
                if (!response.isSuccessful) {
                    if (
                        response.code == 401 &&
                            !route.startsWith("/auth/") &&
                            epoch == generation &&
                            store.vault.clearTokenIfMatches(origin, token)
                    ) {
                        generation++
                        clock.reset()
                        lastRangingReport = null
                        _signedIn.value = false
                        people.value = emptyList()
                        sessions.value = emptyList()
                        radarToken = null
                    }
                    throw ServiceFailure(UserFailures.http(response.code, route))
                }
                val j =
                    try {
                        JSONObject(text)
                    } catch (_: JSONException) {
                        throw ServiceFailure(FailureKind.OFFLINE)
                    }
                if (epoch != generation) throw CancellationException("Account changed")
                j
            }
        }
    }

    suspend fun authenticate(
        password: String,
        register: Boolean,
        profile: Profile,
        username: String? = null,
    ) {
        val authOrigin = base
        val authGeneration = generation
        val j =
            request(
                if (register) "/auth/register" else "/auth/login",
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .apply { if (username != null) put("username", username) }
                    .put("password", password),
            )
        check(base == authOrigin && generation == authGeneration) {
            "Server changed during sign-in"
        }
        generation++
        val accountGeneration = generation
        lastRangingReport = null
        clock.reset()
        store.profile(j.getString("id"), j.getString("name"))
        store.text("username", j.optString("username").takeUnless { it == "null" } ?: "")
        if (username != null)
            withContext(Dispatchers.IO) {
                store.vault.identity(
                    authOrigin,
                    DeviceIdentity(j.getString("id"), username, password),
                )
            }
        if (!register)
            store.text(
                "avatar",
                j.optString("avatar").takeIf { it.startsWith("data:image/") } ?: "",
            )
        withContext(Dispatchers.IO) {
            if (base != authOrigin || generation != accountGeneration)
                throw CancellationException("Account changed")
            store.vault.token(j.getString("token"), authOrigin)
        }
        if (base != authOrigin || generation != accountGeneration)
            throw CancellationException("Account changed")
        _signedIn.value = true
        try {
            if (register && !profile.avatar.isNullOrBlank())
                request(
                    "/profile",
                    JSONObject().put("name", profile.name).put("avatar", profile.avatar),
                )
            sync()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is ServiceFailure && error.kind == FailureKind.AUTHENTICATION) throw error
            offline()
        }
    }

    suspend fun sync() =
        syncLock.withLock {
            if (!configured || !authenticated) return@withLock
            val epoch = generation
            if (lastRangingReport != rangingAvailable) {
                request("/presence", JSONObject().put("ranging", rangingAvailable))
                lastRangingReport = rangingAvailable
            }
            val start = android.os.SystemClock.elapsedRealtime()
            val j = request("/me")
            val end = android.os.SystemClock.elapsedRealtime()
            if (epoch != generation) return@withLock
            clock.observe(j.getLong("serverTime"), start, end)
            val a = j.getJSONArray("people")
            val list =
                (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    val tokens = p.optJSONArray("radarTokens") ?: JSONArray()
                    Person(
                        p.getString("id"),
                        p.getString("name"),
                        Relationship.valueOf(p.getString("relationship")),
                        p.optBoolean("online"),
                        (0 until tokens.length()).map { tokens.getString(it) },
                        p.optString("avatar").takeIf { it.startsWith("data:image/") },
                        p.optBoolean("ranging"),
                    )
                }
            people.value = list
            store.records.replace(
                "friend",
                list.map { p ->
                    Record(
                        "friend",
                        p.id,
                        JSONObject()
                            .put("name", p.name)
                            .put("relationship", p.relationship.name)
                            .put("origin", base)
                            .put("avatar", p.avatar)
                            .toString(),
                    )
                },
            )
            if (epoch != generation) return@withLock
            val s = j.getJSONArray("sessions")
            sessions.value = (0 until s.length()).map { s.getJSONObject(it) }
            radarToken = j.optString("radarToken").takeIf { it.length == 16 }
            status.value = "Connected"
        }

    var rangingAvailable = false
    private var lastRangingReport: Boolean? = null

    suspend fun restoreCache() {
        _signedIn.value = withContext(Dispatchers.IO) { store.vault.tokenFor(base) != null }
        people.value =
            store.records.get("friend").mapNotNull { r ->
                runCatching {
                        val p = JSONObject(r.payload)
                        require(p.optString("origin") == base) { "Cache belongs to another server" }
                        Person(
                            r.id,
                            p.getString("name"),
                            Relationship.valueOf(p.getString("relationship")),
                            avatar = p.optString("avatar").takeIf { it.startsWith("data:image/") },
                        )
                    }
                    .getOrNull()
            }
    }

    fun offline() {
        people.value = people.value.map { it.copy(online = false, radarTokens = emptyList()) }
        radarToken = null
        status.value = "Connection unavailable"
    }

    suspend fun friend(id: String, action: String) {
        try {
            request("/friends", JSONObject().put("id", id).put("action", action))
        } finally {
            withContext(NonCancellable) { runCatching { withTimeout(10000) { sync() } } }
        }
    }

    suspend fun create(kind: String, invitees: List<String>): JSONObject =
        request("/sessions", JSONObject().put("kind", kind).put("invitees", JSONArray(invitees)))
            .also {
                rememberSession(it.getString("id"), kind, "created")
                sync()
            }

    suspend fun sessionAction(id: String, action: String, body: JSONObject = JSONObject()) =
        request("/sessions/$id/$action", body).also {
            if (action in setOf("join", "leave"))
                rememberSession(
                    id,
                    sessions.value.firstOrNull { s -> s.getString("id") == id }?.optString("kind")
                        ?: "session",
                    action,
                )
            sync()
        }

    private suspend fun rememberSession(id: String, kind: String, action: String) {
        store.records.put(
            Record(
                "session_history",
                id,
                JSONObject()
                    .put("kind", kind)
                    .put("action", action)
                    .put("at", System.currentTimeMillis())
                    .toString(),
            )
        )
    }

    suspend fun signOut() {
        try {
            request("/logout", JSONObject())
        } finally {
            generation++
            clock.reset()
            lastRangingReport = null
            _signedIn.value = false
            people.value = emptyList()
            sessions.value = emptyList()
            radarToken = null
            status.value = "Offline mode"
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    store.vault.token(null)
                } finally {
                    store.records.clear("friend")
                }
            }
        }
    }

    suspend fun upload(
        session: String,
        file: File,
        capturedAt: Long,
        uncertainty: Long,
        progress: (Float) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            val origin = base
            val token = store.vault.tokenFor(origin) ?: error("Sign in required")
            val epoch = generation
            val transferContext = currentCoroutineContext()
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    transferContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val body =
                object : RequestBody() {
                    override fun contentType() = "video/mp4".toMediaType()

                    override fun contentLength() = file.length()

                    override fun writeTo(sink: okio.BufferedSink) {
                        file.inputStream().use { input ->
                            val b = ByteArray(65536)
                            var sent = 0L
                            while (true) {
                                transferContext.ensureActive()
                                if (epoch != generation)
                                    throw java.io.IOException("Account changed")
                                val n = input.read(b)
                                if (n < 0) break
                                sink.write(b, 0, n)
                                sent += n
                                progress(sent.toFloat() / file.length())
                            }
                        }
                    }
                }
            val req =
                Request.Builder()
                    .url(origin + "/sessions/$session/clips")
                    .header("Authorization", "Bearer " + token)
                    .header("X-SHA256", sha)
                    .header("X-Captured-At", capturedAt.toString())
                    .header("X-Clock-Uncertainty", uncertainty.toString())
                    .post(body)
                    .build()
            client.newCall(req).awaitBody { r ->
                val j = JSONObject(r.body?.string() ?: "{}")
                check(r.isSuccessful) { j.optString("error", "Transfer failed") }
                j.getString("id")
            }
        }

    suspend fun downloadToFile(session: String, id: String, file: File, progress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val transferContext = currentCoroutineContext()
            val origin = base
            val token = store.vault.tokenFor(origin) ?: error("Sign in required")
            val epoch = generation
            val req =
                Request.Builder()
                    .url(origin + "/sessions/$session/clips/$id")
                    .header("Authorization", "Bearer " + token)
                    .build()
            try {
                client.newCall(req).awaitBody { r ->
                    check(r.isSuccessful) { "Clip unavailable" }
                    val expected = r.header("X-SHA256") ?: error("Missing clip integrity metadata")
                    val body = r.body ?: error("Empty clip")
                    val size = body.contentLength()
                    require(size in 1..104857600) { "Unexpected clip size" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val bytes = ByteArray(65536)
                            while (true) {
                                transferContext.ensureActive()
                                if (epoch != generation)
                                    throw java.io.IOException("Account changed")
                                val n = input.read(bytes)
                                if (n < 0) break
                                count += n
                                require(count <= size)
                                out.write(bytes, 0, n)
                                digest.update(bytes, 0, n)
                                progress(count.toFloat() / size)
                            }
                        }
                    }
                    check(
                        count == size &&
                            PackIntegrity.valid(
                                expected,
                                digest.digest().joinToString("") { "%02x".format(it) },
                            )
                    ) {
                        "Clip integrity failure"
                    }
                }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

    suspend fun oob(to: String, payload: String) {
        request("/oob", JSONObject().put("to", to).put("payload", payload))
    }

    suspend fun packets(): List<Pair<String, String>> {
        val a = request("/oob").getJSONArray("packets")
        return (0 until a.length()).map {
            a.getJSONObject(it).let { p -> p.getString("sender") to p.getString("payload") }
        }
    }
}
