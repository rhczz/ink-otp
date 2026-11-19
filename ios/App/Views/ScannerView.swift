import AVFoundation
import CoreImage
import CoreML
import CoreTransferable
import ImageIO
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import Vision

@MainActor
struct ScannerView: View {
    let onFound: (String) -> Void
    let onCancel: () -> Void
    let onManual: () -> Void
    @ObservedObject var state: AppState
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @StateObject private var scanner = CameraScanner()
    @State private var choosingPhoto = false
    @State private var readingPolicy = false

    private var cameraActive: Bool { scenePhase == .active && !choosingPhoto && !readingPolicy }

    var body: some View {
        InkPage {
            if scanner.available {
                CameraSurface(session: scanner.session)
                    .frame(height: 300)
                    .clipShape(RoundedRectangle(cornerRadius: Ink.cardRadius))
                    .overlay {
                        RoundedRectangle(cornerRadius: Ink.controlRadius)
                            .strokeBorder(.white, lineWidth: 2)
                            .frame(width: 220, height: 220)
                            .allowsHitTesting(false)
                    }
                    .accessibilityLabel("Camera preview")
                Text("Point your camera at the QR code from your service.")
                    .font(.footnote).foregroundStyle(Ink.secondary)
                if scanner.hasTorch {
                    Button(scanner.torchLit ? "Turn flashlight off" : "Turn flashlight on") {
                        scanner.toggleTorch()
                    }
                    .frame(minHeight: Ink.touch)
                }
            } else if scanner.denied {
                InkHeading(title: "Camera access is off",
                           message: "Allow camera access in Settings, choose a photo, or enter the key manually.",
                           symbol: "camera")
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .buttonStyle(InkButtonStyle())
            } else if scanner.unavailable {
                InkHeading(title: "Camera unavailable",
                           message: "Choose a photo or enter the key manually.",
                           symbol: "camera")
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            }
            QRPhotoButton(state: state, onFound: onFound, onWorkingChange: { choosingPhoto = $0 })
            Button("Privacy notice") { readingPolicy = true }
                .frame(maxWidth: .infinity, minHeight: Ink.touch)
            Button("Enter manually", action: onManual).buttonStyle(InkButtonStyle(prominent: false))
        }
        .navigationTitle("Scan QR code")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Back", action: onCancel) }
        }
        .sheet(isPresented: $readingPolicy) {
            NavigationStack {
                PrivacyPolicyView()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") { readingPolicy = false }
                        }
                    }
            }
        }
        .task(id: cameraActive) {
            if cameraActive {
                scanner.onFound = onFound
                await scanner.start()
            } else {
                scanner.stop()
            }
        }
        .onDisappear { scanner.stop(); scanner.onFound = nil }
    }
}

private final class CameraScanner: NSObject, ObservableObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "inkotp.camera.session", qos: .userInitiated)

    @Published private(set) var available = false
    @Published private(set) var denied = false
    @Published private(set) var unavailable = false
    @Published private(set) var hasTorch = false
    @Published private(set) var torchLit = false

    // Camera configuration stays on the session queue. The metadata delegate and UI use the main queue.
    private var device: AVCaptureDevice?
    private var read = false
    private var accepting = false
    var onFound: ((String) -> Void)?

    @MainActor
    func start() async {
        let authorized: Bool
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: authorized = true
        case .notDetermined: authorized = await AVCaptureDevice.requestAccess(for: .video)
        default: authorized = false
        }
        guard !Task.isCancelled else { return }
        denied = !authorized
        guard authorized else { return }
        accepting = true
        read = false
        queue.async { [self] in
            if device == nil { configure() }
            guard let device else {
                DispatchQueue.main.async { self.unavailable = true; self.available = false }
                return
            }
            if !session.isRunning { session.startRunning() }
            let ready = session.isRunning
            let torch = device.hasTorch
            DispatchQueue.main.async {
                guard self.accepting else { return }
                self.available = ready
                self.unavailable = !ready
                self.hasTorch = torch
            }
        }
    }

    @MainActor
    func stop() {
        accepting = false
        available = false
        torchLit = false
        queue.async { [self] in
            if let device, device.hasTorch, (try? device.lockForConfiguration()) != nil {
                device.torchMode = .off
                device.unlockForConfiguration()
            }
            if session.isRunning { session.stopRunning() }
        }
    }

    @MainActor
    func toggleTorch() {
        let enabled = !torchLit
        queue.async { [self] in
            guard let device, device.hasTorch, device.isTorchAvailable else { return }
            do {
                try device.lockForConfiguration()
                defer { device.unlockForConfiguration() }
                device.torchMode = enabled ? .on : .off
                DispatchQueue.main.async { if self.accepting { self.torchLit = enabled } }
            } catch {
                DispatchQueue.main.async { self.torchLit = false }
            }
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard accepting, !read,
              let code = metadataObjects.lazy.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first,
              code.utf16.count <= 16_384 else { return }
        read = true
        onFound?(code)
    }

    private func configure() {
        guard let camera = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: camera) else { return }
        let output = AVCaptureMetadataOutput()
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        guard session.canAddInput(input), session.canAddOutput(output) else { return }
        session.addInput(input)
        session.addOutput(output)
        guard output.availableMetadataObjectTypes.contains(.qr) else {
            session.removeInput(input)
            session.removeOutput(output)
            return
        }
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        device = camera
    }
}

private struct CameraSurface: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> Preview {
        let view = Preview()
        view.preview.videoGravity = .resizeAspectFill
        view.preview.session = session
        return view
    }

    func updateUIView(_ uiView: Preview, context: Context) {}

    final class Preview: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

        override func layoutSubviews() {
            super.layoutSubviews()
            let orientation = window?.windowScene?.interfaceOrientation
            let angle: CGFloat
            switch orientation {
            case .landscapeLeft: angle = 0
            case .landscapeRight: angle = 180
            case .portraitUpsideDown: angle = 270
            default: angle = 90
            }
            if let connection = preview.connection, connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
        }
    }
}

@MainActor
struct QRPhotoButton: View {
    @ObservedObject var state: AppState
    let onFound: (String) -> Void
    var onWorkingChange: (Bool) -> Void = { _ in }
    @State private var picker = false
    @State private var selection: PhotosPickerItem?
    @State private var actionInProgress = false
    @State private var photoError: LocalizedStringKey?
    @State private var pickerSession: UUID?

    var body: some View {
        VStack(spacing: Ink.s) {
            Button("Choose a photo") {
                photoError = nil
                pickerSession = state.sessionID
                state.beginPicker()
                onWorkingChange(true)
                picker = true
            }
            .buttonStyle(InkButtonStyle(prominent: false))
            .disabled(actionInProgress)
            if actionInProgress { ProgressView() }
            if let photoError { Text(photoError).font(.footnote).foregroundStyle(Ink.danger) }
        }
        .photosPicker(isPresented: $picker, selection: $selection, matching: .images, preferredItemEncoding: .current)
        .task(id: selection) {
            guard let selection else { return }
            await read(selection)
        }
        .onChange(of: picker) { _, presented in
            // A dismissal without a selection still releases the picker lifetime.
            if !presented {
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(250))
                    if selection == nil, pickerSession != nil, !picker {
                        state.endPicker()
                        pickerSession = nil
                        onWorkingChange(false)
                    }
                }
            }
        }
        .onDisappear {
            if pickerSession != nil { state.endPicker(); pickerSession = nil }
            onWorkingChange(false)
        }
    }

    private func read(_ selection: PhotosPickerItem) async {
        guard let current = pickerSession else { return }
        actionInProgress = true
        defer {
            actionInProgress = false
            self.selection = nil
            onWorkingChange(false)
            if pickerSession != nil { state.endPicker(); pickerSession = nil }
        }
        do {
            guard let photo = try await selection.loadTransferable(type: QRPhoto.self) else {
                photoError = "The photo could not be read."
                return
            }
            let decoded = try await Task.detached(priority: .userInitiated) { try photo.decode() }.value
            guard !Task.isCancelled, state.sessionID == current else { return }
            guard let decoded else {
                photoError = "No QR code found. Try cropping the image or entering the key manually."
                return
            }
            guard state.endPicker() else {
                photoError = "The photo selection expired. Choose the photo again."
                return
            }
            pickerSession = nil
            onFound(decoded)
        } catch {
            if !Task.isCancelled && state.sessionID == current {
                photoError = "The photo could not be read or is too large. Crop the image and try again."
            }
        }
    }
}

struct QRPhoto: Transferable, Sendable {
    static let maxEncodedBytes = 16 * 1024 * 1024
    private static let maxSourceEdge = 32_768
    private static let maxSourcePixels = 100_000_000
    private static let maxDecodedEdge = 2048
    private static let pngSignature: [UInt8] = [137, 80, 78, 71, 13, 10, 26, 10]
    // The final, zero-length PNG IEND chunk has a fixed type and CRC.
    private static let pngEnd: [UInt8] = [0, 0, 0, 0, 73, 69, 78, 68, 174, 66, 96, 130]

    let bytes: Data

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { file in
            try read(file.file)
        }
    }

    static func read(_ url: URL) throws -> Self {
        Self(bytes: try readBoundedFile(url, maximum: maxEncodedBytes))
    }

    func decode() throws -> String? {
        guard bytes.count <= Self.maxEncodedBytes else { throw CocoaError(.fileReadTooLarge) }
        if bytes.starts(with: Self.pngSignature),
           !bytes.suffix(Self.pngEnd.count).elementsEqual(Self.pngEnd) {
            throw CocoaError(.fileReadCorruptFile)
        }
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(bytes as CFData, options),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              let width = properties[kCGImagePropertyPixelWidth as String] as? Int,
              let height = properties[kCGImagePropertyPixelHeight as String] as? Int,
              (1...Self.maxSourceEdge).contains(width), (1...Self.maxSourceEdge).contains(height),
              width * height <= Self.maxSourcePixels,
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceThumbnailMaxPixelSize: Self.maxDecodedEdge,
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
              ] as CFDictionary),
              CGImageSourceGetStatusAtIndex(source, 0) == .statusComplete,
              image.width <= Self.maxDecodedEdge, image.height <= Self.maxDecodedEdge
        else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        #if targetEnvironment(simulator)
        // Vision's frame detector can select an accelerator unavailable in a hosted VM.
        for (stage, devices) in (try? request.supportedComputeStageDevices) ?? [:] {
            if let cpu = devices.first(where: { if case .cpu = $0 { return true }; return false }) {
                request.setComputeDevice(cpu, for: stage)
            }
        }
        #endif
        if (try? VNImageRequestHandler(cgImage: image).perform([request])) != nil,
           let code = request.results?.compactMap(\.payloadStringValue).first(where: Self.acceptablePayload) {
            return code
        }

        // This system detector also works when Vision's barcode model is unavailable.
        let context = CIContext(options: [.useSoftwareRenderer: true])
        let detector = CIDetector(
            ofType: CIDetectorTypeQRCode,
            context: context,
            options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]
        )
        return detector?.features(in: CIImage(cgImage: image))
            .compactMap { ($0 as? CIQRCodeFeature)?.messageString }
            .first(where: Self.acceptablePayload)
    }

    private static func acceptablePayload(_ value: String) -> Bool {
        !value.isEmpty && value.utf16.count <= 16_384
    }
}
