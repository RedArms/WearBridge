# BLE & ANCS Protocol Details

## ANCS Characteristics

| Characteristic | UUID | Role |
|---|---|---|
| Notification Source | `9FBF120D-6301-42D9-8C58-25E699A21DBD` | Subscribe — 8-byte event notifications |
| Control Point | `69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9` | Write — request attributes / perform actions |
| Data Source | `22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB` | Subscribe — fragmented attribute responses |

**Subscribe to Data Source BEFORE Notification Source** (Apple requirement).

## BLE Connection Flow

1. Watch scans → finds iPhone (or uses bonded device)
2. `connectGatt()` → `onConnectionStateChange(CONNECTED)`
3. `requestMtu(512)` → `onMtuChanged` → `discoverServices()`
4. ANCS not visible (not bonded) → `createBond()` → iOS pairing dialog
5. `BOND_BONDED` → `discoverServices()` again → ANCS visible
6. Subscribe DS CCCD → Subscribe NS CCCD → session active (guard against double subscription)
7. NS events → fetch attributes via CP → parse DS → post Android notification

## Reconnection

- `autoConnect=true` in `connectGatt()` for bonded devices (chipset whitelist, battery efficient)
- Exponential backoff: 3s → 6s → 12s → 30s cap
- `START_STICKY` service + `BootReceiver` for persistence
- `getBondedIPhone()` matches device names containing "iPhone", "AncsBrid", "AncsBridge", "WearBrid", "WearBridge"
- GAP Device Name characteristic (0x2A00) read after ANCS connects for real iPhone name

## Silent/Pre-existing Notification Handling

- Pre-existing notifications (`EVENT_FLAG_PRE_EXISTING`) are skipped at NS event level — no GATT writes, no haptics
- 60-second reconnection grace period skips all non-call NS events to avoid vibration storms
- **Never use `setNotificationSilent()`** — it kills heads-up overlays on Wear OS

## Notification Channels

Channels are **versioned** (suffix `_v7` etc.) because Android permanently caches channel settings. Bump version in `AncsApplication.kt` to change settings.

Current channels: `service` (LOW), `incoming_call`, `messages`, `email`, `social`, `schedule`, `other` — all at `IMPORTANCE_HIGH` with system-default sound. Vibration handled via direct Vibrator API only.

## Telecom Framework (Incoming Calls)

1. `AncsConnectionService` registers a `PhoneAccount` with `CAPABILITY_SELF_MANAGED`
2. On cat=1 NS event, `TelecomManager.addNewIncomingCall()` is called immediately
3. System creates `AncsCallConnection` via `onCreateIncomingConnection()`
4. Caller name updated when attributes arrive (~1s later)
5. Answer/Decline sends ANCS action back to iPhone
6. Fallback: CallStyle notification + direct `startActivity()` if Telecom rejects

## Call State Dismissal (Three Layers)

When a call is answered on the iPhone, the watch overlay transitions from "Incoming Call" to "Active on iPhone" (auto-dismisses in 5s). Three detection layers:

1. **Companion BLE** (opt-in): `CXCallObserver` → call state characteristic `A0E3D5B4-...` → watch subscribes for notifications. Toggle: Settings → Live Call Status.
2. **ANCS MODIFIED**: NS event with EventID=1 for active call UID triggers overlay transition.
3. **Safety timeout**: 45-second auto-dismiss if no events arrive.

## Companion Service Characteristics

| Characteristic | UUID | Role |
|---|---|---|
| Connection Status | `A0E3D5B2-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Read/Notify — watch reads to signal connection |
| Quick Reply | `A0E3D5B3-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Write — watch sends `"callerName\|replyText"` |
| Call State | `A0E3D5B4-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Read/Notify — iPhone pushes call state (0x00=idle, 0x01=ringing, 0x02=active, 0x03=ended) |
| Health Data | `A0E3D5B5-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Write (encryption required) — watch sends health record batches |

## Health Data Bridge

Opt-in on both sides: **Health Sync** on the watch, **Settings → Apple Health** in the iOS app. Optionally, the phone relay (`wearos-app/relay`) on the Android phone the watch is paired with adds Samsung Health data.

1. Watch: `HealthMonitoring` registers `HealthPassiveListenerService` with Health Services (heart rate, steps, distance, floors, "asleep" activity state). Registration is redone on every `AncsService` start because Health Services forgets it on reboot.
2. Watch: records go to `HealthRecordStore`, a fixed-record file queue (`health_queue.bin`, capped at 50k records).
3. Watch: `HealthSyncManager` flushes 10 s after connecting, once a full write's worth of new data is queued, and every 15 min, but never while a call rings or within 3 s of ANCS activity (every GATT write can buzz the watch, gotcha 10). One write-with-response at a time, sized to the MTU (max 30 records); confirmed records leave the queue in chunks of up to 20 writes.
4. iPhone: `PeripheralManager` decodes the batch, stages it on disk (`HealthKitWriter`, file protection *until first user authentication*), then responds `.success`. It responds `.requestNotSupported` when Apple Health sync is off, so the watch keeps its queue.
5. iPhone: staged records are saved to HealthKit when it is writable (HealthKit is locked with the phone): right away, on unlock, on app foreground, or with the next batch. Samples of types the user denied in Health are dropped; a type still undetermined holds the queue until the app is opened, which shows the permission sheet again.

Wire format (little-endian), mirrored in `HealthProtocol.kt` / `HealthProtocol.swift`, with a shared golden test vector:

```
Batch  := version:u8 (=1) | count:u8 (1..255) | Record × count
Record := type:u8 | start:u32 (Unix s) | end:u32 (Unix s) | value:f64     (17 bytes)
type   := 1 heart rate (bpm) | 2 steps | 3 distance (m) | 4 floors
        | 5 sleep (value = stage: 0 unspecified, 1 awake, 2 light, 3 deep, 4 REM)
        | 6 in bed (whole session, value = sleep score 0..100, 0 = none)
        | 7 SpO2 (%) | 8 blood pressure (value = systolic * 1000 + diastolic, mmHg)
```

HealthKit mapping: 5 → sleepAnalysis asleepUnspecified/awake/asleepCore/asleepDeep/asleepREM, 6 → inBed (score in `WearBridgeSleepScore` metadata), 7 → oxygenSaturation (fraction), 8 → bloodPressure correlation (systolic + diastolic). HealthKit can't take skin temperature (`appleSleepingWristTemperature` is read-only) or ECG from third-party apps.

Every HealthKit sample gets `HKMetadataKeySyncIdentifier = wearbridge.<type>.<start>.<end>` (version 1), so batches resent after a lost acknowledgement are not duplicated.

If the watch only offers `*_DAILY` totals passively, `DailyTotals` turns them into increments. Sleep periods shorter than 10 min or longer than 24 h are dropped (`SleepSessions`).

### Phone relay (Samsung Health)

Health Services only exposes an "asleep" state. Samsung Health on the paired Android phone has the full picture, readable through the Samsung Health Data SDK in Samsung Health's developer mode (personal use, read-only, no partner registration).

1. `:relay` (phone, same applicationId `com.wearos.ancsbridge` and signing key as the watch app, which the Data Layer requires) reads sleep (sessions, stages, score), SpO2, blood pressure (+ pulse) and heart rate. `RelayWorker` runs hourly via WorkManager, re-reading 36 h before the last success (30 days on the first run); `SentLog` skips records sent in the last 24 h.
2. Batches (same wire format, ≤255 records) go to the watch with `MessageClient` on path `/wearbridge/health/batch`.
3. Watch: `HealthRelayListenerService` appends them to the same queue, so they reach the iPhone over the existing BLE link. While the relay is active (data in the last 7 days), the watch stops sending its own basic sleep.
4. iPhone: before saving a chunk with in-bed sessions, `HealthKitWriter` deletes its own `asleepUnspecified` samples inside those sessions, so staged sleep replaces basic sleep.

The relay module is only included in the Gradle build when the SDK AAR is in `wearos-app/relay/libs/` (not redistributable, gitignored). The `:shared` module holds `HealthProtocol` for both apps.

## Quick Reply Flow

1. Watch: user taps quick reply → ANCS negative action + BLE write to quick reply characteristic
2. iPhone: `PeripheralManager` receives write → opens `shortcuts://run-shortcut?name=WearBridge%20Reply`
3. User creates iOS Shortcut "WearBridge Reply" to send SMS/iMessage with the input text

## iPhone Companion App Watch Detection

The iPhone can't detect ANCS connections (system-level). Instead, `CBCentralManager.retrieveConnectedPeripherals(withServices:)` probes for devices with standard BLE services (Device Info 0x180A, Battery 0x180F, etc.) and filters by known watch name patterns. Polls every 10 seconds.

## App Icon Mapping

75 iOS bundle IDs mapped in `AppIconMapper.kt`. 37 notification icons with 25% padding (viewport 32, translate 4). Call screen uses 3 Lucide stroke icons. Watch UI uses Material Icons Extended.
