import CoreFoundation
import Foundation

typealias JSONObject = [String: Any]

enum InterchangeJSON {
    static func text(_ bytes: Data, allowBOM: Bool = true) throws -> String {
        guard bytes.count <= Interchange.maximumFileBytes else { throw InterchangeError(.fileTooLarge) }
        let bom = [UInt8(0xEF), 0xBB, 0xBF]
        let includesBOM = bytes.starts(with: bom)
        guard allowBOM || !includesBOM,
              let text = String(bytes: bytes.dropFirst(includesBOM ? bom.count : 0), encoding: .utf8) else {
            throw InterchangeError(.malformedFile)
        }
        return text
    }

    static func parse(_ bytes: Data, allowBOM: Bool = true, interchangeBudget: Bool = true) throws -> Any {
        try parse(text(bytes, allowBOM: allowBOM), interchangeBudget: interchangeBudget)
    }

    static func parse(_ text: String, interchangeBudget: Bool = true) throws -> Any {
        // Keep the Foundation parser below the shared nesting, container and token budgets.
        var depth = 0
        var containers = 0
        var separators = 0
        var insideString = false
        var escaped = false
        var previousToken: UInt8?
        for byte in text.utf8 {
            if insideString {
                if escaped { escaped = false }
                else if byte == 92 { escaped = true }
                else if byte == 34 { insideString = false }
                continue
            }
            switch byte {
            case 9, 10, 13, 32:
                continue
            case 34:
                insideString = true
            case 123, 91:
                depth += 1
                containers += 1
                guard !interchangeBudget || (depth <= 32 && containers <= 100_000) else {
                    throw InterchangeError(.malformedFile)
                }
            case 125, 93:
                depth -= 1
                guard depth >= 0, previousToken != 44 else { throw InterchangeError(.malformedFile) }
            case 44, 58:
                separators += 1
                guard !interchangeBudget || separators <= 500_000 else { throw InterchangeError(.malformedFile) }
            default:
                break
            }
            previousToken = byte
        }
        guard depth == 0, !insideString else { throw InterchangeError(.malformedFile) }
        return try JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed])
    }

    static func encode(_ object: Any) throws -> Data {
        try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
    }
}

extension Dictionary where Key == String, Value == Any {
    func requiredObject(_ key: String) throws -> JSONObject {
        guard let object = self[key] as? JSONObject else { throw InterchangeError(.malformedFile) }
        return object
    }

    func requiredArray(_ key: String) throws -> [Any] {
        guard let array = self[key] as? [Any] else { throw InterchangeError(.malformedFile) }
        return array
    }

    func requiredString(_ key: String) throws -> String {
        guard let string = try optionalString(key) else { throw InterchangeError(.malformedFile) }
        return string
    }

    func optionalString(_ key: String) throws -> String? {
        guard let value = self[key], !(value is NSNull) else { return nil }
        guard let string = value as? String else { throw InterchangeError(.malformedFile) }
        return string
    }

    func requiredInteger(_ key: String) throws -> Int64 {
        guard let integer = try optionalInteger(key) else { throw InterchangeError(.malformedFile) }
        return integer
    }

    func optionalInteger(_ key: String) throws -> Int64? {
        guard let value = self[key], !(value is NSNull) else { return nil }
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.objCType.pointee != 100, number.objCType.pointee != 102,
              let integer = Int64(number.stringValue) else { throw InterchangeError(.malformedFile) }
        return integer
    }

    func hasKeysOutside(_ allowed: Set<String>) -> Bool {
        keys.contains { !allowed.contains($0) }
    }
}
