package com.sphere.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.sphere.agent.service.ScreenCaptureService
import com.sphere.agent.ui.screens.MainScreen
import com.sphere.agent.ui.theme.SphereAgentTheme
import com.sphere.agent.ui.viewmodel.MainEffect
import com.sphere.agent.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity - Главная Activity приложения SphereAgent
 * 
 * Функционал:
 * - Jetpack Compose UI
 * - MediaProjection permission handling
 * - ViewModel integration
 * - Effect handling (Toast, Navigation)
 */

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    private val viewModel: MainViewModel by viewModels()
    
    // MediaProjection permission launcher
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Сохраняем результат для сервиса
            ScreenCaptureService.setMediaProjectionResult(result.resultCode, result.data)
            viewModel.onPermissionResult(true)
        } else {
            viewModel.onPermissionResult(false)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge UI только для Android 10+ (API 29+)
        // На Android 9 (API 28) может вызывать краши
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                enableEdgeToEdge()
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "enableEdgeToEdge failed: ${e.message}")
        }
        
        // Собираем эффекты с защитой
        try {
            observeEffects()
        } catch (e: Exception) {
            Log.e("MainActivity", "observeEffects failed: ${e.message}")
        }
        
        // Устанавливаем Compose UI
        try {
            setContent {
                SphereAgentTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val uiState by viewModel.uiState.collectAsState()
                        
                        MainScreen(
                            uiState = uiState,
                            onEvent = viewModel::onEvent
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "setContent failed: ${e.message}", e)
        }
    }
    
    private fun observeEffects() {
        lifecycleScope.launch {
            viewModel.effect.collectLatest { effect ->
                when (effect) {
                    is MainEffect.ShowToast -> {
                        Toast.makeText(this@MainActivity, effect.message, Toast.LENGTH_SHORT).show()
                    }
                    is MainEffect.RequestMediaProjection -> {
                        requestMediaProjection()
                    }
                    is MainEffect.OpenAccessibilitySettings -> {
                        openAccessibilitySettings()
                    }
                    is MainEffect.Navigate -> {
                        // TODO: Navigation handling
                    }
                }
            }
        }
    }
    
    private fun requestMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
    
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(
                this,
                "Enable 'SphereAgent' in Accessibility services",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open Accessibility settings", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Failed to open Accessibility settings", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Не останавливаем сервис при уничтожении Activity - он работает в фоне
    }
}
