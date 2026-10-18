import CryptoKit
import HealthKit
import XCTest
@testable import AncsBridge

final class AncsBridgeTests: XCTestCase {

    // MARK: - Quick reply URL

    func testQuickReplyURLEncodesQuerySeparators() throws {
        let url = try XCTUnwrap(QuickReplyLauncher.shortcutURL(for: "Mom & Dad|On my way = 5 min + traffic?"))
        XCTAssertEqual(url.absoluteString,
                       "shortcuts://run-shortcut?name=WearBridge%20Reply&input=text&text=Mom%20%26%20Dad%7COn%20my%20way%20%3D%205%20min%20%2B%20traffic%3F")
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(items.map(\.name), ["name", "input", "text"])
        XCTAssertEqual(items.last?.value, "Mom & Dad|On my way = 5 min + traffic?")
    }

    func testQuickReplyURLKeepsUnicode() throws {
        let url = try XCTUnwrap(QuickReplyLauncher.shortcutURL(for: "Léa|J'arrive 🚗"))
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(items.last?.value, "Léa|J'arrive 🚗")
    }

    // MARK: - Call state

    private let ringing = CallStateObserver.CallInfo(isOutgoing: false, hasConnected: false, hasEnded: false)
    private let answered = CallStateObserver.CallInfo(isOutgoing: false, hasConnected: true, hasEnded: false)
    private let ended = CallStateObserver.CallInfo(isOutgoing: false, hasConnected: true, hasEnded: true)
    private let outgoing = CallStateObserver.CallInfo(isOutgoing: true, hasConnected: true, hasEnded: false)

    func testIncomingCallLifecycle() {
        let idle = BLEConstants.callStateIdle
        XCTAssertEqual(CallStateObserver.state(of: [ringing], previous: idle), BLEConstants.callStateRinging)
        XCTAssertEqual(CallStateObserver.state(of: [answered], previous: BLEConstants.callStateRinging), BLEConstants.callStateActive)
        XCTAssertEqual(CallStateObserver.state(of: [ended], previous: BLEConstants.callStateActive), BLEConstants.callStateEnded)
        XCTAssertEqual(CallStateObserver.state(of: [], previous: BLEConstants.callStateEnded), BLEConstants.callStateEnded)
    }

    func testCallWaitingKeepsTheRingingCall() {
        // Ending the active call while a second one rings must not dismiss the ringing one.
        XCTAssertEqual(CallStateObserver.state(of: [ended, ringing], previous: BLEConstants.callStateRinging),
                       BLEConstants.callStateRinging)
        XCTAssertEqual(CallStateObserver.state(of: [answered, ringing], previous: BLEConstants.callStateActive),
                       BLEConstants.callStateRinging)
        // Declining the waiting call goes back to the active one.
        XCTAssertEqual(CallStateObserver.state(of: [answered, ended], previous: BLEConstants.callStateRinging),
                       BLEConstants.callStateActive)
    }

    func testOutgoingCallsAreIgnored() {
        XCTAssertEqual(CallStateObserver.state(of: [outgoing], previous: BLEConstants.callStateIdle), BLEConstants.callStateIdle)
        XCTAssertEqual(CallStateObserver.state(of: [outgoing, ringing], previous: BLEConstants.callStateIdle),
                       BLEConstants.callStateRinging)
        XCTAssertEqual(CallStateObserver.state(of: [outgoing, ended], previous: BLEConstants.callStateRinging),
                       BLEConstants.callStateEnded)
    }
}

final class HealthProtocolTests: XCTestCase {

    private let heartRate = HealthRecord(type: HealthProtocol.typeHeartRate, start: 1_760_000_000, end: 1_760_000_000, value: 72.5)
    private let steps = HealthRecord(type: HealthProtocol.typeSteps, start: 1_760_000_000, end: 1_760_000_060, value: 123)

    /// Same vector as HealthProtocolTest on the Wear OS side.
    private let golden = Data([
        0x01, 0x02,
        0x01, 0x00, 0x78, 0xE7, 0x68, 0x00, 0x78, 0xE7, 0x68, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x52, 0x40,
        0x02, 0x00, 0x78, 0xE7, 0x68, 0x3C, 0x78, 0xE7, 0x68, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC0, 0x5E, 0x40,
    ])

    func testHealthBatchGoldenVector() {
        XCTAssertEqual(HealthProtocol.decodeBatch(golden), [heartRate, steps])
        XCTAssertEqual(HealthProtocol.encodeBatch([heartRate, steps]), golden)
    }

    func testUnsignedTimestampsRoundTrip() {
        let late = HealthRecord(type: HealthProtocol.typeSleep, start: 3_000_000_000, end: 3_000_028_800, value: 0)
        XCTAssertEqual(HealthProtocol.decodeBatch(HealthProtocol.encodeBatch([late])), [late])
    }

    func testMalformedBatchesAreRejected() {
        XCTAssertNil(HealthProtocol.decodeBatch(Data()))
        XCTAssertNil(HealthProtocol.decodeBatch(Data([0x01])))
        XCTAssertNil(HealthProtocol.decodeBatch(Data([0x02, 0x01]) + Data(count: 17)))
        XCTAssertNil(HealthProtocol.decodeBatch(Data([0x01, 0x00])))
        XCTAssertNil(HealthProtocol.decodeBatch(golden.dropLast()))
        XCTAssertNil(HealthProtocol.decodeBatch(golden + Data([0x00])))
    }

    func testRecordsMapToHealthKitSamples() throws {
        let hr = try XCTUnwrap(HealthKitWriter.makeSample(heartRate) as? HKQuantitySample)
        XCTAssertEqual(hr.quantityType, HKQuantityType(.heartRate))
        XCTAssertEqual(hr.quantity.doubleValue(for: HKUnit.count().unitDivided(by: .minute())), 72.5)
        XCTAssertEqual(hr.metadata?[HKMetadataKeySyncIdentifier] as? String, "wearbridge.1.1760000000.1760000000")

        let stepSample = try XCTUnwrap(HealthKitWriter.makeSample(steps) as? HKQuantitySample)
        XCTAssertEqual(stepSample.quantityType, HKQuantityType(.stepCount))
        XCTAssertEqual(stepSample.endDate.timeIntervalSince(stepSample.startDate), 60)

        let sleep = HealthRecord(type: HealthProtocol.typeSleep, start: 1_760_000_000, end: 1_760_025_200, value: 0)
        let sleepSample = try XCTUnwrap(HealthKitWriter.makeSample(sleep) as? HKCategorySample)
        XCTAssertEqual(sleepSample.value, HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue)
    }

    func testSleepStagesMapToHealthKitValues() throws {
        let expected: [(Int, HKCategoryValueSleepAnalysis)] = [
            (HealthProtocol.sleepStageUnspecified, .asleepUnspecified),
            (HealthProtocol.sleepStageAwake, .awake),
            (HealthProtocol.sleepStageLight, .asleepCore),
            (HealthProtocol.sleepStageDeep, .asleepDeep),
            (HealthProtocol.sleepStageREM, .asleepREM),
            (HealthProtocol.sleepStageWatch, .asleepUnspecified),
        ]
        for (stage, value) in expected {
            let record = HealthRecord(type: HealthProtocol.typeSleep, start: 100, end: 200, value: Double(stage))
            let sample = try XCTUnwrap(HealthKitWriter.makeSample(record) as? HKCategorySample)
            XCTAssertEqual(sample.value, value.rawValue)
        }
        XCTAssertNil(HealthKitWriter.makeSample(HealthRecord(type: HealthProtocol.typeSleep, start: 100, end: 200, value: 9)))
    }

    func testInBedCarriesTheSleepScore() throws {
        let record = HealthRecord(type: HealthProtocol.typeInBed, start: 100, end: 28_900, value: 82)
        let sample = try XCTUnwrap(HealthKitWriter.makeSample(record) as? HKCategorySample)
        XCTAssertEqual(sample.value, HKCategoryValueSleepAnalysis.inBed.rawValue)
        XCTAssertEqual(sample.metadata?[HealthKitWriter.sleepScoreMetadataKey] as? Int, 82)
        XCTAssertEqual(HealthKitWriter.sleepSessionRanges([record, steps]).count, 1)
    }

    func testOxygenSaturationUsesAFraction() throws {
        let record = HealthRecord(type: HealthProtocol.typeOxygenSaturation, start: 100, end: 100, value: 96)
        let sample = try XCTUnwrap(HealthKitWriter.makeSample(record) as? HKQuantitySample)
        XCTAssertEqual(sample.quantity.doubleValue(for: .percent()), 0.96, accuracy: 0.0001)
    }

    func testBloodPressureBecomesACorrelation() throws {
        let record = HealthRecord(type: HealthProtocol.typeBloodPressure, start: 100, end: 100, value: 121_080)
        let correlation = try XCTUnwrap(HealthKitWriter.makeSample(record) as? HKCorrelation)
        let systolic = try XCTUnwrap(correlation.objects(for: HKQuantityType(.bloodPressureSystolic)).first as? HKQuantitySample)
        let diastolic = try XCTUnwrap(correlation.objects(for: HKQuantityType(.bloodPressureDiastolic)).first as? HKQuantitySample)
        XCTAssertEqual(systolic.quantity.doubleValue(for: .millimeterOfMercury()), 121)
        XCTAssertEqual(diastolic.quantity.doubleValue(for: .millimeterOfMercury()), 80)
        XCTAssertNil(HealthKitWriter.makeSample(HealthRecord(type: HealthProtocol.typeBloodPressure, start: 1, end: 1, value: 120)))
    }

    func testUnusableRecordsAreSkipped() {
        XCTAssertNil(HealthKitWriter.makeSample(HealthRecord(type: 99, start: 1, end: 2, value: 1)))
        XCTAssertNil(HealthKitWriter.makeSample(HealthRecord(type: HealthProtocol.typeSteps, start: 10, end: 5, value: 1)))
        XCTAssertNil(HealthKitWriter.makeSample(HealthRecord(type: HealthProtocol.typeHeartRate, start: 1, end: 1, value: 0)))
        XCTAssertNil(HealthKitWriter.makeSample(HealthRecord(type: HealthProtocol.typeSleep, start: 1, end: 1, value: 0)))
    }
}

final class RelayMailboxTests: XCTestCase {
    private let key = Data((0..<32).map { UInt8($0) })

    /// Same vectors as MailboxCodecTest.kt (computed independently with Python's AES-GCM).
    private let goldenBatch = "AAECAwQFBgcICQoLRgXWG8XkwBqNOXDjsZGfBYPWhzTwWw08OmedYnVVeFVpEK78r8HSxjROJLxyLPNBnLlpiyWljhVc"
    private let goldenAck = "DA0ODxAREhMUFRYXmvxp3392_Fs3Kki6E7A7A6xfCGEZjAhnT7LU"

    func testOpensTheGoldenBatch() throws {
        let (seq, records) = try XCTUnwrap(RelayMailbox.openBatch(goldenBatch, key: key))
        XCTAssertEqual(seq, 7)
        XCTAssertEqual(records, [
            HealthRecord(type: HealthProtocol.typeHeartRate, start: 1_760_000_000, end: 1_760_000_000, value: 72.5),
            HealthRecord(type: HealthProtocol.typeSteps, start: 1_760_000_000, end: 1_760_000_060, value: 123),
        ])
    }

    func testSealsTheGoldenAck() throws {
        let nonce = try AES.GCM.Nonce(data: Data((12..<24).map { UInt8($0) }))
        XCTAssertEqual(try RelayMailbox.sealAck([7, 8], key: key, nonce: nonce), goldenAck)
    }

    func testRejectsTheWrongKeyAndTampering() {
        XCTAssertNil(RelayMailbox.openBatch(goldenBatch, key: Data(repeating: 7, count: 32)))
        XCTAssertNil(RelayMailbox.openBatch(String(goldenBatch.dropLast()) + "A", key: key))
        XCTAssertNil(RelayMailbox.openBatch(goldenAck, key: key)) // an ack isn't a batch
    }

    func testParsesThePairingLink() throws {
        let link = "wearbridge://relay?s=https%3A%2F%2Fntfy.sh&t=wb-AbC_d-123&k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        let pairing = try XCTUnwrap(RelayMailbox.parse(try XCTUnwrap(URL(string: link))))
        XCTAssertEqual(pairing.server.absoluteString, "https://ntfy.sh")
        XCTAssertEqual(pairing.topic, "wb-AbC_d-123")
        XCTAssertEqual(pairing.key, key)
        XCTAssertNil(RelayMailbox.parse(try XCTUnwrap(URL(string: "wearbridge://relay?s=https%3A%2F%2Fntfy.sh&t=wb&k=AAAA"))))
    }
}
