import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit
import XCTest
@testable import InkOTP

final class QRPhotoTests: XCTestCase {
    // The same published seed and payload used by Android's QrImageReaderTest.
    private let content = "otpauth://totp/Test:sample?secret=JBSWY3DPEHPK3PXP&issuer=Test"

    @MainActor
    func testRecognizesRealPNGAtOrdinaryAndDownsampledSizes() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }

        for edge in [320, 2560] {
            let url = directory.appendingPathComponent("verification-\(edge).png")
            try qrPNG(edge: edge).write(to: url)
            let photo = try QRPhoto.read(url)
            XCTAssertEqual(try photo.decode(), content, "\(edge)-pixel PNG")
        }
    }

    @MainActor
    func testReturnsNoCodeForBlankPNG() throws {
        XCTAssertNil(try QRPhoto(bytes: renderPNG(edge: 320)).decode())
    }

    @MainActor
    func testRejectsNonImageAndTruncatedPNGWithoutDisclosingPayload() throws {
        let png = try qrPNG(edge: 320)
        for data in [Data(content.utf8), Data(png.prefix(png.count / 2))] {
            XCTAssertThrowsError(try QRPhoto(bytes: data).decode()) { error in
                let failure = error as NSError
                XCTAssertEqual(failure.domain, NSCocoaErrorDomain)
                XCTAssertEqual(failure.code, CocoaError.Code.fileReadCorruptFile.rawValue)
                XCTAssertFalse(failure.localizedDescription.contains("JBSWY3DPEHPK3PXP"))
            }
        }
    }

    func testRejectsBytesAboveTheSharedPhotoLimitBeforeDecoding() {
        let oversized = Data(count: 16 * 1024 * 1024 + 1)
        XCTAssertThrowsError(try QRPhoto(bytes: oversized).decode()) { error in
            XCTAssertEqual((error as? CocoaError)?.code, .fileReadTooLarge)
        }
    }

    @MainActor
    private func qrPNG(edge: Int) throws -> Data {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(content.utf8)
        filter.correctionLevel = "M"
        let qr = try XCTUnwrap(filter.outputImage)
        let context = CIContext(options: [.useSoftwareRenderer: true])
        let modules = try XCTUnwrap(context.createCGImage(qr, from: qr.extent))
        let pixelsPerModule = edge / (max(modules.width, modules.height) + 8)
        return renderPNG(edge: edge) { graphics in
            // Give the finder patterns at least four white modules on every side.
            let width = modules.width * pixelsPerModule
            let height = modules.height * pixelsPerModule
            let imageRect = CGRect(
                x: CGFloat((edge - width) / 2), y: CGFloat((edge - height) / 2),
                width: CGFloat(width), height: CGFloat(height)
            )
            graphics.interpolationQuality = .none
            UIImage(cgImage: modules).draw(in: imageRect)
        }
    }

    @MainActor
    private func renderPNG(edge: Int, draw: (CGContext) -> Void = { _ in }) -> Data {
        let size = CGSize(width: CGFloat(edge), height: CGFloat(edge))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).pngData { renderer in
            renderer.cgContext.setFillColor(UIColor.white.cgColor)
            renderer.cgContext.fill(CGRect(origin: .zero, size: size))
            draw(renderer.cgContext)
        }
    }
}
