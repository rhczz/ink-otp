import AppKit
import AVFoundation
import CoreMedia
import CoreVideo
import Darwin
import ImageIO
import InkOTPCore
import SwiftUI
import Vision

enum QRFailure: LocalizedError, Sendable {
    case unreadable, unsupported, multiple, noCamera, permission, camera

    var errorDescription: String? {
        switch self {
        case .unreadable: tr("The image could not be opened or is too large.", "无法打开图片，或图片过大。")
        case .unsupported: tr("No verification QR code was found in the image.", "图片中未找到验证码二维码。")
        case .multiple: tr("The image contains multiple verification QR codes. Choose an image with one account.", "图片中含多个验证码二维码。请选择只有一个账号的图片。")
        case .noCamera: tr("No camera is available on this Mac.", "这台 Mac 没有可用的相机。")
        case .permission: tr("Camera access is turned off. Allow InkOTP in System Settings → Privacy & Security → Camera, or use a QR image.", "相机权限已关闭。请在系统设置 → 隐私与安全性 → 相机中允许 InkOTP，或从图片读取二维码。")
        case .camera: tr("The camera is unavailable. Try again or select a QR image.", "相机不可用。请重试或选取二维码图片。")
        }
    }
}

enum QRImage {
    private static let maxFileBytes = 16 * 1_024 * 1_024
    private static let maxSourceEdge = 32_768
    private static let maxSourcePixels = 100_000_000
    private static let maxDecodedEdge = 2_048

    static func read(_ url: URL) throws -> String {
        let didAccess = url.startAccessingSecurityScopedResource()
        defer { if didAccess { url.stopAccessingSecurityScopedResource() } }

        var encoded = try readBoundedImage(url)
        defer { encoded.resetBytes(in: 0..<encoded.count) }
        guard let source = CGImageSourceCreateWithData(encoded as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              let width = properties[kCGImagePropertyPixelWidth as String] as? Int,
              let height = properties[kCGImagePropertyPixelHeight as String] as? Int,
              (1...maxSourceEdge).contains(width), (1...maxSourceEdge).contains(height),
              width * height <= maxSourcePixels,
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxDecodedEdge,
                kCGImageSourceShouldCacheImmediately: true,
              ] as CFDictionary),
              image.width <= maxDecodedEdge, image.height <= maxDecodedEdge else { throw QRFailure.unreadable }

        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        try VNImageRequestHandler(cgImage: image).perform([request])
        let codes = Set((request.results ?? []).compactMap(\.payloadStringValue).filter(isSetup))
        guard let code = codes.first else { throw QRFailure.unsupported }
        guard codes.count == 1 else { throw QRFailure.multiple }
        return code
    }

    private static func readBoundedImage(_ url: URL) throws -> Data {
        let fd = Darwin.open(url.path, O_RDONLY | O_CLOEXEC | O_NONBLOCK | O_NOFOLLOW)
        guard fd >= 0 else { throw QRFailure.unreadable }
        let file = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? file.close() }
        var info = stat()
        guard Darwin.fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG,
              info.st_size > 0, info.st_size <= maxFileBytes else { throw QRFailure.unreadable }

        var bytes = Data()
        while let next = try file.read(upToCount: min(65_536, maxFileBytes + 1 - bytes.count)), !next.isEmpty {
            bytes.append(next)
            guard bytes.count <= maxFileBytes else { throw QRFailure.unreadable }
        }
        return bytes
    }

    fileprivate static func isSetup(_ value: String) -> Bool {
        guard value.utf8.count <= 16_384 else { return false }
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let prefix = text.prefix(20).lowercased()
        if prefix.hasPrefix("otpauth://") || prefix.hasPrefix("otpauth-migration://") { return true }
        guard let data = try? OTP.base32Decode(text) else { return false }
        return (1...255).contains(data.count)
    }
}

@MainActor
struct CameraSheet: View {
    @ObservedObject var store: VaultStore
    @EnvironmentObject private var activity: DesktopActivity
    let close: () -> Void
    let scanned: (String) -> Void
    @State private var capture = CameraCapture()
    @State private var authorization: AVAuthorizationStatus = .notDetermined
    @State private var request: Task<Void, Never>?
    @State private var failure: QRFailure?
    @State private var captureAttempt: UUID?
    @State private var showingPrivacy = false

    var body: some View {
        Group {
            if showingPrivacy {
                PrivacySheet(store: store, close: returnToCamera, allowConsent: false)
            } else {
                scanner.modifier(DesktopPrivacyCurtain())
            }
        }
        .onAppear(perform: refreshAuthorization)
        .onChange(of: activity.isActive) { _, isActive in
            if isActive {
                refreshAuthorization()
            } else {
                stopCapture()
            }
        }
        .onDisappear {
            request?.cancel()
            stopCapture()
        }
    }

    private var scanner: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(tr("Scan Verification QR Code", "扫描验证码二维码"))
                .font(.title2.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            if authorization == .authorized {
                cameraPreview
            } else {
                permissionRationale
            }
            HStack {
                Spacer()
                Button(tr("Cancel", "取消"), action: close).keyboardShortcut(.cancelAction)
            }
        }
        .padding(24)
        .frame(width: 528)
    }

    private var permissionRationale: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(tr("InkOTP uses the camera only while scanning. It reads QR codes on this Mac and saves no camera frames.",
                     "InkOTP 只在扫码时使用相机。二维码在本机识别，应用不会保存相机画面。"),
                  systemImage: "camera")
                .accessibilityIdentifier("camera-rationale")
            Button(tr("Read Privacy Statement", "阅读隐私声明")) {
                stopCapture()
                showingPrivacy = true
            }
            .accessibilityIdentifier("camera-privacy")
            .buttonStyle(.link)
            if authorization == .notDetermined {
                Button(tr("Allow Camera Access", "授予相机权限"), action: requestPermission)
                    .accessibilityIdentifier("camera-request-permission")
                    .buttonStyle(.borderedProminent)
                    .disabled(request != nil)
            } else {
                Text(QRFailure.permission.errorDescription ?? "").foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var cameraPreview: some View {
        if let failure {
            Label(failure.errorDescription ?? "", systemImage: "exclamationmark.triangle")
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 125)
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(.black)
                if captureAttempt != nil {
                    CameraPreview(session: capture.session)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel(tr("Camera preview", "相机预览"))
                    RoundedRectangle(cornerRadius: 14)
                        .strokeBorder(.white.opacity(0.8), lineWidth: 2)
                        .frame(width: 160, height: 160)
                        .accessibilityHidden(true)
                } else {
                    ProgressView().tint(.white).accessibilityLabel(tr("Preparing camera", "正在准备相机"))
                }
            }
            .frame(width: 480, height: 280)
            Text(tr("Center one account’s verification QR code inside the frame.", "请将单个账号的二维码置于识别框内。"))
                .foregroundStyle(.secondary)
        }
    }

    private func refreshAuthorization() {
        authorization = AVCaptureDevice.authorizationStatus(for: .video)
        if authorization == .authorized { startCapture() } else { stopCapture() }
    }

    private func requestPermission() {
        guard request == nil, !showingPrivacy, activity.isActive, store.model != nil,
              authorization == .notDetermined else { return }
        request = Task {
            defer { request = nil }
            _ = await AVCaptureDevice.requestAccess(for: .video)
            guard !Task.isCancelled else { return }
            refreshAuthorization()
        }
    }

    private func returnToCamera() {
        showingPrivacy = false
        refreshAuthorization()
    }

    private func startCapture() {
        guard activity.isActive, store.model != nil, !showingPrivacy, authorization == .authorized,
              failure == nil, captureAttempt == nil else { return }
        let attempt = UUID()
        captureAttempt = attempt
        capture.start { result in
            guard activity.isActive, store.model != nil, captureAttempt == attempt else { return }
            captureAttempt = nil
            switch result {
            case let .success(value): scanned(value)
            case let .failure(error): failure = error
            }
        }
    }

    private func stopCapture() {
        guard captureAttempt != nil else { return }
        captureAttempt = nil
        capture.stop()
    }
}

private struct CameraPreview: NSViewRepresentable {
    let session: AVCaptureSession

    func makeNSView(context: Context) -> NSView {
        let preview = NSView()
        preview.wantsLayer = true
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        preview.layer = layer
        return preview
    }

    func updateNSView(_ view: NSView, context: Context) {}
}

private final class CameraCapture: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "InkOTP.Camera", qos: .userInitiated)
    private var finish: (@MainActor @Sendable (Result<String, QRFailure>) -> Void)?
    private var configured = false
    private var lastFrame: TimeInterval = 0

    func start(_ completion: @escaping @MainActor @Sendable (Result<String, QRFailure>) -> Void) {
        queue.async {
            self.finish = completion
            if !self.configured {
                do { try self.configure() }
                catch {
                    self.complete(.failure((error as? QRFailure) ?? .camera))
                    return
                }
            }
            self.lastFrame = 0
            if !self.session.isRunning { self.session.startRunning() }
            if !self.session.isRunning { self.complete(.failure(.camera)) }
        }
    }

    func stop() {
        queue.async {
            self.finish = nil
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    private func configure() throws {
        guard let device = AVCaptureDevice.default(for: .video) else { throw QRFailure.noCamera }
        let input = try AVCaptureDeviceInput(device: device)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)

        session.beginConfiguration()
        defer { session.commitConfiguration() }
        for preset in [AVCaptureSession.Preset.hd1920x1080, .hd1280x720, .medium] {
            if session.canSetSessionPreset(preset) {
                session.sessionPreset = preset
                break
            }
        }
        guard session.canAddInput(input) else { throw QRFailure.camera }
        session.addInput(input)
        guard session.canAddOutput(output) else {
            session.removeInput(input)
            throw QRFailure.camera
        }
        session.addOutput(output)
        configured = true
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput buffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        let now = ProcessInfo.processInfo.systemUptime
        guard finish != nil, now - lastFrame >= 0.2, let image = CMSampleBufferGetImageBuffer(buffer) else { return }
        let width = CVPixelBufferGetWidth(image)
        let height = CVPixelBufferGetHeight(image)
        guard (1...4096).contains(width), (1...4096).contains(height),
              width * height <= 4_194_304 else { return }
        lastFrame = now
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        guard (try? VNImageRequestHandler(cvPixelBuffer: image).perform([request])) != nil else { return }
        let values = Set((request.results ?? []).compactMap(\.payloadStringValue).filter(QRImage.isSetup))
        if values.count == 1, let value = values.first { complete(.success(value)) }
    }

    private func complete(_ result: Result<String, QRFailure>) {
        let callback = finish
        finish = nil
        queue.async {
            if self.session.isRunning { self.session.stopRunning() }
            Task { @MainActor in callback?(result) }
        }
    }
}
