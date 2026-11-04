import Foundation

/// Creates a normal custom Journal question, never an answer or a second habit store.
/// The Android counterpart lives in the older fork-only #54 pilot;
/// this Apple-only successor does not bring its stacked commits to main.
///
/// The UTF-16 length check intentionally matches Kotlin String.length rather
/// than Swift Character count, which differs for emoji and combining sequences.
func twoMinuteHabitQuestion(_ cueInput: String, _ actionInput: String) -> String? {
    let cue = cleanHabitPart(cueInput)
    let action = cleanHabitPart(actionInput)
    guard !cue.isEmpty, !action.isEmpty, cue.utf16.count <= 80, action.utf16.count <= 80 else {
        return nil
    }
    return "After \(cue), did you \(action) for two minutes?"
}

/// Collapse Unicode whitespace into single ASCII spaces while dropping
/// trailing punctuation, matching the Android journal-composer normalization.
private func cleanHabitPart(_ input: String) -> String {
    var out = ""
    var pendingSpace = false
    for scalar in input.unicodeScalars {
        if isAndroidJournalWhitespace(scalar) {
            if !out.isEmpty { pendingSpace = true }
        } else {
            if pendingSpace { out.append(" ") }
            out.unicodeScalars.append(scalar)
            pendingSpace = false
        }
    }
    while let last = out.last, " .,;?!:".contains(last) { out.removeLast() }
    return out
}

/// Kotlin `Char.isWhitespace()` is the Android parity text contract.
/// Foundation also classifies U+0085 and U+200B as whitespace while Kotlin
/// does not, and Kotlin includes U+001C..U+001F that Foundation excludes.
/// Pin the BMP set so habits persist under the same canonical key on both
/// platforms rather than allowing OS Unicode tables to drift.
private func isAndroidJournalWhitespace(_ scalar: Unicode.Scalar) -> Bool {
    switch scalar.value {
    case 0x0009...0x000D, 0x001C...0x0020, 0x00A0, 0x1680,
         0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000:
        return true
    default:
        return false
    }
}
