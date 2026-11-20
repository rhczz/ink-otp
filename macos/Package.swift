// swift-tools-version: 5.10
import Foundation
import PackageDescription

let environment = ProcessInfo.processInfo.environment
let argon2Prefix = environment["INKOTP_ARGON2_PREFIX"] ?? "/opt/homebrew/opt/argon2"
let opensslPrefix = environment["INKOTP_OPENSSL_PREFIX"] ?? "/opt/homebrew/opt/openssl@3"

let package = Package(
    name: "InkOTP",
    platforms: [.macOS(.v14)],
    products: [.executable(name: "InkOTP", targets: ["InkOTPApp"])],
    dependencies: [.package(path: "../apple/GoogleOTPTransfer")],
    targets: [
        .target(
            name: "CInkOTPCrypto",
            cSettings: [.unsafeFlags(["-I" + argon2Prefix + "/include", "-I" + opensslPrefix + "/include"])],
            linkerSettings: [.unsafeFlags([argon2Prefix + "/lib/libargon2.a", opensslPrefix + "/lib/libcrypto.a"])]
        ),
        .target(name: "InkOTPCore", dependencies: ["CInkOTPCrypto", .product(name: "GoogleOTPTransfer", package: "GoogleOTPTransfer")]),
        .executableTarget(name: "InkOTPApp", dependencies: ["InkOTPCore", .product(name: "GoogleOTPTransfer", package: "GoogleOTPTransfer")]),
        .testTarget(name: "InkOTPCoreTests", dependencies: ["InkOTPCore", .product(name: "GoogleOTPTransfer", package: "GoogleOTPTransfer")]),
    ]
)
