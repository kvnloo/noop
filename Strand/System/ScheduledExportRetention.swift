import Foundation

/// The only deletion policy for scheduled debug exports (#650).
///
/// Kept separate from ScheduledDebugExport's timers, BGTask lifecycle and log writer so a review
/// of which Files-visible Documents entries may be deleted does not have to audit those paths.
/// The existing filename contract and generation ordering are unchanged: the log and optional raw
/// capture share one stamp; only our prefix/extension pairs are candidates. Interactive shares
/// are staged elsewhere. Android's LogExport uses the same keep-N generations policy.
enum ScheduledExportRetention {
    private static let logPrefix = "noop-strap-log-"
    private static let rawPrefix = "noop-raw-capture-"

    /// Extract the timestamp from a scheduled log or raw capture; unrelated filenames return nil.
    static func stamp(fromFilename name: String) -> String? {
        if name.hasPrefix(logPrefix), name.hasSuffix(".txt") {
            return String(name.dropFirst(logPrefix.count).dropLast(4))
        }
        if name.hasPrefix(rawPrefix), name.hasSuffix(".json") {
            return String(name.dropFirst(rawPrefix.count).dropLast(5))
        }
        return nil
    }

    /// Keep the newest N distinct stamps. Fixed-width yyMMdd-HHmm timestamps sort chronologically
    /// as strings; a log and capture from the same minute count as one generation.
    static func stampsToPrune(_ names: [String], keep: Int) -> Set<String> {
        let stamps = Array(Set(names.compactMap { stamp(fromFilename: $0) })).sorted(by: >)
        guard stamps.count > keep else { return [] }
        return Set(stamps.dropFirst(keep))
    }

    /// Best-effort post-write pruning; a failed deletion must not fail the completed log export.
    static func prune(in documents: URL, keep: Int, fileManager: FileManager = .default) {
        guard let names = try? fileManager.contentsOfDirectory(atPath: documents.path) else { return }
        let toPrune = stampsToPrune(names, keep: keep)
        guard !toPrune.isEmpty else { return }
        for name in names {
            guard let found = stamp(fromFilename: name), toPrune.contains(found) else { continue }
            try? fileManager.removeItem(at: documents.appendingPathComponent(name))
        }
    }

    /// Clear only scheduled log/capture files. Return the count actually deleted.
    @discardableResult
    static func clear(in documents: URL, fileManager: FileManager = .default) -> Int {
        guard let names = try? fileManager.contentsOfDirectory(atPath: documents.path) else { return 0 }
        var removed = 0
        for name in names where stamp(fromFilename: name) != nil {
            if (try? fileManager.removeItem(at: documents.appendingPathComponent(name))) != nil {
                removed += 1
            }
        }
        return removed
    }
}
