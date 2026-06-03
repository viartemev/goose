# Goose Android — Architecture

Чистый Kotlin-порт iOS-приложения. Rust core не используется — вся логика переносится в Kotlin. Целевое устройство: Pixel (Android 12+).

## Стек

| Слой | Технология |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Navigation | Navigation Compose (single-activity) |
| State | ViewModel + StateFlow + collectAsState |
| DI | Hilt |
| BLE | BluetoothGatt (raw) + callbackFlow |
| БД | Room (SQLite) |
| Health | Health Connect |
| Background | Foreground Service (overnight) |
| HTTP | Ktor / OkHttp (Coach/OpenAI) |
| Тесты | JUnit 5 + Robolectric + MockK |

Минимальный API: 31 (Android 12) — обязателен для Health Connect и новой BLE permissions модели.

---

## Слои и их аналоги в iOS

```
┌──────────────────────────────────────────────────────┐
│                  UI (Jetpack Compose)                 │
│  HomeScreen  HealthScreen  CoachScreen  MoreScreen   │
│  OnboardingScreen                                     │
├──────────────────────────────────────────────────────┤
│               ViewModels (StateFlow)                  │
│  GooseViewModel     HealthViewModel                   │
│  CoachViewModel     OvernightViewModel                │
├────────────────────┬─────────────────────────────────┤
│    BLE Layer       │       Domain / Use Cases         │
│  GooseBLEManager   │  StartOvernightUseCase           │
│  WhoopFrameParser  │  RunHistoricalSyncUseCase        │
│  CommandBuilder    │  RunMetricRollupUseCase           │
├────────────────────┴─────────────────────────────────┤
│               Algorithm Layer (pure Kotlin)           │
│  HrvCalculator  SleepScorer  RecoveryRollup           │
│  StrainCalculator  EnergyRollup  ActivityClassifier   │
│  CalibrationEngine                                    │
├──────────────────────────────────────────────────────┤
│               Repository Layer                        │
│  CaptureRepository   MetricsRepository               │
│  ActivityRepository  ExportRepository                 │
│  HealthConnectRepository                              │
├──────────────────────────────────────────────────────┤
│               Data Layer (Room)                       │
│  GooseDatabase                                        │
│  FrameDao  MetricDao  ActivityDao  VitalDao           │
├──────────────────────────────────────────────────────┤
│               Services                                │
│  OvernightForegroundService                           │
│  OvernightFrameSpool  OvernightSQLiteMirror           │
└──────────────────────────────────────────────────────┘
```

---

## 1. Точка входа и навигация

```
MainActivity
  └─ GooseApp()
       ├─ OnboardingScreen        (если !onboardingComplete)
       └─ AppShell()
            ├─ BottomNavigationBar (Home / Health / Coach / More)
            └─ NavHost
                 ├─ HomeScreen
                 ├─ HealthScreen
                 │    └─ HealthDetailScreen(route)   ← вложенный граф
                 ├─ CoachScreen
                 └─ MoreScreen
                      └─ MoreDetailScreen(route)
```

**Аналог:** `GooseSwiftApp` → `RootView` → `AppShellView` (TabView).

`AppRouter` (iOS) → `GooseNavController`: wrapper над `NavController`, хранит состояние вкладок и pending deep-link.

---

## 2. GooseViewModel — центральная точка состояния

Аналог `GooseAppModel` (@MainActor ObservableObject).

```kotlin
@HiltViewModel
class GooseViewModel @Inject constructor(
    private val bleManager: GooseBLEManager,
    private val startOvernight: StartOvernightUseCase,
    private val historicalSync: RunHistoricalSyncUseCase,
    private val captureRepo: CaptureRepository,
) : ViewModel() {

    // BLE state
    val connectionState: StateFlow<String> = bleManager.connectionState
    val liveHeartRate: StateFlow<Int?> = bleManager.liveHeartRate
    val isHistoricalSyncing: StateFlow<Boolean> = bleManager.isHistoricalSyncing

    // Overnight
    val overnightActive: StateFlow<Boolean> = ...
    val overnightFrameCount: StateFlow<Int> = ...

    fun connectToDevice(device: BluetoothDevice) = viewModelScope.launch {
        bleManager.connect(device)
    }

    fun startOvernightGuard() = viewModelScope.launch {
        startOvernight()
    }
}
```

Extension-файлы iOS (`+OvernightRun`, `+HealthCapture`, `+NotificationPipeline`) → отдельные `UseCase`-классы в `domain/usecases/`.

---

## 3. BLE — GooseBLEManager

Аналог `GooseBLEClient` + его 9 extension-файлов (~5000 строк).

Ключевая идея: `callbackFlow` превращает callback-based Android BLE API в корутины.

```kotlin
@Singleton
class GooseBLEManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val frameParser: WhoopFrameParser,
    private val commandBuilder: WhoopCommandBuilder,
) {
    val connectionState = MutableStateFlow("disconnected")
    val liveHeartRate   = MutableStateFlow<Int?>(null)
    val isHistoricalSyncing = MutableStateFlow(false)

    private val _frames = MutableSharedFlow<WhoopFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<WhoopFrame> = _frames

    fun scanFlow(): Flow<ScanResult> = callbackFlow {
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result)
            }
        }
        scanner.startScan(buildScanFilters(), buildScanSettings(), callback)
        awaitClose { scanner.stopScan(callback) }
    }

    suspend fun connect(device: BluetoothDevice) {
        // BluetoothGatt + gattCallback → updateConnectionState, emit frames
    }

    suspend fun sendCommand(command: WhoopCommand) {
        val bytes = commandBuilder.build(command)
        commandCharacteristic?.let { gatt?.writeCharacteristic(it, bytes, WRITE_TYPE_DEFAULT) }
    }
}
```

**Разбивка по аналогам iOS:**

| iOS | Android |
|---|---|
| `GooseBLEClient+CentralDelegate` | scan/connect логика в `GooseBLEManager` |
| `GooseBLEClient+PeripheralDelegate` | `BluetoothGattCallback` |
| `GooseBLEClient+Parsing` | `WhoopFrameParser` |
| `GooseBLEClient+Commands` | `WhoopCommandBuilder` |
| `GooseBLEClient+HistoricalCommands/Handlers` | `HistoricalSyncManager` |
| `GooseBLEClient+VitalsAndLogging` | внутри `GooseBLEManager` + `GooseLogger` |
| `GooseBLEClient+UserActions` | методы `GooseBLEManager` + use cases |
| `GooseBLEClient+DebugAndSync` | `DebugRepository` |
| `WhoopDataSignalPipeline` | `Channel(BUFFERED)` + `consumeAsFlow()` |

---

## 4. WhoopFrameParser

Порт `protocol.rs` + `GooseBLEClient+Parsing.swift`.

```kotlin
class WhoopFrameParser {
    private val reassemblyBuffer = ByteArray(4096)
    private var bufferLen = 0

    // Те же константы что в protocol.rs
    companion object {
        const val FRAME_START: Byte = 0xAA.toByte()
        const val PACKET_TYPE_REALTIME_DATA: Byte = 40
        const val PACKET_TYPE_HISTORICAL_DATA: Byte = 47
        const val PACKET_TYPE_COMMAND_RESPONSE: Byte = 36
        // ...
    }

    fun push(bytes: ByteArray): List<WhoopFrame> {
        // Reassembly логика, CRC проверка
    }
}
```

---

## 5. Algorithm Layer — чистый Kotlin

Нет Android-зависимостей → тестируются как обычные unit tests.

```kotlin
// ← порт metrics.rs
object HrvCalculator {
    data class Input(val rrIntervalsMs: List<Double>, val startTime: Instant, val endTime: Instant)
    data class Output(val rmssdMs: Double, val sdnnMs: Double, val pnn50: Double, val meanNnMs: Double)

    fun compute(input: Input): Output {
        val valid = input.rrIntervalsMs.filter { it in 200.0..2000.0 }
        val diffs = valid.zipWithNext { a, b -> (b - a).pow(2) }
        val rmssd = sqrt(diffs.average())
        val mean = valid.average()
        val sdnn = sqrt(valid.map { (it - mean).pow(2) }.average())
        val pnn50 = valid.zipWithNext().count { (a, b) -> abs(b - a) > 50 }.toDouble() / valid.size
        return Output(rmssd, sdnn, pnn50, mean)
    }
}

// ← порт sleep_validation.rs
class SleepScorer { ... }

// ← порт recovery_rollup.rs
class RecoveryRollup { ... }

// ← порт energy_rollup.rs
class EnergyRollup { ... }

// ← порт calibration.rs
class CalibrationEngine { ... }

// ← порт activity_candidates.rs
class ActivityClassifier { ... }
```

---

## 6. Room Database

Порт схемы `store.rs` (v14). Начинаем с v1, мигрируем по мере добавления таблиц.

```kotlin
@Database(
    entities = [FrameEntity::class, MetricEntity::class,
                ActivitySessionEntity::class, VitalEntity::class,
                AlgorithmRunEntity::class],
    version = 1,
)
abstract class GooseDatabase : RoomDatabase() {
    abstract fun frameDao(): FrameDao
    abstract fun metricDao(): MetricDao
    abstract fun activityDao(): ActivityDao
    abstract fun vitalDao(): VitalDao
    abstract fun algorithmRunDao(): AlgorithmRunDao
}

@Entity(tableName = "frames")
data class FrameEntity(
    @PrimaryKey val id: String,
    val capturedAt: Long,           // epoch ms
    val packetType: Int,
    val payloadHex: String,
    val sessionId: String,
    val checksumOk: Boolean,
)
```

---

## 7. Overnight Foreground Service

Главное архитектурное отличие от iOS. На iOS — это просто Task внутри GooseAppModel. На Android — обязателен `Service` с `startForeground()`.

```kotlin
@AndroidEntryPoint
class OvernightForegroundService : Service() {

    @Inject lateinit var bleManager: GooseBLEManager
    @Inject lateinit var spool: OvernightFrameSpool
    @Inject lateinit var mirror: OvernightSQLiteMirror

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Goose overnight recording…"))
        scope.launch { collectFrames() }
        return START_STICKY
    }

    private suspend fun collectFrames() {
        bleManager.frames.collect { frame ->
            spool.append(frame)
            mirror.insert(frame)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
```

`START_STICKY` — сервис перезапускается если OS его убивает. Нужно также отключить battery optimization через `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` в onboarding.

---

## 8. Health Connect

Аналог `HealthKitSleepImporter.swift` + workout write.

```kotlin
@Singleton
class HealthConnectRepository @Inject constructor(
    private val client: HealthConnectClient,
) {
    val readPermissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )
    val writePermissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
    )

    suspend fun readSleepSessions(start: Instant, end: Instant): List<SleepSessionRecord> =
        client.readRecords(ReadRecordsRequest(SleepSessionRecord::class,
            TimeRangeFilter.between(start, end))).records

    suspend fun writeWorkout(session: GooseActivitySession) {
        client.insertRecords(listOf(session.toExerciseSessionRecord()))
    }
}
```

---

## 9. Coach — OpenAI/Codex клиент

Порт `OpenAICoachChat.swift` + `OpenAICoachResponsesClient.swift`. Логика авторизации через device code flow (`CodexEmbeddedAuth`) переносится 1:1 — это чистый HTTP.

```kotlin
@Singleton
class OpenAICoachClient @Inject constructor(private val httpClient: HttpClient) {
    fun streamResponse(messages: List<CoachMessage>): Flow<String> = flow {
        // SSE stream от OpenAI Responses API
    }
}
```

---

## Структура модулей (папки)

```
GooseAndroid/
  app/
    src/main/kotlin/com/goose/android/
      ui/
        screens/         — HomeScreen, HealthScreen, CoachScreen, MoreScreen, OnboardingScreen
        components/      — переиспользуемые Compose-компоненты
        theme/           — GooseTheme, цвета, типографика
      viewmodel/         — GooseViewModel, HealthViewModel, CoachViewModel
      ble/               — GooseBLEManager, WhoopFrameParser, WhoopCommandBuilder, HistoricalSyncManager
      algorithms/        — HrvCalculator, SleepScorer, RecoveryRollup, StrainCalculator, EnergyRollup, ActivityClassifier, CalibrationEngine
      domain/
        usecases/        — StartOvernightUseCase, RunHistoricalSyncUseCase, RunMetricRollupUseCase
        models/          — доменные модели (WhoopFrame, GooseMetric, ActivitySession)
      data/
        db/              — GooseDatabase, entities, DAOs
        repo/            — CaptureRepository, MetricsRepository, ActivityRepository, ExportRepository
      health/            — HealthConnectRepository
      coach/             — OpenAICoachClient, CoachViewModel
      service/           — OvernightForegroundService, OvernightFrameSpool, OvernightSQLiteMirror
      di/                — Hilt modules (DatabaseModule, BLEModule, RepoModule)
      navigation/        — GooseNavController, NavGraph, Routes
```

---

## Ключевые отличия от iOS

| Тема | iOS | Android |
|---|---|---|
| Background overnight | Task в GooseAppModel | Foreground Service + START_STICKY |
| BLE callbacks | CoreBluetooth delegate | callbackFlow обёртка |
| State management | @Published + @MainActor | StateFlow + viewModelScope |
| Storage | Rust/rusqlite | Room |
| Health data | HealthKit | Health Connect |
| DI | Ручной | Hilt |
| Battery | Нет ограничений foreground | REQUEST_IGNORE_BATTERY_OPTIMIZATIONS в onboarding |
| Notifications | UserNotifications | NotificationCompat + Channel |
