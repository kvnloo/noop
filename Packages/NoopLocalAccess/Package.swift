// swift-tools-version: 5.9
// Product version for `noop-local-access --version` / `-V` lives in
// NoopLocalAccessCore (`noopLocalAccessServerVersion`); SPM Package() has no version field.
//
// CLI + library are Foundation + GRDB. They are intended to build on Linux and macOS.
// SPM `platforms` only records Apple minimum versions. There is no SupportedPlatform.linux
// API, so `.macOS(.v13)` is the Darwin floor, not a macOS-only package.
// iOS/macOS app/SwiftUI targets elsewhere in this repo stay Apple-only and are not this package.
import PackageDescription

let package = Package(
    name: "NoopLocalAccess",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .library(name: "NoopLocalAccessCore", targets: ["NoopLocalAccessCore"]),
        .executable(name: "noop-local-access", targets: ["noop-local-access"]),
    ],
    dependencies: [
        // Supply-chain: pinned EXACT (not `from:`) so a clean resolve can't auto-pull a newer —
        // potentially compromised — upstream release. Must match the same exact version in the
        // other Packages/*/Package.swift and project.yml, or SPM resolution fails. Bump deliberately.
        .package(url: "https://github.com/groue/GRDB.swift.git", exact: "6.29.3"),
    ],
    targets: [
        .target(
            name: "NoopLocalAccessCore",
            dependencies: [
                .product(name: "GRDB", package: "GRDB.swift"),
            ]
        ),
        .executableTarget(
            name: "noop-local-access",
            dependencies: ["NoopLocalAccessCore"]
        ),
        .testTarget(
            name: "NoopLocalAccessCoreTests",
            dependencies: [
                "NoopLocalAccessCore",
                .product(name: "GRDB", package: "GRDB.swift"),
            ]
        ),
    ]
)
