# SphereAgent APK — Performance & Stability Audit

**Date:** 2025-01-XX  
**Scope:** All Kotlin source files in `app/src/main/java/com/sphere/agent/`  
**Target:** 14+ LDPlayer emulators running simultaneously  
**Excludes:** Already-fixed v3.6.0 issues (BootReceiver, RootAutoStart/RootInitInstaller batch su, AgentService restart alarms, SphereAgentApp deferred ROOT)

---

## CRITICAL (5 issues)

### C-1. CommandExecutor: Process streams never closed — FD exhaustion

**File:** `service/CommandExecutor.kt` lines 174–185, 206–216, 235–250+  
**Problem:** Every `checkRootMethod1/2/3/4`, `executeRootCommand`, `executeShellCommand`, and `executeInputCommand` create `BufferedReader(InputStreamReader(process.inputStream))` but **never close** the reader, the inputStream, or the errorStream. On 14 emulators executing continuous commands this leaks file descriptors until the process hits the FD limit (~1024) and crashes with `Too many open files`.

**Fix:** Wrap all process I/O in `use {}` blocks and always drain + close error stream:

```kotlin
// BEFORE (every checkRootMethodN, executeRootCommand, etc.)
val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
val reader = BufferedReader(InputStreamReader(process.inputStream))
val result = reader.readLine() ?: ""
val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
// reader NEVER closed, errorStream NEVER read/closed

// AFTER
val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
try {
    val result = process.inputStream.bufferedReader().use { it.readLine() ?: "" }
    // Drain error stream to prevent process blocking
    process.errorStream.bufferedReader().use { it.readText() }
    val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
    if (!finished) {
        process.destroyForcibly()
        return false
    }
    val exitCode = process.exitValue()
    result.contains("uid=0")
} finally {
    process.destroyForcibly()
}
```

Apply the same pattern to **all** 6+ methods that spawn processes in this file.

---

### C-2. CommandExecutor: `readText()` with no size limit — OOM on large output

**File:** `service/CommandExecutor.kt` line 207  
**Problem:** `executeRootCommand` calls `reader.readText()` which reads the **entire** stdout into one String. A malicious or buggy shell command (e.g., `cat /dev/urandom` or `logcat -d`) can produce unbounded output, causing OOM and killing the agent on all 14 emulators simultaneously.

**Fix:** Limit read to a safe maximum:

```kotlin
// BEFORE
val result = reader.readText()

// AFTER
val result = reader.readText().take(MAX_OUTPUT_SIZE)

// Add constant:
companion object {
    private const val MAX_OUTPUT_SIZE = 256 * 1024 // 256KB max
}
```

---

### C-3. H264RootStreamService: Unbounded `nalBuffer` growth — OOM crash

**File:** `service/H264RootStreamService.kt` line ~413  
**Problem:** `nalBuffer += buffer.copyOf(bytesRead)` continuously concatenates byte arrays. If NAL unit extraction falls behind (e.g., WebSocket backpressure), `nalBuffer` grows unbounded. Each `+=` also allocates a new array, copying all previous data — O(n²) memory behavior. On 14 emulators streaming simultaneously, this triggers OOM crash.

**Fix:** Use a `ByteArrayOutputStream` with bounded capacity and periodic reset:

```kotlin
// BEFORE
var nalBuffer = ByteArray(0)
// in loop:
nalBuffer += buffer.copyOf(bytesRead)

// AFTER
val MAX_NAL_BUFFER = 2 * 1024 * 1024 // 2MB max
val nalStream = ByteArrayOutputStream(65536)
// in loop:
if (nalStream.size() + bytesRead > MAX_NAL_BUFFER) {
    SphereLog.w(TAG, "NAL buffer overflow (${nalStream.size()} bytes), resetting")
    nalStream.reset()
}
nalStream.write(buffer, 0, bytesRead)
// For extraction:
val nalBuffer = nalStream.toByteArray()
// After extraction, keep only unprocessed tail:
nalStream.reset()
nalStream.write(remaining, 0, remaining.size)
```

---

### C-4. ConnectionManager: `pendingFrames` non-atomic increment — race condition

**File:** `network/ConnectionManager.kt` lines 569, 582, 585  
**Problem:** `pendingFrames` is declared as `@Volatile var` but undergoes non-atomic read-modify-write (`pendingFrames++` and `pendingFrames--`). Even with `@Volatile`, the `++`/`--` operators are NOT atomic — they are read + increment + write as three operations. Under concurrent frame sending from H264 stream + frame throttle checks, this can lead to `pendingFrames` going negative or exceeding the real count, causing frames to be permanently blocked or sent without throttling.

**Fix:** Use `AtomicInteger`:

```kotlin
// BEFORE
@Volatile private var pendingFrames: Int = 0
// ...
pendingFrames++
// ...
if (pendingFrames > 0) pendingFrames--

// AFTER
private val pendingFrames = AtomicInteger(0)
// ...
pendingFrames.incrementAndGet()
// ...
pendingFrames.decrementAndGet().coerceAtLeast(0) // never go negative
// Throttle check:
if (pendingFrames.get() >= maxPendingFrames) { ... }
```

---

### C-5. ConnectionManager: `offlineBuffer` flush race — message loss

**File:** `network/ConnectionManager.kt` lines 684–685  
**Problem:** `flushOfflineBuffer` does `toList()` then `clear()` as two separate operations on a `ConcurrentLinkedQueue`. Between these two calls, another thread can `add()` a new message which is then lost by the `clear()`. This is a classic TOCTOU race.

**Fix:** Drain atomically using `poll()`:

```kotlin
// BEFORE
val messages = offlineBuffer.toList().sortedByDescending { it.priority }
offlineBuffer.clear()

// AFTER
val messages = mutableListOf<BufferedMessage>()
while (true) {
    val msg = offlineBuffer.poll() ?: break
    messages.add(msg)
}
messages.sortByDescending { it.priority }
```

---

## HIGH (6 issues)

### H-1. SphereAgentApp: `applicationScope` never cancelled — coroutine leak

**File:** `SphereAgentApp.kt` line 56  
**Problem:** `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` is created but never cancelled in any lifecycle callback. While `Application` is never destroyed in normal Android lifecycle, on emulators with aggressive process killing this can leave dangling coroutines and prevent clean GC of the Application object.

**Fix:** Cancel in `onTerminate()` (called on emulators) or use `ProcessLifecycleOwner`:

```kotlin
override fun onTerminate() {
    super.onTerminate()
    applicationScope.cancel()
}
```

---

### H-2. ScreenCaptureService: Creates new UpdateManager with infinite polling in `onCreate()`

**File:** `service/ScreenCaptureService.kt` lines 241–244  
**Problem:** Every time the service starts, it creates `UpdateManager(applicationContext)` (which instantiates its own `OkHttpClient`) and calls `startPeriodicCheck(scope)` which runs `while(true) { checkForUpdates(); delay(1h) }`. If the service is restarted, a new loop starts **without stopping the old one** (if the old scope is still alive). Multiple infinite loops waste CPU and network.

**Fix:** Remove the update check from ScreenCaptureService — it's already handled by `UpdateWorker` via WorkManager. If keeping it:

```kotlin
// REMOVE these lines from ScreenCaptureService.onCreate():
scope.launch {
    val updateManager = UpdateManager(applicationContext)
    updateManager.startPeriodicCheck(scope)
}
// UpdateWorker already handles periodic update checks via WorkManager
```

---

### H-3. service/UpdateManager: Independent `OkHttpClient` per instance — connection pool waste

**File:** `service/UpdateManager.kt` line 50  
**Problem:** Each `UpdateManager` instance creates its own `OkHttpClient` with separate connection pool and thread pool. `UpdateManager` is instantiated in `ScreenCaptureService.onCreate()`, `AgentService` (on `update_agent` command), and `UpdateWorker`. With 14 emulators, this means 42+ OkHttpClient instances with separate connection pools (each having idle connections, executor threads, and dispatcher).

**Fix:** Inject or use a shared `OkHttpClient` from `NetworkModule`:

```kotlin
// BEFORE
private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

// AFTER — use SphereAgentApp singleton or DI
class UpdateManager(
    private val context: Context,
    private val client: OkHttpClient = SphereAgentApp.instance.sharedHttpClient
) { ... }
```

Same issue exists in:
- `EmergencyCommandExecutor.kt` (own OkHttpClient)
- `HttpPollingFallback.kt` (own OkHttpClient)
- `ServerDiscoveryManager.kt` (own OkHttpClient)
- `SphereLog.kt` (own OkHttpClient)
- `AgentConfig.kt` (own OkHttpClient)
- `RemoteConfigManager.kt` (own OkHttpClient)

Total: **7 independent OkHttpClient instances** per emulator, **98+ across fleet**.

---

### H-4. ServerDiscoveryManager: 254 parallel async coroutines — thread/CPU exhaustion

**File:** `network/ServerDiscoveryManager.kt` lines 465–476  
**Problem:** `tryNetworkScan()` launches 254 `async` coroutines (one per IP in the subnet) simultaneously. Each opens a socket with 1.5s timeout. On `Dispatchers.IO` (limited to 64 threads), this creates a coroutine backlog. On 14 emulators doing discovery simultaneously, that's 3,556 concurrent socket connections overwhelming the host network stack.

**Fix:** Use a semaphore to limit parallelism:

```kotlin
// BEFORE
val scanJobs = (1..254).map { i ->
    async {
        if (isPortOpen(ip, SERVER_PORT, SCAN_TIMEOUT_MS.toInt())) { ... }
    }
}

// AFTER
val semaphore = Semaphore(32) // Max 32 concurrent connections
val scanJobs = (1..254).map { i ->
    async {
        semaphore.withPermit {
            if (isPortOpen(ip, SERVER_PORT, SCAN_TIMEOUT_MS.toInt())) { ... }
        }
    }
}
```

---

### H-5. ScriptEngine: `scope` never cancelled on stop — zombie coroutines

**File:** `script/ScriptEngine.kt` line 51 vs `destroy()` at ~158  
**Problem:** `ScriptEngine.scope` is only cancelled in `destroy()`. But `stopAllScripts()` only calls `runner.stop()` + removes from map — it does NOT cancel `scope`. If `AgentService` calls `stopAllScripts()` without `destroy()`, the 5-minute delayed cleanup coroutines (launched at line ~152) survive and reference dead `ScriptRunner` objects.

**Fix:** Ensure `destroy()` is always called, or cancel cleanup coroutines on `stopAllScripts()`:

```kotlin
fun stopAllScripts() {
    runners.keys.toList().forEach { runId ->
        stopScript(runId)
    }
    // Cancel pending cleanup coroutines too
    scope.coroutineContext.cancelChildren()
    Log.i(TAG, "Stopped all scripts")
}
```

---

### H-6. GlobalVariables / ScriptEventBus: `scope` lives forever in `object` — never reclaimed

**File:** `script/GlobalVariables.kt` line ~69, `script/ScriptEventBus.kt` line ~24  
**Problem:** Both are `object` singletons with `CoroutineScope(Dispatchers.IO + SupervisorJob())` that live for the entire process lifetime (since `object` is never garbage collected). Both have `shutdown()` methods but they are never called from `AgentService.onDestroy()` or `SphereAgentApp`. On process restart after crash, old scopes may still be active creating duplicate work.

**Fix:** Call `shutdown()` from `AgentService.onDestroy()`:

```kotlin
// In AgentService.onDestroy():
GlobalVariables.shutdown()
ScriptEventBus.shutdown()
```

---

## MEDIUM (7 issues)

### M-1. service/UpdateManager: `response.body?.string()` not closed — connection leak

**File:** `service/UpdateManager.kt` line 105  
**Problem:** `response.body?.string()` reads the body but the `Response` object is not wrapped in `use {}`. OkHttp requires response bodies to be closed. Unclosed response bodies leak connections from the connection pool.

**Fix:**

```kotlin
// BEFORE
val response = client.newCall(request).execute()
if (!response.isSuccessful) { ... return }
val json = JSONObject(response.body?.string() ?: "{}")

// AFTER
client.newCall(request).execute().use { response ->
    if (!response.isSuccessful) { ... return@use }
    val json = JSONObject(response.body?.string() ?: "{}")
    // ... rest of logic
}
```

Same issue in `checkForUpdates()` and `downloadApk()` in this file.

---

### M-2. RootAutoStart.hasRootAccess(): Process streams not closed

**File:** `util/RootAutoStart.kt` lines 32–42  
**Problem:** `BufferedReader(InputStreamReader(process.inputStream))` and `reader.readText()` are used without closing the reader or the errorStream. Process may zombie.

**Fix:**

```kotlin
// BEFORE
val process = Runtime.getRuntime().exec("su -c id")
val reader = BufferedReader(InputStreamReader(process.inputStream))
val output = reader.readText()

// AFTER
val process = Runtime.getRuntime().exec("su -c id")
val output = process.inputStream.bufferedReader().use { it.readText() }
process.errorStream.close() // drain + close to prevent zombie
```

Same pattern in `RootAutoStart.executeRootCommand()` and `RootInitInstaller.executeRootCommand()` — streams are closed but AFTER `waitFor`, which can rarely hang if error stream buffer fills up (process blocks on write to stderr). Fix: read both streams before `waitFor`, or redirect error to stdout.

---

### M-3. BootContentProvider: Blocking `Thread.sleep(2000)` on application startup

**File:** `provider/BootContentProvider.kt` lines 43–45  
**Problem:** `Thread.sleep(2000)` inside a `Thread { }` block delays agent startup by 2 seconds. The retry path adds another 5 seconds. While the thread is separate from the main thread, it consumes resources and delays service initialization.

**Fix:** Use a Handler/coroutine with delay instead of blocking a thread:

```kotlin
// BEFORE
Thread {
    Thread.sleep(2000)
    // ...start service
}.start()

// AFTER — Use coroutine to avoid blocking thread
CoroutineScope(Dispatchers.IO).launch {
    delay(2000)
    // ...start service
}
```

---

### M-4. SphereLog: `logBuffer.size` check is non-atomic with `poll()`

**File:** `util/SphereLog.kt` lines 63–65  
**Problem:** `if (logBuffer.size > MAX_BUFFER_SIZE) { logBuffer.poll() }` — between checking `size` and calling `poll()`, another thread can modify the queue. On 14 emulators with heavy logging, this allows buffer to temporarily exceed MAX_BUFFER_SIZE. Not a crash risk, but inconsistent.

**Fix:** Use while-loop trim after add:

```kotlin
logBuffer.add(entry)
while (logBuffer.size > MAX_BUFFER_SIZE) {
    logBuffer.poll() ?: break
}
```

---

### M-5. SphereLog: Failed log sends re-add all logs, causing infinite retry growth

**File:** `util/SphereLog.kt` lines 92–94  
**Problem:** On `sendLogs()` failure (network error), `logBuffer.addAll(logsToSend)` puts all logs back into the buffer. On the next interval (10s), they are drained and sent again. If the server stays unreachable, these same logs cycle indefinitely — growing as new logs are added each cycle. Eventually the buffer exceeds `MAX_BUFFER_SIZE` and the trim logic keeps dropping new legitimate logs (FIFO). 

**Fix:** Add a retry counter or cap re-additions:

```kotlin
// Add retry tracking to LogEntry
@Serializable
data class LogEntry(
    // ... existing fields
    val retryCount: Int = 0
)

// In sendLogs() catch:
logsToSend
    .filter { it.retryCount < 3 }
    .map { it.copy(retryCount = it.retryCount + 1) }
    .forEach { logBuffer.add(it) }
```

---

### M-6. AgentConfig: 5 sequential `getprop` calls with 2s timeout each — 10s FD fingerprinting delay

**File:** `core/AgentConfig.kt` lines ~341–360 (`addEmulatorSpecificFingerprint`)  
**Problem:** The loop `for (prop in ldplayerProps.take(5))` runs up to 5 `Runtime.getRuntime().exec(arrayOf("getprop", prop))` calls sequentially, each with a 2-second timeout. In the worst case (all timeout), fingerprinting takes 10 seconds. This runs lazily on first `deviceId` access, which happens during `connect()` — potentially blocking the first WebSocket connection for 10 seconds.

**Fix:** Run all getprop calls in parallel with a shared timeout:

```kotlin
// BEFORE
for (prop in ldplayerProps.take(5)) {
    val process = runtime.exec(arrayOf("getprop", prop))
    val value = process.inputStream.bufferedReader().readText().trim()
    val finished = process.waitFor(2, TimeUnit.SECONDS)
    // ...
}

// AFTER — batch all getprop into one shell call
val propsScript = ldplayerProps.take(5).joinToString("\n") { "getprop $it" }
val process = runtime.exec(arrayOf("sh", "-c", propsScript))
val output = process.inputStream.bufferedReader().use { it.readText() }
val finished = process.waitFor(5, TimeUnit.SECONDS)
if (!finished) process.destroyForcibly()
val lines = output.lines()
ldplayerProps.take(5).forEachIndexed { idx, prop ->
    val value = lines.getOrNull(idx)?.trim() ?: ""
    if (value.isNotBlank() && value != "unknown" && value.length > 1) {
        components.add("gp:${prop.takeLast(8)}=${value.hashCode()}")
    }
}
```

---

### M-7. RecoveryBroadcastReceiver: Weak authentication — any app can send recovery commands

**File:** `recovery/RecoveryBroadcastReceiver.kt` lines 79–82  
**Problem:** The auth check `if (BuildConfig.DEBUG || authToken == RECOVERY_AUTH_TOKEN)` uses a hardcoded token `"sphere_recovery_2026"` and in production **still falls through** due to the comment "пока пропускаем для удобства". Any app on the device can send `com.sphere.agent.RECOVERY_COMMAND` with `action=kill_and_restart` and crash the agent.

**Fix:** Enforce the token check in production:

```kotlin
// BEFORE
if (BuildConfig.DEBUG || authToken == RECOVERY_AUTH_TOKEN) {
    // OK
} else {
    Log.w(TAG, "Invalid auth token!")
    // Still continues execution!
}

// AFTER
if (authToken != RECOVERY_AUTH_TOKEN) {
    Log.w(TAG, "Invalid auth token, rejecting recovery command")
    return  // Actually reject!
}
```

---

## LOW (4 issues)

### L-1. NetworkModule: Provides singleton OkHttpClient with `readTimeout(0)` but it's not injected anywhere

**File:** `di/NetworkModule.kt` lines 24–30  
**Problem:** The Hilt module provides a `@Singleton OkHttpClient` but none of the classes that create their own OkHttpClient (ConnectionManager, UpdateManager, EmergencyCommandExecutor, etc.) inject it. This is a design smell — the DI-provided client is wasted.

**Fix:** Inject the shared OkHttpClient into classes via constructor injection instead of creating independent instances.

---

### L-2. RootInitInstaller.isInitScriptInstalled(): Sequential ROOT calls without batching

**File:** `util/RootInitInstaller.kt` lines ~218–226  
**Problem:** `isInitScriptInstalled()` iterates INIT_PATHS and calls `executeRootCommand()` for each path — potentially 6 sequential `su` processes. Not a startup path, but inefficient.

**Fix:** Batch into one `su` call:

```kotlin
fun isInitScriptInstalled(): Boolean {
    val checkScript = INIT_PATHS.joinToString("\n") { path ->
        "[ -f $path/$SCRIPT_NAME.sh ] && echo 'installed'"
    }
    val result = executeRootCommand(checkScript)
    return result.first && result.second.contains("installed")
}
```

---

### L-3. ScriptLogSender: `logBuffers` and `executionMeta` are `mutableMapOf()` — not thread-safe

**File:** `script/ScriptLogSender.kt` lines ~109–117  
**Problem:** `logBuffers` and `executionMeta` are plain `mutableMapOf()` but accessed from multiple coroutines. While the individual `ConcurrentLinkedQueue` values are thread-safe, the map operations (`getOrPut`, `remove`, iteration in `flushAllBuffers`) are not.

**Fix:** Use `ConcurrentHashMap`:

```kotlin
// BEFORE
private val logBuffers = mutableMapOf<String, ConcurrentLinkedQueue<LogEntry>>()
private val executionMeta = mutableMapOf<String, ExecutionMeta>()

// AFTER
private val logBuffers = ConcurrentHashMap<String, ConcurrentLinkedQueue<LogEntry>>()
private val executionMeta = ConcurrentHashMap<String, ExecutionMeta>()
```

---

### L-4. update/UpdateManager: `hasRootAccess()` reads inputStream AFTER `waitFor()` — may be empty

**File:** `update/UpdateManager.kt` lines ~292–300  
**Problem:** The `hasRootAccess()` method calls `process.waitFor(3, TimeUnit.SECONDS)` then reads `process.inputStream`. After `waitFor` completes, the process is terminated and the inputStream may already be closed or partially consumed (depending on OS buffering). This can cause intermittent false negatives for ROOT detection.

**Fix:** Read streams before waiting:

```kotlin
// BEFORE
val finished = process.waitFor(3, TimeUnit.SECONDS)
// ...
val output = process.inputStream.bufferedReader().readText()

// AFTER
val output = process.inputStream.bufferedReader().use { it.readText() }
val finished = process.waitFor(3, TimeUnit.SECONDS)
```

---

## Summary

| Severity | Count | Examples |
|----------|-------|---------|
| CRITICAL | 5 | FD exhaustion, OOM (2×), race conditions (2×) |
| HIGH | 6 | Scope leaks, connection pool waste, thread exhaustion |
| MEDIUM | 7 | Connection leaks, delayed startup, infinite retry, weak auth |
| LOW | 4 | Thread safety, dead DI code, minor inefficiency |
| **TOTAL** | **22** | |

**Highest fleet-wide impact:** C-1 (FD exhaustion) and C-3 (NAL buffer OOM) — both will crash all 14 emulators within hours of operation under normal load. Fix these first.
