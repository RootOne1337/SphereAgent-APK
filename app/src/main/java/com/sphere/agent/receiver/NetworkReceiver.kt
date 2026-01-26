package com.sphere.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.sphere.agent.SphereAgentApp
import com.sphere.agent.util.SphereLog

/**
 * NetworkReceiver - Отслеживание изменений сетевого подключения
 * 
 * v2.27.0: Восстановлена работа с умным debounce
 * - При появлении сети делает forceReconnect если отключены > 5 сек
 * - Debounce 10 секунд между reconnect'ами
 * 
 * Совместимость: Android 7.0+ (API 24)
 */
class NetworkReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NetworkReceiver"
        @Volatile
        private var lastReconnectTime: Long = 0
        @Volatile
        private var lastDisconnectTime: Long = 0
        private const val RECONNECT_DEBOUNCE_MS = 10_000L  // 10 секунд debounce (было 30)
        private const val MIN_DISCONNECT_TIME_MS = 5_000L  // Минимум 5 сек disconnect перед reconnect
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        
        val now = System.currentTimeMillis()
        val hasNetwork = isNetworkAvailable(context)
        
        Log.d(TAG, "Network state changed: hasNetwork=$hasNetwork")
        SphereLog.i(TAG, "📶 Network state changed: hasNetwork=$hasNetwork")
        
        if (!hasNetwork) {
            // Сеть пропала - запоминаем время
            lastDisconnectTime = now
            SphereLog.w(TAG, "📶 Network lost, recording disconnect time")
            return
        }
        
        // Сеть появилась! Проверяем нужен ли reconnect
        val app = try {
            context.applicationContext as? SphereAgentApp
        } catch (e: Exception) {
            null
        }
        
        if (app == null) {
            Log.w(TAG, "App not available")
            return
        }
        
        val connectionManager = app.connectionManager
        val isConnected = connectionManager.isConnected
        
        // Debounce - не чаще чем раз в 10 секунд
        if (now - lastReconnectTime < RECONNECT_DEBOUNCE_MS) {
            SphereLog.d(TAG, "📶 Debounce: skipping reconnect (last was ${(now - lastReconnectTime)/1000}s ago)")
            return
        }
        
        // Если уже подключены - ничего не делаем
        if (isConnected) {
            SphereLog.d(TAG, "📶 Already connected, skipping")
            return
        }
        
        // Проверяем что disconnect был достаточно долгим
        val disconnectDuration = now - lastDisconnectTime
        if (lastDisconnectTime > 0 && disconnectDuration < MIN_DISCONNECT_TIME_MS) {
            SphereLog.d(TAG, "📶 Disconnect was only ${disconnectDuration}ms, skipping rapid reconnect")
            return
        }
        
        // Сеть вернулась и мы отключены - делаем forceReconnect!
        SphereLog.i(TAG, "📶 Network restored after ${disconnectDuration}ms disconnect - forcing reconnect!")
        lastReconnectTime = now
        
        try {
            connectionManager.forceReconnect()
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to force reconnect", e)
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
