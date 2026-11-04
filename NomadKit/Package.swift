// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "NomadKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ZIMKit", targets: ["ZIMKit"]),
        .library(name: "NomadSearch", targets: ["NomadSearch"]),
        .executable(name: "nomad-index", targets: ["nomad-index"]),
    ],
    dependencies: [
        // BSD-licensed zstd; decodes clusters with compression type 5.
        .package(url: "https://github.com/facebook/zstd", from: "1.5.7"),
    ],
    targets: [
        .target(
            name: "ZIMKit",
            dependencies: [.product(name: "libzstd", package: "zstd")]
        ),
        .target(
            name: "NomadSearch",
            dependencies: ["ZIMKit"],
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        .executableTarget(
            name: "nomad-index",
            dependencies: ["ZIMKit", "NomadSearch"]
        ),
        .testTarget(
            name: "ZIMKitTests",
            dependencies: ["ZIMKit"]
        ),
        .testTarget(
            name: "NomadSearchTests",
            dependencies: ["NomadSearch", "ZIMKit"]
        ),
    ],
    swiftLanguageModes: [.v6]
)
