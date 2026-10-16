import AppIntents
import Foundation

/// Shown by Shortcuts when the automation couldn't tell the watch.
struct WatchNotReachedError: LocalizedError {
    var errorDescription: String? {
        "WearBridge couldn't reach the watch. Check that it's connected to this iPhone."
    }
}

/// Shortcuts actions that keep the watch's wake-up alarm in sync with the iPhone's.
///
/// iOS has no API to observe the Clock app's alarms, but Shortcuts can run an
/// action when the wake-up alarm goes off, is snoozed or is stopped (Automation →
/// Alarm). Those automations call these intents, which tell the watch over BLE.
struct RingWatchAlarmIntent: AppIntent {
    static let title: LocalizedStringResource = "Ring Watch Alarm"
    static let description: IntentDescription? = IntentDescription(
        "Starts the gentle wake-up vibration on the watch. Run it from an automation when your wake-up alarm goes off."
    )
    static let supportedModes: IntentModes = .background

    @MainActor
    func perform() async throws -> some IntentResult {
        guard await PeripheralManager.shared.setWakeAlarm(ringing: true) else { throw WatchNotReachedError() }
        return .result()
    }
}

struct StopWatchAlarmIntent: AppIntent {
    static let title: LocalizedStringResource = "Stop Watch Alarm"
    static let description: IntentDescription? = IntentDescription(
        "Stops the wake-up vibration on the watch. Run it from an automation when your wake-up alarm is stopped or snoozed."
    )
    static let supportedModes: IntentModes = .background

    @MainActor
    func perform() async throws -> some IntentResult {
        guard await PeripheralManager.shared.setWakeAlarm(ringing: false) else { throw WatchNotReachedError() }
        return .result()
    }
}

struct WearBridgeShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: RingWatchAlarmIntent(),
            phrases: ["Ring my watch alarm with \(.applicationName)"],
            shortTitle: "Ring Watch Alarm",
            systemImageName: "alarm"
        )
        AppShortcut(
            intent: StopWatchAlarmIntent(),
            phrases: ["Stop my watch alarm with \(.applicationName)"],
            shortTitle: "Stop Watch Alarm",
            systemImageName: "alarm.waves.left.and.right"
        )
    }
}
