package com.sphere.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.sphere.agent.SphereAgentApp

/**
 * NetworkReceiver - Отслеживание изменений сетевого подключения
 * 
 * Совместимость: Android 7.0+ (API 24)
 */
class NetworkReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NetworkReceiver"
        @Volatile
        private var lastReconnectTime: Long = 0
        private const val RECONNECT_DEBOUNCE_MS = 30000L  // 30 секунд между переподключениями
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        // v2.0.3: Полностью отключаем - NetworkReceiver вызывает reconnect loop
        // ConnectionManager сам обрабатывает переподключения через scheduleReconnect()
        Log.d(TAG, "Network state changed - ignoring (disabled in v2.0.3)")
    }
    
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }
}
