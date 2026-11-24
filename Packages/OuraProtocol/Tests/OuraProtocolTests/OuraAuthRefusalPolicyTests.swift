import XCTest
@testable import OuraProtocol

final class OuraAuthRefusalPolicyTests: XCTestCase {
    private var policy = OuraAuthRefusalPolicy()

    private func authAttempt(
        _ status: OuraAuthStatus = .authError,
        ambiguous: Bool = true,
        liveHRWanted: Bool = true
    ) -> OuraAuthRefusalPolicy.Action? {
        // A new driver per link, but the same policy in the transport.
        let driver = OuraDriver(ringGen: .gen3, authKey: Array(0..<16))
        driver.liveHRWanted = liveHRWanted
        _ = driver.nextStep(after: .ready)
        XCTAssertNil(policy.observe(driver.phase))
        let nonce = Array(16..<UInt8(16 + OuraAuth.nonceLength))
        let proof = driver.nextStep(after: .nonceReceived(nonce))
        XCTAssertEqual(proof.map(\.label), ["submit_proof"])
        if ambiguous {
            let foreign = Array(32..<UInt8(32 + OuraAuth.nonceLength))
            XCTAssertTrue(driver.nextStep(after: .nonceReceived(foreign)).isEmpty)
        }
        _ = driver.nextStep(after: .authCompleted(status))
        return policy.observe(driver.phase)
    }

    func testSecondAmbiguousRefusalStopsAutoReconnect() {
        XCTAssertEqual(authAttempt(.inFactoryReset), .reconnect)
        XCTAssertEqual(authAttempt(.inFactoryReset), .needsPairing)
    }

    func testAmbiguousAndPlainRefusalsShareTheBudget() {
        XCTAssertEqual(authAttempt(), .reconnect)
        XCTAssertEqual(authAttempt(ambiguous: false), .needsPairing)
        policy = OuraAuthRefusalPolicy()
        XCTAssertEqual(authAttempt(ambiguous: false), .reconnect)
        XCTAssertEqual(authAttempt(), .needsPairing)
    }

    func testAuthSuccessReplenishesRetryWithOrWithoutLiveHR() {
        for liveHRWanted in [true, false] {
            policy = OuraAuthRefusalPolicy()
            XCTAssertEqual(authAttempt(ambiguous: false), .reconnect)
            XCTAssertNil(authAttempt(.success, liveHRWanted: liveHRWanted))
            XCTAssertEqual(authAttempt(), .reconnect)
            XCTAssertEqual(authAttempt(), .needsPairing)
        }
    }

    func testUserReconnectReplenishesRetry() {
        XCTAssertEqual(authAttempt(), .reconnect)
        XCTAssertEqual(authAttempt(), .needsPairing)
        policy.resetForUserReconnect()
        XCTAssertEqual(authAttempt(), .reconnect)
        XCTAssertEqual(authAttempt(), .needsPairing)
    }
}
