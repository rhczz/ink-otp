import CoreFoundation
import Foundation

typealias InterchangeObject = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func requiredText(_ key: String) throws -> String {
        guard let value = try optionalText(key) else { throw InterchangeFailure(.malformedFile) }
        return value
    }

    func optionalText(_ key: String) throws -> String? {
        guard let value = self[key], !(value is NSNull) else { return nil }
        guard let string = value as? String else { throw InterchangeFailure(.malformedFile) }
        return string
    }

    func object(_ key: String) throws -> InterchangeObject {
        guard let value = self[key] as? InterchangeObject else { throw InterchangeFailure(.malformedFile) }
        return value
    }

    func array(_ key: String) throws -> [Any] {
        guard let value = self[key] as? [Any] else { throw InterchangeFailure(.malformedFile) }
        return value
    }

    func optionalInt64(_ key: String) throws -> Int64? {
        guard let value = self[key], !(value is NSNull) else { return nil }
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              let integer = Int64(number.stringValue) else { throw InterchangeFailure(.malformedFile) }
        return integer
    }

    func optionalInt(_ key: String) throws -> Int? {
        guard let value = try optionalInt64(key) else { return nil }
        guard let integer = Int32(exactly: value) else { throw InterchangeFailure(.malformedFile) }
        return Int(integer)
    }

    func integer(_ key: String) throws -> Int {
        guard let value = try optionalInt(key) else { throw InterchangeFailure(.malformedFile) }
        return value
    }

    func hasExtraKeys(_ expected: Set<String>) -> Bool {
        keys.contains { !expected.contains($0) }
    }
}

enum InterchangeJSON {
    static func parse(_ text: String) throws -> Any {
        var depth = 0
        var containers = 0
        var separators = 0
        var insideString = false
        var escaped = false
        for byte in text.utf8 {
            if insideString {
                if escaped {
                    escaped = false
                } else if byte == 92 {
                    escaped = true
                } else if byte == 34 {
                    insideString = false
                }
                continue
            }
            switch byte {
            case 34:
                insideString = true
            case 91, 123:
                depth += 1
                containers += 1
                guard depth <= 32, containers <= 100_000 else { throw InterchangeFailure(.malformedFile) }
            case 93, 125:
                depth -= 1
            case 44, 58:
                separators += 1
                guard separators <= 500_000 else { throw InterchangeFailure(.malformedFile) }
            default:
                break
            }
        }
        return try JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed])
    }

    static func encode(_ object: Any) throws -> Data {
        try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
    }

    static func text(_ data: Data) throws -> String {
        guard data.count <= InterchangeCodec.maxFileBytes else { throw InterchangeFailure(.fileTooLarge) }
        guard let string = String(data: data, encoding: .utf8) else { throw InterchangeFailure(.malformedFile) }
        return string.first == "\u{FEFF}" ? String(string.dropFirst()) : string
    }
}
