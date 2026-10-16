# Claude для Pixel Watch (MVP)

Wear OS приложение: по долгому нажатию боковой кнопки открывает новый чат с Claude, сразу слушает голос, стримит ответ на экран и зачитывает его. Если ответ ещё идёт или вы его не видели, повторный запуск открывает тот же чат, а не новый.

- Модель и effort по умолчанию: **Opus 5.5 · low**, меняются в ⚙ (модель: Opus 5.5 / Sonnet 5.5 / Haiku 5.5; effort: low…max).
- Ключ: Claude API key из platform.claude.com.
- Для Opus/Sonnet 5.5 включён серверный fallback (`fallbacks: "default"`): если фильтр безопасности отклонит запрос, API сам переотправит его на подходящую модель.

## Что нужно на Mac

1. Android Studio (с ним ставятся Android SDK и `adb`).
2. Файл `local.properties` в корне проекта (в git не попадает):
   ```
   sdk.dir=/Users/<you>/Library/Android/sdk
   claude.apiKey=sk-ant-...
   notion.token=ntn_...        # необязательно
   notion.prompt=...           # необязательно: инструкции к своим страницам Notion одной строкой
   notion.writablePages=...    # ID страниц Notion через запятую, куда Claude может писать (вместе с подстраницами)
   ```
   В сборку ключи не попадают: их передают на часы после установки (см. ниже).

## Сборка

```
./gradlew :app:assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk`. На часы ставьте release: debug-сборка заметно медленнее и тратит больше батареи.

Release подписывается ключом из `keystore.properties` в корне проекта (в git не попадает); если файла нет, локальная сборка подписывается debug-ключом.

## Установка на часы

1. На часах: Настройки → Система → О часах → 7 раз тапнуть «Номер сборки» → появятся «Параметры разработчика».
2. Параметры разработчика → включить «Отладка по ADB» и «Отладка по Wi-Fi» → «Подключить новое устройство» (покажет IP:порт и код).
3. На Mac (часы и Mac в одной Wi-Fi сети):
   ```
   adb pair <IP>:<порт_сопряжения>     # ввести код с часов
   adb connect <IP>:<порт_отладки>
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
4. Передать ключи из `local.properties` (один раз; повторить, если ключи сменились или данные приложения стёрты):
   ```
   ./setup-keys.sh
   ```
   Вызвать `KeySetupActivity` можно только из `adb shell`: она закрыта разрешением `WRITE_SECURE_SETTINGS`, которое обычным приложениям не выдаётся.

   Ключ Claude можно ввести и на самих часах: если ключа нет, при запуске откроется системный ввод, а сменить ключ можно в ⚙ → «API-ключ Claude».

## Назначить на кнопку

Настройки часов → Приложения → Приложения по умолчанию → Цифровой помощник → **Claude**.
Затем долгое нажатие боковой кнопки (или коронки) должно открывать Claude.

Если пункта нет или кнопка продолжает открывать Gemini — это тот самый риск из оценки. Запасной вариант без root:
```
adb shell settings put secure assistant dev.mushinlab.claudewatch/.assist.ClaudeVoiceInteractionService
adb shell settings put secure voice_interaction_service dev.mushinlab.claudewatch/.assist.ClaudeVoiceInteractionService
```
Если и это не сработает, приложение всё равно запускается из списка приложений, и его можно повесить на плитку.

## Устройство проекта

| Файл | Что делает |
|---|---|
| `MainActivity.kt` | Точка входа: решает, открыть текущий чат или начать новый и слушать голос |
| `KeySetupActivity.kt` | Приём ключей через `adb shell` (без UI) |
| `AppContainer.kt`, `ClaudeWatchApplication.kt` | Ручной DI: репозитории и общий scope приложения |
| `data/ChatRepository.kt` | Запрос к Claude, состояние чата, «Повторить», сохранение на диск |
| `data/ChatSession.kt`, `data/ChatStore.kt` | Модель чата и её хранение в `noBackupFilesDir` |
| `data/ClaudeRemoteDataSource.kt` | Anthropic Java SDK: стриминг с отменой, таймауты, веб-поиск, инструменты Notion |
| `data/NotionApi.kt` | Клиент Notion: запись только в разрешённые страницы |
| `data/SettingsRepository.kt`, `data/SecretCipher.kt` | Настройки; ключи шифруются ключом из Android Keystore |
| `service/ChatRequestService.kt` | Foreground service и Ongoing Activity только на время запроса |
| `ui/` | Compose for Wear OS: навигация со свайпом назад, экраны чата и настроек, озвучка |
| `assist/AssistServices.kt` | VoiceInteractionService, чтобы часы видели приложение как цифрового помощника |

## Известные ограничения MVP

- Сохраняется только текущий чат; просмотренный чат заменяется новым при следующем запуске.
- Пока идёт ответ, на циферблате виден значок Ongoing Activity (нужно разрешение на уведомления, его спросят один раз).
- Тестовый хук `--es prompt "..."` у `MainActivity` работает только в debug-сборке.
- Пока приложение назначено помощником, системное распознавание речи для *других* приложений может перестать работать (заглушка RecognitionService). Наш голосовой ввод идёт через распознаватель Google и не затронут.
