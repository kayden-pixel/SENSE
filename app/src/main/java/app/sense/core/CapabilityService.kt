package app.sense.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.net.*
import android.os.Build
import kotlinx.coroutines.flow.*

class CapabilityService(private val context: Context) {
    private val mutable = MutableStateFlow(Capabilities())
    val state: StateFlow<Capabilities> = mutable.asStateFlow()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                refresh()
            }
        }
    private val audioCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>) {
                refresh()
            }

            override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>) {
                refresh()
            }
        }
    private val netCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                refresh()
            }

            override fun onLost(n: Network) {
                refresh()
            }

            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                refresh()
            }
        }

    init {
        val filter =
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction(android.location.LocationManager.MODE_CHANGED_ACTION)
            }
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        audio.registerAudioDeviceCallback(audioCallback, null)
        network.registerDefaultNetworkCallback(netCallback)
        refresh()
    }

    @Volatile
    var headsetDevice: AudioDeviceInfo? = null
        private set

    private fun granted(p: String) =
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        val pm = context.packageManager
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headset =
            devices.firstOrNull {
                it.type in
                    setOf(
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                    ) +
                        (if (Build.VERSION.SDK_INT >= 31) setOf(AudioDeviceInfo.TYPE_BLE_HEADSET)
                        else emptySet())
            }
        headsetDevice = headset
        val nearby =
            if (Build.VERSION.SDK_INT >= 31)
                listOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                    )
                    .all { granted(it) } && granted(Manifest.permission.ACCESS_FINE_LOCATION)
            else granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val bt =
            runCatching {
                    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled ==
                        true
                }
                .getOrDefault(false)
        val net = network.getNetworkCapabilities(network.activeNetwork)
        mutable.value =
            Capabilities(
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
                bt,
                headset != null,
                pm.hasSystemFeature("android.hardware.uwb"),
                Build.VERSION.SDK_INT >= 36 && context.getSystemService("ranging") != null,
                nearby,
                granted(Manifest.permission.CAMERA),
                granted(Manifest.permission.RECORD_AUDIO),
                Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
                Build.VERSION.SDK_INT >= 36 && granted("android.permission.RANGING"),
                net?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                headset?.productName?.toString() ?: "Speaker",
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT),
                pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
                context
                    .getSystemService(android.location.LocationManager::class.java)
                    .isLocationEnabled,
            )
    }

    fun close() {
        context.unregisterReceiver(receiver)
        audio.unregisterAudioDeviceCallback(audioCallback)
        network.unregisterNetworkCallback(netCallback)
    }
}
