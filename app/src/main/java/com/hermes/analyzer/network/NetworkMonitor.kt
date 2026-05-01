package com.hermes.analyzer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData

/**
 * NetworkMonitor
 * 오프라인/온라인 상태 감지 및 자동 모드 전환
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val isOnline = MutableLiveData<Boolean>(checkOnline())
    val networkType = MutableLiveData<String>("unknown")

    enum class Mode { OFFLINE, ONLINE }
    var currentMode: Mode = if (checkOnline()) Mode.ONLINE else Mode.OFFLINE

    fun checkOnline(): Boolean {
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getNetworkType(): String {
        val nw = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(nw) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    fun register(owner: LifecycleOwner, onModeChanged: (Mode) -> Unit) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val online = checkOnline()
                isOnline.postValue(online)
                networkType.postValue(getNetworkType())
                val newMode = if (online) Mode.ONLINE else Mode.OFFLINE
                if (newMode != currentMode) {
                    currentMode = newMode
                    onModeChanged(newMode)
                }
            }
            override fun onLost(network: android.net.Network) {
                isOnline.postValue(false)
                networkType.postValue("none")
                if (currentMode != Mode.OFFLINE) {
                    currentMode = Mode.OFFLINE
                    onModeChanged(Mode.OFFLINE)
                }
            }
        })
    }
}
