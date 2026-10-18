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
| Wake Alarm | `A0E3D5B6-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Read/Notify — iPhone pushes its wake-up alarm state (0x00=stopped, 0x01=ringing) |

## Health Data Bridge

Opt-in on both sides: **Health Sync** on the watch, **Settings → Apple Health** in the iOS app. Optionally, the phone relay (`wearos-app/relay`) on the Android phone the watch is paired with adds Samsung Health data.

1. Watch: `HealthMonitoring` registers `HealthPassiveListenerService` with Health Services (heart rate, steps, distance, floors, "asleep" activity state). Registration is redone on every `AncsService` start because Health Services forgets it on reboot.
2. Watch: records go to `HealthRecordStore`, a fixed-record file queue (`health_queue.bin`, capped at 50k records).
3. Watch: `HealthSyncManager` flushes 10 s after connecting, once a full write's worth of new data is queued, and every 15 min, but never while a call rings or within 3 s of ANCS activity (every GATT write can buzz the watch, gotcha 10). One write-with-response at a time, sized to the MTU (max 30 records), at most 25 writes per flush (a backlog drains every 2 min); confirmed records leave the queue in chunks of up to 20 writes. After the iPhone refuses a batch (Apple Health sync off), it waits 30 min or the next connection.
4. iPhone: `PeripheralManager` decodes the batch, stages it on disk (`HealthKitWriter`, file protection *until first user authentication*), then responds `.success`. It responds `.requestNotSupported` when Apple Health sync is off, so the watch keeps its queue.
5. iPhone: staged records are saved to HealthKit when it is writable (HealthKit is locked with the phone): right away, on unlock, on app foreground, or with the next batch. Samples of types the user denied in Health are dropped; a type still undetermined holds the queue until the app is opened, which shows the permission sheet again.

Wire format (little-endian), mirrored in `HealthProtocol.kt` / `HealthProtocol.swift`, with a shared golden test vector:

```
Batch  := version:u8 (=1) | count:u8 (1..255) | Record × count
Record := type:u8 | start:u32 (Unix s) | end:u32 (Unix s) | value:f64     (17 bytes)
type   := 1 heart rate (bpm) | 2 steps | 3 distance (m) | 4 floors
        | 5 sleep (value = stage: 0 unspecified, 1 awake, 2 light, 3 deep, 4 REM, 5 watch "asleep" state)
        | 6 in bed (whole session, value = sleep score 0..100, 0 = none)
        | 7 SpO2 (%) | 8 blood pressure (value = systolic * 1000 + diastolic, mmHg)
```

HealthKit mapping: 5 → sleepAnalysis asleepUnspecified/awake/asleepCore/asleepDeep/asleepREM (stage 5: asleepUnspecified tagged `WearBridgeWatchSleep`), 6 → inBed (score in `WearBridgeSleepScore` metadata), 7 → oxygenSaturation (fraction), 8 → bloodPressure correlation (systolic + diastolic). HealthKit can't take skin temperature (`appleSleepingWristTemperature` is read-only) or ECG from third-party apps.

Every HealthKit sample gets `HKMetadataKeySyncIdentifier = wearbridge.<type>.<start>.<end>` (version 1), so batches resent after a lost acknowledgement are not duplicated.

If the watch only offers `*_DAILY` totals passively, `DailyTotals` turns them into increments. Sleep periods shorter than 10 min or longer than 24 h are dropped (`SleepSessions`).

### Phone relay (Samsung Health)

Health Services only exposes an "asleep" state. Samsung Health on the paired Android phone has the full picture, readable through the Samsung Health Data SDK in Samsung Health's developer mode (personal use, read-only, no partner registration).

1. `:relay` (phone, same applicationId `com.wearos.ancsbridge` and signing key as the watch app, which the Data Layer requires) reads sleep (sessions, stages, score), SpO2, blood pressure (+ pulse) and heart rate. `RelayWorker` runs every 15 min via WorkManager, re-reading 36 h before the last success (30 days on the first run); `SentLog` skips records sent in the last 24 h.
2. Batches (same wire format, ≤255 records) go to the watch with `MessageClient` on path `/wearbridge/health/batch`.
3. Watch: `HealthRelayListenerService` appends them to the same queue, so they reach the iPhone over the existing BLE link. While the relay sends heart rate (not counting a blood pressure reading's pulse), i.e. it did in the last 3 days, the watch stops sending its own heart rate, so Health doesn't get two overlapping series. The relay re-reads from 36 h before its last success, so it fills any gap when it comes back. The watch always sends its own sleep (the relay may be late).
4. iPhone: `HealthKitWriter` remembers relay sleep sessions (in-bed records, 45 days) and doesn't save watch sleep (tagged `WearBridgeWatchSleep`) inside them. Watch sleep saved before the relay's arrived is deleted after the relay chunk is saved; deleting needs an unlocked phone, so pending ranges are kept on disk and retried with every flush instead of holding back the save. Relay samples without a stage are kept.

The relay module is only included in the Gradle build when the SDK AAR is in `wearos-app/relay/libs/` (not redistributable, gitignored). The `:shared` module holds `HealthProtocol` and `MailboxCodec` for both apps.

### Internet relay (mailbox)

So the relay phone can stay at home and the watch needs no internet, the relay also sends straight to the iPhone app over the internet:

1. Pairing: the relay creates a random ntfy topic and an AES-256 key (`MailboxCodec.newPairing`) and shows `wearbridge://relay?s=<server>&t=<topic>&k=<key>` as a QR code. The iPhone camera opens the link in WearBridge (`CFBundleURLTypes`), which keeps it in the Keychain (`RelayMailbox`). Default server `https://ntfy.sh`; any ntfy server works (self-hosted, https).
2. Relay: new records go into `MailboxOutbox` (on disk). Heart-rate-only records are grouped for up to 1 h, other types are packed at once, ≤170 records per message so a message stays under ntfy's 4096-byte limit. Messages are posted to the topic and re-posted every 6 h until acknowledged (ntfy keeps messages 12 h), dropped after 7 days. `RelayWorker` now runs every 15 min; it still sends to the watch too when connected (the iPhone deduplicates by sync identifier).
3. Format (`MailboxCodec.kt` / `RelayMailbox.swift`, shared golden vector): text = base64url(nonce:12 | AES-256-GCM | tag:16), AAD `wearbridge.relay.v1`; plaintext = kind:u8 | body; kind 1 = seq:u32 | HealthProtocol batch; kind 2 (ack) = count:u16 | seq:u32 × count.
4. iPhone: polls `<topic>/json?poll=1&since=<last id>` when the app comes to the foreground, when the watch writes over BLE (at most every 10 min), and in background app refresh (`com.wearos.ancsbridge.relay-fetch`); stages records through `HealthKitWriter.shared`, then posts encrypted acks to `<topic>-ack`, and only then remembers the last message id. The relay reads acks before each post.

## Wake-up Alarm

1. iPhone: the Sleep Schedule wake-up alarm rings (its sounds ramp up on their own). A Shortcuts automation (Automation → Alarm → goes off) runs `RingWatchAlarmIntent`, which may launch the app in the background; `PeripheralManager.shared.setWakeAlarm` waits for Bluetooth / the restored service, then notifies the wake alarm characteristic. Stopped / snoozed automations run `StopWatchAlarmIntent` (0x00).
2. Watch: `BleConnectionManager` subscribes to the wake alarm characteristic after call state. `AncsService` starts / stops `WakeAlarm`.
3. Watch: if an iPhone Clock alarm arrives over ANCS (`com.apple.mobiletimer` with actions; some iPhones never send it, reported on iPhone 14+), it starts `WakeAlarm` too, and REMOVED stops it. Its action labels decide which ANCS action stops or snoozes the iPhone alarm from the watch (`ClockAlarm`).
4. `WakeAlarm`: waveform from `WakeRamp` (60 s from 60 ms / amplitude 30 taps to 650 ms / 255 buzzes, then a repeating double buzz) with `VibrationAttributes.USAGE_ALARM`, so it goes through Bedtime mode and notification vibrations don't cut it; full-screen `WakeAlarmActivity` (channel `ancs_wake_alarm_v1`, silent); stops by itself after 10 min.
5. iOS state restoration: restored characteristics are re-bound (call state notifications used to stop after a relaunch), and a restored service without the wake alarm characteristic is rebuilt.

## Quick Reply Flow

1. Watch: user taps quick reply → ANCS negative action + BLE write to quick reply characteristic
2. iPhone: `PeripheralManager` receives write → opens `shortcuts://run-shortcut?name=WearBridge%20Reply`
3. User creates iOS Shortcut "WearBridge Reply" to send SMS/iMessage with the input text

## iPhone Companion App Watch Detection

The iPhone can't detect ANCS connections (system-level). Instead, `CBCentralManager.retrieveConnectedPeripherals(withServices:)` probes for devices with standard BLE services (Device Info 0x180A, Battery 0x180F, etc.) and filters by known watch name patterns. Polls every 10 seconds.

## Notification Look

`NotificationLook` (pure Kotlin, unit tested) decides how a notification reads; `AncsService.postNotification` applies it. Everything uses `MessagingStyle` (gotcha 14).

- **Sender**: for chat apps (Messages, WhatsApp, Telegram, Signal, Messenger, Slack, Discord, Teams, Instagram, Snapchat), calls and email, the ANCS title is the sender. A chat subtitle becomes the group name (`setConversationTitle` + group conversation). For every other app, the app itself is the sender and the title becomes a bold first line.
- **Email**: sender + bold subject + body.
- **Avatars**: `NotificationAvatars` draws initials on a colored disc (person outline for phone numbers). People get a stable palette color from their name; apps get their accent color.
- **Accent color**: `setColor` with the app's brand color (white for black brands), else by category, else a stable palette color per app.
- **App names**: the iPhone's own (localized) display name, fetched once per app with ANCS GetAppAttributes after its first notification and kept in `AppNameStore` (SharedPreferences). Until then: `knownAppNames`, else a prettified bundle ID ("com.vinted.app" -> "Vinted").
- `DataSourceAssembler.onData` returns either a notification or an app attributes result. A fragment boundary inside an attribute's 2-byte length used to blank that attribute; the ID and length are now re-read together.

## App Icon Mapping

75 iOS bundle IDs mapped in `AppIconMapper.kt`. 37 notification icons with 25% padding (viewport 32, translate 4). Call screen uses 3 Lucide stroke icons. Watch UI uses Material Icons Extended.
