/// A manual menu-bar request, independent of the recording and live screens.
/// Kept by the app model so closing/reopening the popover doesn't lose ownership.
struct MenuBarRealtimeRequest {
    private(set) var isHeld = false

    func toggled(onStart: () -> Void, onStop: () -> Void) -> Self {
        if isHeld {
            onStop()
        } else {
            onStart()
        }
        return Self(isHeld: !isHeld)
    }
}
