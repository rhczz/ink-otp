// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "InkOTPCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "InkOTPCore", targets: ["InkOTPCore"])],
    targets: [
        .target(
            name: "CInkKDF",
            exclude: ["PROVENANCE.md", "SHA256SUMS", "argon2/LICENSE", "scrypt/COPYRIGHT"],
            publicHeadersPath: "include",
            cSettings: [.headerSearchPath("argon2")]
        ),
        .target(name: "InkOTPCore", dependencies: ["CInkKDF"]),
        .testTarget(name: "InkOTPCoreTests", dependencies: ["InkOTPCore"])
    ]
)
