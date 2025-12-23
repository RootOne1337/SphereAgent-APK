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
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * UpdateWorker - фоновая проверка обновлений
 * 
 * Использует WorkManager для периодической проверки обновлений
 * даже когда приложение закрыто. Работает по расписанию с
 * учётом ограничений батареи и сети.
 */
class UpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "UpdateWorker"
        const val WORK_NAME = "sphere_update_check"
        
        /**
         * Планирование периодической проверки обновлений
         */
        fun schedule(context: Context) {
            try {
                val intervalHours = BuildConfig.UPDATE_CHECK_INTERVAL_HOURS.toLong()
                
                // Ограничения - только при наличии сети
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
                
                val workRequest = PeriodicWorkRequestBuilder<UpdateWorker>(
                    intervalHours, TimeUnit.HOURS
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        1, TimeUnit.HOURS
                    )
                    .build()
                
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                
                Log.d(TAG, "Update check scheduled every $intervalHours hours")
                
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
            Log.d(TAG, "Running update check...")
            
            val updateManager = UpdateManager(applicationContext)
            val state = updateManager.checkForUpdates(force = true)
            
            when (state) {
                is UpdateState.UpdateAvailable -> {
                    Log.d(TAG, "Update available: ${state.version.version}")
                    
                    // Показываем уведомление о доступном обновлении
                    showUpdateNotification(state.version)
                    
                    // Если включено автообновление - скачиваем
                    if (BuildConfig.AUTO_UPDATE_ENABLED && state.version.required) {
                        Log.d(TAG, "Auto-downloading required update...")
                        updateManager.downloadUpdate(state.version)
                    }
                }
                is UpdateState.UpToDate -> {
                    Log.d(TAG, "App is up to date")
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
