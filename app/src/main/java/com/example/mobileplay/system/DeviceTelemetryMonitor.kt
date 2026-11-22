package com.example.mobileplay.system

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceRuntimeMetrics(
    val isWifiConnected: Boolean = false,
    val networkTransportLabel: String = "Checking...",
    val availableMemoryMb: Long = 0L,
    val totalMemoryMb: Long = 0L,
    val appHeapUsedMb: Long = 0L,
    val cpuCoreCount: Int = Runtime.getRuntime().availableProcessors()
)

/**
 * Monitors real Android Wi-Fi connectivity and measurable device memory/CPU metrics.
 * Immediately invokes [onWifiLost] when local Wi-Fi disconnects so stuck controller states cannot persist.
 */
class DeviceTelemetryMonitor(
    context: Context,
    private val onWifiLost: () -> Unit
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val latencyLock by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
        else runCatching {
            appContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MobilePlay:PS5")
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    private val _metrics = MutableStateFlow(sampleMetrics())
    val metrics: StateFlow<DeviceRuntimeMetrics> = _metrics.asStateFlow()

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            refresh()
        }

        override fun onLost(network: Network) {
            val wasWifi = _metrics.value.isWifiConnected
            refresh()
            if (wasWifi && !_metrics.value.isWifiConnected) {
                onWifiLost()
            }
        }
    }

    fun start() {
        refresh()
        if (registered) return
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            registered = true
        }
    }

    fun stop() {
        setLowLatencyWifi(false)
        if (!registered) return
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }
        registered = false
    }

    fun setLowLatencyWifi(enabled: Boolean) {
        val lock = latencyLock ?: return
        runCatching {
            if (enabled && !lock.isHeld) lock.acquire()
            if (!enabled && lock.isHeld) lock.release()
        }
    }

    fun refresh() {
        _metrics.value = sampleMetrics()
    }

    private fun sampleMetrics(): DeviceRuntimeMetrics {
        val activeNetwork = runCatching { connectivityManager?.activeNetwork }.getOrNull()
        val caps = runCatching { connectivityManager?.getNetworkCapabilities(activeNetwork) }.getOrNull()
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val transportLabel = when {
            isWifi -> "Local Wi-Fi"
            isEthernet -> "Ethernet"
            isCellular -> "Cellular (LAN discovery unavailable)"
            else -> "Offline"
        }

        val memInfo = ActivityManager.MemoryInfo()
        runCatching { activityManager?.getMemoryInfo(memInfo) }
        val runtime = Runtime.getRuntime()
        val heapUsedMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).coerceAtLeast(0L)

        return DeviceRuntimeMetrics(
            isWifiConnected = isWifi || isEthernet,
            networkTransportLabel = transportLabel,
            availableMemoryMb = memInfo.availMem / (1024L * 1024L),
            totalMemoryMb = memInfo.totalMem / (1024L * 1024L),
            appHeapUsedMb = heapUsedMb,
            cpuCoreCount = runtime.availableProcessors()
        )
    }
}
