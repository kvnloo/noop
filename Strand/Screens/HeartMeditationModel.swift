import Foundation
import StrandAnalytics

// Pure, clock-injected presentation model for Live heart meditation.
// Kotlin twin: android/app/src/main/java/com/noop/ui/HeartMeditationModel.kt.
// Display only: no BLE commands, persisted metrics, recovery scores or diagnostic claims.
enum HeartMeditationTuning {
    static let minRR = 300
    static let maxRR = 2_000
    static let deviation = HRVAnalyzer.ectopicThreshold
    static let seedBeats = 3
    static let reseedAfterRejects = 4
    static let steadinessWindow = 20
    static let minAcceptedForSteady = 8
    static let steadyFraction = 0.8
    static let staleMs: Int64 = 5_000
    static let maxQueuedBeats = 4
    static let restLevel = 0.52
    static let levelSwing = 0.30
    static let minHalfSwingBpm = 3.0
    static let baselineWeight = 0.05
    static let turnHysteresisBpm = 0.6
    static let swingWeight = 0.2
    static let focusWindowSeconds = 64.0
    static let focusSampleHz = 2.0
}

enum HeartBeatVerdict: Equatable {
    case accepted, outOfRange, deviant, seeding
}

final class HeartBeatGate {
    private var reference: Int?
    private var seed: [Int] = []
    private var rejectsInRow = 0

    var seeded: Bool { reference != nil }

    func reset() {
        reference = nil
        seed.removeAll()
        rejectsInRow = 0
    }

    func offer(_ rr: Int) -> HeartBeatVerdict {
        if rr < HeartMeditationTuning.minRR || rr > HeartMeditationTuning.maxRR {
            noteReject()
            seed.removeAll()
            return .outOfRange
        }
        if let ref = reference {
            if Double(abs(rr - ref)) / Double(ref) <= HeartMeditationTuning.deviation {
                reference = rr
                rejectsInRow = 0
                return .accepted
            }
            noteReject()
            return .deviant
        }
        seed.append(rr)
        if seed.count > HeartMeditationTuning.seedBeats { seed.removeFirst() }
        if seed.count == HeartMeditationTuning.seedBeats && agrees(seed) {
            reference = rr
            seed.removeAll()
            rejectsInRow = 0
            return .accepted
        }
        return .seeding
    }

    private func noteReject() {
        rejectsInRow += 1
        if rejectsInRow >= HeartMeditationTuning.reseedAfterRejects && reference != nil {
            reference = nil
            seed.removeAll()
        }
    }

    private func agrees(_ beats: [Int]) -> Bool {
        let sorted = beats.sorted()
        let n = sorted.count
        let median = n.isMultiple(of: 2)
            ? Double(sorted[n / 2 - 1] + sorted[n / 2]) / 2
            : Double(sorted[n / 2])
        return beats.allSatisfy {
            abs(Double($0) - median) / median <= HeartMeditationTuning.deviation
        }
    }
}

enum HeartBeatSteadiness: Equatable {
    case waiting, noisy, steady
}

func heartBeatSteadiness(_ recent: [HeartBeatVerdict]) -> HeartBeatSteadiness {
    let accepted = recent.filter { $0 == .accepted }.count
    guard accepted >= HeartMeditationTuning.minAcceptedForSteady else { return .waiting }
    let judged = recent.filter { $0 != .seeding }.count
    let fraction = judged == 0 ? 0 : Double(accepted) / Double(judged)
    return fraction >= HeartMeditationTuning.steadyFraction ? .steady : .noisy
}

/// Plays a batched packet at the recorded R-R spacing, dropping stale queue entries.
final class HeartBeatPlayout {
    private var queue: [Int] = []
    private var lastPulseMs: Int64?

    var queued: Int { queue.count }

    func enqueue(_ rr: Int) {
        queue.append(rr)
        while queue.count > HeartMeditationTuning.maxQueuedBeats { queue.removeFirst() }
    }

    func reset() {
        queue.removeAll()
        lastPulseMs = nil
    }

    func poll(_ nowMs: Int64) -> Int? {
        guard let next = queue.first else { return nil }
        if let last = lastPulseMs {
            guard nowMs - last >= Int64(next) else { return nil }
            queue.removeFirst()
            lastPulseMs = nowMs - last < Int64(next) * 2 ? last + Int64(next) : nowMs
            return next
        }
        queue.removeFirst()
        lastPulseMs = nowMs
        return next
    }
}

/// Beat-triggered heart-rate wave and an eased vessel fill (0...1).
final class HeartRhythmTracker {
    private var baselineHR: Double?
    private var lastThree: [Double] = []
    private var lastDirection = 0
    private var candidate = 0.0
    private var runLow: Double?
    private var runHigh: Double?
    private var lastExtreme: Double?

    private(set) var swingBpm: Double?
    private(set) var wave: [Double] = []

    private var fromLevel = HeartMeditationTuning.restLevel
    private var toLevel = HeartMeditationTuning.restLevel
    private var segmentStartMs: Int64 = 0
    private var segmentMs: Int64 = 1

    func reset(toRest: Bool = true) {
        baselineHR = nil
        lastThree.removeAll()
        lastDirection = 0
        candidate = 0
        runLow = nil
        runHigh = nil
        lastExtreme = nil
        swingBpm = nil
        wave.removeAll()
        guard toRest else { return }
        fromLevel = HeartMeditationTuning.restLevel
        toLevel = HeartMeditationTuning.restLevel
        segmentStartMs = 0
        segmentMs = 1
    }

    func onBeat(_ rrMs: Int, nowMs: Int64) {
        let hr = 60_000.0 / Double(rrMs)
        lastThree.append(hr)
        if lastThree.count > 3 { lastThree.removeFirst() }
        let smooth = lastThree.reduce(0, +) / Double(lastThree.count)
        let base = baselineHR.map {
            $0 + HeartMeditationTuning.baselineWeight * (smooth - $0)
        } ?? smooth
        baselineHR = base
        trackTurningPoint(smooth)

        wave.append(smooth)
        if wave.count > 40 { wave.removeFirst() }

        let half = max(HeartMeditationTuning.minHalfSwingBpm, (swingBpm ?? 0) / 2)
        let unit = max(-1, min(1, (smooth - base) / half))
        fromLevel = levelAt(nowMs)
        toLevel = HeartMeditationTuning.restLevel + HeartMeditationTuning.levelSwing * unit
        segmentStartMs = nowMs
        segmentMs = Int64(max(1, rrMs))
    }

    private func trackTurningPoint(_ hr: Double) {
        let h = HeartMeditationTuning.turnHysteresisBpm
        switch lastDirection {
        case 0:
            let lo = min(runLow ?? hr, hr)
            let hi = max(runHigh ?? hr, hr)
            runLow = lo
            runHigh = hi
            if hr - lo > h {
                lastDirection = 1
                candidate = hr
            } else if hi - hr > h {
                lastDirection = -1
                candidate = hr
            }
        case 1:
            if hr >= candidate {
                candidate = hr
            } else if candidate - hr > h {
                confirmTurn(-1, at: hr)
            }
        default:
            if hr <= candidate {
                candidate = hr
            } else if hr - candidate > h {
                confirmTurn(1, at: hr)
            }
        }
    }

    private func confirmTurn(_ newDirection: Int, at hr: Double) {
        let extreme = candidate
        if let previous = lastExtreme {
            let swing = abs(extreme - previous)
            swingBpm = swingBpm.map {
                $0 + HeartMeditationTuning.swingWeight * (swing - $0)
            } ?? swing
        }
        lastExtreme = extreme
        lastDirection = newDirection
        candidate = hr
    }

    func levelAt(_ nowMs: Int64) -> Double {
        let t = max(0, min(1, Double(nowMs - segmentStartMs) / Double(segmentMs)))
        let eased = 0.5 - 0.5 * cos(.pi * t)
        return fromLevel + (toLevel - fromLevel) * eased
    }

    func settle(_ nowMs: Int64, overMs: Int64 = 1_500) {
        fromLevel = levelAt(nowMs)
        toLevel = HeartMeditationTuning.restLevel
        segmentStartMs = nowMs
        segmentMs = max(1, overMs)
    }
}

struct HeartRhythmFocus {
    let focus: Double
    let perMinute: Double
}

/// The Kotlin twin's 64-second, 2 Hz, detrended/Hann-windowed direct DFT.
/// Peak and neighbors are compared with total power in 0.0033...0.4 Hz.
func heartRhythmFocus(_ cleanRR: [Int]) -> HeartRhythmFocus? {
    let window = HeartMeditationTuning.focusWindowSeconds
    let hz = HeartMeditationTuning.focusSampleHz
    guard cleanRR.count >= 8 else { return nil }

    var covered = 0.0
    var start = cleanRR.count
    while start > 0 && covered < window * 1_000 {
        start -= 1
        covered += Double(cleanRR[start])
    }
    guard covered >= window * 1_000 else { return nil }

    let beats = Array(cleanRR[start...])
    var times: [Double] = []
    var acc = 0.0
    for rr in beats {
        acc += Double(rr) / 1_000
        times.append(acc)
    }
    let hr = beats.map { 60_000.0 / Double($0) }
    guard let end = times.last else { return nil }
    let n = Int((window * hz).rounded())
    var samples = [Double](repeating: 0, count: n)
    var j = 0
    for k in 0..<n {
        let t = end - window + Double(k) / hz
        while j < times.count - 2 && times[j + 1] < t { j += 1 }
        let j1 = min(j + 1, times.count - 1)
        let t0 = times[j]
        let t1 = times[j1]
        let v0 = hr[j]
        let v1 = hr[j1]
        let frac = t1 > t0 ? max(0, min(1, (t - t0) / (t1 - t0))) : 0
        samples[k] = t1 > t0 ? v0 + (v1 - v0) * frac : v0
    }

    let mean = samples.reduce(0, +) / Double(n)
    for k in 0..<n {
        let hann = 0.5 - 0.5 * cos(2 * .pi * Double(k) / Double(n - 1))
        samples[k] = (samples[k] - mean) * hann
    }
    let df = hz / Double(n)
    let lowBin = max(1, Int((0.0033 / df).rounded()))
    let highBin = Int(0.4 / df)
    var power = [Double](repeating: 0, count: highBin + 2)
    for b in lowBin...(highBin + 1) {
        var re = 0.0
        var im = 0.0
        for k in 0..<n {
            let angle = 2 * .pi * Double(b * k) / Double(n)
            re += samples[k] * cos(angle)
            im -= samples[k] * sin(angle)
        }
        power[b] = re * re + im * im
    }
    let total = power[lowBin...highBin].reduce(0, +)
    guard total > 1e-9 else { return nil }
    let peakLow = max(lowBin + 1, Int(ceil(0.04 / df)))
    let peakHigh = Int(0.26 / df)
    var peak = peakLow
    for b in peakLow...peakHigh where power[b] > power[peak] { peak = b }
    let peakPower = power[peak - 1] + power[peak] + power[peak + 1]
    return HeartRhythmFocus(
        focus: max(0, min(1, peakPower / total)),
        perMinute: Double(peak) * df * 60
    )
}

private func heartCyclePosition(_ elapsedMs: Int64, bpm: Double) -> (position: Double, cycleMs: Double) {
    let cycle = 60_000.0 / max(3, min(12, bpm))
    let rem = Double(elapsedMs).truncatingRemainder(dividingBy: cycle)
    return ((rem + cycle).truncatingRemainder(dividingBy: cycle), cycle)
}

/// 0 -> 1 during inhale, then 1 -> 0 during exhale.
func heartPacerLevel(_ elapsedMs: Int64, bpm: Double, inhaleFraction: Double = 0.5) -> Double {
    let (position, cycle) = heartCyclePosition(elapsedMs, bpm: bpm)
    let fraction = max(0.1, min(0.9, inhaleFraction))
    let phase = position / cycle
    return phase < fraction
        ? 0.5 - 0.5 * cos(.pi * phase / fraction)
        : 0.5 + 0.5 * cos(.pi * (phase - fraction) / (1 - fraction))
}

func heartPacerInhaling(_ elapsedMs: Int64, bpm: Double, inhaleFraction: Double = 0.5) -> Bool {
    let (position, cycle) = heartCyclePosition(elapsedMs, bpm: bpm)
    return position / cycle < max(0.1, min(0.9, inhaleFraction))
}

func heartPacerMsToNextPhase(_ elapsedMs: Int64, bpm: Double, inhaleFraction: Double = 0.5) -> Int64 {
    let (position, cycle) = heartCyclePosition(elapsedMs, bpm: bpm)
    let inhaleEnd = cycle * max(0.1, min(0.9, inhaleFraction))
    let boundary = position < inhaleEnd ? inhaleEnd : cycle
    return max(1, Int64((boundary - position).rounded()))
}

/// The exact packet/clock lifecycle of the Kotlin HeartMeditationSession.
final class HeartMeditationSession {
    let gate = HeartBeatGate()
    private let playout = HeartBeatPlayout()
    let rhythm = HeartRhythmTracker()
    private var verdicts: [HeartBeatVerdict] = []
    private var clean: [Int] = []
    private var lastAcceptedMs: Int64?

    private(set) var pulses = 0
    private(set) var focus: HeartRhythmFocus?

    var steadiness: HeartBeatSteadiness { heartBeatSteadiness(verdicts) }

    func onPacket(_ intervals: [Int], nowMs: Int64) {
        for rr in intervals where rr > 0 {
            let verdict = gate.offer(rr)
            verdicts.append(verdict)
            if verdicts.count > HeartMeditationTuning.steadinessWindow { verdicts.removeFirst() }
            if verdict == .accepted {
                lastAcceptedMs = nowMs
                playout.enqueue(rr)
                clean.append(rr)
                if clean.count > 200 { clean.removeFirst() }
            }
        }
        focus = steadiness == .steady ? heartRhythmFocus(clean) : nil
    }

    @discardableResult
    func tick(_ nowMs: Int64) -> Bool {
        if let last = lastAcceptedMs, nowMs - last > HeartMeditationTuning.staleMs {
            lastAcceptedMs = nil
            gate.reset()
            playout.reset()
            verdicts.removeAll()
            clean.removeAll()
            focus = nil
            rhythm.reset(toRest: false)
            rhythm.settle(nowMs)
            return false
        }
        guard let rr = playout.poll(nowMs) else { return false }
        rhythm.onBeat(rr, nowMs: nowMs)
        if steadiness == .waiting {
            rhythm.settle(nowMs)
            return false
        }
        pulses += 1
        return true
    }

    var cleanBpm: Int? {
        guard steadiness != .waiting, !clean.isEmpty else { return nil }
        let recent = clean.suffix(5)
        let average = Double(recent.reduce(0, +)) / Double(recent.count)
        return Int((60_000.0 / average).rounded())
    }

    func levelAt(_ nowMs: Int64) -> Double { rhythm.levelAt(nowMs) }
}
