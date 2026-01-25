# Enterprise Slot Assignment System - Архитектура

## Проблема

При клонировании эмулятора с агентом:
1. Агент детектирует "новое окружение" → генерирует НОВЫЙ `device_id`
2. Но на бэкенде привязки (аккаунты, прокси, группы) связаны со СТАРЫМ `device_id`
3. **Результат: клон "чистый", не знает свои данные**

## Решение: Slot-Based Architecture

### Концепция

```
┌─────────────────────────────────────────────────────────────────┐
│                     SLOT ASSIGNMENT SYSTEM                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  LDPlayer PC                                                    │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Instance 0         Instance 1         Instance 23        │  │
│  │ (port:5555)        (port:5557)        (port:5599)        │  │
│  │ ┌─────────┐        ┌─────────┐        ┌─────────┐       │  │
│  │ │ Агент   │        │ Агент   │        │ Агент   │       │  │
│  │ │slot_id=0│        │slot_id=1│        │slot_id=23│      │  │
│  │ │device=a1│        │device=b2│        │device=c3│       │  │
│  │ └────┬────┘        └────┬────┘        └────┬────┘       │  │
│  └──────┼──────────────────┼──────────────────┼─────────────┘  │
│         │                  │                  │                 │
│         └──────────────────┼──────────────────┘                 │
│                            ▼                                    │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                    BACKEND SERVER                         │  │
│  │                                                           │  │
│  │   slot_assignments                                        │  │
│  │   ┌──────────┬────────────┬─────────┬──────────────┐     │  │
│  │   │ slot_id  │ device_id  │ account │ proxy        │     │  │
│  │   ├──────────┼────────────┼─────────┼──────────────┤     │  │
│  │   │ pc1:0    │ a1b2c3d4   │ @user1  │ socks5://... │     │  │
│  │   │ pc1:1    │ e5f6g7h8   │ @user2  │ socks5://... │     │  │
│  │   │ pc1:23   │ NULL (new) │ @user24 │ socks5://... │     │  │
│  │   └──────────┴────────────┴─────────┴──────────────┘     │  │
│  │                                                           │  │
│  │   При регистрации агента:                                 │  │
│  │   1. Агент отправляет: device_id + slot_id               │  │
│  │   2. Сервер: UPDATE slot_assignments                      │  │
│  │              SET device_id = $new_id                      │  │
│  │              WHERE slot_id = $slot_id                     │  │
│  │   3. Сервер → агенту: assigned_account, assigned_proxy   │  │
│  │                                                           │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Источники Slot ID

### 1. LDPlayer (приоритет 1)
```kotlin
// build.prop или getprop
ro.ld.player.index = 0, 1, 2, 3...
ro.ld.adb.port = 5555, 5557, 5559...

// Slot ID формат:
slot_id = "ld:${playerIndex}"  // "ld:0", "ld:1", "ld:23"
```

### 2. Memu (приоритет 1)
```kotlin
// getprop
ro.memu.instance.id = memu_1, memu_2...

// Slot ID:
slot_id = "memu:${instanceId}"
```

### 3. Nox (приоритет 1)
```kotlin
// getprop
ro.nox.instance.id = nox:0, nox:1...

// Slot ID:
slot_id = "nox:${instanceId}"
```

### 4. SD Card Config (приоритет 2 - fallback)
```
/sdcard/.sphere_slot
содержит: pc1:5 (PC hostname + slot number)
```

### 5. Manual Assignment (приоритет 3)
```kotlin
// APK Settings позволяет вручную задать slot_id
// Полезно для физических устройств или нестандартных эмуляторов
```

### 6. Auto-assign (fallback)
```
Если ничего не определено:
slot_id = "auto:${first_8_chars_of_device_id}"
```

## Данные привязанные к Slot

```python
class SlotAssignment:
    # Идентификация
    slot_id: str           # "ld:0", "memu:1", "pc1:5"
    pc_identifier: str     # "pc1", "farm-server-2"
    
    # Текущее устройство (меняется при клоне/пересоздании)
    current_device_id: Optional[UUID]  # Последний известный device_id
    last_seen_at: datetime
    
    # СТАБИЛЬНЫЕ привязки (НЕ МЕНЯЮТСЯ при пересоздании)
    assigned_account_id: Optional[UUID]     # Telegram/Instagram аккаунт
    assigned_proxy_id: Optional[UUID]       # Прокси
    assigned_group_id: Optional[UUID]       # Группа устройств
    assigned_template_id: Optional[UUID]    # Farm template
    
    # Конфигурация слота
    config: dict  # {
    #   "auto_start_script": "uuid",
    #   "max_daily_actions": 500,
    #   "timezone": "Europe/Moscow",
    #   "tags": ["premium", "active"]
    # }
    
    # Состояние
    status: str  # "active", "paused", "error", "unassigned"
    last_script_state: Optional[dict]  # Для resume после пересоздания
```

## Сценарии

### Сценарий 1: Первый запуск нового эмулятора

```
1. LDPlayer создаёт эмулятор #5
2. Агент запускается, определяет: 
   - device_id = "abc123..."
   - slot_id = "ld:5" (из ro.ld.player.index)
3. Агент → сервер: {"type": "hello", "device_id": "abc123", "slot_id": "ld:5"}
4. Сервер проверяет slot_assignments:
   - ЕСЛИ slot_id "ld:5" существует → 
     UPDATE device_id, возврат assigned_account/proxy
   - ЕСЛИ не существует →
     INSERT новый slot, статус "unassigned"
5. Агент получает ответ:
   {
     "type": "registered",
     "slot_id": "ld:5",
     "assignment": {
       "account": {"id": "...", "username": "@user5", "session": "..."},
       "proxy": {"type": "socks5", "host": "...", "port": ...},
       "script_id": "auto-start-script-uuid"
     }
   }
6. Агент применяет конфигурацию
```

### Сценарий 2: Клонирование эмулятора

```
1. LDPlayer #5 клонируется → LDPlayer #6
2. Клон #6 запускается
3. Агент определяет:
   - Fingerprint изменился → НОВЫЙ device_id = "xyz789"
   - slot_id = "ld:6" (из ro.ld.player.index=6)
4. Агент → сервер: {"device_id": "xyz789", "slot_id": "ld:6"}
5. Сервер видит: slot "ld:6" существует с другим device_id
   → UPDATE device_id = "xyz789"
   → Возврат существующих привязок
6. Клон получает ПРАВИЛЬНЫЕ аккаунт/прокси для слота 6!
```

### Сценарий 3: Пересоздание эмулятора (clean install)

```
1. LDPlayer #5 удаляется и создаётся заново
2. SD-карта сохранена → /sdcard/.sphere_slot содержит "ld:5"
3. Агент устанавливается:
   - Читает slot_id с SD-карты
   - Генерирует новый device_id
4. Регистрация с тем же slot_id
5. Все привязки сохранены!
```

### Сценарий 4: Resume скрипта после disconnect

```
1. Эмулятор #5 отключился во время скрипта на шаге 15 из 50
2. Сервер сохраняет в slot_assignments:
   last_script_state = {
     "execution_id": "...",
     "step": 15,
     "variables": {...},
     "interrupted_at": "..."
   }
3. Эмулятор #5 возвращается (возможно с новым device_id после reboot)
4. Регистрация со slot_id = "ld:5"
5. Сервер возвращает: resume_execution = {...}
6. Агент продолжает с шага 15
```

## API Endpoints

### POST /api/v1/slots
Создать/обновить слот
```json
{
  "slot_id": "ld:5",
  "pc_identifier": "farm-pc-1",
  "account_id": "uuid",
  "proxy_id": "uuid",
  "group_id": "uuid",
  "config": {...}
}
```

### GET /api/v1/slots/{slot_id}
Получить информацию о слоте

### GET /api/v1/slots?pc_identifier=farm-pc-1
Список слотов PC

### POST /api/v1/slots/batch
Массовое назначение (для 24 слотов сразу)
```json
{
  "pc_identifier": "farm-pc-1",
  "slots": [
    {"slot_id": "ld:0", "account_id": "...", "proxy_id": "..."},
    {"slot_id": "ld:1", "account_id": "...", "proxy_id": "..."},
    ...
  ]
}
```

### PUT /api/v1/slots/{slot_id}/assignment
Изменить привязку аккаунта/прокси

### DELETE /api/v1/slots/{slot_id}
Удалить слот (освободить)

## Изменения в APK

### 1. SlotConfig class
```kotlin
class SlotConfig(private val context: Context) {
    fun detectSlotId(): String {
        // 1. LDPlayer
        val ldIndex = getprop("ro.ld.player.index")
        if (ldIndex.isNotEmpty()) return "ld:$ldIndex"
        
        // 2. Memu
        val memuId = getprop("ro.memu.instance.id")
        if (memuId.isNotEmpty()) return "memu:$memuId"
        
        // 3. Nox
        val noxId = getprop("ro.nox.instance.id")
        if (noxId.isNotEmpty()) return "nox:$noxId"
        
        // 4. SD card fallback
        val sdSlot = readFile("/sdcard/.sphere_slot")
        if (sdSlot != null) return sdSlot
        
        // 5. Auto-assign
        return "auto:${deviceId.take(8)}"
    }
}
```

### 2. Extended Hello message
```kotlin
data class Hello(
    val type: String = "hello",
    val device_id: String,
    val slot_id: String,           // NEW!
    val slot_source: String,       // "ldplayer", "memu", "sdcard", "manual", "auto"
    val device_name: String,
    // ... остальные поля
)
```

### 3. Handle assignment response
```kotlin
fun handleRegistered(data: JsonObject) {
    val assignment = data["assignment"]?.jsonObject
    if (assignment != null) {
        // Сохраняем локально
        slotConfig.saveAssignment(
            accountId = assignment["account_id"]?.jsonPrimitive?.content,
            proxyConfig = assignment["proxy"]?.jsonObject,
            autoStartScript = assignment["auto_start_script"]?.jsonPrimitive?.content
        )
        
        // Применяем прокси
        if (assignment["proxy"] != null) {
            networkManager.setProxy(assignment["proxy"])
        }
        
        // Запускаем auto-start скрипт
        if (assignment["auto_start_script"] != null) {
            scriptEngine.execute(assignment["auto_start_script"])
        }
    }
}
```

## Изменения в Backend

### 1. Новая таблица slot_assignments
```sql
CREATE TABLE slot_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Идентификация слота
    slot_id VARCHAR(100) UNIQUE NOT NULL,  -- "ld:0", "memu:1"
    pc_identifier VARCHAR(255),             -- "farm-pc-1"
    
    -- Текущее устройство
    current_device_id UUID REFERENCES devices(id),
    last_device_fingerprint VARCHAR(255),
    last_seen_at TIMESTAMP WITH TIME ZONE,
    
    -- Привязки (стабильные)
    assigned_account_id UUID,
    assigned_proxy_id UUID REFERENCES proxies(id),
    assigned_group_id UUID,
    assigned_template_id UUID REFERENCES farm_templates(id),
    
    -- Конфигурация
    config JSONB DEFAULT '{}',
    
    -- Состояние
    status VARCHAR(50) DEFAULT 'unassigned',  -- active, paused, error, unassigned
    last_script_state JSONB,  -- Для resume
    
    -- Audit
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_slot_assignments_slot_id ON slot_assignments(slot_id);
CREATE INDEX idx_slot_assignments_device ON slot_assignments(current_device_id);
CREATE INDEX idx_slot_assignments_pc ON slot_assignments(pc_identifier);
```

### 2. SlotAssignmentService
```python
class SlotAssignmentService:
    async def register_device(
        self,
        device_id: str,
        slot_id: str,
        slot_source: str
    ) -> SlotAssignment:
        """
        Регистрация устройства в слоте.
        Если слот существует - обновляем device_id.
        Если нет - создаём новый.
        """
        
    async def get_assignment(self, slot_id: str) -> Optional[dict]:
        """Получить текущие привязки слота"""
        
    async def assign_account(
        self,
        slot_id: str,
        account_id: str,
        proxy_id: Optional[str] = None
    ) -> SlotAssignment:
        """Привязать аккаунт к слоту"""
        
    async def batch_assign(
        self,
        pc_identifier: str,
        assignments: List[dict]
    ) -> List[SlotAssignment]:
        """Массовое назначение для фермы"""
```

### 3. Изменения в agent_router.py
```python
# При hello/register
slot_id = data.get("slot_id")
slot_source = data.get("slot_source", "auto")

if slot_id:
    # Регистрируем в slot system
    assignment = await slot_service.register_device(
        device_id=agent_id,
        slot_id=slot_id,
        slot_source=slot_source
    )
    
    # Отправляем назначения агенту
    await websocket.send_json({
        "type": "registered",
        "agent_id": agent_id,
        "slot_id": slot_id,
        "assignment": {
            "account": await get_account_config(assignment.assigned_account_id),
            "proxy": await get_proxy_config(assignment.assigned_proxy_id),
            "auto_start_script": assignment.config.get("auto_start_script"),
            "resume_execution": assignment.last_script_state
        }
    })
```

## Миграция существующих устройств

```sql
-- Для каждого существующего device создаём slot
INSERT INTO slot_assignments (slot_id, current_device_id, status)
SELECT 
    'auto:' || LEFT(CAST(id AS TEXT), 8),
    id,
    'active'
FROM devices
ON CONFLICT (slot_id) DO NOTHING;
```

## Тестовые сценарии

1. [ ] Первая регистрация LDPlayer instance 0
2. [ ] Клонирование → новый instance с существующим slot
3. [ ] Переподключение после reboot
4. [ ] Resume скрипта после disconnect
5. [ ] Batch assignment для 24 слотов
6. [ ] Ручное переназначение аккаунта
7. [ ] SD-card fallback при отсутствии props

## Безопасность

- Slot ID не может быть изменён после создания
- Привязки меняются только через API с авторизацией
- Device ID не влияет на привязки - только slot_id
- Аккаунты не передаются в plain text - только session tokens
