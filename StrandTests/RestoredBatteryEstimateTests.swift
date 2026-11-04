import XCTest
import StrandAnalytics
import WhoopProtocol
import WhoopStore
@testable import Strand

/// Regression coverage for ryanbr/noop#2887. No CoreBluetooth peripheral required.
@MainActor
final class RestoredBatteryEstimateTests: XCTestCase {
    func testPersistedWhoop5StartsWithItsOwnRatedLife() throws {
        let key = "selectedWhoopModel"
        let previous = UserDefaults.standard.object(forKey: key)
        defer {
            if let previous {
                UserDefaults.standard.set(previous, forKey: key)
            } else {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }
        UserDefaults.standard.set(WhoopModel.whoop5mg.rawValue, forKey: key)

        let live = LiveState()
        let manager = BLEManager(state: live, collector: nil)
        XCTAssertTrue(manager.isWhoop5)
        XCTAssertEqual(live.batteryRatedHours, BatteryEstimator.ratedLifeHoursWhoop5)

        live.bankBatterySample(91, now: 1_800_000_000)
        let estimate = try XCTUnwrap(live.batteryEstimate)
        XCTAssertEqual(estimate.remainingHours, 0.91 * 288, accuracy: 0.000001)
    }

    func testReconnectReseedsMeasuredSlopeFromPersistedSocRows() async throws {
        let now = 1_800_000_000
        let store = try await WhoopStore.inMemory()
        _ = try await store.insert(Streams(battery: [
            BatterySample(ts: now - 10 * 3600, soc: 100, mv: 4400),
            BatterySample(ts: now, soc: 90, mv: 4300),
        ]), deviceId: "my-whoop")

        let live = LiveState()
        let manager = BLEManager(state: live, collector: nil)
        await manager.seedBatterySamplesFromStore(store, now: now)
        XCTAssertEqual(live.batterySamples.count, 2)
        XCTAssertEqual(try XCTUnwrap(live.batteryEstimate).source.rawValue, "measured")
        XCTAssertEqual(try XCTUnwrap(live.batteryEstimate).remainingHours, 90, accuracy: 0.000001)

        live.clearBiometrics()
        XCTAssertTrue(live.batterySamples.isEmpty)
        XCTAssertNil(live.batteryEstimate)

        // A query completed for a disconnected strap must not repopulate its cleared buffer.
        await manager.seedBatterySamplesFromStore(store, now: now, connectedOnly: true)
        XCTAssertTrue(live.batterySamples.isEmpty)

        live.connected = true
        await manager.seedBatterySamplesFromStore(store, now: now, connectedOnly: true)
        XCTAssertEqual(live.batterySamples.count, 2)
        XCTAssertEqual(try XCTUnwrap(live.batteryEstimate).source.rawValue, "measured")
        XCTAssertEqual(try XCTUnwrap(live.batteryEstimate).remainingHours, 90, accuracy: 0.000001)

        // Repeated callbacks do not inflate the regression sample count.
        await manager.seedBatterySamplesFromStore(store, now: now, connectedOnly: true)
        XCTAssertEqual(live.batterySamples.count, 2)
    }
}
