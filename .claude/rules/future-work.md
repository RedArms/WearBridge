# Future Work

## App Icon Support
- Currently 24dp white Material Design vector drawables with 25% padding
- Upgrade to Play Store PNGs as `largeIcon` for full-color icons

## Multi-Device Pairing
- Support pairing with multiple Wear OS watches
- Persist bonded device list and per-watch notification category preferences
- Only one watch active at a time (BLE limitation with ANCS)

## Health Data Bridge — follow-ups
- Shipped: heart rate, steps, distance, floors, asleep periods; with the phone relay: sleep stages + score, SpO2, blood pressure (see ble-ancs-protocol.md)
- Relay: use the SDK's change queries instead of the 36 h re-read window; SpO2 series data during sleep (currently one value per record)
- Sleep score updates after the first send are ignored by HealthKit (same sync identifier and version)
- Workouts (ExerciseClient sessions → HKWorkout with route)
- Calories: Health Services CALORIES mixes basal + active; needs splitting before mapping to activeEnergyBurned
- Show pending record count / last sync time on the watch and in the iOS app

## Quick Reply List Sync
- The iOS app's editable reply list stays on the iPhone; the watch uses its hard-coded `QUICK_REPLIES`
- Sync it over a companion characteristic (read on connect, notify on change)

## iOS Shortcut Template
- Provide a pre-built "WearBridge Reply" shortcut for download
- Currently users must create the iOS Shortcut manually to handle quick reply text
