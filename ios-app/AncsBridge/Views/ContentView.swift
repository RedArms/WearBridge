import SwiftUI

struct ContentView: View {
    @ObservedObject private var relayMailbox = RelayMailbox.shared

    var body: some View {
        TabView {
            StatusView()
                .tabItem {
                    Label("System", systemImage: "antenna.radiowaves.left.and.right")
                }

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape")
                }
        }
        // A relay link opened from the camera, Messages or a web page is only used once confirmed.
        .alert(
            "Pair with this Samsung relay?",
            isPresented: Binding(
                get: { relayMailbox.pendingPairing != nil },
                set: { presented in if !presented { relayMailbox.cancelPendingPairing() } }
            ),
            presenting: relayMailbox.pendingPairing
        ) { pairing in
            Button("Pair") { relayMailbox.pair(with: pairing) }
            Button("Cancel", role: .cancel) { relayMailbox.cancelPendingPairing() }
        } message: { pairing in
            Text("Health data from the relay phone will be added to Apple Health, through \(pairing.server.host ?? pairing.server.absoluteString)."
                 + (relayMailbox.pairing != nil ? " This replaces the current relay pairing." : "")
                 + " Only pair with a code shown by your own WearBridge Relay app.")
        }
    }
}
