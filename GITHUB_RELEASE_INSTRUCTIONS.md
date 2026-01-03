# Инструкция: Создание GitHub Release для APK v1.9.2

## Проблема

APK файл (11MB) загружен в git репозиторий, но GitHub **не отдаёт файлы >10MB через raw.githubusercontent.com**.

Проверка:
```bash
# APK ЕСТЬ в git (11MB)
git ls-tree HEAD releases/
git cat-file -s 18bc1d552d4583747587496a199cc8184aca23e7
# Output: 11045103

# Но raw.githubusercontent.com возвращает 404
curl -I https://raw.githubusercontent.com/RootOne1337/SphereAgent-APK/main/releases/SphereAgent-v1.9.2.apk
# Output: HTTP/2 404
```

## Решение: GitHub Releases

Для файлов >10MB используй GitHub Releases с Assets.

### Шаг 1: Открой GitHub

https://github.com/RootOne1337/SphereAgent-APK/releases/new

### Шаг 2: Заполни форму Release

**Tag version:** `v1.9.2`

**Release title:** `SphereAgent v1.9.2 - OTA Updates & ROOT Fix`

**Description:**

```markdown
# SphereAgent v1.9.2 - OTA Updates & ROOT Fix

## Новые возможности

### 🔄 OTA Updates (Over-The-Air)
- Автоматическая проверка обновлений при подключении
- Загрузка APK в фоновом режиме
- Тихая установка через ROOT без подтверждения пользователя
- Принудительные обновления (force_update в changelog.json)

### 🔧 ROOT Detection Fix
- Исправлено: результат checkRoot() не сохранялся
- Теперь has_root правильно определяется перед подключением
- Все команды управления (tap/swipe/keyEvent) работают корректно

### 📦 Silent Install via ROOT
- pm install -r -d через su
- Fallback на PackageInstaller если ROOT недоступен
- Логирование всех операций обновления

## Технические детали

- **Version Code:** 18
- **Version Name:** 1.9.2
- **Размер:** 11 MB
- **Min SDK:** 26 (Android 8.0)

## Загрузка

```bash
wget https://github.com/RootOne1337/SphereAgent-APK/releases/download/v1.9.2/SphereAgent-v1.9.2.apk
```

## Установка

```bash
adb install -r SphereAgent-v1.9.2.apk
```

## Changelog

### Added
- OTA Updates система
- Silent ROOT install
- update_agent command handler

### Fixed
- ROOT detection bug (has_root не сохранялся)
- Команды управления теперь работают через веб

### Changed
- UpdateManager использует ROOT для тихой установки
- AgentService обрабатывает команду update_agent
```

### Шаг 3: Загрузи APK как Asset

1. Нажми **"Attach binaries by dropping them here or selecting them"**
2. Выбери файл: `/home/rootone/SphereAgent-APK-Repo/releases/SphereAgent-v1.9.2.apk`
3. Дождись загрузки (11MB)
4. Убедись что filename: `SphereAgent-v1.9.2.apk`

### Шаг 4: Опубликуй Release

1. Сними галочку **"Set as a pre-release"** (если это stable release)
2. Нажми **"Publish release"**

## Результат

После публикации APK будет доступен по ссылке:

```
https://github.com/RootOne1337/SphereAgent-APK/releases/download/v1.9.2/SphereAgent-v1.9.2.apk
```

Эту ссылку можно использовать для:
- Прямой загрузки через браузер
- Скачивания через wget/curl
- OTA updates в SphereADB

## Обновление changelog.json

После создания Release обнови `sphere-config/changelog.json`:

```json
{
  "version": "1.9.2",
  "version_code": 18,
  "download_url": "https://github.com/RootOne1337/SphereAgent-APK/releases/download/v1.9.2/SphereAgent-v1.9.2.apk",
  "changelog": "v1.9.2: OTA обновления + fix ROOT detection",
  "release_date": "2026-01-03",
  "size_bytes": 11045103,
  "required": true
}
```

## Проверка

```bash
# Скачай APK
wget https://github.com/RootOne1337/SphereAgent-APK/releases/download/v1.9.2/SphereAgent-v1.9.2.apk

# Проверь размер
ls -lh SphereAgent-v1.9.2.apk
# Output: -rw-rw-r-- 1 rootone rootone 11M Jan  3 14:45 SphereAgent-v1.9.2.apk

# Проверь тип
file SphereAgent-v1.9.2.apk
# Output: Android package (APK), with gradle app-metadata.properties

# Установи на устройство
adb install -r SphereAgent-v1.9.2.apk
```

## Альтернатива: GitHub CLI

Если установлен `gh`:

```bash
# Установка GitHub CLI
curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | sudo dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null
sudo apt update
sudo apt install gh

# Авторизация
gh auth login

# Создание Release с APK
cd /home/rootone/SphereAgent-APK-Repo
gh release create v1.9.2 \
  --title "SphereAgent v1.9.2 - OTA Updates & ROOT Fix" \
  --notes-file RELEASE_NOTES_1.9.2.md \
  releases/SphereAgent-v1.9.2.apk
```

## Важно

- **НЕ используй** `raw.githubusercontent.com` для APK >10MB
- **ИСПОЛЬЗУЙ** GitHub Releases для binary файлов
- **ОБНОВИ** download_url в changelog.json после создания Release
- **ТЕСТИРУЙ** download link перед распространением

## Статус

- ✅ APK v1.9.2 (11MB) загружен в git репозиторий
- ✅ Коммит запушен на GitHub (c24ff97)
- ✅ Код обновлён (version_code: 18, version_name: 1.9.2)
- ✅ Документация обновлена (CHANGELOG.md, README.md)
- ❌ GitHub Release НЕ создан (требуется ручное создание)
- ❌ download_url в changelog.json указывает на несуществующий raw URL

## Следующие шаги

1. Создай GitHub Release (https://github.com/RootOne1337/SphereAgent-APK/releases/new)
2. Загрузи APK как asset
3. Обнови download_url в sphere-config/changelog.json
4. Запушь изменения в sphere-config
5. Протестируй OTA update на устройстве
