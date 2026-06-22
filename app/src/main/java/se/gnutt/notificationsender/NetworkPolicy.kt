package se.gnutt.notificationsender

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun isNetworkAllowed(context: Context, settings: SettingsManager): Boolean {
    if (!settings.wifiOnly) return true
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
