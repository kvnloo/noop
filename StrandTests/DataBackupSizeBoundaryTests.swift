import Foundation
import XCTest
@testable import Strand

/// Pins the Swift half of #1807's backup-size contract. All sizes are injected:
/// no SQLite file or 2 GiB allocation is needed.
final class DataBackupSizeBoundaryTests: XCTestCase {
    private let destination = URL(fileURLWithPath: "/tmp/noop-size-boundary.noopbak")

    func testExportExactlyAtTwoGiBIsNotOversize() {
        XCTAssertEqual(DataBackup.maxBackupSQLiteBytes, 2_147_483_648)
        switch DataBackup.exportOutcome(destination, databaseBytes: 2_147_483_648) {
        case .exported(let url):
            XCTAssertEqual(url, destination)
        default:
            XCTFail("A database exactly at the restore ceiling must not warn")
        }
    }

    func testExportOneByteOverCarriesActualSizeAndRecoveryLimit() {
        switch DataBackup.exportOutcome(destination, databaseBytes: 2_147_483_649) {
        case .exportedOversize(let url, let bytes, let limit):
            XCTAssertEqual(url, destination)
            XCTAssertEqual(bytes, 2_147_483_649)
            XCTAssertEqual(limit, 2_147_483_648)
        default:
            XCTFail("One byte past the restore ceiling must warn with the actual size")
        }
    }

    func testMissingOrSmallerSizeKeepsPreviousExportOutcome() {
        for bytes: Int64? in [nil, 0, 2_147_483_647] {
            switch DataBackup.exportOutcome(destination, databaseBytes: bytes) {
            case .exported(let url):
                XCTAssertEqual(url, destination)
            default:
                XCTFail("A non-oversize or unreadable size must not warn")
            }
        }
    }

    func testRestoreCapIsStrictByDefaultAndLiftedOnlyOnOptIn() {
        let atLimit: Int64 = 2_147_483_648
        let oneOver = atLimit + 1
        let normalCap = DataBackup.sqliteRestoreLimit(allowOversize: false)
        let optedInCap = DataBackup.sqliteRestoreLimit(allowOversize: true)

        XCTAssertEqual(normalCap, atLimit)
        XCTAssertEqual(optedInCap, Int64.max)
        XCTAssertLessThanOrEqual(atLimit, normalCap)
        XCTAssertGreaterThan(oneOver, normalCap)
        XCTAssertLessThanOrEqual(oneOver, optedInCap)
    }
}
