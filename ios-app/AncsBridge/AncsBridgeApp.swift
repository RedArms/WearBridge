import SwiftUI

@main
struct AncsBridgeApp: App {
    @StateObject private var bleViewModel = BLEViewModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bleViewModel)
                // Pairing link from the phone relay's QR code: wearbridge://relay?...
                .onOpenURL { url in
                    RelayMailbox.shared.pair(with: url)
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
