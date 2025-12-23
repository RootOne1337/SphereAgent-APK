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
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        
        Log.d(TAG, "Network state changed")
        
        try {
            val isConnected = isNetworkAvailable(context)
            Log.d(TAG, "Network available: $isConnected")
            
            if (isConnected) {
                // Переподключаемся к серверу
                try {
                    val app = context.applicationContext as? SphereAgentApp
                    app?.connectionManager?.reconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reconnect", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling network change", e)
        }
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
