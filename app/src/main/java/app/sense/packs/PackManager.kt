package app.sense.packs

import android.content.Context
import app.sense.BuildConfig
import app.sense.core.*
import app.sense.data.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.*

data class PackMetadata(
    val id: String,
    val version: String,
    val sha256: String,
    val downloadSize: Long,
    val installedSize: Long,
    val requiredAppVersion: Int,
    val requiredHardware: List<String>,
    val license: String,
    val source: String,
    val runtime: String,
) {
    fun validate() {
        PackFiles.name(id, version, sha256)
        val url = source.toHttpUrlOrNull()
        require(
            url != null &&
                url.isHttps &&
                url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.fragment == null
        ) {
            "Use an HTTPS pack source"
        }
        require(downloadSize in 1..1_000_000_000 && installedSize == downloadSize)
        require(requiredAppVersion >= 1 && license.isNotBlank() && runtime.isNotBlank())
        require(requiredHardware.size <= 10)
    }

    fun json() =
        JSONObject()
            .put("id", id)
            .put("version", version)
            .put("sha256", sha256)
            .put("downloadSize", downloadSize)
            .put("installedSize", installedSize)
            .put("requiredAppVersion", requiredAppVersion)
            .put("requiredHardware", JSONArray(requiredHardware))
            .put("license", license)
            .put("source", source)
            .put("runtime", runtime)

    companion object {
        fun parse(j: JSONObject): PackMetadata {
            val h = j.getJSONArray("requiredHardware")
            return PackMetadata(
                    j.getString("id"),
                    j.getString("version"),
                    j.getString("sha256"),
                    j.getLong("downloadSize"),
                    j.getLong("installedSize"),
                    j.getInt("requiredAppVersion"),
                    (0 until h.length()).map { h.getString(it) },
                    j.getString("license"),
                    j.getString("source"),
                    j.getString("runtime"),
                )
                .also {
                    it.validate()

                    require(
                        it.id.matches(Regex("[a-z0-9_-]{1,64}")) &&
                            it.version.matches(Regex("[a-zA-Z0-9._-]{1,32}"))
                    )
                    require(it.source.startsWith("https://"))
                    require(it.sha256.matches(Regex("[a-fA-F0-9]{64}")))
                    require(
                        it.downloadSize in 1..1_000_000_000 && it.installedSize == it.downloadSize
                    )
                    require(it.license.isNotBlank() && it.runtime.isNotBlank())
                }
        }
    }
}

data class PackDownload(val id: String, val progress: Float = 0f, val error: String? = null)

class PackManager(
    context: Context,
    private val records: Records,
    private val capabilities: CapabilityService,
    private val scope: CoroutineScope,
) {
    private val folder = File(context.filesDir, "packs").apply { mkdirs() }
    private val client = SenseHttp.client
    private var job: Job? = null
    private var call: Call? = null
    private val lock = Mutex()
    val catalog = MutableStateFlow<List<PackMetadata>>(emptyList())
    val download = MutableStateFlow<PackDownload?>(null)
    val installed =
        records
            .observe("pack")
            .map { list ->
                list.mapNotNull {
                    runCatching { PackMetadata.parse(JSONObject(it.payload)) }.getOrNull()
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun loadCatalog(url: String) =
        withContext(Dispatchers.IO) {
            require(url.startsWith("https://")) { "Use an HTTPS catalog" }
            client.newCall(Request.Builder().url(url).build()).awaitBody { r ->
                check(r.isSuccessful) { "Catalog unavailable" }
                val bytes =
                    r.body?.byteStream()?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val b = ByteArray(8192)
                        while (true) {
                            val n = input.read(b)
                            if (n < 0) break
                            require(out.size() + n <= 262144) { "Catalog too large" }
                            out.write(b, 0, n)
                        }
                        out.toByteArray()
                    } ?: ByteArray(0)
                val text = bytes.toString(Charsets.UTF_8)
                val a = JSONArray(text)
                require(a.length() <= 50)
                catalog.value = (0 until a.length()).map { PackMetadata.parse(a.getJSONObject(it)) }
            }
        }

    fun install(p: PackMetadata) {
        cancel()
        job =
            scope.launch(Dispatchers.IO) {
                lock.withLock {
                    download.value = PackDownload(p.id)
                    val temp = File.createTempFile("download-", ".part", folder)
                    try {
                        p.validate()
                        val transferContext = currentCoroutineContext()
                        require(p.requiredAppVersion <= BuildConfig.VERSION_CODE) {
                            "Update SENSE before installing this pack"
                        }
                        val c = capabilities.state.value
                        require(
                            p.requiredHardware.all {
                                when (it) {
                                    "camera" -> c.camera
                                    "microphone" -> c.microphone
                                    "uwb" -> c.uwb
                                    else -> false
                                }
                            }
                        ) {
                            "Required hardware is unavailable"
                        }
                        val request = client.newCall(Request.Builder().url(p.source).build())
                        call = request
                        request.awaitBody { r ->
                            check(r.isSuccessful) { "Download failed" }
                            val body = r.body ?: error("Empty download")
                            val digest = MessageDigest.getInstance("SHA-256")
                            var count = 0L
                            java.io.FileOutputStream(temp).use { out ->
                                body.byteStream().use { input ->
                                    val b = ByteArray(65536)
                                    while (true) {
                                        transferContext.ensureActive()
                                        val n = input.read(b)
                                        if (n < 0) break
                                        count += n
                                        require(count <= p.downloadSize) {
                                            "Unexpected download size"
                                        }
                                        digest.update(b, 0, n)
                                        out.write(b, 0, n)
                                        download.value =
                                            PackDownload(p.id, count.toFloat() / p.downloadSize)
                                    }
                                }
                                out.fd.sync()
                            }
                            require(
                                count == p.downloadSize &&
                                    PackIntegrity.valid(
                                        p.sha256,
                                        digest.digest().joinToString("") { "%02x".format(it) },
                                    )
                            ) {
                                "Integrity check failed"
                            }
                        }
                        ensureActive()
                        // Immutable content address + atomic rename; database remains the
                        // authority.
                        withContext(NonCancellable) {
                            val previous = records.get("pack").firstOrNull { it.id == p.id }
                            val file = File(folder, PackFiles.name(p.id, p.version, p.sha256))
                            PackFiles.commit(temp, file, p.installedSize, p.sha256)
                            records.put(Record("pack", p.id, p.json().toString()))
                            previous?.let { old ->
                                runCatching {
                                    val meta = PackMetadata.parse(JSONObject(old.payload))
                                    val oldFile =
                                        File(
                                            folder,
                                            PackFiles.name(meta.id, meta.version, meta.sha256),
                                        )
                                    if (oldFile != file) oldFile.delete()
                                    File(folder, meta.id + "-" + meta.version + ".model").delete()
                                }
                            }
                        }
                        download.value = null
                    } catch (e: Exception) {
                        temp.delete()
                        if (e is CancellationException || !currentCoroutineContext().isActive) {
                            download.value = null
                            throw CancellationException("Download cancelled", e)
                        } else download.value = PackDownload(p.id, error = e.message)
                    } finally {
                        call = null
                    }
                }
            }
    }

    suspend fun verifiedFile(id: String): File =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val record =
                    records.get("pack").firstOrNull { it.id == id } ?: error("Pack not installed")
                val p = PackMetadata.parse(JSONObject(record.payload))
                require(p.requiredAppVersion <= BuildConfig.VERSION_CODE)
                val current = File(folder, PackFiles.name(p.id, p.version, p.sha256))
                // Version-1 installs are retained and verified; no destructive migration.
                val file =
                    if (current.exists()) current
                    else File(folder, p.id + "-" + p.version + ".model")
                PackFiles.verify(file, p.installedSize, p.sha256)
            }
        }

    suspend fun remove(id: String) =
        withContext(Dispatchers.IO) {
            if (download.value?.id == id) cancel()
            lock.withLock {
                val record = records.get("pack").firstOrNull { it.id == id }
                records.delete("pack", id)
                record?.let {
                    val p = PackMetadata.parse(JSONObject(it.payload))
                    File(folder, PackFiles.name(p.id, p.version, p.sha256)).delete()
                    File(folder, p.id + "-" + p.version + ".model").delete()
                }
            }
        }

    fun cancel() {
        call?.cancel()
        job?.cancel()
        job = null
        download.value = null
    }
}
