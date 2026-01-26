package com.sphere.agent.worker

import android.content.Context
import androidx.work.*
import com.sphere.agent.service.AgentService
import com.sphere.agent.util.SphereLog
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * ENTERPRISE: WorkManager для периодической проверки состояния AgentService
 * Работает даже если приложение убито - WorkManager гарантирует выполнение
 */
class AgentWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    
    companion object {
        private const val TAG = "AgentWorker"
        private const val UNIQUE_WORK_NAME = "agent_health_check"
        
        /**
         * Запланировать периодическую проверку здоровья сервиса
         * Вызывать при старте приложения и из BootReceiver
         */
        fun schedule(context: Context) {
            SphereLog.i(TAG, "Scheduling periodic health check")
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            // Каждые 15 минут (минимальный интервал WorkManager)
            // ENTERPRISE: Jitter чтобы не синхронизировать весь флот
            val jitterMinutes = Random.nextLong(0, 5)
            val workRequest = PeriodicWorkRequestBuilder<AgentWorker>(
                15, TimeUnit.MINUTES
            )
                .setInitialDelay(jitterMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Не заменять если уже есть
                workRequest
            )
            
            SphereLog.i(TAG, "Periodic health check scheduled (every 15 min, jitter=${jitterMinutes}m)")
        }
        
        /**
         * Одноразовая проверка - вызвать немедленно
         */
        fun checkNow(context: Context) {
            SphereLog.i(TAG, "Scheduling immediate health check")
            
            val workRequest = OneTimeWorkRequestBuilder<AgentWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
        }
        
        /**
         * Отменить все проверки
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            SphereLog.i(TAG, "Periodic health check cancelled")
        }
    }
    
    override fun doWork(): Result {
        SphereLog.i(TAG, "Health check running - isServiceRunning: ${AgentService.isRunning}")
        
        return try {
            if (!AgentService.isRunning) {
                SphereLog.w(TAG, "AgentService not running! Starting...")
                AgentService.start(context)
            } else {
                SphereLog.d(TAG, "AgentService is running OK")
            }
            
            Result.success()
        } catch (e: Exception) {
            SphereLog.e(TAG, "Health check failed", e)
            
            // Попробовать запустить в любом случае
            try {
                AgentService.start(context)
            } catch (e2: Exception) {
                SphereLog.e(TAG, "Failed to start service", e2)
            }
            
            Result.retry()
        }
    }
}
