import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import ImageIO
import UniformTypeIdentifiers

struct URIExample: Decodable {
    let name: String
    let input: String
    let accept: Bool
}

struct MigrationExamples: Decodable {
    struct Example: Decodable {
        let name: String
        let uri: String
    }
    let cases: [Example]
}

struct FixtureError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

func makeCode(named name: String, from examples: [URIExample], using renderer: CIContext) throws -> CGImage {
    let matches = examples.filter { $0.name == name && $0.accept }
    guard matches.count == 1, let example = matches.first else {
        throw FixtureError(message: "Expected exactly one accepted public URI example named \(name)")
    }
    return try makeCode(example.input, name: name, using: renderer)
}

func makeCode(_ uri: String, name: String, using renderer: CIContext) throws -> CGImage {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(uri.utf8)
    filter.correctionLevel = "M"
    guard let qr = filter.outputImage,
          let bitmap = renderer.createCGImage(qr, from: qr.extent) else {
        throw FixtureError(message: "Unable to render public QR example \(name)")
    }
    return bitmap
}

func writeImage(_ codes: [CGImage], tile: Int, scale: Int, to url: URL) throws {
    let width = tile * max(1, codes.count)
    guard let canvas = CGContext(data: nil, width: width, height: tile, bitsPerComponent: 8,
                                 bytesPerRow: 0, space: CGColorSpaceCreateDeviceGray(),
                                 bitmapInfo: CGImageAlphaInfo.none.rawValue) else {
        throw FixtureError(message: "Unable to allocate \(url.lastPathComponent)")
    }
    canvas.setFillColor(gray: 1, alpha: 1)
    canvas.fill(CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(tile)))
    canvas.interpolationQuality = .none
    canvas.setShouldAntialias(false)
    for (index, code) in codes.enumerated() {
        let side = code.width * scale
        let offset = (tile - side) / 2
        canvas.draw(code, in: CGRect(x: CGFloat(index * tile + offset), y: CGFloat(offset),
                                     width: CGFloat(side), height: CGFloat(side)))
    }
    guard let bitmap = canvas.makeImage(),
          let output = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw FixtureError(message: "Unable to create \(url.lastPathComponent)")
    }
    CGImageDestinationAddImage(output, bitmap, nil)
    guard CGImageDestinationFinalize(output) else {
        throw FixtureError(message: "Unable to save \(url.lastPathComponent)")
    }
}

do {
    guard CommandLine.arguments.count == 4 else {
        throw FixtureError(message: "Usage: swift make-ui-qr-fixtures.swift <otpauth-cases.json> <google-transfer-cases.json> <output-directory>")
    }
    let spec = URL(fileURLWithPath: CommandLine.arguments[1])
    let googleSpec = URL(fileURLWithPath: CommandLine.arguments[2])
    let directory = URL(fileURLWithPath: CommandLine.arguments[3], isDirectory: true)
    let examples = try JSONDecoder().decode([URIExample].self, from: Data(contentsOf: spec))
    let renderer = CIContext(options: [.useSoftwareRenderer: true])
    let primary = try makeCode(named: "standard_totp_uri", from: examples, using: renderer)
    let second = try makeCode(named: "account_only_no_issuer", from: examples, using: renderer)
    let scale = 8
    // Leave a white quiet zone of at least eight modules on all four sides of each code.
    let tile = (max(primary.width, second.width) + 16) * scale
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    try writeImage([primary], tile: tile, scale: scale, to: directory.appendingPathComponent("qr-single.png"))
    try writeImage([], tile: tile, scale: scale, to: directory.appendingPathComponent("qr-blank.png"))
    try writeImage([primary, second], tile: tile, scale: scale, to: directory.appendingPathComponent("qr-multiple.png"))
    let migration = try JSONDecoder().decode(MigrationExamples.self, from: Data(contentsOf: googleSpec))
    guard let firstPage = migration.cases.first(where: { $0.name == "first_page_defaults" }),
          let secondPage = migration.cases.first(where: { $0.name == "second_page_urlsafe" }) else {
        throw FixtureError(message: "Missing the public two-page Google Authenticator transfer")
    }
    let transferCode = try makeCode(secondPage.uri, name: secondPage.name, using: renderer)
    let transferTile = (transferCode.width + 16) * scale
    try writeImage([transferCode], tile: transferTile, scale: scale, to: directory.appendingPathComponent("google-page-two.png"))
    try firstPage.uri.write(to: directory.appendingPathComponent("google-page-one.txt"), atomically: true, encoding: .utf8)
} catch {
    FileHandle.standardError.write(Data("\(error.localizedDescription)\n".utf8))
    exit(1)
}
