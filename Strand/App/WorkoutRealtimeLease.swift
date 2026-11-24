/// The ongoing recording owns one realtime-HR request independently of the workout sheet
/// and the Live tab. Begin/end are idempotent, so restoring a session or dismissing a
/// screen cannot steal another owner's request.
///
/// Kotlin twin: android/app/src/main/java/com/noop/ui/WorkoutRealtimeLease.kt.
final class WorkoutRealtimeLease {
    private let onBegin: () -> Void
    private let onEnd: () -> Void
    private var held = false

    init(onBegin: @escaping () -> Void, onEnd: @escaping () -> Void) {
        self.onBegin = onBegin
        self.onEnd = onEnd
    }

    func begin() {
        guard !held else { return }
        held = true
        onBegin()
    }

    func end() {
        guard held else { return }
        held = false
        onEnd()
    }
}
