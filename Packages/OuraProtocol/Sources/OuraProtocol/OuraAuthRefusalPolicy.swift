/// App reconnect policy for auth verdicts. Keep one value across BLE links, not one per driver.
public struct OuraAuthRefusalPolicy {
    public enum Action: Equatable {
        case reconnect
        case needsPairing
    }

    private var retryUsed = false

    public init() {}

    public mutating func resetForUserReconnect() {
        retryUsed = false
    }

    /// Observe the resulting phase after an authenticating step. A successful auth replenishes the retry allowance.
    public mutating func observe(_ phase: OuraDriverPhase) -> Action? {
        switch phase {
        case .retryableAuthFailure:
            return .reconnect
        case .authFailed:
            guard !retryUsed else { return .needsPairing }
            retryUsed = true
            return .reconnect
        case .enablingLiveHR, .streaming:
            retryUsed = false
            return nil
        default:
            return nil
        }
    }
}
