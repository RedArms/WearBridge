import SwiftUI

@main
struct AncsBridgeApp: App {
    @StateObject private var bleViewModel = BLEViewModel()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Core Bluetooth can relaunch the app in the background (state restoration, a
        // watch write); the restoring manager must exist even if no scene is built.
        _ = PeripheralManager.shared
        // Before any scene: a tap on a quick reply notification may be what launches the app.
        QuickReplyLauncher.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bleViewModel)
                .task { QuickReplyLauncher.shared.requestAuthorization() }
                // Pairing link from the phone relay's QR code: wearbridge://relay?...
                .onOpenURL { url in
                    RelayMailbox.shared.offer(url) // confirmed in ContentView
                }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                Task { await RelayMailbox.shared.fetch(reason: "foreground") }
            case .background:
                RelayMailbox.shared.scheduleRefresh()
            default:
                break
            }
        }
        .backgroundTask(.appRefresh(RelayMailbox.refreshTaskIdentifier)) {
            await RelayMailbox.shared.backgroundRefresh()
        }
    }
}
