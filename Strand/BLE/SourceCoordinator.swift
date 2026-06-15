import Foundation
import Combine
import WhoopStore

/// Runs exactly ONE device's live BLE at a time, driven by `DeviceRegistry.activeDeviceId`.
///
/// WHOOP-FIRST, ZERO REGRESSION
/// ----------------------------
/// This coordinator is a deliberate **NO-OP whenever the active device is the WHOOP** (id
/// "my-whoop", or any `brand == "WHOOP"` row). That is the default state and EVERY state where no
/// generic strap is paired: WHOOP is active, so the coordinator does nothing and the existing WHOOP
/// flow (`BLEManager` via `AppModel.scan(...)`) runs exactly as it does today. It only ever *acts*
/// when the active device is a NON-WHOOP generic HR strap:
///
///   • switching TO a generic strap → `stopWhoop()` (BLEManager's existing `disconnect()`), then
///     `start` the isolated `StandardHRSource` for that strap's deviceId.
///   • switching BACK to WHOOP     → `stop()` the `StandardHRSource`, then `startWhoop()`
///     (BLEManager's existing scan entry point) — but only if we had actually been on a strap, so a
///     plain launch with WHOOP active does NOT re-trigger a redundant WHOOP scan.
///
/// It never imports or references `BLEManager`: the WHOOP start/stop are injected closures from the
/// app model, so the two BLE flows stay fully decoupled (mirrors `StandardHRSource`'s isolation).
@MainActor
final class SourceCoordinator: ObservableObject {

    // MARK: - Dependencies

    private let registry: DeviceRegistry
    private let live: LiveState
    /// Resolves the shared on-device store for the strap persist closure (opened lazily by the app's
    /// `Repository`, matching the existing async store lifecycle — we never force it open early).
    private let storeHandle: () async -> WhoopStore?
    /// Re-trigger WHOOP's EXISTING scan/connect entry point (e.g. `AppModel.scan()` → `BLEManager.connect`).
    private let startWhoop: () -> Void
    /// Pause WHOOP via its EXISTING teardown (e.g. `AppModel.disconnect()` → `BLEManager.disconnect`).
    private let stopWhoop: () -> Void

    // MARK: - State

    /// The lazily-created generic-strap source. nil until the first switch to a strap; reused after.
    private var standardSource: StandardHRSource?
    /// The deviceId the `standardSource` is currently running for (so we don't churn on the same id).
    private var activeStrapId: String?
    /// True once we've transitioned onto a generic strap. While false (the default / WHOOP-active
    /// state), switching to WHOOP is a pure no-op — we never issue a redundant WHOOP (re)scan.
    private var onStrap = false

    private var cancellable: AnyCancellable?

    // MARK: - Init

    /// - Parameters:
    ///   - registry: the Phase 1A device registry; `activeDeviceId` drives every transition.
    ///   - live: the shared `LiveState` the Live UI observes (fed by whichever source is running).
    ///   - storeHandle: resolves the shared `WhoopStore` for the strap persist closure.
    ///   - startWhoop: WHOOP's existing scan entry point (injected so we never touch `BLEManager`).
    ///   - stopWhoop: WHOOP's existing disconnect (injected for the same reason).
    init(registry: DeviceRegistry,
         live: LiveState,
         storeHandle: @escaping () async -> WhoopStore?,
         startWhoop: @escaping () -> Void,
         stopWhoop: @escaping () -> Void) {
        self.registry = registry
        self.live = live
        self.storeHandle = storeHandle
        self.startWhoop = startWhoop
        self.stopWhoop = stopWhoop
    }

    // MARK: - Wiring

    /// Begin observing `registry.activeDeviceId`. `removeDuplicates()` collapses redundant emissions;
    /// the first value (WHOOP on a normal launch) is handled by `activeDeviceChanged` as a no-op.
    func start() {
        cancellable = registry.$activeDeviceId
            .removeDuplicates()
            .sink { [weak self] id in self?.activeDeviceChanged(to: id) }
    }

    // MARK: - Transitions

    /// Resolve the device for `id` and reconcile which live source is running. Idempotent and guarded
    /// against redundant churn:
    ///   • WHOOP active while we were NOT on a strap (the default / first-launch case) → DO NOTHING.
    ///   • WHOOP active after a strap → stop the strap source + resume WHOOP exactly once.
    ///   • A generic strap → pause WHOOP + (re)start `StandardHRSource` for that strap's id.
    func activeDeviceChanged(to id: String) {
        if isWhoop(id) {
            switchToWhoop()
        } else {
            switchToStrap(id: id)
        }
    }

    /// Active device is the WHOOP. If we'd been on a strap, tear that source down and resume WHOOP;
    /// otherwise (the dormant default) this is a pure no-op so the existing WHOOP startup is untouched.
    private func switchToWhoop() {
        guard onStrap else { return }   // already WHOOP-mode (incl. first launch) → no churn
        standardSource?.stop()
        activeStrapId = nil
        onStrap = false
        startWhoop()
    }

    /// Active device is a generic strap. Pause WHOOP (once, on the WHOOP→strap edge) and run the
    /// isolated `StandardHRSource` for this strap's deviceId. Re-running for the SAME id is a no-op.
    private func switchToStrap(id: String) {
        guard activeStrapId != id else { return }   // already streaming this strap → no churn

        // Leaving WHOOP for the first strap: pause WHOOP's BLE via its existing teardown.
        if !onStrap { stopWhoop() }

        // Switching strap→strap: stop the previous strap's source before starting the new one.
        if standardSource != nil { standardSource?.stop() }

        let source = StandardHRSource(
            live: live,
            deviceId: id,
            persist: { [storeHandle] streams in
                Task { if let store = await storeHandle() { try? await store.insert(streams, deviceId: id) } }
            })
        source.scan()              // discover + auto-connect the chosen strap on its own central
        standardSource = source
        activeStrapId = id
        onStrap = true
    }

    // MARK: - Classification

    /// Classify a device id as WHOOP vs a generic strap. WHOOP if the id is the canonical
    /// "my-whoop", or the registry row's `brand` is "WHOOP" (case-insensitive). Unknown ids default
    /// to WHOOP so the coordinator stays dormant rather than ever stealing the WHOOP's BLE.
    private func isWhoop(_ id: String) -> Bool {
        if id == "my-whoop" { return true }
        guard let device = registry.devices.first(where: { $0.id == id }) else { return true }
        return Self.isWhoop(device)
    }

    /// A device is WHOOP when its brand is "WHOOP" (the seeded `my-whoop` row's brand).
    static func isWhoop(_ device: PairedDevice) -> Bool {
        device.id == "my-whoop" || device.brand.caseInsensitiveCompare("WHOOP") == .orderedSame
    }
}
