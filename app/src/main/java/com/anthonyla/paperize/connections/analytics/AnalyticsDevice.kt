package com.anthonyla.paperize.connections.analytics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.anthonyla.paperize.R
import java.util.Locale
import java.util.TimeZone

/** Reads what the phone says about itself: public Build fields, screen size, network type and carrier name. */
internal object AnalyticsDevice {
    @Suppress("DEPRECATION")
    fun snapshot(context: Context): DeviceContext {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val metrics = context.resources.displayMetrics
        val (wifi, cellular) = connectionTypes(context)
        return DeviceContext(
            appName = context.getString(R.string.app_name),
            appVersion = info.versionName.orEmpty(),
            appBuild = info.longVersionCode.toString(),
            namespace = context.packageName,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            brand = Build.BRAND,
            deviceName = Build.DEVICE,
            osVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            density = metrics.density,
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            wifi = wifi,
            cellular = cellular,
            carrier = context.getSystemService(TelephonyManager::class.java)?.networkOperatorName?.takeIf { it.isNotBlank() },
            installedAt = info.firstInstallTime
        )
    }

    private fun connectionTypes(context: Context): Pair<Boolean, Boolean> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false to false
        val network = manager.activeNetwork ?: return false to false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false to false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) to
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
