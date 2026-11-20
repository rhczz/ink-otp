// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "GoogleOTPTransfer",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "GoogleOTPTransfer", targets: ["GoogleOTPTransfer"])],
    targets: [
        .target(name: "GoogleOTPTransfer"),
        .testTarget(name: "GoogleOTPTransferTests", dependencies: ["GoogleOTPTransfer"])
    ]
)
