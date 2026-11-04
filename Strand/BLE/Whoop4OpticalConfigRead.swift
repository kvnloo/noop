import Foundation
import WhoopProtocol

/// Pure Apple-side guard for the WHOOP 4.0 optical front-end READ commands (#2793).
///
/// This does not send BLE writes, change a strap setting, or identify red/IR channels.
/// It gives a future, explicitly initiated iOS diagnostic a bounded request builder and
/// an integrity- and link-correlated response classifier. WHOOP 5/MG shares opcode
/// numbers with unrelated commands, so only a caller that has positively established
/// WHOOP 4.0 may use these frames. No generic command-sender entry is added.
///
/// Protocol evidence: docs/PROTOCOL_COMMANDS.md (40/42/44 on 41.17.6.0);
/// docs/PROTOCOL_TRANSPORT.md (type 36, cmd @6, origin @7, result @8).
enum Whoop4OpticalConfigRead {
    enum Operation: UInt8, CaseIterable, Equatable {
        case ledDrive = 40
        case tiaGain = 42
        case biasOffset = 44
    }

    /// Only three GETs, with the one-byte body observed in the 41.16.6.0 probe.
    /// Crucially, the adjacent SET opcodes 39/41/43 have no request path here.
    static func requestFrame(opcode: UInt8, sequence: UInt8, family: DeviceFamily) -> [UInt8]? {
        guard family == .whoop4, Operation(rawValue: opcode) != nil else { return nil }
        return frameFromPayload([0x00], type: 35, seq: sequence, cmd: opcode)
    }

    /// Hard bound for a single retained raw response (header and CRC included).
    /// Three requests may produce pending/final pairs; the caller must also cap
    /// its transcript and end the run on disconnect, background or timeout.
    static let maxResponseBytes = 256

    /// WHOOP 4.0 observed result roles; none confirms a physical LED effect or
    /// establishes which sensor trace is red/IR. Unknown codes remain unknown.
    enum Result: Equatable {
        case failure
        case success
        case pending
        case unsupported
        case unknown(UInt8)

        init(byte: UInt8) {
            switch byte {
            case 0: self = .failure
            case 1: self = .success
            case 2: self = .pending
            case 3: self = .unsupported
            default: self = .unknown(byte)
            }
        }
    }

    struct Response: Equatable {
        let operation: Operation
        let requestSequence: UInt8
        let result: Result
        /// Entire CRC-verified wire response, not an inferred channel/gain/value.
        let rawFrame: [UInt8]

        /// Body starts after type, reply-sequence, opcode, origin and result.
        /// The four CRC32 trailer bytes must never be interpreted as body.
        var rawBody: [UInt8] { Array(rawFrame[9..<(rawFrame.count - 4)]) }
    }

    enum Classification: Equatable {
        case unrelated
        case uncorrelated
        case truncated
        case corrupt
        case overLimit
        case response(Response)
    }

    /// Classify one reassembled frame. The receivedLinkGeneration parameter comes
    /// from the transport: the connection generation is NOT part of the WHOOP 4
    /// envelope. An old reply with an identical 8-bit origin cannot cross links.
    ///
    /// Both WHOOP 4 checksums must pass before retaining bytes or reading fields.
    /// A reply is matched only by packet type, opcode, originating request
    /// sequence and link generation; the independent reply sequence at @5 is
    /// deliberately not used to correlate a request.
    static func classify(
        frame: [UInt8],
        expecting operation: Operation,
        requestSequence: UInt8,
        expectedLinkGeneration: UInt64,
        receivedLinkGeneration: UInt64,
        family: DeviceFamily
    ) -> Classification {
        guard family == .whoop4 else { return .unrelated }
        guard frame.count <= maxResponseBytes else { return .overLimit }
        let check = verifyFrame(frame, family: .whoop4)
        guard check.ok else {
            switch check.reason {
            case .belowMinimumLength, .lengthMismatch: return .truncated
            case .none, .noStartOfFrame, .headerChecksumMismatch, .payloadCRCMismatch:
                return .corrupt
            }
        }

        // [4] type 36, [5] reply seq, [6] cmd, [7] request origin,
        // [8] result, [9...] body, final four bytes CRC32.
        guard frame.count >= 13 else { return .truncated }
        guard frame[4] == 36, frame[6] == operation.rawValue else { return .unrelated }
        guard expectedLinkGeneration == receivedLinkGeneration,
              frame[7] == requestSequence else { return .uncorrelated }
        return .response(Response(operation: operation,
                                  requestSequence: requestSequence,
                                  result: Result(byte: frame[8]),
                                  rawFrame: frame))
    }
}
