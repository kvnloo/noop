import Foundation
import XCTest
#if canImport(Strand)
@testable import Strand
#endif

/// Exercises the retention service against real temporary filesystem entries.
final class ScheduledExportRetentionFileTests: XCTestCase {
    func testPruneIgnoresDirectoriesAndSymlinksInGenerationBudget() throws {
        try withDocuments { docs in
            let fm = FileManager.default
            let old = docs.appendingPathComponent("noop-strap-log-260610-0700.txt")
            let newest = docs.appendingPathComponent("noop-strap-log-260617-0700.txt")
            try Data().write(to: old)
            try Data().write(to: newest)
            let directory = docs.appendingPathComponent("noop-raw-capture-260601-0700.json")
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            let child = directory.appendingPathComponent("personal.txt")
            try Data("keep".utf8).write(to: child)
            let link = docs.appendingPathComponent("noop-strap-log-260630-0700.txt")
            try fm.createSymbolicLink(at: link, withDestinationURL: newest)

            ScheduledExportRetention.prune(in: docs, keep: 1)

            XCTAssertFalse(fm.fileExists(atPath: old.path))
            XCTAssertTrue(fm.fileExists(atPath: newest.path))
            XCTAssertTrue(fm.fileExists(atPath: child.path))
            XCTAssertEqual(try? fm.destinationOfSymbolicLink(atPath: link.path), newest.path)
        }
    }

    func testClearPreservesMatchingDirectoriesAndSymlinks() throws {
        try withDocuments { docs in
            let fm = FileManager.default
            let regular = docs.appendingPathComponent("noop-strap-log-260617-0700.txt")
            try Data().write(to: regular)
            let directory = docs.appendingPathComponent("noop-raw-capture-260610-0700.json")
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            let child = directory.appendingPathComponent("personal.txt")
            try Data("keep".utf8).write(to: child)
            let link = docs.appendingPathComponent("noop-strap-log-260630-0700.txt")
            try fm.createSymbolicLink(at: link, withDestinationURL: child)

            XCTAssertEqual(ScheduledExportRetention.clear(in: docs), 1)
            XCTAssertEqual(ScheduledExportRetention.clear(in: docs), 0)
            XCTAssertFalse(fm.fileExists(atPath: regular.path))
            XCTAssertEqual(try? String(contentsOf: child, encoding: .utf8), "keep")
            XCTAssertEqual(try? fm.destinationOfSymbolicLink(atPath: link.path), child.path)
        }
    }

    private func withDocuments(_ body: (URL) throws -> Void) throws {
        let fm = FileManager.default
        let docs = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try fm.createDirectory(at: docs, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: docs) }
        try body(docs)
    }
}
