import Foundation

/// One health measurement received from the watch.
/// Mirrors `HealthRecord` in the Wear OS app.
struct HealthRecord: Equatable {
    let type: UInt8
    /// Unix seconds. Equal to `end` for instantaneous samples (heart rate).
    let start: UInt32
    let end: UInt32
    let value: Double
}

/// Wire format of the Health Data characteristic. Mirrors `HealthProtocol.kt`;
/// keep both in sync.
///
///     Batch  := version:u8 (=1) | count:u8 (1...255) | Record × count
///     Record := type:u8 | start:u32 | end:u32 | value:f64      (little-endian, 17 bytes)
enum HealthProtocol {
    static let version: UInt8 = 1
    static let headerSize = 2
    static let recordSize = 17

    static let typeHeartRate: UInt8 = 1 // beats per minute, instantaneous
    static let typeSteps: UInt8 = 2     // count over the interval
    static let typeDistance: UInt8 = 3  // meters over the interval
    static let typeFloors: UInt8 = 4    // floors climbed over the interval
    static let typeSleep: UInt8 = 5     // asleep interval, value unused

    /// Decodes a batch written by the watch. Returns nil if it is malformed or
    /// uses a protocol version this app does not know.
    static func decodeBatch(_ data: Data) -> [HealthRecord]? {
        let bytes = [UInt8](data)
        guard bytes.count >= headerSize, bytes[0] == version else { return nil }
        let count = Int(bytes[1])
        guard count > 0, bytes.count == headerSize + count * recordSize else { return nil }
        return decodeRecords(bytes, offset: headerSize, count: count)
    }

    /// Encodes a batch (used by tests; the watch is the only real writer).
    static func encodeBatch(_ records: [HealthRecord]) -> Data {
        precondition((1...255).contains(records.count), "Batch must hold 1...255 records")
        var bytes: [UInt8] = [version, UInt8(records.count)]
        records.forEach { append($0, to: &bytes) }
        return Data(bytes)
    }

    /// Records back to back, without a header (the on-disk staging format).
    static func encodeRecords(_ records: [HealthRecord]) -> Data {
        var bytes: [UInt8] = []
        bytes.reserveCapacity(records.count * recordSize)
        records.forEach { append($0, to: &bytes) }
        return Data(bytes)
    }

    static func decodeRecords(_ bytes: [UInt8], offset: Int = 0, count: Int) -> [HealthRecord] {
        (0..<count).map { index in
            let base = offset + index * recordSize
            return HealthRecord(
                type: bytes[base],
                start: readUInt32(bytes, at: base + 1),
                end: readUInt32(bytes, at: base + 5),
                value: Double(bitPattern: readUInt64(bytes, at: base + 9))
            )
        }
    }

    private static func append(_ record: HealthRecord, to bytes: inout [UInt8]) {
        bytes.append(record.type)
        appendLittleEndian(UInt64(record.start), byteCount: 4, to: &bytes)
        appendLittleEndian(UInt64(record.end), byteCount: 4, to: &bytes)
        appendLittleEndian(record.value.bitPattern, byteCount: 8, to: &bytes)
    }

    private static func appendLittleEndian(_ value: UInt64, byteCount: Int, to bytes: inout [UInt8]) {
        for index in 0..<byteCount {
            bytes.append(UInt8(truncatingIfNeeded: value >> (8 * UInt64(index))))
        }
    }

    private static func readUInt32(_ bytes: [UInt8], at index: Int) -> UInt32 {
        UInt32(truncatingIfNeeded: readLittleEndian(bytes, at: index, byteCount: 4))
    }

    private static func readUInt64(_ bytes: [UInt8], at index: Int) -> UInt64 {
        readLittleEndian(bytes, at: index, byteCount: 8)
    }

    private static func readLittleEndian(_ bytes: [UInt8], at index: Int, byteCount: Int) -> UInt64 {
        var value: UInt64 = 0
        for offset in 0..<byteCount {
            value |= UInt64(bytes[index + offset]) << (8 * UInt64(offset))
        }
        return value
    }
}
