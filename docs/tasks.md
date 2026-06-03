# Goose Android — Задачи (trunk-based)

Каждая задача — один PR, который не ломает main. Работа идёт по фазам, но внутри фазы задачи можно параллелить.

Правило для trunk: каждый PR компилируется, проходит тесты, не регрессирует то что уже работает. Незавершённые экраны закрываются `TODO` заглушкой, а не feature-флагом.

---

## Фаза 1 — Фундамент проекта

### T-001 · Создать Android-проект
- Новый проект через Android Studio: `com.goose.android`, min SDK 31, Kotlin DSL
- Подключить: Hilt, Compose, Navigation Compose, Room, Coroutines
- `build.gradle.kts` с version catalog (`libs.versions.toml`)
- Проверка: проект собирается, запускается пустой экран

### T-002 · Настроить CI (GitHub Actions)
- Workflow: `./gradlew assembleDebug test` на каждый PR
- Кэш Gradle
- Проверка: CI зелёный на пустом проекте

### T-003 · Подключить ktlint + detekt
- `ktlint` через Gradle plugin, конфиг `.editorconfig`
- `detekt` с базовым ruleset
- Добавить в CI
- Проверка: `./gradlew ktlintCheck detekt` проходит

### T-004 · Skeleton Navigation + AppShell
- `MainActivity` → `GooseApp()` → `AppShell()`
- `BottomNavigationBar` с 4 вкладками: Home, Health, Coach, More
- Каждый экран — пустой `Text("Home TODO")` и т.д.
- `GooseNavController` wrapper
- Проверка: переключение вкладок работает

### T-005 · GooseTheme
- `GooseTheme.kt`: тёмный фон `(0.06, 0.09, 0.11)`, цвета метрик, типографика
- `MaterialTheme` wrapper
- Проверка: тема применяется, темный фон виден

---

## Фаза 2 — BLE фундамент

### T-006 · WhoopFrameParser — константы и структуры
- Перенести константы из `protocol.rs`: `FRAME_START`, все `PACKET_TYPE_*`
- `WhoopFrame` sealed class: `RealtimeData`, `HistoricalData`, `CommandResponse`, `Event`, `Metadata`, `Unknown`
- Unit tests для всех типов фреймов
- Проверка: тесты проходят, никакого Android кода нет

### T-007 · WhoopFrameParser — reassembly + CRC
- Порт логики из `GooseBLEClient+Parsing.swift` (~961 строк) и `protocol.rs`
- Reassembly буфер: накапливает байты между уведомлениями, выдаёт полные фреймы
- CRC32 проверка (порт из Rust `crc32fast`)
- Unit tests с реальными hex-фреймами из `Rust/core/fixtures/`
- Проверка: все fixture-тесты проходят

### T-008 · WhoopCommandBuilder
- Порт `GooseBLEClient+Commands.swift` (~948 строк) и `commands.rs`
- `WhoopCommand` sealed class: `GetHello`, `GetDataRange`, `SetAlarm`, `StartPhysiology`, `StopPhysiology`, `StartHighFrequencySync`, ...
- Каждый command → `ByteArray` с правильным framing и CRC
- Unit tests
- Проверка: команды собираются корректно

### T-009 · GooseBLEManager — scan и permission
- BLE permissions: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+)
- `scanFlow(): Flow<BluetoothDevice>` с фильтром по WHOOP service UUID
- `stopScan()`
- Hilt singleton
- Проверка: на Pixel находит WHOOP устройство

### T-010 · GooseBLEManager — connect и GATT
- `connect(device)` → `BluetoothGattCallback` → обновление `connectionState`
- Service discovery → поиск WHOOP characteristics
- Enable notifications на нужных characteristics
- `disconnect()` + reconnect state machine (порт `GooseBLEClient+CentralDelegate.swift`)
- Проверка: коннект к WHOOP, connectionState = "ready"

### T-011 · GooseBLEManager — notification pipeline
- `onCharacteristicChanged` → `WhoopFrameParser.push()` → emit в `SharedFlow<WhoopFrame>`
- Back-pressure: `Channel(BUFFERED)` с drop-oldest стратегией (аналог `WhoopDataSignalPipeline`)
- Счётчики: queue depth, dropped frames
- Проверка: фреймы текут из WHOOP в flow

### T-012 · GooseBLEManager — vitals из realtime фреймов
- Парсить `PACKET_TYPE_REALTIME_DATA` → `liveHeartRate`, `liveHRV`, `restingHeartRate`
- Порт `GooseBLEClient+VitalsAndLogging.swift`
- Проверка: HR обновляется на экране в реальном времени

### T-013 · GooseBLEManager — historical sync
- `GET_DATA_RANGE` команда → получить диапазон доступных данных
- Порт `GooseBLEClient+HistoricalCommands.swift` + `+HistoricalHandlers.swift`
- `isHistoricalSyncing`, `historicalSyncStatus`, `historicalPacketCount`
- Проверка: исторические данные приходят и парсируются

---

## Фаза 3 — База данных

### T-014 · Room schema v1 — frames
- `FrameEntity`: id, capturedAt, packetType, payloadHex, sessionId, checksumOk
- `FrameDao`: insert, insertBatch, queryBySession, queryByType, deleteOlderThan
- `GooseDatabase` с версией 1
- Unit tests с in-memory DB
- Проверка: insert/query работает

### T-015 · Room schema — metrics
- `MetricEntity`: id, date, family, algorithmId, value, sourceKind, provenanceScope, components (JSON)
- `MetricDao`: upsert, queryByDate, queryByFamily, queryRange
- Допустимые `sourceKind`: `device_counter`, `device_sensor`, `local_estimate`, `unavailable`
- Проверка: метрики сохраняются и читаются

### T-016 · Room schema — activity_sessions и vitals
- `ActivitySessionEntity`: id, type, startTime, endTime, status, strain, heartRateAvg
- `VitalEntity`: id, timestamp, type, value, source
- DAOs с нужными запросами
- Migration scaffold для будущих версий
- Проверка: entities работают

### T-017 · CaptureRepository
- `importFrames(sessionId, frames)` → batch insert в Room
- `getFramesBySession(sessionId)` 
- Аналог `capture_import.rs` логики
- Проверка: фреймы из BLE попадают в БД

---

## Фаза 4 — Алгоритмы

### T-018 · HrvCalculator
- Порт `metrics.rs` HRV секции
- `compute(rrIntervalsMs)` → RMSSD, SDNN, pNN50, meanNN
- Фильтрация невалидных интервалов (< 200ms или > 2000ms)
- Unit tests с известными значениями
- Проверка: результаты совпадают с Rust reference

### T-019 · SleepScorer
- Порт `sleep_validation.rs` и `metrics.rs` SleepInput/SleepOutput
- Стадии сна: Awake, Light, REM, Deep
- Sleep score расчёт
- Unit tests
- Проверка: score совпадает с Rust reference для test fixtures

### T-020 · RecoveryRollup
- Порт `recovery_rollup.rs`
- Входные данные: HRV RMSSD, resting HR, SpO2, respiratory rate
- Recovery score 0–100
- Unit tests
- Проверка: score совпадает с reference

### T-021 · StrainCalculator
- Порт strain части из `metrics.rs`
- Входные данные: HR time series, workout duration
- Strain score 0–21
- Unit tests
- Проверка: score совпадает с reference

### T-022 · EnergyRollup
- Порт `energy_rollup.rs`
- Hourly и daily rollup
- Unit tests
- Проверка: rollup совпадает с reference

### T-023 · ActivityClassifier
- Порт `activity_candidates.rs` базовая классификация
- Input: motion samples, HR, gravity
- Output: activity type + confidence
- Unit tests с fixture данными из `Rust/core/fixtures/`
- Проверка: классификация совпадает

### T-024 · CalibrationEngine
- Порт `calibration.rs`
- Linear calibration применение к метрикам
- Проверка: calibrated values совпадают с Rust output

### T-025 · MetricsRepository — rollup pipeline
- `runDailyRollup(date)`: читает фреймы из БД → запускает алгоритмы → сохраняет метрики
- Порт `GooseAppModel+HealthCapture.swift` оркестрация
- Проверка: после исторического синка метрики появляются в БД

---

## Фаза 5 — Onboarding

### T-026 · Onboarding — модели и навигация
- `OnboardingStep` enum: HealthConnect, Location, Bluetooth, Notifications, Connect, Profile
- `OnboardingViewModel` с progress
- Navigation через шаги
- Проверка: можно пройти все шаги

### T-027 · Onboarding — разрешения
- Health Connect permissions request
- Location permission (`ACCESS_FINE_LOCATION` для BLE scan на старых API)
- Bluetooth permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`)
- Notifications permission (API 33+)
- Battery optimization: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- Проверка: все разрешения запрашиваются и сохраняются

### T-028 · Onboarding — Connect WHOOP
- Экран scan: список найденных устройств
- Тап → connect → ждём `connectionState == "ready"`
- Сохранить device ID в DataStore для автореконнекта
- Проверка: WHOOP коннектится через onboarding

### T-029 · Onboarding — профиль пользователя
- Поля: имя, дата рождения, пол, рост, вес, единицы измерения
- Сохранение в DataStore (аналог `OnboardingPersistence.swift`)
- Валидация
- Проверка: профиль сохраняется, при следующем запуске загружается

---

## Фаза 6 — Home Tab

### T-030 · HomeScreen — connection widget
- Состояние подключения: disconnected / scanning / connected / ready
- Имя устройства, battery level
- Кнопка "Connect" / "Disconnect"
- Проверка: отражает реальное состояние BLE

### T-031 · HomeScreen — daily scores
- Три карточки: Recovery, Strain, Sleep
- Значение + цветовая индикация (зелёный/жёлтый/красный)
- Пустое состояние если данных нет
- Аналог `HomeScoreViews.swift`
- Проверка: карточки показывают данные после синка

### T-032 · HomeScreen — activity timeline
- Список активностей за день
- Тип, время, strain, HR
- Пустое состояние
- Аналог `HomeTimelineViews.swift`
- Проверка: активности появляются

### T-033 · HomeScreen — Health Monitor
- Live HR, live HRV, SpO2
- Обновляется в реальном времени из `GooseViewModel.liveHeartRate`
- Аналог `HomeHealthMonitorViews.swift`
- Проверка: значения обновляются при подключённом WHOOP

---

## Фаза 7 — Health Tab

### T-034 · HealthScreen — метрика catalog skeleton
- Список семейств метрик: Recovery, Sleep, Strain, Stress, Cardio, Energy
- Каждый item: название, последнее значение, timestamp
- Навигация в detail
- Аналог `HealthView.swift` + `HealthDataStore`
- Проверка: список отображается

### T-035 · HealthScreen — Sleep detail
- Sleep score, duration, стадии (bar chart)
- Sleep window (lights out → wake time)
- Пустое состояние
- Аналог `HealthSleepOverviewViews.swift` + `SleepDetailViews.swift`
- Проверка: данные сна отображаются корректно

### T-036 · HealthScreen — Recovery detail
- Recovery score 0–100
- Компоненты: HRV, resting HR, SpO2, respiratory rate
- Источник данных (device sensor / local estimate / unavailable)
- Аналог `HealthRecoveryStressViews.swift`
- Проверка: компоненты с источниками

### T-037 · HealthScreen — Strain и Stress detail
- Strain 0–21, карточки активностей
- Stress timeline (hourly)
- Аналог `HealthMetricFamilyStrainViews.swift` + `HealthStressCharts.swift`
- Проверка: графики отображаются

### T-038 · HealthScreen — Cardio и Energy detail
- Cardio Load
- Energy Bank hourly rollup
- Аналог `HealthCardioViews.swift`
- Проверка: данные отображаются

### T-039 · HealthScreen — Trends
- График за 7/30/90 дней для любой метрики
- Аналог `HealthSleepTrendViews.swift` + trend infrastructure
- Проверка: тренд строится из исторических данных

### T-040 · HealthScreen — Packet Inputs (debug)
- Показывает источники входных данных для каждой метрики
- Только в Debug build или под "More → Debug"
- Аналог `HealthDataStore+PacketInputs.swift`
- Проверка: источники видны

---

## Фаза 8 — Coach Tab

### T-041 · CoachViewModel — базовая структура
- `messages: StateFlow<List<CoachMessage>>`
- `streamState: StateFlow<CoachStreamState>` (idle / streaming / error)
- Персистенция диалога в DataStore
- Аналог `OpenAICoachChatModel`
- Проверка: ViewModel инициализируется, сообщения загружаются

### T-042 · OpenAICoachClient — авторизация
- Device code flow (порт `CodexEmbeddedAuth.swift`)
- Хранение токена в EncryptedSharedPreferences
- `refreshIfNeeded()` логика
- Проверка: sign-in работает, токен сохраняется

### T-043 · OpenAICoachClient — streaming
- POST к OpenAI Responses API
- SSE stream → `Flow<String>` (chunk by chunk)
- Порт `OpenAIResponsesClient.swift`
- Проверка: ответ стримится

### T-044 · CoachScreen — UI
- Список сообщений (user / assistant bubbles)
- Input field + send button
- Typing indicator во время стриминга
- Scroll to bottom
- Sign-in экран если не авторизован
- Аналог `CoachChatScreen.swift`
- Проверка: можно отправить сообщение и получить ответ

### T-045 · CoachLocalContext — метрики для coach
- Формировать summary текущих метрик для system prompt
- Аналог `CoachLocalToolContext.swift`
- Проверка: coach знает о recovery score, sleep, strain

---

## Фаза 9 — More Tab

### T-046 · MoreScreen — skeleton и навигация
- Список секций: Device, Capture, Sync, Algorithms, Storage, Privacy, Support
- Аналог `MoreView.swift` + `MoreRouteModels.swift`
- Проверка: навигация по секциям работает

### T-047 · MoreScreen — Device info
- Firmware version, hardware revision, battery
- Connection log (последние события)
- Аналог `DeviceView.swift`
- Проверка: данные с устройства отображаются

### T-048 · MoreScreen — Capture
- Кнопка "Start Health Capture" / "Stop"
- Счётчик фреймов по family
- Аналог `MoreCaptureViews.swift`
- Проверка: capture запускается и считает фреймы

### T-049 · MoreScreen — Raw Export
- Экспорт фреймов в ZIP (JSON)
- Share intent
- Аналог `MoreRawExportViews.swift` + `GooseLocalDataExporter.swift`
- Проверка: ZIP скачивается и валиден

### T-050 · MoreScreen — Storage и Privacy
- Размер БД, количество фреймов
- Кнопка "Delete all data"
- Аналог `MoreDataStore.swift` + privacy секция
- Проверка: удаление работает, размер обновляется

### T-051 · MoreScreen — Debug (только Debug build)
- Packet monitor: поток входящих фреймов в реальном времени
- Packet UI state (аналог `PacketUIStateAggregator`)
- BLE log
- Аналог `MoreDebugViews.swift`
- Проверка: фреймы видны в дебаг-режиме

---

## Фаза 10 — Overnight Guard

### T-052 · OvernightFrameSpool
- Запись сырых фреймов в файл (бинарный spool)
- `start(sessionId)` → `append(frame)` → `finish()`
- Thread-safe через `Mutex`
- Аналог `OvernightRawNotificationSpool.swift`
- Проверка: файл записывается корректно

### T-053 · OvernightSQLiteMirror
- Параллельная запись фреймов в отдельную SQLite БД
- Нужно для recovery после crash без финального синка
- Аналог `OvernightSQLiteMirrorQueue.swift`
- Проверка: фреймы есть в SQLite после записи

### T-054 · OvernightForegroundService — основа
- `Service` + `startForeground()` с persistent уведомлением
- `START_STICKY`
- Bind к `GooseBLEManager` через Hilt
- `startOvernightGuard()` / `stopOvernightGuard()` intent actions
- `OvernightViewModel` управляет сервисом
- Аналог `GooseAppModel+OvernightRun.swift`
- Проверка: сервис запускается, уведомление появляется

### T-055 · OvernightForegroundService — сбор данных
- `bleManager.frames.collect` → spool + mirror
- Счётчики: frame count, session duration, battery
- Watchdog: проверка что BLE живой каждые N минут
- Аналог overnight collection логики
- Проверка: фреймы пишутся всю ночь без потерь

### T-056 · Overnight — финальный синк и экспорт
- После пробуждения: импорт spool → CaptureRepository → runDailyRollup
- Экспорт ZIP (spool + manifest)
- Аналог `GooseAppModel+OvernightRecovery.swift`
- Проверка: после ночи метрики появляются в Health Tab

---

## Фаза 11 — Health Connect интеграция

### T-057 · HealthConnectRepository — сон импорт
- Читать `SleepSessionRecord` из Health Connect
- Обогащать данные сна из WHOOP (стадии)
- Аналог `HealthKitSleepImporter.swift`
- Проверка: сессии сна из HC видны в Sleep detail

### T-058 · HealthConnectRepository — запись тренировок
- `GooseActivitySession` → `ExerciseSessionRecord`
- Запись после подтверждения активности
- Аналог `GooseAppModel+ActivityRecording.swift`
- Проверка: тренировка появляется в Google Fit / HC

### T-059 · HealthConnectRepository — вес профиля
- Читать `WeightRecord` для автозаполнения профиля
- Аналог `HealthKitProfileImporter.swift`
- Проверка: вес подставляется в onboarding Profile step

---

## Фаза 12 — Шлифовка

### T-060 · Уведомления — каналы и типы
- `NotificationChannel` для: Overnight Guard, Sync complete, Low battery warning
- Аналог iOS UserNotifications setup
- Проверка: уведомления приходят корректно

### T-061 · Автореконнект к WHOOP
- При старте приложения: искать сохранённый device ID, коннектиться автоматически
- Аналог `GooseBLEClient+UserActions.swift` remembered device логика
- Проверка: при открытии приложения WHOOP коннектится автоматически

### T-062 · Работа в фоне без overnight
- `WorkManager` для периодической синхронизации исторических данных (раз в час)
- Не мешает Foreground Service
- Проверка: исторический синк происходит в фоне

### T-063 · Privacy lint
- Порт `privacy_lint.rs` логики: проверка что raw payload не попадает в health views
- Статический анализ или runtime assert в Debug
- Проверка: никаких raw bytes в Home/Health экранах

### T-064 · Performance baseline
- Замерить время запуска, время historical sync, время rollup
- Аналог `perf_budget.rs`
- Задокументировать baseline в `docs/perf-baseline.md`
- Проверка: rollup < 2с на 30-дневном датасете

---

## Порядок фаз для trunk

```
Фаза 1 (T-001..T-005)      ← всё параллельно после T-001
Фаза 2 (T-006..T-013)      ← T-006→T-007→T-008 последовательно, T-009..T-013 после T-009
Фаза 3 (T-014..T-017)      ← параллельно с Фазой 2 частично
Фаза 4 (T-018..T-025)      ← параллельно, каждый алгоритм независим
Фаза 5 (T-026..T-029)      ← последовательно (навигация → разрешения → connect → профиль)
Фаза 6 (T-030..T-033)      ← после Фаз 2+3+4
Фаза 7 (T-034..T-040)      ← параллельно после T-034 skeleton
Фаза 8 (T-041..T-045)      ← T-041→T-042→T-043→T-044, T-045 независимо
Фаза 9 (T-046..T-051)      ← T-046 первым, остальные параллельно
Фаза 10 (T-052..T-056)     ← строго последовательно
Фаза 11 (T-057..T-059)     ← параллельно
Фаза 12 (T-060..T-064)     ← параллельно
```

**Минимальный working slice** (можно продемонстрировать):
`T-001 → T-004 → T-009 → T-010 → T-011 → T-014 → T-030`

После этого приложение коннектится к WHOOP, фреймы текут, BLE state виден на Home.
