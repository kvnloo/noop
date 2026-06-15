import Foundation
import Combine
import CoreBluetooth
import WhoopProtocol
import WhoopStore

/// An ISOLATED standard-Bluetooth Heart-Rate source for generic HR straps
/// (Polar / Wahoo / Coospo / Garmin HRM / Amazfit Helio broadcast) that expose the standard
/// BLE Heart Rate Service (0x180D) with the Heart Rate Measurement characteristic (0x2A37).
///
/// WHOOP-FIRST ISOLATION: this class runs its OWN `CBCentralManager` and never imports, calls, or
/// shares state with `BLEManager`. The WHOOP path cannot regress because of anything here — the two
/// CoreBluetooth flows are fully independent. The only shared surfaces are `LiveState` (so the
/// existing Live UI shows the strap's HR) and the `persist` closure (wired by the app to
/// `StreamStore.insert`). The pure HR→Streams mapping lives in `WhoopStore.StandardHRMapping` so it
/// can be unit-tested away from CoreBluetooth.
@MainActor
public final class StandardHRSource: NSObject, ObservableObject {

    // MARK: - Public model

    /// A generic HR strap seen during a scan.
    public struct DiscoveredStrap: Identifiable, Equatable {
        public let id: UUID
        public let name: String
        public let rssi: Int
    }

    /// Straps discovered during the current scan, keyed by peripheral identifier.
    @Published public private(set) var discovered: [DiscoveredStrap] = []
    /// True while a scan is running (UI affordance).
    @Published public private(set) var scanning: Bool = false

    // MARK: - Standard BLE UUIDs

    private static let heartRateService = CBUUID(string: "180D")
    private static let heartRateMeasurement = CBUUID(string: "2A37")

    // MARK: - Dependencies (injected — no BLEManager reference)

    private let live: LiveState
    private let persist: (Streams) -> Void
    private let deviceId: String

    // MARK: - CoreBluetooth state (OWN central, separate from WHOOP)

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    /// A peripheral asked to connect before `centralManagerDidUpdateState` reported `.poweredOn`.
    private var pendingConnectID: UUID?
    /// Peripherals retained by identifier so a chosen one survives until connection.
    private var seenPeripherals: [UUID: CBPeripheral] = [:]

    // MARK: - Sample buffer

    /// Buffered (hr, rr, ts) readings, flushed to `persist` in batches to keep the write path off
    /// the per-notification hot loop.
    private var buffer: [(hr: Int, rr: [Int], ts: Int)] = []
    private var lastFlush: Date = .init()
    /// Flush thresholds — whichever trips first.
    private let flushCount = 30
    private let flushInterval: TimeInterval = 30

    // MARK: - Init

    /// - Parameters:
    ///   - live: the shared `LiveState` the Live UI observes.
    ///   - deviceId: the datastore device id these samples are attributed to.
    ///   - persist: wired by the app to `store.insert(_, deviceId:)`. Called on the main actor.
    public init(live: LiveState, deviceId: String, persist: @escaping (Streams) -> Void) {
        self.live = live
        self.deviceId = deviceId
        self.persist = persist
        super.init()
        // Dedicated queue-less central → callbacks arrive on the main queue, matching @MainActor.
        self.central = CBCentralManager(delegate: self, queue: nil)
    }

    // MARK: - Scanning

    /// Begin scanning for generic HR straps advertising the 0x180D service.
    public func scan() {
        discovered.removeAll()
        seenPeripherals.removeAll()
        scanning = true
        guard central.state == .poweredOn else { return }   // deferred until poweredOn
        central.scanForPeripherals(withServices: [Self.heartRateService],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    /// Stop an in-progress scan.
    public func stopScan() {
        scanning = false
        if central.state == .poweredOn { central.stopScan() }
    }

    // MARK: - Connecting

    /// Connect to the chosen discovered strap and start streaming its HR.
    public func connect(_ id: UUID) {
        stopScan()
        guard let p = seenPeripherals[id] else { return }
        peripheral = p
        p.delegate = self
        guard central.state == .poweredOn else { pendingConnectID = id; return }
        central.connect(p, options: nil)
    }

    /// Tear down: cancel the peripheral connection and stop scanning. Idempotent.
    public func stop() {
        stopScan()
        pendingConnectID = nil
        if let p = peripheral {
            central.cancelPeripheralConnection(p)
        }
        peripheral = nil
        flush()                       // persist anything still buffered
        live.connected = false
    }

    // MARK: - Buffer / persistence

    private func enqueue(hr: Int, rr: [Int]) {
        buffer.append((hr: hr, rr: rr, ts: Int(Date().timeIntervalSince1970)))
        if buffer.count >= flushCount || Date().timeIntervalSince(lastFlush) >= flushInterval {
            flush()
        }
    }

    private func flush() {
        guard !buffer.isEmpty else { lastFlush = Date(); return }
        for sample in buffer {
            persist(StandardHRMapping.samples(fromHR: sample.hr, rr: sample.rr, at: sample.ts))
        }
        buffer.removeAll()
        lastFlush = Date()
    }

    // CB delegate callbacks live in the @preconcurrency extensions below. The queue-less central
    // delivers them on the main thread, so MainActor isolation is sound; @preconcurrency lets this
    // @MainActor type satisfy the nonisolated CoreBluetooth requirements (same pattern as BLEManager).
}

// MARK: - CBCentralManagerDelegate

extension StandardHRSource: @preconcurrency CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            // Replay any intent that arrived before the radio was ready.
            if let id = pendingConnectID, let p = seenPeripherals[id] {
                pendingConnectID = nil
                central.connect(p, options: nil)
            } else if scanning {
                central.scanForPeripherals(withServices: [Self.heartRateService],
                                           options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
            }
        default:
            // Radio off / unauthorized / resetting → the link is not live.
            live.connected = false
        }
    }

    public func centralManager(_ central: CBCentralManager,
                               didDiscover peripheral: CBPeripheral,
                               advertisementData: [String: Any],
                               rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        seenPeripherals[id] = peripheral
        let advName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let name = advName ?? peripheral.name ?? "Heart Rate Strap"
        let strap = DiscoveredStrap(id: id, name: name, rssi: RSSI.intValue)
        if let idx = discovered.firstIndex(where: { $0.id == id }) {
            discovered[idx] = strap
        } else {
            discovered.append(strap)
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices([Self.heartRateService])
    }

    public func centralManager(_ central: CBCentralManager,
                               didFailToConnect peripheral: CBPeripheral, error: Error?) {
        live.connected = false
    }

    public func centralManager(_ central: CBCentralManager,
                               didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        flush()
        live.connected = false
        if self.peripheral?.identifier == peripheral.identifier {
            self.peripheral = nil
        }
    }
}

// MARK: - CBPeripheralDelegate

extension StandardHRSource: @preconcurrency CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services else { return }
        for svc in services where svc.uuid == Self.heartRateService {
            peripheral.discoverCharacteristics([Self.heartRateMeasurement], for: svc)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral,
                           didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil, let chars = service.characteristics else { return }
        for ch in chars where ch.uuid == Self.heartRateMeasurement {
            peripheral.setNotifyValue(true, for: ch)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral,
                           didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil,
              characteristic.uuid == Self.heartRateMeasurement,
              let value = characteristic.value else { return }
        guard let parsed = StandardHeartRate.parse([UInt8](value)) else { return }
        live.heartRate = parsed.hr
        live.setRRIntervals(parsed.rr)
        live.connected = true
        enqueue(hr: parsed.hr, rr: parsed.rr)
    }
}
