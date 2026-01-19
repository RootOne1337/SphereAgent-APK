package com.sphere.agent.update

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sphere.agent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * UpdateWorker - Enterprise фоновая проверка обновлений
 * 
 * Использует WorkManager для периодической проверки обновлений
 * даже когда приложение закрыто. Работает по расписанию с
 * учётом ограничений батареи и сети.
 * 
 * ENTERPRISE FEATURES:
 * - Jitter: случайная задержка 0-30 минут для предотвращения thundering herd
 * - Exponential backoff при ошибках
 * - Автоматическое скачивание required обновлений
 * - Silent install на ROOT устройствах
 */
class UpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "UpdateWorker"
        const val WORK_NAME = "sphere_update_check"
        
        /**
         * Enterprise Jitter: случайная задержка для распределения нагрузки
         * При 1000+ устройствах предотвращает одновременные запросы
         * Диапазон: 0-30 минут (1_800_000 ms)
         */
        private const val MAX_JITTER_MS = 30 * 60 * 1000L // 30 минут
        
        /**
         * Планирование периодической проверки обновлений
         * С flex window для естественного распределения нагрузки
         */
        fun schedule(context: Context) {
            try {
                val intervalHours = BuildConfig.UPDATE_CHECK_INTERVAL_HOURS.toLong()
                
                // Ограничения - только при наличии сети и достаточном заряде
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
                
                // Enterprise: используем flex interval для распределения нагрузки
                // Flex = 15 минут означает что WorkManager может запустить в окне [interval-flex, interval]
                val flexMinutes = 15L
                
                val workRequest = PeriodicWorkRequestBuilder<UpdateWorker>(
                    intervalHours, TimeUnit.HOURS,
                    flexMinutes, TimeUnit.MINUTES  // Flex window для распределения
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        1, TimeUnit.HOURS
                    )
                    // Initial delay с jitter для первого запуска
                    .setInitialDelay(
                        Random.nextLong(0, MAX_JITTER_MS),
                        TimeUnit.MILLISECONDS
                    )
                    .build()
                
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                
                Log.d(TAG, "Update check scheduled every $intervalHours hours with ${flexMinutes}min flex window")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule update worker", e)
            }
        }
        
        /**
         * Отмена периодической проверки
         */
        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "Update check cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel update worker", e)
            }
        }
        
        /**
         * Немедленная проверка обновлений
         */
        fun checkNow(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<UpdateWorker>()
                    .setConstraints(constraints)
                    .build()
                
                WorkManager.getInstance(context).enqueue(workRequest)
                Log.d(TAG, "Immediate update check requested")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request immediate check", e)
            }
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Enterprise: Runtime jitter для распределения нагрузки при массовом обновлении
            // Даже если WorkManager запустил несколько устройств одновременно,
            // они не будут делать запросы в один момент
            val jitterMs = Random.nextLong(0, MAX_JITTER_MS / 2) // 0-15 минут дополнительно
            Log.d(TAG, "Running update check with ${jitterMs/1000}s jitter...")
            delay(jitterMs)
            
            val updateManager = UpdateManager(applicationContext)
            val state = updateManager.checkForUpdates(force = true)
            
            when (state) {
                is UpdateState.UpdateAvailable -> {
                    Log.d(TAG, "Update available: ${state.version.version}")
                    
                    // Показываем уведомление о доступном обновлении
                    showUpdateNotification(state.version)
                    
                    // Enterprise: Автоматическое скачивание и установка для required обновлений
                    // На эмуляторах с ROOT это будет silent install
                    if (BuildConfig.AUTO_UPDATE_ENABLED) {
                        if (state.version.required) {
                            Log.d(TAG, "Auto-downloading REQUIRED update v${state.version.version}...")
                            updateManager.downloadUpdate(state.version)
                        } else {
                            Log.d(TAG, "Optional update available, waiting for user action")
                        }
                    }
                }
                is UpdateState.UpToDate -> {
                    Log.d(TAG, "App is up to date (v${updateManager.currentVersionName})")
                }
                is UpdateState.Error -> {
                    Log.e(TAG, "Update check error: ${state.message}")
                    return@withContext Result.retry()
                }
                else -> {}
            }
            
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Update worker failed", e)
            Result.retry()
        }
    }
    
    private fun showUpdateNotification(version: VersionInfo) {
        try {
            val notificationHelper = UpdateNotificationHelper(applicationContext)
            notificationHelper.showUpdateAvailable(version)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }
}
