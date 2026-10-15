package app.sense.radar

import android.annotation.SuppressLint
import android.content.Context
import android.ranging.*
import android.ranging.oob.*
import android.util.Base64
import androidx.annotation.RequiresApi
import app.sense.core.*
import app.sense.data.Backend
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.*

@RequiresApi(36)
@SuppressLint("MissingPermission")
class PlatformRangingProvider(
    private val context: Context,
    private val backend: Backend,
    private val parent: CoroutineScope,
    private val localId: String,
    private val output: (Measurement) -> Unit,
    private val error: (String) -> Unit,
    private val nearby: () -> Set<String>,
) : RangingProvider {
    override val technology = Technology.ANDROID_RANGING
    private val scope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val manager = context.getSystemService(RangingManager::class.java)
    private val executor = Executor { context.mainExecutor.execute(it) }

    private data class Peer(val transport: Transport, val session: RangingSession)

    private val peers = mutableMapOf<String, Peer>()
    private var polling: Job? = null
    private var closed = false
    private val retryAt = mutableMapOf<String, Long>()

    private inner class Transport(private val peer: String) : TransportHandle {
        private var callback: TransportHandle.ReceiveCallback? = null
        private var executor: Executor? = null
        private val pending = ArrayDeque<ByteArray>()
        @Volatile private var transportClosed = false

        @Synchronized
        override fun registerReceiveCallback(e: Executor, c: TransportHandle.ReceiveCallback) {
            if (transportClosed) {
                e.execute { c.onClose() }
                return
            }
            executor = e
            callback = c
            while (pending.isNotEmpty()) {
                val data = pending.removeFirst()
                e.execute { c.onReceiveData(data) }
            }
        }

        override fun sendData(data: ByteArray) {
            if (transportClosed || data.size > 9000) return
            scope.launch {
                runCatching { backend.oob(peer, Base64.encodeToString(data, Base64.NO_WRAP)) }
                    .onFailure { executor?.execute { callback?.onSendFailed() } }
            }
        }

        @Synchronized
        fun receive(data: ByteArray) {
            if (transportClosed) return
            if (callback == null) {
                if (pending.size < 16) pending.addLast(data)
                return
            }
            val c = callback
            executor?.execute { if (!transportClosed) c?.onReceiveData(data) }
        }

        @Synchronized
        override fun close() {
            transportClosed = true
            val c = callback
            executor?.execute { c?.onClose() }
            callback = null
            pending.clear()
        }
    }

    override fun start() {
        refreshPeers()
        polling =
            scope.launch {
                while (isActive) {
                    runCatching { backend.packets() }
                        .onSuccess { packets ->
                            packets.forEach { (id, payload) ->
                                runCatching {
                                    peers[id]
                                        ?.transport
                                        ?.receive(Base64.decode(payload, Base64.NO_WRAP))
                                }
                            }
                        }
                    delay(400)
                }
            }
    }

    fun refreshPeers() {
        if (closed) return
        val seen = nearby()
        val ids =
            backend.people.value
                .filter {
                    it.relationship == Relationship.ACCEPTED &&
                        it.online &&
                        it.ranging &&
                        it.id in seen
                }
                .map { it.id }
                .toSet()
        (peers.keys - ids).forEach { id ->
            peers.remove(id)?.let {
                runCatching { it.session.close() }
                it.transport.close()
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        ids.filter { it !in peers && now >= (retryAt[it] ?: 0L) }
            .take((4 - peers.size).coerceAtLeast(0))
            .forEach { id ->
                runCatching { open(id) }
                    .onFailure {
                        failed(id)
                        error("Precise ranging unavailable; using Bluetooth")
                    }
            }
    }

    private fun failed(id: String, expected: Transport? = null) {
        if (expected != null && peers[id]?.transport !== expected) return
        retryAt[id] = android.os.SystemClock.elapsedRealtime() + 15000
        peers.remove(id)?.let {
            runCatching { it.session.close() }
            it.transport.close()
        }
    }

    private fun open(id: String) {
        val transport = Transport(id)
        val device =
            RangingDevice.Builder().setUuid(UUID.nameUUIDFromBytes(id.toByteArray())).build()
        val handle = DeviceHandle.Builder(device, transport).build()
        val session =
            manager.createRangingSession(
                executor,
                object : RangingSession.Callback {
                    override fun onOpened() {}

                    override fun onOpenFailed(reason: Int) {
                        failed(id, transport)
                        if (!closed) error("Precise ranging unavailable; using Bluetooth")
                    }

                    override fun onClosed(reason: Int) {
                        if (!closed && peers[id]?.transport === transport) failed(id, transport)
                    }

                    override fun onStarted(device: RangingDevice, technology: Int) {}

                    override fun onStopped(device: RangingDevice, technology: Int) {}

                    override fun onResults(device: RangingDevice, data: RangingData) {
                        if (closed || peers[id]?.transport !== transport || id !in nearby()) return
                        val age = android.os.SystemClock.elapsedRealtime() - data.timestampMillis
                        if (age !in 0..6000) return
                        val d = data.distance ?: return
                        val quality =
                            when (d.confidence) {
                                RangingMeasurement.CONFIDENCE_HIGH -> .9
                                RangingMeasurement.CONFIDENCE_MEDIUM -> .6
                                else -> .3
                            }
                        val source =
                            if (data.rangingTechnology == RangingManager.UWB) Technology.UWB
                            else Technology.ANDROID_RANGING
                        // Android Ranging UWB preserves backend angles in radians.
                        output(
                            Measurement(
                                id,
                                d.measurement,
                                data.azimuth
                                    ?.takeIf {
                                        it.confidence in
                                            setOf(
                                                RangingMeasurement.CONFIDENCE_HIGH,
                                                RangingMeasurement.CONFIDENCE_MEDIUM,
                                            )
                                    }
                                    ?.measurement,
                                data.elevation
                                    ?.takeIf {
                                        it.confidence in
                                            setOf(
                                                RangingMeasurement.CONFIDENCE_HIGH,
                                                RangingMeasurement.CONFIDENCE_MEDIUM,
                                            )
                                    }
                                    ?.measurement,
                                source,
                                quality,
                                System.currentTimeMillis() -
                                    (android.os.SystemClock.elapsedRealtime() -
                                            data.timestampMillis)
                                        .coerceAtLeast(0),
                            )
                        )
                    }
                },
            ) ?: throw IllegalStateException("Ranging session unavailable")
        peers[id] = Peer(transport, session)
        try {
            val initiator = localId < id
            val config: RangingConfig =
                if (initiator)
                    OobInitiatorRangingConfig.Builder()
                        .setFastestRangingInterval(Duration.ofMillis(200))
                        .setSlowestRangingInterval(Duration.ofSeconds(1))
                        .addDeviceHandle(handle)
                        .build()
                else OobResponderRangingConfig.Builder(handle).build()
            session.start(
                RangingPreference.Builder(
                        if (initiator) RangingPreference.DEVICE_ROLE_INITIATOR
                        else RangingPreference.DEVICE_ROLE_RESPONDER,
                        config,
                    )
                    .build()
            )
        } catch (e: Exception) {
            failed(id)
            throw e
        }
    }

    override fun close() {
        closed = true
        polling?.cancel()
        peers.values.forEach {
            runCatching { it.session.close() }
            it.transport.close()
        }
        peers.clear()
        scope.cancel()
    }
}
