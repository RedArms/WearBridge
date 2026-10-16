import HealthKit
import XCTest
@testable import AncsBridge

final class AncsBridgeTests: XCTestCase {
    func testPlaceholder() {
        XCTAssertTrue(true)
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
