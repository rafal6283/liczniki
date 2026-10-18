import Foundation
import ActivityKit

/// Stoper trwającego zdarzenia na ekranie blokady i w Dynamic Island.
struct TrackerActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var name: String
        var color: Int
        var paused: Bool
        /// Punkt odniesienia dla stopera: teraz − czas aktywny (stoper liczy bez pauz).
        var timerStart: Date
        var activeMs: Int64
        var startedAt: Date
    }

    var trackerId: Int64
}

enum LiveActivityManager {
    /// Live Activity można zakładać tylko z aplikacji, nie z widżetu.
    static var isApp: Bool { Bundle.main.bundleURL.pathExtension == "app" }

    private static var chain: Task<Void, Never>?

    static func sync() {
        #if WIDGET_EXTENSION
        return
        #else
        guard isApp else { return }
        let prev = chain
        chain = Task {
            await prev?.value
            await syncNow()
        }
        #endif
    }

    static func state(for s: Session, tracker t: Tracker) -> TrackerActivityAttributes.ContentState {
        let pauses = Repo.pauses(s.id)
        let now = nowMs()
        let active = Timing.activeMs(s, pauses, now: now)
        return TrackerActivityAttributes.ContentState(
            name: t.name,
            color: t.color,
            paused: pauses.contains { $0.end == nil },
            timerStart: (now - active).date,
            activeMs: active,
            startedAt: s.start.date
        )
    }

    static func syncNow() async {
        #if WIDGET_EXTENSION
        return
        #else
        guard isApp else { return }
        let running = Repo.running()
        let trackers = Dictionary(uniqueKeysWithValues: Repo.trackers().map { ($0.id, $0) })
        var keep = Set<Int64>()
        let enabled = ActivityAuthorizationInfo().areActivitiesEnabled
        for s in running {
            guard let t = trackers[s.trackerId] else { continue }
            keep.insert(t.id)
            let content = ActivityContent(state: state(for: s, tracker: t), staleDate: nil)
            if let a = Activity<TrackerActivityAttributes>.activities.first(where: { $0.attributes.trackerId == t.id }) {
                await a.update(content)
            } else if enabled {
                _ = try? Activity<TrackerActivityAttributes>.request(attributes: TrackerActivityAttributes(trackerId: t.id), content: content, pushType: nil)
            }
        }
        for a in Activity<TrackerActivityAttributes>.activities where !keep.contains(a.attributes.trackerId) {
            await a.end(nil, dismissalPolicy: .immediate)
        }
        #endif
    }
}
