import Foundation

public struct ClockDriftSample: Equatable, Sendable {
    public let drifted: Bool
    public let wallDelta: TimeInterval
    public let monotonicDelta: TimeInterval
}

public struct ClockDriftDetector: Sendable {
    private var anchor: (wall: Date, monotonic: ContinuousClock.Instant)?

    public init() {}

    public mutating func reset() {
        anchor = nil
    }

    public mutating func sample(
        wall: Date = .now,
        monotonic: ContinuousClock.Instant = ContinuousClock().now
    ) -> ClockDriftSample {
        guard let anchor else {
            anchor = (wall, monotonic)
            return ClockDriftSample(drifted: false, wallDelta: 0, monotonicDelta: 0)
        }

        let wallDelta = wall.timeIntervalSince(anchor.wall)
        let elapsed = anchor.monotonic.duration(to: monotonic).components
        let monotonicDelta = Double(elapsed.seconds) + Double(elapsed.attoseconds) / 1e18
        return ClockDriftSample(
            drifted: abs(wallDelta - monotonicDelta) > 2,
            wallDelta: wallDelta,
            monotonicDelta: monotonicDelta
        )
    }
}
