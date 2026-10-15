# Future Work

## App Icon Support
- Currently 24dp white Material Design vector drawables with 25% padding
- Upgrade to Play Store PNGs as `largeIcon` for full-color icons

## Multi-Device Pairing
- Support pairing with multiple Wear OS watches
- Persist bonded device list and per-watch notification category preferences
- Only one watch active at a time (BLE limitation with ANCS)

## Health Data Bridge — follow-ups
- Shipped: heart rate, steps, distance, floors, asleep periods (see ble-ancs-protocol.md)
- Workouts (ExerciseClient sessions → HKWorkout with route)
- Calories: Health Services CALORIES mixes basal + active; needs splitting before mapping to activeEnergyBurned
- Show pending record count / last sync time on the watch and in the iOS app

## iOS Shortcut Template
- Provide a pre-built "WearBridge Reply" shortcut for download
- Currently users must create the iOS Shortcut manually to handle quick reply text
