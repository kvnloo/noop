import Foundation
import SwiftUI
import StrandDesign
import StrandAnalytics

/// Apple companion to Android's Live heart meditation (#125). Uses the same pure,
/// clock-injected artifact gate, packet playout, wave and optional breathing guide.
struct HeartMeditationView: View {
    @EnvironmentObject private var live: LiveState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject private var motion = NoopMotionState.shared
    @AppStorage("live.heartMeditation.guide") private var guideOn = true

    let onClose: () -> Void

    @State private var session = HeartMeditationSession()
    @State private var startedMs: Int64 = 0
    @State private var frameMs: Int64 = 0
    @State private var pulseCount = 0
    @State private var pulseAtMs: Int64 = 0
    @State private var bpm: Int?
    @State private var steadiness: HeartBeatSteadiness = .waiting
    @State private var swingBpm: Double?
    @State private var focus: HeartRhythmFocus?
    @State private var wave: [Double] = []
    @State private var level = HeartMeditationTuning.restLevel

    private let pulseTimer = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    /// Same user-selected resonance pace as Android; no changes to stored settings.
    private var pace: Double { BiofeedbackPrefs.lockedPace ?? ResonanceEngine.fallbackBpm }

    /// The separate catalog contains translations for every new surface string.
    private func label(_ key: String) -> String {
        String(localized: String.LocalizationValue(key), table: "HeartMeditation")
    }

    private var elapsedMs: Int64 { max(0, frameMs - startedMs) }
    private var showingBeats: Bool { steadiness != .waiting }
    private var still: Bool { motion.poseStill(reduceMotion) }
    private var haloProgress: Double {
        guard pulseAtMs > 0, !still else { return 0 }
        return max(0, 1 - Double(max(0, frameMs - pulseAtMs)) / 900)
    }
    private var guideLevel: Double {
        guideOn ? heartPacerLevel(elapsedMs, bpm: pace) : 0
    }

    private var status: String {
        switch steadiness {
        case .waiting: return label("Waiting for steady beats")
        case .noisy: return label("Some beats look noisy")
        case .steady: return label("Steady beats")
        }
    }

    private var phase: String {
        if !showingBeats { return label("Waiting for steady beats") }
        if guideOn {
            return heartPacerInhaling(elapsedMs, bpm: pace)
                ? label("Breathe in") : label("Breathe out")
        }
        return label("Breathe slowly and watch the level follow")
    }

    var body: some View {
        ScreenScaffold(
            title: label("Heart meditation"),
            subtitle: label("Each ripple is one of your heartbeats. The level rises and falls with the wave your breathing puts into your heart rate."),
            topBackground: liquidScaffoldSky()
        ) {
            VStack(alignment: .leading, spacing: NoopMetrics.sectionGap) {
                HStack {
                    Spacer()
                    Button(action: onClose) {
                        Label(label("Close heart meditation"), systemImage: "xmark")
                    }
                    .buttonStyle(.noopSecondary)
                    .accessibilityLabel(Text(verbatim: label("Close heart meditation")))
                }

                NoopCard {
                    VStack(spacing: NoopMetrics.gap) {
                        ZStack {
                            if guideOn {
                                Circle()
                                    .stroke(StrandPalette.accent.opacity(0.18), lineWidth: NoopMetrics.hairlineWidth)
                                    .scaleEffect(0.9 + 0.2 * guideLevel)
                                    .accessibilityHidden(true)
                            }
                            if haloProgress > 0 {
                                Circle()
                                    .stroke(
                                        StrandPalette.effortColor.opacity(0.4 * haloProgress),
                                        lineWidth: NoopMetrics.hairlineWidth
                                    )
                                    .scaleEffect(1 + 0.22 * (1 - haloProgress))
                                    .accessibilityHidden(true)
                            }
                            LiquidVessel(
                                value: showingBeats && !still ? level : HeartMeditationTuning.restLevel,
                                tint: StrandPalette.effortColor,
                                animated: showingBeats && !still
                            )
                            .accessibilityHidden(true)

                            VStack(spacing: NoopMetrics.space1) {
                                Text(verbatim: bpm.map(String.init) ?? "—")
                                    .font(StrandFont.display(64))
                                    .monospacedDigit()
                                    .foregroundStyle(StrandPalette.textPrimary)
                                Text(verbatim: label("bpm"))
                                    .font(StrandFont.caption)
                                    .foregroundStyle(StrandPalette.textSecondary)
                            }
                        }
                        .frame(maxWidth: NoopMetrics.chartHeight)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text(verbatim: "\(status), \(bpm.map(String.init) ?? "—") \(label("bpm"))"))

                        Text(verbatim: phase)
                            .font(StrandFont.headline)
                            .foregroundStyle(StrandPalette.textPrimary)


                        Text(verbatim: status)
                            .font(StrandFont.caption)
                            .foregroundStyle(StrandPalette.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                }

                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                        Text(verbatim: label("Heart-rate wave"))
                            .font(StrandFont.overline)
                            .foregroundStyle(StrandPalette.textSecondary)
                        if wave.count >= 2 && showingBeats {
                            LiquidThread(
                                bpm: wave,
                                tint: StrandPalette.effortColor,
                                height: NoopMetrics.tileHeight,
                                animated: !still
                            )
                            .accessibilityHidden(true)
                        } else {
                            Text(verbatim: label("Sit still with the ring snug. The view starts once a few clean beats agree."))
                                .font(StrandFont.caption)
                                .foregroundStyle(StrandPalette.textTertiary)
                                .frame(maxWidth: .infinity, minHeight: NoopMetrics.tileHeight)
                        }
                    }
                }

                HStack(alignment: .top, spacing: NoopMetrics.gap) {
                    NoopCard {
                        metric(
                            label("Wave swing"),
                            value: swingBpm.map { "±\(Int(($0 / 2).rounded()))" } ?? "—",
                            unit: label("bpm")
                        )
                    }
                    NoopCard {
                        metric(
                            label("Rhythm focus"),
                            value: focus.map { "\(Int(($0.focus * 100).rounded()))% · ~\(String(format: "%.1f", $0.perMinute))" } ?? "—",
                            unit: label("breaths/min")
                        )
                    }
                }

                NoopCard {
                    Toggle(isOn: $guideOn) {
                        VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                            Text(verbatim: label("Breathing guide"))
                                .font(StrandFont.headline)
                                .foregroundStyle(StrandPalette.textPrimary)
                            Text(verbatim: "\(String(format: "%.1f", pace)) \(label("breaths/min"))")
                                .font(StrandFont.captionNumber)
                                .foregroundStyle(StrandPalette.textSecondary)
                        }
                    }
                    .tint(StrandPalette.accent)
                }

                Text(verbatim: label("Estimates from optical beat timing, with noisy beats skipped. They only mean something while you are still, and they are not a medical reading."))
                    .font(StrandFont.caption)
                    .foregroundStyle(StrandPalette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .onAppear {
            let now = monotonicMs()
            startedMs = now
            frameMs = now
        }
        // Observe rrSeq, not the packet value: repeated identical R-R packets are distinct beats.
        .onRRPackets(live) { intervals in
            let now = monotonicMs()
            session.onPacket(intervals, nowMs: now)
            session.tick(now)
            updateSnapshot(at: now)
        }
        .onReceive(pulseTimer) { _ in
            guard startedMs != 0 else { return }
            let now = monotonicMs()
            let previousPulses = session.pulses
            session.tick(now)
            // In Reduce Motion, redraw only when a beat arrives, quality changes or a breath
            // phase changes. The liquid primitive itself uses a static canvas in this mode.
            if still && session.pulses == previousPulses {
                let oldPhase = heartPacerInhaling(elapsedMs, bpm: pace)
                let newPhase = heartPacerInhaling(now - startedMs, bpm: pace)
                if oldPhase == newPhase && session.steadiness == steadiness { return }
            }
            updateSnapshot(at: now)
        }
    }

    private func metric(_ title: String, value: String, unit: String) -> some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            Text(verbatim: title)
                .font(StrandFont.overline)
                .foregroundStyle(StrandPalette.textSecondary)
            HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space1) {
                Text(verbatim: value)
                    .font(StrandFont.bodyNumber)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text(verbatim: unit)
                    .font(StrandFont.caption)
                    .foregroundStyle(StrandPalette.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func updateSnapshot(at now: Int64) {
        frameMs = now
        let nextSteadiness = session.steadiness
        steadiness = nextSteadiness
        if session.pulses != pulseCount { pulseAtMs = now }
        pulseCount = session.pulses
        bpm = session.cleanBpm
        swingBpm = nextSteadiness == .waiting ? nil : session.rhythm.swingBpm
        focus = session.focus
        wave = nextSteadiness == .waiting ? [] : session.rhythm.wave
        level = session.levelAt(now)
    }

    private func monotonicMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1_000)
    }
}
