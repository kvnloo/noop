import Foundation
import XCTest
@testable import NoopLocalAccessCore

final class DatabasePathResolverTests: XCTestCase {
    func testPersonalBundleIsNotADefaultCandidate() {
        let candidates = DatabasePathResolver.candidates(home: "/Users/example")

        XCTAssertTrue(candidates.contains("/Users/example/Library/Containers/com.noopapp.noop/Data/Library/Application Support/OpenWhoop/whoop.sqlite"))
        XCTAssertFalse(candidates.contains { $0.contains("com.noopapp.noop.personal") })
    }

    func testCustomBundleIDIsExplicitOptIn() {
        let candidates = DatabasePathResolver.candidates(bundleID: "com.example.noop", home: "/Users/example")

        XCTAssertEqual(
            candidates.first,
            "/Users/example/Library/Containers/com.example.noop/Data/Library/Application Support/OpenWhoop/whoop.sqlite"
        )
        XCTAssertTrue(candidates.contains("/Users/example/Library/Containers/com.noopapp.noop/Data/Library/Application Support/OpenWhoop/whoop.sqlite"))
    }

    func testExplicitPathMustExist() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.emptyFileURL) { url in
            let config = LocalAccessConfiguration(databasePath: url.path)

            XCTAssertEqual(try DatabasePathResolver.resolve(configuration: config), url.path)
        }
    }

    func testExplicitPathFailureDoesNotFallBack() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.emptyFileURL) { url in
            let missing = url.deletingLastPathComponent().appendingPathComponent("missing.sqlite")
            let config = LocalAccessConfiguration(databasePath: missing.path)

            XCTAssertThrowsError(try DatabasePathResolver.resolve(configuration: config)) { error in
                XCTAssertEqual(error as? LocalAccessError, .databaseUnavailable("NOOP database not found at NOOP_DB_PATH."))
                XCTAssertLessThan(String(describing: error).count, 100)
            }
            XCTAssertThrowsError(try NoopDataAccess.open(configuration: config)) { error in
                XCTAssertEqual(error as? LocalAccessError, .databaseUnavailable("NOOP database not found at NOOP_DB_PATH."))
            }
            XCTAssertFalse(FileManager.default.fileExists(atPath: missing.path))
        }
    }

    func testHomeExpansionUsesInjectedHomeAndOnlyExpandsLeadingTilde() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.emptyFileURL) { url in
            let home = url.deletingLastPathComponent().path
            XCTAssertEqual(DatabasePathResolver.expandHome("~", home: home), home)
            XCTAssertEqual(DatabasePathResolver.expandHome("~/whoop.sqlite", home: home), url.path)
            XCTAssertEqual(DatabasePathResolver.expandHome("folder/~/whoop.sqlite", home: home), "folder/~/whoop.sqlite")
            XCTAssertEqual(DatabasePathResolver.expandHome(url.path, home: home), url.path)

            let paths = DatabasePathResolver.candidates(bundleID: "com.example.noop", home: home)
            XCTAssertEqual(paths.count, 3)
            XCTAssertEqual(paths.last, "\(home)/Library/Application Support/OpenWhoop/whoop.sqlite")
            XCTAssertTrue(paths.allSatisfy { $0.hasPrefix(home + "/") })
        }
    }

    func testExistingSymlinkIsAcceptedWithoutCanonicalizingExplicitPath() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.seeded) { url in
            let alias = url.deletingLastPathComponent().appendingPathComponent("alias.sqlite")
            try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: url)

            let config = LocalAccessConfiguration(databasePath: alias.path)
            XCTAssertEqual(try DatabasePathResolver.resolve(configuration: config), alias.path)
            let store = try ReadonlyNoopStore(path: alias.path)
            XCTAssertTrue(try store.isReadOnlyForTest())
            XCTAssertEqual(try store.latestHRSampleTs(deviceId: "my-whoop"), 102)
        }
    }

    func testDanglingSymlinkIsMissingAndDoesNotTriggerFallback() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.emptyFileURL) { url in
            let directory = url.deletingLastPathComponent()
            let target = directory.appendingPathComponent("absent.sqlite")
            let alias = directory.appendingPathComponent("dangling.sqlite")
            try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: target)

            XCTAssertFalse(FileManager.default.fileExists(atPath: alias.path))
            let config = LocalAccessConfiguration(databasePath: alias.path)
            XCTAssertThrowsError(try DatabasePathResolver.resolve(configuration: config)) { error in
                XCTAssertEqual(error as? LocalAccessError, .databaseUnavailable("NOOP database not found at NOOP_DB_PATH."))
            }
            XCTAssertFalse(FileManager.default.fileExists(atPath: target.path))
        }
    }
}
