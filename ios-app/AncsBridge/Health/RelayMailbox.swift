import BackgroundTasks
import CryptoKit
import Foundation
import Security
import UIKit
import os.log

/// Receives Samsung Health data from the phone relay over the internet, so the
/// relay phone can stay at home and the watch doesn't need internet.
///
/// The relay posts end-to-end encrypted batches to an ntfy topic (the
/// "mailbox"); this class polls it, stages the records for Apple Health and
/// posts encrypted acks to "<topic>-ack", so the relay re-posts anything that
/// didn't arrive. Format and golden vector: MailboxCodec.kt in wearos-app/shared.
///
/// Checks happen when the app comes to the foreground, when the watch writes
/// to the iPhone over BLE (which wakes the app), and in background app refresh.
final class RelayMailbox: ObservableObject {

    static let shared = RelayMailbox()
    static let refreshTaskIdentifier = "com.wearos.ancsbridge.relay-fetch"

    struct Pairing: Codable, Equatable {
        let server: URL
        let topic: String
        let key: Data
    }

    @Published private(set) var pairing: Pairing?
    @Published private(set) var status: String
    /// A pairing link that was opened (camera, Messages, a web page) and waits for the user to confirm it.
    @Published private(set) var pendingPairing: Pairing?

    private static let aad = Data("wearbridge.relay.v1".utf8)
    private static let kindBatch: UInt8 = 1
    private static let kindAck: UInt8 = 2
    private static let maxSeqsPerAck = 700
    private static let watchTriggerInterval: TimeInterval = 10 * 60
    private static let refreshInterval: TimeInterval = 20 * 60

    private enum Keys {
        static let lastMessageId = "relayMailboxLastMessageId"
        static let status = "relayMailboxStatus"
        static let lastDataAt = "relayMailboxLastDataAt"
    }

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "RelayMailbox")
    private let defaults = UserDefaults.standard
    private var isFetching = false
    private var lastFetch: Date?
    private var protectedDataObserver: NSObjectProtocol?

    /// Dedicated session: no cache or cookies, and short timeouts so a check fits in a background wake.
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 15
        return URLSession(configuration: configuration)
    }()

    private init() {
        pairing = Keychain.loadPairing()
        status = UserDefaults.standard.string(forKey: Keys.status) ?? ""
        // Before the first unlock after a reboot the Keychain can't be read; try again once it can.
        protectedDataObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { _ = self?.currentPairing() }
        }
    }

    /// The pairing, re-read from the Keychain if it couldn't be read before (locked at launch).
    @MainActor
    private func currentPairing() -> Pairing? {
        if pairing == nil, let stored = Keychain.loadPairing() {
            pairing = stored
        }
        return pairing
    }

    // MARK: - Pairing

    /// A relay link was opened from outside the app (camera, Messages, a web page):
    /// keep it until the user confirms, so a stray link can't replace the real relay.
    @MainActor
    func offer(_ url: URL) {
        guard let offered = Self.parse(url) else {
            logger.error("Not a WearBridge relay link")
            setStatus("That link isn't a valid relay pairing link (it must use https)")
            return
        }
        guard offered != currentPairing() else { return } // already paired with it
        pendingPairing = offered
    }

    @MainActor
    func cancelPendingPairing() {
        pendingPairing = nil
    }

    /// Pairs with the relay the user confirmed.
    @discardableResult
    @MainActor
    func pair(with pairing: Pairing) -> Bool {
        pendingPairing = nil
        guard Keychain.save(pairing) else {
            setStatus("Couldn't save the relay pairing")
            return false
        }
        self.pairing = pairing
        defaults.removeObject(forKey: Keys.lastMessageId)
        defaults.removeObject(forKey: Keys.lastDataAt)
        setStatus("Paired with the relay")
        Task { await fetch(reason: "paired") }
        return true
    }

    @MainActor
    func unpair() {
        Keychain.deletePairing()
        pairing = nil
        defaults.removeObject(forKey: Keys.lastMessageId)
        defaults.removeObject(forKey: Keys.lastDataAt)
        setStatus("")
    }

    static func parse(_ url: URL) -> Pairing? {
        guard url.scheme == "wearbridge", url.host == "relay",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return nil }
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
        // https only: App Transport Security blocks plain http anyway.
        guard let serverText = value("s"), let server = URL(string: serverText),
              server.scheme == "https", server.host?.isEmpty == false,
              let topic = value("t"), topic.range(of: "^[-_A-Za-z0-9]{1,60}$", options: .regularExpression) != nil,
              let keyText = value("k"), let key = Data(base64URLEncoded: keyText), key.count == 32
        else { return nil }
        return Pairing(server: server, topic: topic, key: key)
    }

    // MARK: - Fetching

    /// Called when the watch wakes the app over BLE; checks at most every 10 minutes.
    @MainActor
    func fetchIfDue(reason: String) {
        if let lastFetch, Date().timeIntervalSince(lastFetch) < Self.watchTriggerInterval { return }
        Task { await fetch(reason: reason) }
    }

    /// Polls the mailbox, stages new batches for Apple Health and acknowledges them.
    @MainActor
    func fetch(reason: String) async {
        guard let pairing = currentPairing(), !isFetching else { return }
        guard SettingsStore.isHealthSyncEnabled() else {
            setStatus("Apple Health sync is off (above): relay data waits on the relay phone")
            return
        }
        isFetching = true
        lastFetch = Date()
        defer { isFetching = false }

        // A BLE or refresh wake only gives a few seconds; ask for more, and stop cleanly
        // if iOS wants the time back (nothing acknowledged yet means nothing is lost).
        let work = Task { @MainActor in await self.receive(pairing, reason: reason) }
        let assertion = BackgroundAssertion.begin(name: "RelayMailbox") { work.cancel() }
        await work.value
        assertion.end()
    }

    @MainActor
    private func receive(_ pairing: Pairing, reason: String) async {
        do {
            let since = defaults.string(forKey: Keys.lastMessageId) ?? "all"
            let messages = try await poll(pairing, topic: pairing.topic, since: since)
            var received: [UInt32] = []
            var recordCount = 0
            for message in messages {
                guard let text = message.message, let batch = Self.openBatch(text, key: pairing.key) else { continue }
                let (seq, records) = batch
                guard HealthKitWriter.shared.stage(records) else {
                    setStatus("Couldn't store relay data on the iPhone; will retry")
                    return // nothing acknowledged, so the relay sends it again
                }
                received.append(seq)
                recordCount += records.count
            }
            for chunk in stride(from: 0, to: received.count, by: Self.maxSeqsPerAck) {
                let seqs = Array(received[chunk..<min(chunk + Self.maxSeqsPerAck, received.count)])
                try await post(pairing, topic: pairing.topic + "-ack", text: Self.sealAck(seqs, key: pairing.key))
            }
            // Only move on once the acks are out; a repeat poll is harmless (sync identifiers).
            if let lastId = messages.last?.id {
                defaults.set(lastId, forKey: Keys.lastMessageId)
            }
            if recordCount > 0 {
                defaults.set(Date().timeIntervalSince1970, forKey: Keys.lastDataAt)
                HealthKitWriter.shared.flush()
            }
            let time = Date().formatted(date: .omitted, time: .shortened)
            setStatus(recordCount > 0
                      ? "Last check \(time): \(recordCount) records received"
                      : "Last check \(time): nothing new\(lastDataNote)")
            logger.info("Mailbox (\(reason)): \(received.count) batches, \(recordCount) records")
        } catch {
            if Task.isCancelled {
                setStatus("Last check interrupted; will retry")
            } else {
                setStatus("Last check failed: \(error.localizedDescription)")
            }
            logger.error("Mailbox fetch failed (\(reason)): \(error.localizedDescription)")
        }
    }

    /// " · last data <date>" when something ever arrived.
    private var lastDataNote: String {
        let seconds = defaults.double(forKey: Keys.lastDataAt)
        guard seconds > 0 else { return "" }
        return " · last data \(Date(timeIntervalSince1970: seconds).formatted(date: .abbreviated, time: .shortened))"
    }

    /// A UIKit background task that ends exactly once, whether the work finishes or iOS expires it.
    private final class BackgroundAssertion: @unchecked Sendable {
        private var id: UIBackgroundTaskIdentifier = .invalid

        @MainActor
        static func begin(name: String, onExpiry: @escaping @Sendable () -> Void) -> BackgroundAssertion {
            let assertion = BackgroundAssertion()
            assertion.id = UIApplication.shared.beginBackgroundTask(withName: name) {
                onExpiry()
                assertion.end()
            }
            return assertion
        }

        @MainActor
        func end() {
            guard id != .invalid else { return }
            UIApplication.shared.endBackgroundTask(id)
            id = .invalid
        }
    }

    // MARK: - Background refresh

    func scheduleRefresh() {
        guard pairing != nil || Keychain.loadPairing() != nil else { return }
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTaskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: Self.refreshInterval)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            logger.error("Couldn't schedule a mailbox refresh: \(error.localizedDescription)")
        }
    }

    /// Runs from the app's `.backgroundTask(.appRefresh(...))` handler.
    func backgroundRefresh() async {
        scheduleRefresh()
        await fetch(reason: "background refresh")
    }

    // MARK: - HTTP (ntfy)

    private struct PolledMessage: Decodable {
        let id: String
        let event: String
        let message: String?
    }

    private enum MailboxError: LocalizedError {
        case http(Int)
        var errorDescription: String? {
            switch self {
            case .http(let code): return "relay server answered \(code)"
            }
        }
    }

    private func poll(_ pairing: Pairing, topic: String, since: String) async throws -> [PolledMessage] {
        var components = URLComponents(
            url: pairing.server.appendingPathComponent(topic).appendingPathComponent("json"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [URLQueryItem(name: "poll", value: "1"), URLQueryItem(name: "since", value: since)]
        guard let url = components?.url else { throw URLError(.badURL) }
        let (data, response) = try await Self.session.data(for: URLRequest(url: url))
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw MailboxError.http(code) }
        let decoder = JSONDecoder()
        return String(decoding: data, as: UTF8.self)
            .split(separator: "\n")
            .compactMap { try? decoder.decode(PolledMessage.self, from: Data($0.utf8)) }
            .filter { $0.event == "message" }
    }

    private func post(_ pairing: Pairing, topic: String, text: String) async throws {
        var request = URLRequest(url: pairing.server.appendingPathComponent(topic))
        request.httpMethod = "POST"
        request.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(text.utf8)
        let (_, response) = try await Self.session.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw MailboxError.http(code) }
    }

    // MARK: - Messages (see MailboxCodec.kt)

    /// The sequence number and records of a batch message, or nil if it isn't one of ours.
    static func openBatch(_ text: String, key: Data) -> (UInt32, [HealthRecord])? {
        guard let plain = open(text, key: key), plain.count >= 5, plain[0] == kindBatch else { return nil }
        let seq = UInt32(plain[1]) | UInt32(plain[2]) << 8 | UInt32(plain[3]) << 16 | UInt32(plain[4]) << 24
        guard let records = HealthProtocol.decodeBatch(Data(plain[5...])) else { return nil }
        return (seq, records)
    }

    static func sealAck(_ seqs: [UInt32], key: Data, nonce: AES.GCM.Nonce = AES.GCM.Nonce()) throws -> String {
        var plain: [UInt8] = [kindAck, UInt8(seqs.count & 0xFF), UInt8(seqs.count >> 8)]
        for seq in seqs {
            plain += [UInt8(seq & 0xFF), UInt8(seq >> 8 & 0xFF), UInt8(seq >> 16 & 0xFF), UInt8(seq >> 24)]
        }
        let box = try AES.GCM.seal(Data(plain), using: SymmetricKey(data: key), nonce: nonce, authenticating: aad)
        guard let combined = box.combined else { throw CryptoKitError.incorrectParameterSize }
        return combined.base64URLEncodedString()
    }

    private static func open(_ text: String, key: Data) -> [UInt8]? {
        guard let sealed = Data(base64URLEncoded: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              sealed.count >= 12 + 16,
              let box = try? AES.GCM.SealedBox(combined: sealed),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
        else { return nil }
        return [UInt8](plain)
    }

    @MainActor
    private func setStatus(_ text: String) {
        status = text
        defaults.set(text, forKey: Keys.status)
    }

    // MARK: - Keychain

    /// The pairing holds the encryption key, so it lives in the Keychain, readable
    /// after the first unlock (background refresh runs with the phone locked).
    private enum Keychain {
        private static let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.wearos.ancsbridge.relay",
            kSecAttrAccount as String: "pairing",
        ]

        static func loadPairing() -> Pairing? {
            var request = query
            request[kSecReturnData as String] = true
            request[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: AnyObject?
            guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess,
                  let data = result as? Data else { return nil }
            return try? JSONDecoder().decode(Pairing.self, from: data)
        }

        static func save(_ pairing: Pairing) -> Bool {
            guard let data = try? JSONEncoder().encode(pairing) else { return false }
            SecItemDelete(query as CFDictionary)
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
        }

        static func deletePairing() {
            SecItemDelete(query as CFDictionary)
        }
    }
}

extension Data {
    init?(base64URLEncoded text: String) {
        var base64 = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        self.init(base64Encoded: base64)
    }

    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
