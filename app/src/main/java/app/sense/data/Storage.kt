package app.sense.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.sense.core.*
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.json.*

private val Context.preferences by preferencesDataStore("sense_preferences")

data class DeviceIdentity(val id: String, val username: String, val recoveryKey: String)

class SecureVault(context: Context) {
    private val prefs = context.getSharedPreferences("sealed_credentials", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("sense-v1", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "sense-v1",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    @Synchronized
    fun seal(value: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    @Synchronized
    fun open(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(c.doFinal(bytes.copyOfRange(12, bytes.size)))
    }

    private var tokenOrigin: String? = prefs.getString("origin", null)

    private fun identityKey(origin: String) =
        "identity-" +
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(origin.toByteArray())
                .joinToString("") { "%02x".format(it) }

    @Synchronized
    fun identity(origin: String): DeviceIdentity? =
        runCatching {
                val encoded = prefs.getString(identityKey(origin), null) ?: return null
                val j = JSONObject(open(encoded))
                check(j.getString("origin") == origin)
                DeviceIdentity(j.getString("id"), j.getString("username"), j.getString("key"))
            }
            .getOrNull()

    @Synchronized
    fun identity(origin: String, value: DeviceIdentity) {
        require(origin.startsWith("https://"))
        val sealed =
            seal(
                JSONObject()
                    .put("origin", origin)
                    .put("id", value.id)
                    .put("username", value.username)
                    .put("key", value.recoveryKey)
                    .toString()
            )
        check(prefs.edit().putString(identityKey(origin), sealed).commit()) {
            "Could not securely save this device identity"
        }
    }

    @Synchronized
    fun tokenFor(origin: String): String? = if (tokenOrigin == origin) token() else null

    private var tokenLoaded = false
    private var cachedToken: String? = null

    @Synchronized
    fun token(): String? {
        if (!tokenLoaded) {
            cachedToken =
                prefs.getString("token", null)?.let { runCatching { open(it) }.getOrNull() }
            tokenLoaded = true
        }
        return cachedToken
    }

    @Synchronized
    fun token(value: String?, origin: String? = null) {
        require(value == null || !origin.isNullOrBlank())
        val sealed = value?.let { seal(it) }
        prefs
            .edit()
            .apply {
                if (value == null) {
                    remove("token")
                    remove("origin")
                } else {
                    putString("token", sealed)
                    putString("origin", origin)
                }
            }
            .apply()
        tokenOrigin = if (value == null) null else origin
        cachedToken = value
        tokenLoaded = true
    }
}

@Entity(
    tableName = "records",
    primaryKeys = ["kind", "id"],
    indices = [Index(value = ["kind"], name = "records_kind_index")],
)
data class Record(
    val kind: String,
    val id: String,
    val payload: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface Records {
    @Query("SELECT * FROM records WHERE kind=:kind ORDER BY updatedAt DESC")
    fun observe(kind: String): Flow<List<Record>>

    @Query("SELECT * FROM records WHERE kind=:kind ORDER BY updatedAt DESC")
    suspend fun get(kind: String): List<Record>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: Record)

    @Query("DELETE FROM records WHERE kind=:kind AND id=:id")
    suspend fun delete(kind: String, id: String)

    @Query("DELETE FROM records WHERE kind=:kind") suspend fun clear(kind: String)

    @Transaction
    suspend fun replace(kind: String, records: List<Record>) {
        clear(kind)
        records.forEach { put(it) }
    }
}

@Database(entities = [Record::class], version = 2, exportSchema = true)
abstract class SenseDatabase : RoomDatabase() {
    abstract fun records(): Records

    companion object {
        val migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS records_kind_index ON records(kind)")
                }
            }

        fun create(c: Context) =
            Room.databaseBuilder(c, SenseDatabase::class.java, "sense.db")
                .addMigrations(migration)
                .build()
    }
}

class LocalStore(private val context: Context, val records: Records, val vault: SecureVault) {
    val preferences = context.preferences.data
    val profile =
        preferences.map {
            Profile(
                it[stringPreferencesKey("id")] ?: "",
                it[stringPreferencesKey("name")] ?: "",
                it[stringPreferencesKey("avatar")],
            )
        }

    suspend fun ensureProfile() {
        context.preferences.edit {
            if (it[stringPreferencesKey("id")] == null)
                it[stringPreferencesKey("id")] = UUID.randomUUID().toString()
        }
    }

    suspend fun profile(id: String, name: String) {
        require(name.trim().length in 1..40)
        context.preferences.edit {
            it[stringPreferencesKey("id")] = id
            it[stringPreferencesKey("name")] = name.trim()
        }
    }

    suspend fun preference(key: String, value: Boolean) {
        context.preferences.edit { it[booleanPreferencesKey(key)] = value }
    }

    suspend fun text(key: String, value: String) {
        context.preferences.edit { it[stringPreferencesKey(key)] = value }
    }

    fun flag(key: String) = preferences.map { it[booleanPreferencesKey(key)] ?: false }

    fun text(key: String) = preferences.map { it[stringPreferencesKey(key)] ?: "" }

    val commands =
        records
            .observe("command")
            .map { rows ->
                rows.mapNotNull { r ->
                    runCatching {
                            val j = JSONObject(vault.open(r.payload))
                            val samples = j.getJSONArray("samples")
                            SilentCommand(
                                r.id,
                                j.getString("name"),
                                j.getString("action"),
                                (0 until samples.length()).map { i ->
                                    val frames = samples.getJSONArray(i)
                                    (0 until frames.length()).map { k ->
                                        val a = frames.getJSONArray(k)
                                        FloatArray(a.length()) { a.getDouble(it).toFloat() }
                                    }
                                },
                                j.optDouble("threshold", .78),
                                j.optBoolean("confirm", true),
                            )
                        }
                        .getOrNull()
                }
            }
            .flowOn(Dispatchers.Default)

    suspend fun command(c: SilentCommand) =
        withContext(Dispatchers.Default) {
            val samples = JSONArray()
            c.samples.forEach { s ->
                val frames = JSONArray()
                s.forEach { f -> frames.put(JSONArray(f.toList())) }
                samples.put(frames)
            }
            records.put(
                Record(
                    "command",
                    c.id,
                    vault.seal(
                        JSONObject()
                            .put("name", c.name)
                            .put("action", c.action)
                            .put("samples", samples)
                            .put("threshold", c.threshold)
                            .put("confirm", c.confirm)
                            .toString()
                    ),
                )
            )
        }

    suspend fun history(id: String, payload: JSONObject) {
        records.put(Record("history", id, payload.toString()))
    }
}
