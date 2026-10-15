package app.sense.radar

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import app.sense.core.*
import app.sense.data.Backend
import java.util.UUID
import kotlin.math.pow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

interface RangingProvider : AutoCloseable {
    val technology: Technology

    fun start()
}

class RadarService(
    private val context: Context,
    private val capabilities: CapabilityService,
    private val backend: Backend,
    private val scope: CoroutineScope,
) {
    val measurements = MutableStateFlow<Map<String, Measurement>>(emptyMap())
    val status = MutableStateFlow("Radar is off")
    private val filter = RangeFilter()
    private var ble: BleProvider? = null
    private var platform: PlatformRangingProvider? = null
    private var job: Job? = null
    private var generation = 0
    private val bleSeen = mutableMapOf<String, Long>()
    private var friendsJob: Job? = null
    var simulation = false
        private set

    fun start(profileId: String) {
        stop()
        if (!capabilities.state.value.canRadar) {
            status.value = "Allow nearby access and enable Bluetooth"
            return
        }
        val gen = generation
        status.value = "Looking for friends"
        friendsJob =
            scope.launch {
                backend.people.collect { people ->
                    val allowed =
                        people
                            .filter { it.relationship == Relationship.ACCEPTED && it.online }
                            .map { it.id }
                            .toSet()
                    measurements.value = measurements.value.filterKeys { it in allowed }
                    bleSeen.keys.retainAll(allowed)
                    if (Build.VERSION.SDK_INT >= 36) platform?.refreshPeers()
                }
            }
        ble =
            BleProvider(
                    context,
                    backend,
                    { m ->
                        if (gen == generation) {
                            bleSeen[m.id] = android.os.SystemClock.elapsedRealtime()
                            accept(m)
                        }
                    },
                    { if (gen == generation) status.value = it },
                )
                .also { it.start() }
        if (
            Build.VERSION.SDK_INT >= 36 &&
                ProviderSelection.choose(
                    capabilities.state.value,
                    setOf(Technology.ANDROID_RANGING, Technology.BLE_RSSI),
                ) == Technology.ANDROID_RANGING
        )
            platform =
                PlatformRangingProvider(
                        context,
                        backend,
                        scope,
                        profileId,
                        { if (gen == generation) accept(it) },
                        { if (gen == generation) status.value = it },
                        {
                            bleSeen
                                .filterValues {
                                    android.os.SystemClock.elapsedRealtime() - it in 0..10000
                                }
                                .keys
                        },
                    )
                    .also { it.start() }
        job =
            scope.launch {
                while (isActive) {
                    delay(1000)
                    if (!capabilities.state.value.canRadar) {
                        stop()
                        status.value =
                            "Nearby sensing is unavailable. Check Bluetooth, location services and permissions"
                        break
                    }
                    val now = System.currentTimeMillis()
                    measurements.value =
                        measurements.value.filter { now - it.value.timestamp < 30000 }
                    ble?.refreshAdvertisement()
                    if (Build.VERSION.SDK_INT >= 36) platform?.refreshPeers()
                }
            }
    }

    private fun accept(m: Measurement) {
        if (
            m.source != Technology.SIMULATION &&
                backend.people.value.none {
                    it.id == m.id && it.relationship == Relationship.ACCEPTED && it.online
                }
        )
            return
        val old = measurements.value[m.id]
        if (
            m.source == Technology.BLE_RSSI &&
                old != null &&
                old.source != Technology.BLE_RSSI &&
                !old.stale(System.currentTimeMillis())
        )
            return
        filter.accept(m)?.let { measurements.value = measurements.value + (it.id to it) }
    }

    fun simulate(enabled: Boolean) {
        stop()
        simulation = enabled
        if (!enabled) return
        status.value = "DEVELOPER SIMULATION"
        job =
            scope.launch {
                var angle = 0.0
                while (isActive) {
                    angle += .02
                    accept(
                        Measurement(
                            "simulation",
                            2.4,
                            angle,
                            null,
                            Technology.SIMULATION,
                            1.0,
                            System.currentTimeMillis(),
                        )
                    )
                    delay(100)
                }
            }
    }

    fun stop() {
        generation++
        friendsJob?.cancel()
        friendsJob = null
        bleSeen.clear()
        job?.cancel()
        job = null
        ble?.close()
        ble = null
        if (Build.VERSION.SDK_INT >= 36) platform?.close()
        platform = null
        filter.clear()
        measurements.value = emptyMap()
        simulation = false
        status.value = "Radar is off"
    }
}

@SuppressLint("MissingPermission")
private class BleProvider(
    context: Context,
    private val backend: Backend,
    private val result: (Measurement) -> Unit,
    private val error: (String) -> Unit,
) : RangingProvider {
    override val technology = Technology.BLE_RSSI
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val uuid = ParcelUuid(UUID.fromString("53656e73-6500-4000-8000-000000000001"))
    private var token: String? = null
    @Volatile private var active = false
    private var scanning = false
    private var advertising = false
    private val scan =
        object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                if (!active) return
                val bytes = r.scanRecord?.getServiceData(uuid) ?: return
                if (bytes.size != 8) return
                val key = bytes.joinToString("") { "%02x".format(it) }
                val friend =
                    backend.people.value.firstOrNull {
                        it.relationship == Relationship.ACCEPTED && key in it.radarTokens
                    } ?: return
                if (r.rssi !in -110..-15) return
                val meters = 10.0.pow((-59.0 - r.rssi) / 25.0).coerceIn(.2, 40.0)
                result(
                    Measurement(
                        friend.id,
                        meters,
                        source = technology,
                        quality = .25,
                        timestamp = System.currentTimeMillis(),
                    )
                )
            }

            override fun onScanFailed(code: Int) {
                if (active) error("Bluetooth scan unavailable ($code)")
            }
        }
    private val advertise =
        object : AdvertiseCallback() {
            override fun onStartFailure(code: Int) {
                advertising = false
                token = null
                if (active) error("This phone cannot advertise nearby presence ($code)")
            }
        }

    override fun start() {
        active = true
        runCatching {
                val scanner =
                    adapter?.bluetoothLeScanner
                        ?: throw IllegalStateException("Bluetooth scanner unavailable")
                scanner.startScan(
                    listOf(ScanFilter.Builder().setServiceData(uuid, ByteArray(0)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scan,
                )
                scanning = true
                refreshAdvertisement()
            }
            .onFailure { error("Bluetooth unavailable") }
    }

    fun refreshAdvertisement() {
        if (!active) return
        val next = backend.radarToken?.takeIf { it.matches(Regex("[a-f0-9]{16}")) }
        if (next == token) return
        if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertise)
        advertising = false
        token = next
        if (next == null) return
        val bytes = next.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        runCatching {
                val advertiser =
                    adapter?.bluetoothLeAdvertiser
                        ?: throw IllegalStateException("Bluetooth advertising unavailable")
                advertiser.startAdvertising(
                    AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setConnectable(false)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                        .build(),
                    AdvertiseData.Builder().addServiceData(uuid, bytes).build(),
                    advertise,
                )
                advertising = true
            }
            .onFailure { error("Nearby advertising unavailable") }
    }

    override fun close() {
        active = false
        runCatching {
            if (scanning) adapter?.bluetoothLeScanner?.stopScan(scan)
            if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertise)
        }
        scanning = false
        advertising = false
    }
}
