# Goose Android

Android-порт приложения Goose для WHOOP 5.0. Чистый Kotlin, без Rust.

**Целевое устройство:** Pixel (Android 12+, API 31+)

## Документация

- [`docs/architecture.md`](docs/architecture.md) — полная архитектура, слои, аналоги iOS-компонентов
- [`docs/tasks.md`](docs/tasks.md) — 64 задачи разбитые по фазам для trunk-based разработки

## Стек

| | |
|---|---|
| UI | Jetpack Compose + Material 3 |
| State | ViewModel + StateFlow |
| DI | Hilt |
| BLE | BluetoothGatt (raw) + callbackFlow |
| БД | Room |
| Health | Health Connect |
| Background | Foreground Service |
| HTTP | Ktor / OkHttp |

## Связь с iOS-проектом

Весь алгоритмический слой (`Rust/core/src/metrics.rs`, `recovery_rollup.rs`, `sleep_validation.rs`, `energy_rollup.rs`, `calibration.rs`, `activity_candidates.rs`) переносится в `algorithms/` как чистый Kotlin без Android-зависимостей.

Протокол (`Rust/core/src/protocol.rs`) переносится в `ble/WhoopFrameParser.kt`.

iOS fixtures (`Rust/core/fixtures/`) используются как reference для unit-тестов алгоритмов.

## Минимальный рабочий slice

```
T-001 → T-004 → T-009 → T-010 → T-011 → T-014 → T-030
```

После этого: приложение запускается, коннектится к WHOOP, фреймы текут, connection state виден на Home.
