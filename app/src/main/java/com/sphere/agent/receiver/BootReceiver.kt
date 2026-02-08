package com.sphere.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sphere.agent.service.AgentService
import com.sphere.agent.worker.AgentWorker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BootReceiver v3.6.0 — Лёгкий автозапуск при загрузке устройства
 * 
 * CRITICAL FIX: Полностью переписан для устранения ANR!
 * 
 * БЫЛО (v2.21.0):
 * - 6 boot events → 6 отдельных Thread → Thread.sleep(2+3+5 сек) 
 * - Каждый thread вызывал BootJobService (ещё thread) + ROOT su processes
 * - На 14 эмуляторах: 84 threads + 14+ su processes = SYSTEM DEAD
 * 
 * СТАЛО (v3.6.0):
 * - AtomicBoolean гарантирует ОДНОКРАТНУЮ обработку
 * - НЕТ Thread.sleep(), НЕТ su processes, НЕТ BootJobService
 * - Только: AgentService.start() + AgentWorker.schedule()
 * - WorkManager (15min) — единственный watchdog
 * 
 * Совместимость: Android 7.0+ (API 24)
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        
        /**
         * Атомарный флаг: гарантирует что startAgentService
         * выполнится ТОЛЬКО ОДИН РАЗ, даже если придёт 6 boot events одновременно.
         * Сбрасывается только при следующей загрузке (новый процесс).
         */
        private val started = AtomicBoolean(false)
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        val action = intent.action ?: return
        Log.d(TAG, "Boot event: $action")
        
        // AtomicBoolean.compareAndSet — потокобезопасная проверка
        // Первый вызов: false → true (выполняем), все остальные: уже true (skip)
        if (!started.compareAndSet(false, true)) {
            Log.d(TAG, "Already handled boot, skipping: $action")
            return
        }
        
        Log.d(TAG, "First boot event accepted: $action — starting AgentService")
        
        // 1. Запускаем AgentService — ОДНА СТРОКА, без Thread, без sleep!
        try {
            AgentService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AgentService", e)
        }
        
        // 2. WorkManager — гарантированный watchdog (каждые 15 мин)
        // Если AgentService упал, WorkManager его поднимет
        try {
            AgentWorker.schedule(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule AgentWorker", e)
        }
    }
}
