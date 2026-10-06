package dev.rq.llmceiling

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Device facts and *proxies* for thermal/power state. Nothing here is an energy measurement. */
class DeviceProbe(private val ctx: Context) {

    fun staticInfo(): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("soc_manufacturer", Build.SOC_MANUFACTURER)
        put("soc_model", Build.SOC_MODEL)
        put("android_release", Build.VERSION.RELEASE)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("build_id", Build.DISPLAY)
        put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        put("cpu_count", Runtime.getRuntime().availableProcessors())
        put("cpu_max_freq_khz", JSONArray(cpuFreqs("cpuinfo_max_freq")))
        put("cpu_parts", JSONArray(cpuParts()))
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        put("mem_total_bytes", mi.totalMem)
        put("large_memory_class_mb", am.largeMemoryClass)
    }

    /** Snapshot of things that change during a benchmark. */
    fun dynamicState(): JSONObject = JSONObject().apply {
        put("t_wall_ms", System.currentTimeMillis())
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            put("battery_level_pct", bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 /
                    maxOf(1, bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)))
            put("battery_temp_c", bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) / 10.0)
            put("battery_voltage_mv", bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
            put("plugged", bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))  // 0 = on battery
        }
        val bm = ctx.getSystemService(BatteryManager::class.java)
        // Instantaneous current, sign/unit conventions are vendor specific: proxy only.
        put("battery_current_now_ua", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        val pm = ctx.getSystemService(PowerManager::class.java)
        put("thermal_status", pm.currentThermalStatus)   // 0 none .. 6 shutdown
        put("thermal_headroom_0s", pm.getThermalHeadroom(0).toDouble().let { if (it.isNaN()) JSONObject.NULL else it })
        put("power_save", pm.isPowerSaveMode)
        put("network", networkState())
        put("airplane_mode", Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        put("cpu_cur_freq_khz", JSONArray(cpuFreqs("scaling_cur_freq")))
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        put("sys_avail_mem_bytes", mi.availMem)
        put("sys_low_memory", mi.lowMemory)
    }

    /** "offline" when Android reports no active network at all. */
    fun networkState(): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(n) ?: return "offline"
        return if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) "online" else "no-internet"
    }

    private fun cpuFreqs(file: String): List<Any> = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
        runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/$file").readText().trim().toLong() }
            .getOrNull() ?: JSONObject.NULL
    }

    private fun cpuParts(): List<String> = runCatching {
        File("/proc/cpuinfo").readLines().filter { it.startsWith("CPU part") }.map { it.substringAfter(":").trim() }
    }.getOrDefault(emptyList())
}
