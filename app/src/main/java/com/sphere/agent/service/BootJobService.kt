package com.sphere.agent.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * ENTERPRISE: Job Service для гарантированного автозапуска
 * 
 * JobScheduler - это системный планировщик который ГАРАНТИРОВАННО выполнит задачу.
 * В отличие от AlarmManager, JobScheduler:
 * - Переживает Doze mode
 * - Работает после reboot (с RECEIVE_BOOT_COMPLETED)
 * - Не убивается системой
 */
class BootJobService : JobService() {
    
    companion object {
        private const val TAG = "BootJobService"
        private const val JOB_ID_BOOT = 1337
        private const val JOB_ID_PERIODIC = 1338
        
        /**
         * Планирует Job для немедленного выполнения
         */
        fun scheduleImmediateJob(context: Context) {
            Log.d(TAG, "Scheduling immediate boot job")
            
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            if (scheduler == null) {
                Log.e(TAG, "JobScheduler not available!")
                return
            }
            
            val componentName = ComponentName(context, BootJobService::class.java)
            
            val jobInfo = JobInfo.Builder(JOB_ID_BOOT, componentName)
                .setOverrideDeadline(0) // Выполнить НЕМЕДЛЕННО
                .setPersisted(true) // Переживает reboot
                .build()
            
            val result = scheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.d(TAG, "Boot job scheduled successfully!")
            } else {
                Log.e(TAG, "Failed to schedule boot job: $result")
            }
        }
        
        /**
         * Планирует периодический Job каждые 15 минут
         */
        fun schedulePeriodicJob(context: Context) {
            Log.d(TAG, "Scheduling periodic watchdog job")
            
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            if (scheduler == null) {
                Log.e(TAG, "JobScheduler not available!")
                return
            }
            
            // Проверяем не запланирован ли уже
            val existingJob = scheduler.getPendingJob(JOB_ID_PERIODIC)
            if (existingJob != null) {
                Log.d(TAG, "Periodic job already scheduled")
                return
            }
            
            val componentName = ComponentName(context, BootJobService::class.java)
            
            // Периодический job каждые 15 минут (минимум для JobScheduler)
            // Добавляем flex 5 минут, чтобы система разнесла запуск по времени
            val jobInfo = JobInfo.Builder(JOB_ID_PERIODIC, componentName)
                .setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L)
                .setPersisted(true) // Переживает reboot!
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            
            val result = scheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.d(TAG, "Periodic watchdog job scheduled successfully!")
            } else {
                Log.e(TAG, "Failed to schedule periodic job: $result")
            }
        }
        
        /**
         * Отменяет все Jobs
         */
        fun cancelAllJobs(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            scheduler?.cancelAll()
            Log.d(TAG, "All jobs cancelled")
        }
    }
    
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "=== BootJobService.onStartJob() ===")
        Log.d(TAG, "JobId: ${params?.jobId}")
        
        // Запускаем сервис в отдельном потоке
        Thread {
            try {
                startAgentService()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AgentService from Job", e)
            } finally {
                // Сообщаем что job завершён
                jobFinished(params, false)
            }
        }.start()
        
        // true = job продолжает работать в фоне
        return true
    }
    
    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "BootJobService.onStopJob()")
        // true = перепланировать job
        return true
    }
    
    private fun startAgentService() {
        Log.d(TAG, "Starting AgentService from BootJobService...")
        
        // Проверяем запущен ли уже сервис
        if (AgentService.isRunning) {
            Log.d(TAG, "AgentService already running, skip")
            return
        }
        
        val intent = Intent(this, AgentService::class.java).apply {
            action = AgentService.ACTION_START
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "AgentService start command sent!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
            
            // Fallback: попробуем через startActivity
            try {
                val activityIntent = packageManager.getLaunchIntentForPackage(packageName)
                activityIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(activityIntent)
                Log.d(TAG, "Started via Activity launch")
            } catch (e2: Exception) {
                Log.e(TAG, "Activity launch also failed", e2)
            }
        }
    }
}
