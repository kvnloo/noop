import XCTest
@testable import Strand

/// #1149: WHOOP 4.0 has no native step count, but its banked HR can still produce a
/// daily calorie estimate. Apple Health owns the value only on days it actually recorded.
final class TodayCaloriesFallbackTests: XCTestCase {

    func testBleOnlyDayUsesComputedCalories() {
        // No Apple Health import; the HR scorer's 245.5 kcal must reach Today.
        XCTAssertEqual(TodayView.chooseCalories(imported: nil, computed: 245.5), 245.5)
    }

    func testSameDayImportBeatsComputedEstimate() {
        XCTAssertEqual(TodayView.chooseCalories(imported: 320, computed: 245.5), 320)
    }

    func testZeroOrNegativeImportDoesNotMaskComputedEstimate() {
        XCTAssertEqual(TodayView.chooseCalories(imported: 0, computed: 245.5), 245.5)
        XCTAssertEqual(TodayView.chooseCalories(imported: -8, computed: 245.5), 245.5)
    }

    func testNeitherSourceIsHonestMissingData() {
        XCTAssertNil(TodayView.chooseCalories(imported: nil, computed: nil))
        XCTAssertTrue(TodayView.resolvedCalories(strap: [], imported: []).isEmpty)
    }

    func testDailySparkUnionUsesApplePriorityWithoutDoubleCounting() {
        let strap = [(day: "2026-08-07", value: 100.0),
                     (day: "2026-08-08", value: 180.0)]
        let apple = [(day: "2026-08-06", value: 75.0),
                     (day: "2026-08-08", value: 300.0)]
        let points = TodayView.resolvedCalories(strap: strap, imported: apple)
        // 06 imported, 07 strap-only, 08 imported overrides 180 rather than adds it.
        XCTAssertEqual(points.map { $0.day }, ["2026-08-06", "2026-08-07", "2026-08-08"])
        XCTAssertEqual(points.map { $0.value }, [75.0, 100.0, 300.0])
    }

    func testYesterdayImportNeverCarriesIntoToday() {
        let points = TodayView.resolvedCalories(
            strap: [(day: "2026-08-08", value: 180.0)],
            imported: [(day: "2026-08-07", value: 300.0)])
        XCTAssertEqual(points.map { $0.day }, ["2026-08-07", "2026-08-08"])
        XCTAssertEqual(points.map { $0.value }, [300.0, 180.0])
    }

    func testNonPositiveImportCannotCreateAnEmptyDay() {
        let points = TodayView.resolvedCalories(
            strap: [(day: "2026-08-08", value: 180.0)],
            imported: [(day: "2026-08-07", value: 0.0),
                       (day: "2026-08-08", value: -12.0)])
        XCTAssertEqual(points.map { $0.day }, ["2026-08-08"])
        XCTAssertEqual(points.map { $0.value }, [180.0])
    }
}
