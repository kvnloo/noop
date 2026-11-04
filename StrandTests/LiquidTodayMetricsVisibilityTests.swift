import XCTest
@testable import Strand

/// #2711: Liquid Today must start as compactly as classic Today and Android.
/// Expanding is presentation-only; it cannot disable or reorder saved Key Metrics.
final class LiquidTodayMetricsVisibilityTests: XCTestCase {

    func testFreshDefaultShowsSixOfTenWithoutDroppingTheirOrder() {
        let enabled = KeyMetric.defaultOrder
        XCTAssertEqual(enabled.count, 10)
        XCTAssertEqual(LiquidTodayView.metricsCollapsedCap, 6)
        XCTAssertEqual(LiquidTodayView.metricsCollapsedCap, TodayView.metricsCollapsedCap)
        XCTAssertEqual(
            LiquidTodayView.visibleMetrics(enabled, expanded: false),
            [.charge, .effort, .rest, .hrv, .restingHr, .bloodOxygen]
        )
    }

    func testExpandAndCollapseDoNotChangeTheFullSavedSelection() {
        let enabled: [KeyMetric] = [
            .steps, .calories, .rest, .charge, .respiratory,
            .hrv, .weight, .effort
        ]
        XCTAssertEqual(
            LiquidTodayView.visibleMetrics(enabled, expanded: false),
            [.steps, .calories, .rest, .charge, .respiratory, .hrv]
        )
        XCTAssertEqual(LiquidTodayView.visibleMetrics(enabled, expanded: true), enabled)
        XCTAssertEqual(
            LiquidTodayView.visibleMetrics(enabled, expanded: false),
            Array(enabled.prefix(6))
        )
    }

    func testNoOverflowForSixOrFewerSelectedMetrics() {
        let exactlySix = Array(KeyMetric.defaultOrder.prefix(6))
        XCTAssertEqual(LiquidTodayView.visibleMetrics(exactlySix, expanded: false), exactlySix)
        XCTAssertEqual(LiquidTodayView.visibleMetrics([.steps, .hrv], expanded: false), [.steps, .hrv])
        XCTAssertEqual(LiquidTodayView.visibleMetrics([], expanded: false), [])
    }

    func testExplicitOptInMetricAppearsWhenExpanded() {
        let enabled = KeyMetric.defaultOrder + [.skinTemp]
        XCTAssertEqual(LiquidTodayView.visibleMetrics(enabled, expanded: false).count, 6)
        XCTAssertEqual(LiquidTodayView.visibleMetrics(enabled, expanded: true).last, .skinTemp)
        XCTAssertEqual(LiquidTodayView.visibleMetrics(enabled, expanded: true).count, 11)
    }
}
