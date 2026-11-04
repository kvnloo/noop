import Foundation
import XCTest
@testable import Strand

/// Regression oracles reuse Kotlin JUnit fixture values from HeartMeditationModelTest.kt
/// at kvnloo/noop#125 (Android head bedcf45f1e71a4dcda576bb2eae742eec7868891).
final class HeartMeditationModelTests: XCTestCase {
    private func rsaBeats(seconds: Double, meanBpm: Double, ampBpm: Double, hz: Double) -> [Int] {
        var result: [Int] = []
        var time = 0.0
        while time < seconds {
            let hr = meanBpm + ampBpm * sin(2 * .pi * hz * time)
            let rr = Int((60_000.0 / hr).rounded())
            result.append(rr)
            time += Double(rr) / 1_000
        }
        return result
    }

    func testNoisyOwnerPacketNeverSeeds() {
        let gate = HeartBeatGate()
        let verdicts = [2_000, 567, 762].map { gate.offer($0) }
        XCTAssertFalse(verdicts.contains(.accepted))
        XCTAssertEqual(heartBeatSteadiness(verdicts), .waiting)
    }

    func testSeedsRejectsArtifactsAndDropsAfterFourRejects() {
        let gate = HeartBeatGate()
        XCTAssertEqual(gate.offer(800), .seeding)
        XCTAssertEqual(gate.offer(820), .seeding)
        XCTAssertEqual(gate.offer(790), .accepted)
        XCTAssertEqual(gate.offer(810), .accepted)
        XCTAssertEqual(gate.offer(1_600), .deviant)
        XCTAssertEqual(gate.offer(400), .deviant)
        XCTAssertEqual(gate.offer(2_500), .outOfRange)
        XCTAssertEqual(gate.offer(805), .accepted)
        for _ in 0..<HeartMeditationTuning.reseedAfterRejects { _ = gate.offer(250) }
        XCTAssertFalse(gate.seeded)
        XCTAssertEqual(gate.offer(805), .seeding)
    }

    func testStepChangeReseeds() {
        let gate = HeartBeatGate()
        for _ in 0..<6 { _ = gate.offer(1_000) }
        let after = (0..<10).map { _ in gate.offer(700) }
        XCTAssertEqual(Array(after.suffix(3)), [.accepted, .accepted, .accepted])
        if let first = after.firstIndex(of: .accepted) {
            XCTAssertLessThan(first, HeartMeditationTuning.reseedAfterRejects + HeartMeditationTuning.seedBeats)
        } else {
            XCTFail("Re-seeding never resumed accepted beats")
        }
    }

    func testDeepBreathingWaveIsNotAnArtifact() {
        let gate = HeartBeatGate()
        let verdicts = rsaBeats(seconds: 60, meanBpm: 62, ampBpm: 12, hz: 0.1)
            .map { gate.offer($0) }
        XCTAssertEqual(verdicts.filter { $0 == .deviant || $0 == .outOfRange }.count, 0)
    }

    func testSteadinessThresholds() {
        XCTAssertEqual(heartBeatSteadiness(Array(repeating: .accepted, count: 20)), .steady)
        XCTAssertEqual(heartBeatSteadiness((0..<20).map { $0.isMultiple(of: 3) ? .deviant : .accepted }), .noisy)
        XCTAssertEqual(heartBeatSteadiness(Array(repeating: .accepted, count: 7)), .waiting)
        XCTAssertEqual(heartBeatSteadiness(Array(repeating: .seeding, count: 20)), .waiting)
    }

    func testBatchedBeatsArePlayedAtRecordedSpacing() {
        let queue = HeartBeatPlayout()
        queue.enqueue(800)
        queue.enqueue(820)
        XCTAssertEqual(queue.poll(0), 800)
        XCTAssertNil(queue.poll(400))
        XCTAssertNil(queue.poll(819))
        XCTAssertEqual(queue.poll(820), 820)
        XCTAssertNil(queue.poll(5_000))
    }

    func testLatePacketReanchorsWithoutBursting() {
        let queue = HeartBeatPlayout()
        queue.enqueue(800)
        XCTAssertEqual(queue.poll(0), 800)
        queue.enqueue(800)
        XCTAssertEqual(queue.poll(5_000), 800)
        queue.enqueue(800)
        XCTAssertNil(queue.poll(5_400))
        XCTAssertEqual(queue.poll(5_800), 800)
    }

    func testQueueTrimsOldestSixOfTen() {
        let queue = HeartBeatPlayout()
        for offset in 0..<10 { queue.enqueue(800 + offset) }
        XCTAssertEqual(queue.queued, 4)
        XCTAssertEqual(queue.poll(0), 806)
    }

    func testVesselTracksInjectedRSAWithSteadyBeats() {
        let session = HeartMeditationSession()
        let beats = rsaBeats(seconds: 90, meanBpm: 65, ampBpm: 5, hz: 0.1)
        var beatTime: Int64 = 0
        var frame: Int64 = 0
        var levels: [Double] = []
        var hrs: [Double] = []
        for rr in beats {
            beatTime += Int64(rr)
            while frame < beatTime { session.tick(frame); frame += 16 }
            session.onPacket([rr], nowMs: frame)
            let before = session.pulses
            while session.pulses == before && frame < beatTime + 3_000 {
                session.tick(frame)
                frame += 16
            }
            if beatTime > 20_000 {
                levels.append(session.levelAt(frame + Int64(rr)))
                hrs.append(60_000.0 / Double(rr))
            }
        }
        XCTAssertEqual(session.steadiness, .steady)
        XCTAssertTrue(levels.allSatisfy { $0 >= 0.2 && $0 <= 0.85 })
        let meanLevel = levels.reduce(0, +) / Double(levels.count)
        let meanHR = hrs.reduce(0, +) / Double(hrs.count)
        var cov = 0.0
        var varLevel = 0.0
        var varHR = 0.0
        for i in levels.indices {
            cov += (levels[i] - meanLevel) * (hrs[i] - meanHR)
            varLevel += pow(levels[i] - meanLevel, 2)
            varHR += pow(hrs[i] - meanHR, 2)
        }
        XCTAssertGreaterThan(cov / sqrt(varLevel * varHR), 0.7)
        guard let swing = session.rhythm.swingBpm else { return XCTFail("No turning points") }
        XCTAssertTrue((6.0...11.0).contains(swing), "Expected approximately 10 bpm swing, got \(swing)")
    }

    func testSwingTracksThreeInjectedAmplitudes() {
        for amplitude in [2.0, 4.0, 8.0] {
            let session = HeartMeditationSession()
            var time: Int64 = 0
            for rr in rsaBeats(seconds: 80, meanBpm: 62, ampBpm: amplitude, hz: 0.1) {
                time += Int64(rr)
                session.onPacket([rr], nowMs: time)
                session.tick(time + 2)
            }
            guard let swing = session.rhythm.swingBpm else { XCTFail("No swing at \(amplitude)"); continue }
            XCTAssertLessThanOrEqual(abs(swing - 2 * amplitude), 0.35 * 2 * amplitude + 1)
        }
    }

    func testStaleRhythmReturnsToRest() {
        let session = HeartMeditationSession()
        var time: Int64 = 0
        for _ in 0..<30 {
            session.onPacket([900], nowMs: time)
            session.tick(time + 1)
            time += 900
        }
        XCTAssertEqual(session.steadiness, .steady)
        session.tick(time + HeartMeditationTuning.staleMs + 100)
        XCTAssertEqual(session.steadiness, .waiting)
        XCTAssertEqual(session.levelAt(time + 20_000), HeartMeditationTuning.restLevel, accuracy: 1e-9)
    }

    func testWarmupHasNoPulseOrReportedRateUntilEightCleanBeats() {
        let session = HeartMeditationSession()
        var time: Int64 = 0
        for _ in 0..<7 {
            session.onPacket([900], nowMs: time)
            session.tick(time + 1)
            time += 900
        }
        XCTAssertEqual(session.steadiness, .waiting)
        XCTAssertEqual(session.pulses, 0)
        XCTAssertNil(session.cleanBpm)
        XCTAssertEqual(session.levelAt(time + 5_000), HeartMeditationTuning.restLevel, accuracy: 1e-9)
        for _ in 0..<3 {
            session.onPacket([900], nowMs: time)
            session.tick(time + 1)
            time += 900
        }
        XCTAssertEqual(session.steadiness, .steady)
        XCTAssertGreaterThan(session.pulses, 0)
        XCTAssertEqual(session.cleanBpm, 67)  // 60,000 / 900 = 66.666... -> 67 bpm
    }

    func testFocusRecoversFourSinusoidalBreathingPaces() {
        for rate in [5.0, 6.0, 7.0, 9.0] {
            let beats = rsaBeats(seconds: 90, meanBpm: 64, ampBpm: 5, hz: rate / 60)
            guard let focus = heartRhythmFocus(beats) else { XCTFail("Missing focus for \(rate)"); continue }
            XCTAssertGreaterThan(focus.focus, 0.6)
            XCTAssertEqual(focus.perMinute, rate, accuracy: 0.6)
        }
    }

    func testFocusWaitsFor64SecondsOfCleanTime() {
        XCTAssertNil(heartRhythmFocus(rsaBeats(seconds: 40, meanBpm: 64, ampBpm: 5, hz: 0.1)))
        XCTAssertNil(heartRhythmFocus([]))
    }

    func testTenSecondPacerPhasesAndBounds() {
        XCTAssertEqual(heartPacerLevel(0, bpm: 6), 0, accuracy: 1e-9)
        XCTAssertEqual(heartPacerLevel(2_500, bpm: 6), 0.5, accuracy: 1e-9)
        XCTAssertEqual(heartPacerLevel(5_000, bpm: 6), 1, accuracy: 1e-9)
        XCTAssertEqual(heartPacerLevel(10_000, bpm: 6), 0, accuracy: 1e-9)
        XCTAssertTrue(heartPacerInhaling(4_999, bpm: 6))
        XCTAssertFalse(heartPacerInhaling(5_001, bpm: 6))
        XCTAssertEqual(heartPacerMsToNextPhase(4_000, bpm: 6), 1_000)
        XCTAssertEqual(heartPacerMsToNextPhase(5_000, bpm: 6), 5_000)
        let rise = (0...50).map { heartPacerLevel(Int64($0 * 100), bpm: 6) }
        let fall = (50...100).map { heartPacerLevel(Int64($0 * 100), bpm: 6) }
        for i in 1..<rise.count { XCTAssertGreaterThanOrEqual(rise[i], rise[i-1]) }
        for i in 1..<fall.count { XCTAssertLessThanOrEqual(fall[i], fall[i-1]) }
    }

    func testFortySixtyPacerSplit() {
        let cycle = 60_000.0 / 5.5
        XCTAssertEqual(heartPacerLevel(Int64((cycle * 0.4).rounded()), bpm: 5.5, inhaleFraction: 0.4), 1, accuracy: 1e-3)
        XCTAssertTrue(heartPacerInhaling(Int64(cycle * 0.39), bpm: 5.5, inhaleFraction: 0.4))
        XCTAssertFalse(heartPacerInhaling(Int64(cycle * 0.41), bpm: 5.5, inhaleFraction: 0.4))
    }

    func testNegativeElapsedTimeWrapsIntoTheSameCycle() {
        // Kotlin's double-modulo rule normalizes negative ticks rather than reporting a negative phase.
        XCTAssertEqual(heartPacerLevel(-2_500, bpm: 6), heartPacerLevel(7_500, bpm: 6), accuracy: 1e-9)
    }
}
