import Foundation
import AppIntents
import WidgetKit

/* Akcje przycisków w widżetach i w Live Activity — działają bez otwierania aplikacji. */

struct IncrementCounterIntent: AppIntent {
    static var title: LocalizedStringResource = "Dodaj do licznika"
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Licznik")
    var counterId: Int

    init() {}
    init(counterId: Int64) { self.counterId = Int(counterId) }

    func perform() async throws -> some IntentResult {
        Repo.increment(Int64(counterId))
        return .result()
    }
}

struct DecrementCounterIntent: AppIntent {
    static var title: LocalizedStringResource = "Cofnij w liczniku"
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Licznik")
    var counterId: Int

    init() {}
    init(counterId: Int64) { self.counterId = Int(counterId) }

    func perform() async throws -> some IntentResult {
        Repo.decrement(Int64(counterId))
        return .result()
    }
}

/// Akcje zdarzeń wykonują się w procesie aplikacji (LiveActivityIntent), więc mogą też
/// założyć lub zaktualizować stoper na ekranie blokady.
struct StartTrackerIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Start zdarzenia"

    @Parameter(title: "Zdarzenie")
    var trackerId: Int

    init() {}
    init(trackerId: Int64) { self.trackerId = Int(trackerId) }

    func perform() async throws -> some IntentResult {
        Repo.startSession(Int64(trackerId))
        await LiveActivityManager.syncNow()
        return .result()
    }
}

struct StopTrackerIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Stop zdarzenia"

    @Parameter(title: "Zdarzenie")
    var trackerId: Int

    init() {}
    init(trackerId: Int64) { self.trackerId = Int(trackerId) }

    func perform() async throws -> some IntentResult {
        Repo.stopTracker(Int64(trackerId))
        await LiveActivityManager.syncNow()
        return .result()
    }
}

struct PauseTrackerIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Pauza zdarzenia"

    @Parameter(title: "Zdarzenie")
    var trackerId: Int

    init() {}
    init(trackerId: Int64) { self.trackerId = Int(trackerId) }

    func perform() async throws -> some IntentResult {
        Repo.pauseTracker(Int64(trackerId))
        await LiveActivityManager.syncNow()
        return .result()
    }
}

struct ResumeTrackerIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Wznów zdarzenie"

    @Parameter(title: "Zdarzenie")
    var trackerId: Int

    init() {}
    init(trackerId: Int64) { self.trackerId = Int(trackerId) }

    func perform() async throws -> some IntentResult {
        Repo.resumeTracker(Int64(trackerId))
        await LiveActivityManager.syncNow()
        return .result()
    }
}
