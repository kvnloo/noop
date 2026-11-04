import XCTest
import WhoopProtocol
@testable import Strand

/// Synthetic WHOOP 4.0 envelopes only: no physical strap or LED mapping is inferred.
@MainActor
final class Whoop4OpticalConfigReadTests: XCTestCase {
    private func reply(
        opcode: UInt8 = 40,
        origin: UInt8 = 7,
        result: UInt8 = 1,
        body: [UInt8] = [0xAB, 0xCD],
        type: UInt8 = 36
    ) -> [UInt8] {
        frameFromPayload([origin, result] + body, type: type, seq: 99, cmd: opcode)
    }

    private func classify(
        _ frame: [UInt8],
        expecting operation: Whoop4OpticalConfigRead.Operation = .ledDrive,
        requestSequence: UInt8 = 7,
        expectedLink: UInt64 = 12,
        receivedLink: UInt64 = 12,
        family: DeviceFamily = .whoop4
    ) -> Whoop4OpticalConfigRead.Classification {
        Whoop4OpticalConfigRead.classify(
            frame: frame, expecting: operation, requestSequence: requestSequence,
            expectedLinkGeneration: expectedLink, receivedLinkGeneration: receivedLink,
            family: family)
    }

    func testExactlyThreeReadOnlyRequestsAndNoSetOpcode() {
        let allowed: Set<UInt8> = [40, 42, 44]
        var constructed = 0
        for raw in 0...255 {
            let opcode = UInt8(raw)
            let frame = Whoop4OpticalConfigRead.requestFrame(opcode: opcode, sequence: 0xFE, family: .whoop4)
            if allowed.contains(opcode) {
                constructed += 1
                guard let frame else { XCTFail("missing GET opcode \(opcode)"); continue }
                XCTAssertEqual(frame.count, 12, "4 header + 3 command + 1 payload + 4 CRC")
                XCTAssertTrue(verifyFrame(frame, family: .whoop4).ok)
                XCTAssertEqual(Array(frame[4...7]), [35, 0xFE, opcode, 0x00])
            } else {
                XCTAssertNil(frame, "only the three GETs may be framed; opcode=\(opcode)")
            }
        }
        XCTAssertEqual(constructed, 3)
        XCTAssertNil(Whoop4OpticalConfigRead.requestFrame(opcode: 39, sequence: 1, family: .whoop4))
        XCTAssertNil(Whoop4OpticalConfigRead.requestFrame(opcode: 41, sequence: 1, family: .whoop4))
        XCTAssertNil(Whoop4OpticalConfigRead.requestFrame(opcode: 43, sequence: 1, family: .whoop4))
    }

    func testWhoopFiveCannotConstructOrAcceptTheSameOpticalRead() {
        for opcode in [UInt8(40), 42, 44] {
            XCTAssertNil(Whoop4OpticalConfigRead.requestFrame(
                opcode: opcode, sequence: 7, family: .whoop5))
        }
        XCTAssertEqual(classify(reply(), family: .whoop5), .unrelated)
    }

    func testCorrelatedSuccessRetainsOnlyAnIntegrityVerifiedBoundedRawFrame() {
        let frame = reply()
        XCTAssertEqual(frame.count, 15, "4 header + 3 prefix + 4 reply bytes + 4 CRC")
        XCTAssertTrue(verifyFrame(frame, family: .whoop4).ok)
        guard case .response(let response) = classify(frame) else {
            XCTFail("expected correlated reply"); return
        }
        XCTAssertEqual(response.operation, .ledDrive)
        XCTAssertEqual(response.requestSequence, 7)
        XCTAssertEqual(response.result, .success)
        XCTAssertEqual(response.rawFrame, frame)
        XCTAssertEqual(response.rawBody, [0xAB, 0xCD], "CRC32 is not body data")
    }

    func testAllObservedResultRolesStayDistinct() {
        let cases: [(UInt8, Whoop4OpticalConfigRead.Result)] = [
            (0, .failure), (1, .success), (2, .pending), (3, .unsupported),
            (0xEE, .unknown(0xEE)),
        ]
        for (byte, expected) in cases {
            guard case .response(let response) = classify(reply(result: byte)) else {
                XCTFail("expected CRC-valid response for result=\(byte)"); continue
            }
            XCTAssertEqual(response.result, expected)
        }
    }

    func testOtherCommandsAndNonResponsesAreUnrelated() {
        XCTAssertEqual(classify(reply(opcode: 42)), .unrelated)
        XCTAssertEqual(classify(reply(type: 35)), .unrelated)
        XCTAssertEqual(classify(reply(opcode: 42), expecting: .tiaGain),
                       .response(.init(operation: .tiaGain,
                                       requestSequence: 7, result: .success,
                                       rawFrame: reply(opcode: 42))))
    }

    func testOriginSequenceAndConnectionGenerationMustBothMatch() {
        XCTAssertEqual(classify(reply(origin: 8)), .uncorrelated)
        XCTAssertEqual(classify(reply(), expectedLink: 12, receivedLink: 11), .uncorrelated)
        XCTAssertEqual(classify(reply(), requestSequence: 8), .uncorrelated)
        // The reply frame's own sequence is 99, not the request origin (7).
        XCTAssertEqual(classify(reply()), classify(reply(), requestSequence: 7))
    }

    func testCRCFailuresAreCorruptAndCannotProduceResponses() {
        var badHeader = reply()
        badHeader[3] ^= 1
        XCTAssertEqual(verifyFrame(badHeader, family: .whoop4).reason,
                       .headerChecksumMismatch)
        XCTAssertEqual(classify(badHeader), .corrupt)

        var badPayload = reply()
        badPayload[10] ^= 1
        XCTAssertEqual(verifyFrame(badPayload, family: .whoop4).reason,
                       .payloadCRCMismatch)
        XCTAssertEqual(classify(badPayload), .corrupt)
    }

    func testShortResponseAndCutTrailerStayTruncated() {
        // A CRC-valid response with only the origin byte lacks the result field.
        let missingResult = frameFromPayload([7], type: 36, seq: 99, cmd: 40)
        XCTAssertTrue(verifyFrame(missingResult, family: .whoop4).ok)
        XCTAssertEqual(missingResult.count, 12)
        XCTAssertEqual(classify(missingResult), .truncated)

        let cutTrailer = Array(reply().dropLast(2))
        XCTAssertEqual(verifyFrame(cutTrailer, family: .whoop4).reason, .lengthMismatch)
        XCTAssertEqual(classify(cutTrailer), .truncated)
    }

    func testOversizedOtherwiseValidResponseIsNeverRetained() {
        // 4 header + 3 prefix + (2 origin/result + 250 body) + 4 CRC = 263.
        let large = reply(body: [UInt8](repeating: 0x55, count: 250))
        XCTAssertEqual(large.count, 263)
        XCTAssertTrue(verifyFrame(large, family: .whoop4).ok)
        XCTAssertEqual(classify(large), .overLimit)
        XCTAssertEqual(Whoop4OpticalConfigRead.maxResponseBytes, 256)
    }
}
