import Foundation
import HealthKit
import UIKit
import os.log

/// Saves health records received from the watch into Apple Health.
///
/// HealthKit can't be written while the iPhone is locked, and the watch mostly
/// syncs in the background. So a BLE write is acknowledged as soon as its
/// records are staged on disk, and staged records are saved to HealthKit when
/// it becomes reachable: right away if possible, otherwise on unlock, when the
/// app comes to the foreground, or with the next batch from the watch.
///
/// Every sample carries a sync identifier derived from its type and interval,
/// so a batch the watch resends after a lost acknowledgement is not duplicated.
final class HealthKitWriter {

    static let syncIdentifierPrefix = "wearbridge"

    /// Same cap as the watch-side queue; the oldest records go first.
    private static let maxStagedRecords = 50_000
    private static let maxRecordsPerSave = 1_000

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "HealthKitWriter")
    private let healthStore = HKHealthStore()
    private let queue = DispatchQueue(label: "com.wearos.ancsbridge.health")
    private let stagingURL: URL
    private let isEnabled: () -> Bool
    private var isFlushing = false
    /// Bumped when staged records are discarded, so a save that was in flight
    /// doesn't remove records staged after the discard.
    private var stagingGeneration = 0
    private var flushGeneration = 0
    private var observers: [NSObjectProtocol] = []

    init(isEnabled: @escaping () -> Bool) {
        self.isEnabled = isEnabled
        let supportDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        stagingURL = supportDirectory.appendingPathComponent("health-staging.bin")

        let center = NotificationCenter.default
        for name in [UIApplication.protectedDataDidBecomeAvailableNotification, UIApplication.didBecomeActiveNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in
                self?.flush()
            })
        }
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    static var isHealthDataAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// The types WearBridge writes. It never reads Health data.
    static var shareTypes: Set<HKSampleType> {
        [
            HKQuantityType(.heartRate),
            HKQuantityType(.stepCount),
            HKQuantityType(.distanceWalkingRunning),
            HKQuantityType(.flightsClimbed),
            HKCategoryType(.sleepAnalysis),
            HKQuantityType(.oxygenSaturation),
            HKQuantityType(.bloodPressureSystolic),
            HKQuantityType(.bloodPressureDiastolic),
        ]
    }

    /// Shows the Health permission sheet. `completion` runs on the main queue.
    func requestAuthorization(completion: @escaping (Bool) -> Void) {
        guard Self.isHealthDataAvailable else {
            completion(false)
            return
        }
        healthStore.requestAuthorization(toShare: Self.shareTypes, read: nil) { [weak self] success, error in
            if let error {
                self?.logger.error("HealthKit authorization failed: \(error.localizedDescription)")
            }
            DispatchQueue.main.async { completion(success) }
        }
    }

    // MARK: - Staging

    /// Durably stages records from the watch. Returns false if they could not be
    /// written, in which case the watch keeps them and retries later.
    func stage(_ records: [HealthRecord]) -> Bool {
        queue.sync {
            do {
                try appendToStaging(HealthProtocol.encodeRecords(records))
                return true
            } catch {
                logger.error("Failed to stage \(records.count) health records: \(error.localizedDescription)")
                return false
            }
        }
    }

    /// Drops staged records, e.g. when the user turns the sync off.
    func discardStaged() {
        queue.async {
            self.stagingGeneration += 1
            try? FileManager.default.removeItem(at: self.stagingURL)
        }
    }

    private func appendToStaging(_ data: Data) throws {
        let length = try appendRecordBytes(data)
        // Trimming while a save is in flight would shift the records it is about to remove.
        let count = Int(length) / HealthProtocol.recordSize
        if !isFlushing, count > Self.maxStagedRecords {
            removeStaged(count: count - Self.maxStagedRecords)
            logger.warning("Health staging full; dropped \(count - Self.maxStagedRecords) oldest records")
        }
    }

    /// Appends to the staging file and returns its new length in bytes.
    private func appendRecordBytes(_ data: Data) throws -> UInt64 {
        let fileManager = FileManager.default
        if !fileManager.fileExists(atPath: stagingURL.path) {
            try fileManager.createDirectory(at: stagingURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            // Writable while locked (after the first unlock since boot), so background BLE writes can be staged.
            try Data().write(to: stagingURL, options: .completeFileProtectionUntilFirstUserAuthentication)
        }
        let handle = try FileHandle(forWritingTo: stagingURL)
        defer { try? handle.close() }
        let length = try handle.seekToEnd()
        let partial = length % UInt64(HealthProtocol.recordSize)
        if partial != 0 {
            // Drop a torn record left by an interrupted write so later records stay aligned.
            try handle.truncate(atOffset: length - partial)
            try handle.seekToEnd()
        }
        try handle.write(contentsOf: data)
        try handle.synchronize()
        return try handle.offset()
    }

    private func stagedBytes() -> [UInt8] {
        guard let data = try? Data(contentsOf: stagingURL) else { return [] }
        return [UInt8](data)
    }

    /// The first `maxRecords` staged records' bytes, without reading the whole file.
    private func stagedPrefix(maxRecords: Int) -> [UInt8] {
        guard let handle = try? FileHandle(forReadingFrom: stagingURL) else { return [] }
        defer { try? handle.close() }
        guard let data = try? handle.read(upToCount: maxRecords * HealthProtocol.recordSize) else { return [] }
        return [UInt8](data)
    }

    private func removeStaged(count: Int) {
        let bytes = stagedBytes()
        let removeBytes = min(count * HealthProtocol.recordSize, bytes.count)
        let remaining = Data(bytes[removeBytes...])
        do {
            if remaining.count < HealthProtocol.recordSize {
                try? FileManager.default.removeItem(at: stagingURL)
            } else {
                try remaining.write(to: stagingURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
        } catch {
            logger.error("Failed to update health staging file: \(error.localizedDescription)")
        }
    }

    // MARK: - Saving to HealthKit

    /// Saves staged records to HealthKit if it is reachable. Safe to call often.
    func flush() {
        queue.async { self.flushOnQueue() }
    }

    private func flushOnQueue() {
        guard !isFlushing, isEnabled(), Self.isHealthDataAvailable else { return }
        let bytes = stagedPrefix(maxRecords: Self.maxRecordsPerSave)
        let count = bytes.count / HealthProtocol.recordSize
        guard count > 0 else { return }

        let records = HealthProtocol.decodeRecords(bytes, count: count)
        var samples: [HKSample] = []
        var denied = 0
        for sample in records.compactMap(Self.makeSample) {
            switch access(for: sample) {
            case .allowed:
                samples.append(sample)
            case .denied:
                denied += 1
            case .undetermined:
                // The permission sheet shows the next time the app is opened.
                logger.info("Health access for \(sample.sampleType.identifier) not decided yet; keeping \(count) staged records")
                return
            }
        }
        if denied > 0 {
            // Types the user turned off in Health are dropped so they don't block the rest.
            logger.info("Skipping \(denied) samples of types not allowed in Health")
        }
        guard !samples.isEmpty else {
            removeStaged(count: count) // nothing usable in this chunk
            flushOnQueue()
            return
        }

        isFlushing = true
        flushGeneration = stagingGeneration
        let canWriteSleep = healthStore.authorizationStatus(for: HKCategoryType(.sleepAnalysis)) == .sharingAuthorized
        let sessions = canWriteSleep ? Self.sleepSessionRanges(records) : []
        guard !sessions.isEmpty else {
            save(samples, stagedCount: count)
            return
        }
        // Samsung Health sleep (from the phone relay) replaces the watch's basic
        // "asleep" samples for the same nights.
        deleteBasicSleep(overlapping: sessions) { [weak self] mustRetry in
            guard let self else { return }
            if mustRetry {
                self.isFlushing = false
                self.logger.info("HealthKit not writable now; keeping \(count) staged records")
            } else {
                self.save(samples, stagedCount: count)
            }
        }
    }

    /// Saves one chunk of staged samples. Runs on `queue` with `isFlushing` set.
    private func save(_ samples: [HKSample], stagedCount count: Int) {
        healthStore.save(samples) { [weak self] success, error in
            guard let self else { return }
            self.queue.async {
                if success {
                    self.finishSave(removing: count, saved: samples.count)
                } else if Self.isRetryable(error) {
                    self.isFlushing = false
                    self.logger.info("HealthKit not writable now (\(error?.localizedDescription ?? "unknown")); keeping \(count) staged records")
                } else {
                    // One bad sample fails the whole batch; save one by one and drop the rejects.
                    self.saveIndividually(samples, stagedCount: count)
                }
            }
        }
    }

    /// Deletes this app's stage-less "asleep" samples within the given sleep sessions.
    /// `completion` runs on `queue`; true means HealthKit was not writable.
    private func deleteBasicSleep(overlapping sessions: [ClosedRange<Date>], completion: @escaping (Bool) -> Void) {
        let group = DispatchGroup()
        var mustRetry = false
        for session in sessions {
            let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [
                HKQuery.predicateForSamples(withStart: session.lowerBound, end: session.upperBound, options: []),
                HKQuery.predicateForCategorySamples(with: .equalTo, value: HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue),
            ])
            group.enter()
            healthStore.deleteObjects(of: HKCategoryType(.sleepAnalysis), predicate: predicate) { success, deleted, error in
                self.queue.async {
                    if success, deleted > 0 {
                        self.logger.info("Replaced \(deleted) basic sleep samples with staged sleep")
                    } else if !success, Self.isRetryable(error) {
                        mustRetry = true
                    }
                    group.leave()
                }
            }
        }
        group.notify(queue: queue) { completion(mustRetry) }
    }

    /// Sleep sessions (in-bed records) in a chunk, which come from the phone relay.
    static func sleepSessionRanges(_ records: [HealthRecord]) -> [ClosedRange<Date>] {
        records
            .filter { $0.type == HealthProtocol.typeInBed && $0.end > $0.start }
            .map { Date(timeIntervalSince1970: TimeInterval($0.start))...Date(timeIntervalSince1970: TimeInterval($0.end)) }
    }

    private func saveIndividually(_ samples: [HKSample], stagedCount: Int) {
        let group = DispatchGroup()
        var saved = 0
        var mustRetry = false
        for sample in samples {
            group.enter()
            healthStore.save(sample) { success, error in
                self.queue.async {
                    if success {
                        saved += 1
                    } else if Self.isRetryable(error) {
                        mustRetry = true
                    } else {
                        self.logger.error("Dropping health sample \(sample.sampleType.identifier): \(error?.localizedDescription ?? "unknown")")
                    }
                    group.leave()
                }
            }
        }
        group.notify(queue: queue) {
            if mustRetry {
                self.isFlushing = false
            } else {
                self.finishSave(removing: stagedCount, saved: saved)
            }
        }
    }

    private func finishSave(removing count: Int, saved: Int) {
        if flushGeneration == stagingGeneration {
            removeStaged(count: count)
        } // else the staging file was discarded while saving; it holds only newer records now
        isFlushing = false
        logger.info("Saved \(saved) health samples to HealthKit")
        flushOnQueue() // continue with the next chunk, if any
    }

    /// Only a locked phone is worth waiting for. Anything else (including a type the
    /// user denied after the access check) is dropped, so one sample can't block the queue.
    private static func isRetryable(_ error: Error?) -> Bool {
        (error as? HKError)?.code == .errorDatabaseInaccessible
    }

    private enum SampleAccess { case allowed, denied, undetermined }

    /// Whether the user lets WearBridge write this sample. A blood pressure
    /// correlation is checked through its systolic and diastolic samples.
    private func access(for sample: HKSample) -> SampleAccess {
        let types: [HKObjectType]
        if let correlation = sample as? HKCorrelation {
            types = correlation.objects.map { $0.sampleType }
        } else {
            types = [sample.sampleType]
        }
        var result = SampleAccess.allowed
        for type in types {
            switch healthStore.authorizationStatus(for: type) {
            case .sharingAuthorized:
                continue
            case .sharingDenied:
                return .denied
            default:
                result = .undetermined
            }
        }
        return result
    }

    // MARK: - Mapping

    /// Samsung Health sleep score (0...100), stored on the "In Bed" sample. Health doesn't display it.
    static let sleepScoreMetadataKey = "WearBridgeSleepScore"

    static func sleepAnalysisValue(forStage stage: Double) -> HKCategoryValueSleepAnalysis? {
        switch stage {
        case Double(HealthProtocol.sleepStageUnspecified): return .asleepUnspecified
        case Double(HealthProtocol.sleepStageAwake): return .awake
        case Double(HealthProtocol.sleepStageLight): return .asleepCore
        case Double(HealthProtocol.sleepStageDeep): return .asleepDeep
        case Double(HealthProtocol.sleepStageREM): return .asleepREM
        default: return nil
        }
    }

    static func syncIdentifier(for record: HealthRecord) -> String {
        "\(syncIdentifierPrefix).\(record.type).\(record.start).\(record.end)"
    }

    /// Converts a record into the matching HealthKit sample, or nil if it can't be represented.
    static func makeSample(_ record: HealthRecord) -> HKSample? {
        guard record.end >= record.start, record.value.isFinite, record.value >= 0 else { return nil }
        let start = Date(timeIntervalSince1970: TimeInterval(record.start))
        let end = Date(timeIntervalSince1970: TimeInterval(record.end))
        let metadata: [String: Any] = [
            HKMetadataKeySyncIdentifier: syncIdentifier(for: record),
            HKMetadataKeySyncVersion: 1,
        ]

        func quantity(_ identifier: HKQuantityTypeIdentifier, _ unit: HKUnit) -> HKSample {
            HKQuantitySample(
                type: HKQuantityType(identifier),
                quantity: HKQuantity(unit: unit, doubleValue: record.value),
                start: start,
                end: end,
                metadata: metadata
            )
        }

        switch record.type {
        case HealthProtocol.typeHeartRate:
            guard record.value > 0 else { return nil }
            return quantity(.heartRate, HKUnit.count().unitDivided(by: .minute()))
        case HealthProtocol.typeSteps:
            return quantity(.stepCount, .count())
        case HealthProtocol.typeDistance:
            return quantity(.distanceWalkingRunning, .meter())
        case HealthProtocol.typeFloors:
            return quantity(.flightsClimbed, .count())
        case HealthProtocol.typeSleep:
            guard record.end > record.start, let stage = sleepAnalysisValue(forStage: record.value) else { return nil }
            return HKCategorySample(
                type: HKCategoryType(.sleepAnalysis),
                value: stage.rawValue,
                start: start,
                end: end,
                metadata: metadata
            )
        case HealthProtocol.typeInBed:
            guard record.end > record.start, record.value <= 100 else { return nil }
            var inBedMetadata = metadata
            if record.value > 0 {
                inBedMetadata[sleepScoreMetadataKey] = Int(record.value)
            }
            return HKCategorySample(
                type: HKCategoryType(.sleepAnalysis),
                value: HKCategoryValueSleepAnalysis.inBed.rawValue,
                start: start,
                end: end,
                metadata: inBedMetadata
            )
        case HealthProtocol.typeOxygenSaturation:
            guard record.value > 0, record.value <= 100 else { return nil }
            return HKQuantitySample(
                type: HKQuantityType(.oxygenSaturation),
                quantity: HKQuantity(unit: .percent(), doubleValue: record.value / 100), // HealthKit percent is 0...1
                start: start,
                end: end,
                metadata: metadata
            )
        case HealthProtocol.typeBloodPressure:
            let (systolic, diastolic) = HealthProtocol.splitBloodPressure(record.value)
            guard systolic > 0, diastolic > 0 else { return nil }
            let identifier = syncIdentifier(for: record)
            func component(_ type: HKQuantityTypeIdentifier, _ mmHg: Int, _ suffix: String) -> HKQuantitySample {
                HKQuantitySample(
                    type: HKQuantityType(type),
                    quantity: HKQuantity(unit: .millimeterOfMercury(), doubleValue: Double(mmHg)),
                    start: start,
                    end: end,
                    metadata: [HKMetadataKeySyncIdentifier: "\(identifier).\(suffix)", HKMetadataKeySyncVersion: 1]
                )
            }
            return HKCorrelation(
                type: HKCorrelationType(.bloodPressure),
                start: start,
                end: end,
                objects: [
                    component(.bloodPressureSystolic, systolic, "systolic"),
                    component(.bloodPressureDiastolic, diastolic, "diastolic"),
                ],
                metadata: metadata
            )
        default:
            return nil
        }
    }
}
