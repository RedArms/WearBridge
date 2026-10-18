import SwiftUI

@main
struct AncsBridgeApp: App {
    @StateObject private var bleViewModel = BLEViewModel()

    init() {
        // Core Bluetooth can relaunch the app in the background (state restoration, a
        // watch write); the restoring manager must exist even if no scene is built.
        _ = PeripheralManager.shared
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bleViewModel)
        }
    }
}
