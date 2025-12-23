package com.sphere.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SphereAccessibilityService - Accessibility Service для управления устройством
 * 
 * Используется когда нет root доступа.
 * Позволяет:
 * - Выполнять tap/swipe через AccessibilityService API
 * - Эмулировать кнопки (Back, Home, Recent)
 * - Получать информацию об UI элементах
 */

class SphereAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "SphereAccessibility"
        
        private val _isEnabled = MutableStateFlow(false)
        val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
        
        private var instance: SphereAccessibilityService? = null
        
        /**
         * Проверка доступности сервиса
         */
        fun isServiceEnabled(): Boolean = instance != null && _isEnabled.value
        
        /**
         * Выполнение tap через Accessibility Service
         */
        fun tap(x: Int, y: Int, callback: ((Boolean) -> Unit)? = null): Boolean {
            val service = instance ?: return false
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                callback?.invoke(false)
                return false
            }
            
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            
            return service.dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Tap completed at ($x, $y)")
                        callback?.invoke(true)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Tap cancelled at ($x, $y)")
                        callback?.invoke(false)
                    }
                },
                null
            )
        }
        
        /**
         * Выполнение swipe через Accessibility Service
         */
        fun swipe(
            startX: Int, 
            startY: Int, 
            endX: Int, 
            endY: Int, 
            duration: Long = 300,
            callback: ((Boolean) -> Unit)? = null
        ): Boolean {
            val service = instance ?: return false
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                callback?.invoke(false)
                return false
            }
            
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            
            return service.dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Swipe completed from ($startX, $startY) to ($endX, $endY)")
                        callback?.invoke(true)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Swipe cancelled")
                        callback?.invoke(false)
                    }
                },
                null
            )
        }
        
        /**
         * Long press через Accessibility Service
         */
        fun longPress(
            x: Int, 
            y: Int, 
            duration: Long = 1000,
            callback: ((Boolean) -> Unit)? = null
        ): Boolean {
            val service = instance ?: return false
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                callback?.invoke(false)
                return false
            }
            
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            
            return service.dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Long press completed at ($x, $y)")
                        callback?.invoke(true)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Long press cancelled")
                        callback?.invoke(false)
                    }
                },
                null
            )
        }
        
        /**
         * Нажатие кнопки Back
         */
        fun back(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        }
        
        /**
         * Нажатие кнопки Home
         */
        fun home(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        }
        
        /**
         * Открытие Recent Apps
         */
        fun recent(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
        }
        
        /**
         * Открытие Notifications
         */
        fun notifications(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false
        }
        
        /**
         * Открытие Quick Settings
         */
        fun quickSettings(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ?: false
        }
        
        /**
         * Power Dialog
         */
        fun powerDialog(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) ?: false
        }
        
        /**
         * Lock Screen (Android 9+)
         */
        fun lockScreen(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
            } else {
                false
            }
        }
        
        /**
         * Take Screenshot (Android 9+)
         */
        fun takeScreenshot(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                instance?.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) ?: false
            } else {
                false
            }
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isEnabled.value = true
        Log.d(TAG, "Accessibility Service connected")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Обрабатываем события accessibility если нужно
        // Можно использовать для отслеживания UI состояния
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }
    
    override fun onDestroy() {
        instance = null
        _isEnabled.value = false
        Log.d(TAG, "Accessibility Service destroyed")
        super.onDestroy()
    }
}
