package com.sphere.agent.script

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Глобальный менеджер переменных - обмен данными между скриптами
 * 
 * Enterprise-level функции:
 * - Глобальные переменные, доступные всем скриптам на устройстве
 * - Потокобезопасность через ConcurrentHashMap + ReadWriteLock
 * - TTL (время жизни) для переменных
 * - Namespaces для изоляции групп переменных
 * - Подписки на изменения переменных
 * - Персистентность (опционально)
 */
object GlobalVariables {
    
    private const val TAG = "GlobalVariables"
    
    // Основное хранилище: namespace -> key -> value
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, VariableEntry>>()
    
    // Подписчики на изменения
    private val listeners = ConcurrentHashMap<String, MutableList<VariableChangeListener>>()
    
    // Lock для атомарных операций
    private val lock = ReentrantReadWriteLock()
    
    // Namespace по умолчанию
    const val DEFAULT_NAMESPACE = "global"
    
    /**
     * Запись переменной с метаданными
     */
    data class VariableEntry(
        val value: Any?,
        val createdAt: Long = System.currentTimeMillis(),
        val expiresAt: Long? = null,  // null = бессрочно
        val createdBy: String? = null, // ID скрипта-создателя
        val metadata: Map<String, Any> = emptyMap()
    ) {
        fun isExpired(): Boolean {
            return expiresAt != null && System.currentTimeMillis() > expiresAt
        }
    }
    
    /**
     * Слушатель изменений переменных
     */
    interface VariableChangeListener {
        fun onVariableChanged(namespace: String, key: String, oldValue: Any?, newValue: Any?)
    }
    
    // ===================== ОСНОВНЫЕ ОПЕРАЦИИ =====================
    
    /**
     * Установить глобальную переменную
     */
    fun set(
        key: String, 
        value: Any?, 
        namespace: String = DEFAULT_NAMESPACE,
        ttlMillis: Long? = null,
        scriptId: String? = null,
        metadata: Map<String, Any> = emptyMap()
    ) {
        lock.write {
            val namespaceStore = store.getOrPut(namespace) { ConcurrentHashMap() }
            val oldEntry = namespaceStore[key]
            val oldValue = oldEntry?.value
            
            val expiresAt = ttlMillis?.let { System.currentTimeMillis() + it }
            val entry = VariableEntry(
                value = value,
                expiresAt = expiresAt,
                createdBy = scriptId,
                metadata = metadata
            )
            
            namespaceStore[key] = entry
            
            Log.d(TAG, "SET [$namespace:$key] = $value (TTL: ${ttlMillis ?: "∞"}ms, by: $scriptId)")
            
            // Уведомляем подписчиков
            notifyListeners(namespace, key, oldValue, value)
        }
    }
    
    /**
     * Получить глобальную переменную
     */
    fun get(key: String, namespace: String = DEFAULT_NAMESPACE): Any? {
        return lock.read {
            val entry = store[namespace]?.get(key)
            
            if (entry == null) {
                Log.d(TAG, "GET [$namespace:$key] = null (not found)")
                return@read null
            }
            
            if (entry.isExpired()) {
                Log.d(TAG, "GET [$namespace:$key] = null (expired)")
                // Удаляем просроченную
                store[namespace]?.remove(key)
                return@read null
            }
            
            Log.d(TAG, "GET [$namespace:$key] = ${entry.value}")
            entry.value
        }
    }
    
    /**
     * Получить как строку
     */
    fun getString(key: String, namespace: String = DEFAULT_NAMESPACE, default: String = ""): String {
        return get(key, namespace)?.toString() ?: default
    }
    
    /**
     * Получить как число
     */
    fun getInt(key: String, namespace: String = DEFAULT_NAMESPACE, default: Int = 0): Int {
        return when (val value = get(key, namespace)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }
    
    /**
     * Получить как boolean
     */
    fun getBoolean(key: String, namespace: String = DEFAULT_NAMESPACE, default: Boolean = false): Boolean {
        return when (val value = get(key, namespace)) {
            is Boolean -> value
            is String -> value.lowercase() in listOf("true", "1", "yes")
            is Number -> value.toInt() != 0
            else -> default
        }
    }
    
    /**
     * Получить как Map
     */
    @Suppress("UNCHECKED_CAST")
    fun getMap(key: String, namespace: String = DEFAULT_NAMESPACE): Map<String, Any>? {
        return get(key, namespace) as? Map<String, Any>
    }
    
    /**
     * Получить как List
     */
    @Suppress("UNCHECKED_CAST")
    fun getList(key: String, namespace: String = DEFAULT_NAMESPACE): List<Any>? {
        return get(key, namespace) as? List<Any>
    }
    
    /**
     * Удалить переменную
     */
    fun remove(key: String, namespace: String = DEFAULT_NAMESPACE): Any? {
        return lock.write {
            val entry = store[namespace]?.remove(key)
            if (entry != null) {
                Log.d(TAG, "REMOVE [$namespace:$key]")
                notifyListeners(namespace, key, entry.value, null)
            }
            entry?.value
        }
    }
    
    /**
     * Проверить существование
     */
    fun exists(key: String, namespace: String = DEFAULT_NAMESPACE): Boolean {
        return lock.read {
            val entry = store[namespace]?.get(key)
            entry != null && !entry.isExpired()
        }
    }
    
    // ===================== АТОМАРНЫЕ ОПЕРАЦИИ =====================
    
    /**
     * Атомарный инкремент
     */
    fun increment(key: String, namespace: String = DEFAULT_NAMESPACE, delta: Int = 1): Int {
        return lock.write {
            val current = getInt(key, namespace, 0)
            val newValue = current + delta
            set(key, newValue, namespace)
            newValue
        }
    }
    
    /**
     * Атомарный декремент
     */
    fun decrement(key: String, namespace: String = DEFAULT_NAMESPACE, delta: Int = 1): Int {
        return increment(key, namespace, -delta)
    }
    
    /**
     * Установить если не существует (CAS - Compare And Set)
     */
    fun setIfAbsent(
        key: String, 
        value: Any?, 
        namespace: String = DEFAULT_NAMESPACE
    ): Boolean {
        return lock.write {
            if (!exists(key, namespace)) {
                set(key, value, namespace)
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Получить и установить (атомарно)
     */
    fun getAndSet(
        key: String, 
        value: Any?, 
        namespace: String = DEFAULT_NAMESPACE
    ): Any? {
        return lock.write {
            val old = get(key, namespace)
            set(key, value, namespace)
            old
        }
    }
    
    /**
     * Добавить в список
     */
    @Suppress("UNCHECKED_CAST")
    fun appendToList(key: String, value: Any, namespace: String = DEFAULT_NAMESPACE) {
        lock.write {
            val list = getList(key, namespace)?.toMutableList() ?: mutableListOf()
            list.add(value)
            set(key, list, namespace)
        }
    }
    
    /**
     * Добавить в Map
     */
    @Suppress("UNCHECKED_CAST")
    fun putToMap(key: String, mapKey: String, mapValue: Any, namespace: String = DEFAULT_NAMESPACE) {
        lock.write {
            val map = getMap(key, namespace)?.toMutableMap() ?: mutableMapOf()
            map[mapKey] = mapValue
            set(key, map, namespace)
        }
    }
    
    // ===================== NAMESPACE ОПЕРАЦИИ =====================
    
    /**
     * Получить все ключи в namespace
     */
    fun keys(namespace: String = DEFAULT_NAMESPACE): Set<String> {
        return lock.read {
            store[namespace]?.keys?.toSet() ?: emptySet()
        }
    }
    
    /**
     * Получить все переменные namespace как Map
     */
    fun getAll(namespace: String = DEFAULT_NAMESPACE): Map<String, Any?> {
        return lock.read {
            store[namespace]
                ?.filter { !it.value.isExpired() }
                ?.mapValues { it.value.value }
                ?: emptyMap()
        }
    }
    
    /**
     * Очистить namespace
     */
    fun clearNamespace(namespace: String) {
        lock.write {
            store.remove(namespace)
            Log.d(TAG, "CLEAR namespace: $namespace")
        }
    }
    
    /**
     * Список всех namespaces
     */
    fun namespaces(): Set<String> {
        return lock.read {
            store.keys.toSet()
        }
    }
    
    /**
     * Очистить всё
     */
    fun clearAll() {
        lock.write {
            store.clear()
            Log.d(TAG, "CLEAR ALL")
        }
    }
    
    // ===================== ПОДПИСКИ =====================
    
    /**
     * Подписаться на изменения переменной
     */
    fun subscribe(key: String, namespace: String = DEFAULT_NAMESPACE, listener: VariableChangeListener) {
        val fullKey = "$namespace:$key"
        listeners.getOrPut(fullKey) { mutableListOf() }.add(listener)
        Log.d(TAG, "SUBSCRIBE to $fullKey")
    }
    
    /**
     * Отписаться
     */
    fun unsubscribe(key: String, namespace: String = DEFAULT_NAMESPACE, listener: VariableChangeListener) {
        val fullKey = "$namespace:$key"
        listeners[fullKey]?.remove(listener)
    }
    
    /**
     * Уведомить подписчиков
     */
    private fun notifyListeners(namespace: String, key: String, oldValue: Any?, newValue: Any?) {
        val fullKey = "$namespace:$key"
        listeners[fullKey]?.forEach { listener ->
            try {
                listener.onVariableChanged(namespace, key, oldValue, newValue)
            } catch (e: Exception) {
                Log.e(TAG, "Listener error for $fullKey: ${e.message}")
            }
        }
    }
    
    // ===================== СЕРИАЛИЗАЦИЯ =====================
    
    /**
     * Экспорт всего состояния в JSON-совместимый Map
     */
    fun exportState(): Map<String, Map<String, Any?>> {
        return lock.read {
            store.mapValues { (_, entries) ->
                entries
                    .filter { !it.value.isExpired() }
                    .mapValues { it.value.value }
            }
        }
    }
    
    /**
     * Импорт состояния
     */
    fun importState(state: Map<String, Map<String, Any?>>) {
        lock.write {
            state.forEach { (namespace, variables) ->
                val namespaceStore = store.getOrPut(namespace) { ConcurrentHashMap() }
                variables.forEach { (key, value) ->
                    namespaceStore[key] = VariableEntry(value)
                }
            }
            Log.d(TAG, "IMPORT state: ${state.size} namespaces")
        }
    }
    
    // ===================== СТАТИСТИКА =====================
    
    /**
     * Получить статистику
     */
    fun stats(): Map<String, Any> {
        return lock.read {
            mapOf(
                "namespaces" to store.size,
                "total_variables" to store.values.sumOf { it.size },
                "listeners" to listeners.values.sumOf { it.size },
                "breakdown" to store.mapValues { it.value.size }
            )
        }
    }
    
    /**
     * Очистить просроченные переменные
     */
    fun cleanup(): Int {
        var removed = 0
        lock.write {
            store.forEach { (namespace, entries) ->
                val iterator = entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.value.isExpired()) {
                        iterator.remove()
                        removed++
                        Log.d(TAG, "CLEANUP expired: [$namespace:${entry.key}]")
                    }
                }
            }
        }
        return removed
    }
}
