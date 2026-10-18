import WidgetKit
import SwiftUI
import AppIntents
import ActivityKit

@main
struct KreskaWidgetBundle: WidgetBundle {
    var body: some Widget {
        CounterWidget()
        CountersListWidget()
        TrackersWidget()
        TrackerLiveActivity()
    }
}

private func nextMidnight() -> Date { Fmt.addDays(Fmt.today, 1).addingTimeInterval(30) }

/* ---------- wybór licznika w ustawieniach widżetu ---------- */

struct CounterEntity: AppEntity {
    let id: Int
    let name: String

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Licznik"
    static var defaultQuery = CounterQuery()

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct CounterQuery: EntityQuery {
    func entities(for identifiers: [Int]) async throws -> [CounterEntity] {
        Repo.counters().filter { identifiers.contains(Int($0.id)) }.map { CounterEntity(id: Int($0.id), name: $0.name) }
    }

    func suggestedEntities() async throws -> [CounterEntity] {
        Repo.counters().map { CounterEntity(id: Int($0.id), name: $0.name) }
    }

    func defaultResult() async -> CounterEntity? {
        Repo.counters().first.map { CounterEntity(id: Int($0.id), name: $0.name) }
    }
}

struct SelectCounterIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Wybierz licznik"
    static var description = IntentDescription("Który licznik pokazać w widżecie.")

    @Parameter(title: "Licznik")
    var counter: CounterEntity?

    init() {}
}

/* ---------- widżet: jeden licznik ---------- */

struct CounterEntry: TimelineEntry {
    let date: Date
    let counter: Counter?
    let total: Int
}

struct CounterProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> CounterEntry {
        CounterEntry(date: .now, counter: Counter(id: 0, name: "Szklanki wody", step: 1, goal: 8, color: 0, notify: true), total: 3)
    }

    func snapshot(for configuration: SelectCounterIntent, in context: Context) async -> CounterEntry {
        entry(configuration)
    }

    func timeline(for configuration: SelectCounterIntent, in context: Context) async -> Timeline<CounterEntry> {
        Timeline(entries: [entry(configuration)], policy: .after(nextMidnight()))
    }

    private func entry(_ cfg: SelectCounterIntent) -> CounterEntry {
        let all = Repo.counters()
        var c: Counter? = all.first
        if let sel = cfg.counter, let found = all.first(where: { $0.id == Int64(sel.id) }) { c = found }
        return CounterEntry(date: .now, counter: c, total: c.map { Repo.todayTotal($0.id) } ?? 0)
    }
}

struct CounterWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: CounterEntry

    var body: some View {
        if let c = entry.counter {
            let col = Palette.color(c.color)
            switch family {
            case .accessoryCircular:
                Button(intent: IncrementCounterIntent(counterId: c.id)) {
                    ZStack {
                        AccessoryWidgetBackground()
                        VStack(spacing: 0) {
                            Text(Fmt.num(entry.total)).font(.system(.title3, design: .rounded).weight(.semibold)).minimumScaleFactor(0.5)
                            Text("+\(c.step)").font(.caption2)
                        }
                    }
                }
                .buttonStyle(.plain)
            case .accessoryRectangular:
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(c.name).font(.headline).lineLimit(1)
                        Text(Fmt.num(entry.total) + (c.goal.map { " / \(Fmt.num($0))" } ?? ""))
                            .font(.system(.title3, design: .rounded).weight(.semibold))
                    }
                    Spacer(minLength: 4)
                    Button(intent: IncrementCounterIntent(counterId: c.id)) {
                        Image(systemName: "plus.circle.fill").font(.title)
                    }
                    .buttonStyle(.plain)
                }
            default:
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Circle().fill(col).frame(width: 8, height: 8)
                        Text(c.name).font(.caption.weight(.semibold)).lineLimit(1)
                    }
                    HStack(alignment: .firstTextBaseline, spacing: 0) {
                        Text(Fmt.num(entry.total)).font(.system(size: 38, weight: .semibold, design: .rounded)).minimumScaleFactor(0.5)
                        if let g = c.goal {
                            Text(" /\(Fmt.num(g))").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    if let g = c.goal {
                        ProgressView(value: min(1, Double(entry.total) / Double(max(1, g)))).tint(col)
                    }
                    Spacer(minLength: 0)
                    HStack(spacing: 6) {
                        Button(intent: DecrementCounterIntent(counterId: c.id)) {
                            Image(systemName: "minus").frame(maxWidth: .infinity, minHeight: 28)
                        }
                        .tint(.gray)
                        Button(intent: IncrementCounterIntent(counterId: c.id)) {
                            Text("+\(c.step)").fontWeight(.bold).frame(maxWidth: .infinity, minHeight: 28)
                        }
                        .tint(col)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        } else {
            Text("Dodaj licznik w aplikacji Kreska").font(.caption).multilineTextAlignment(.center)
        }
    }
}

struct CounterWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "CounterWidget", intent: SelectCounterIntent.self, provider: CounterProvider()) { entry in
            CounterWidgetView(entry: entry)
                .containerBackground(for: .widget) { Color(.systemBackground) }
        }
        .configurationDisplayName("Licznik")
        .description("Jeden licznik z przyciskami + i −. Przytrzymaj widżet, aby wybrać licznik.")
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular])
    }
}

/* ---------- widżet: kilka liczników ---------- */

struct CountersEntry: TimelineEntry {
    let date: Date
    let rows: [(Counter, Int)]
}

struct CountersProvider: TimelineProvider {
    func placeholder(in context: Context) -> CountersEntry {
        CountersEntry(date: .now, rows: [
            (Counter(id: 0, name: "Szklanki wody", step: 1, goal: 8, color: 0, notify: true), 3),
            (Counter(id: 0, name: "Pompki", step: 10, goal: nil, color: 1, notify: true), 40),
        ])
    }

    func getSnapshot(in context: Context, completion: @escaping (CountersEntry) -> Void) {
        completion(load())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<CountersEntry>) -> Void) {
        completion(Timeline(entries: [load()], policy: .after(nextMidnight())))
    }

    private func load() -> CountersEntry {
        let list = Repo.counters().filter { $0.notify }
        return CountersEntry(date: .now, rows: list.map { ($0, Repo.todayTotal($0.id)) })
    }
}

struct CountersListView: View {
    @Environment(\.widgetFamily) private var family
    let entry: CountersEntry

    var body: some View {
        let limit = family == .systemLarge ? 6 : 3
        let rows = Array(entry.rows.prefix(limit))
        VStack(alignment: .leading, spacing: 6) {
            if rows.isEmpty {
                Text("Brak liczników do pokazania. Włącz „Pokaż w widżecie” w ustawieniach licznika.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            ForEach(rows.indices, id: \.self) { i in
                let c = rows[i].0
                let total = rows[i].1
                HStack(spacing: 8) {
                    Circle().fill(Palette.color(c.color)).frame(width: 8, height: 8)
                    Text(c.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Spacer(minLength: 4)
                    Text(Fmt.num(total) + (c.goal.map { "/\(Fmt.num($0))" } ?? ""))
                        .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                    Button(intent: DecrementCounterIntent(counterId: c.id)) {
                        Image(systemName: "minus").frame(width: 26, height: 22)
                    }
                    .tint(.gray)
                    Button(intent: IncrementCounterIntent(counterId: c.id)) {
                        Image(systemName: "plus").frame(width: 30, height: 22)
                    }
                    .tint(Palette.color(c.color))
                }
                .buttonStyle(.borderedProminent)
                if i < rows.count - 1 { Divider() }
            }
            Spacer(minLength: 0)
        }
    }
}

struct CountersListWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CountersListWidget", provider: CountersProvider()) { entry in
            CountersListView(entry: entry)
                .containerBackground(for: .widget) { Color(.systemBackground) }
        }
        .configurationDisplayName("Liczniki")
        .description("Kilka liczników z przyciskami + i −.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

/* ---------- widżet: zdarzenia ---------- */

struct TrackerRowData {
    let tracker: Tracker
    let running: Bool
    let paused: Bool
    let timerStart: Date
    let activeMs: Int64
}

struct TrackersEntry: TimelineEntry {
    let date: Date
    let rows: [TrackerRowData]
}

struct TrackersProvider: TimelineProvider {
    func placeholder(in context: Context) -> TrackersEntry {
        TrackersEntry(date: .now, rows: [
            TrackerRowData(tracker: Tracker(id: 0, name: "Praca", color: 0, notify: true), running: true, paused: false,
                           timerStart: Date().addingTimeInterval(-1800), activeMs: 1_800_000),
        ])
    }

    func getSnapshot(in context: Context, completion: @escaping (TrackersEntry) -> Void) {
        completion(load())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<TrackersEntry>) -> Void) {
        completion(Timeline(entries: [load()], policy: .after(Date().addingTimeInterval(3600))))
    }

    private func load() -> TrackersEntry {
        let running = Dictionary(Repo.running().map { ($0.trackerId, $0) }, uniquingKeysWith: { a, _ in a })
        let now = nowMs()
        let rows: [TrackerRowData] = Repo.trackers().compactMap { (t: Tracker) -> TrackerRowData? in
            if let s = running[t.id] {
                let ps = Repo.pauses(s.id)
                let active = Timing.activeMs(s, ps, now: now)
                return TrackerRowData(tracker: t, running: true, paused: ps.contains { $0.end == nil },
                                      timerStart: (now - active).date, activeMs: active)
            }
            guard t.notify else { return nil }
            return TrackerRowData(tracker: t, running: false, paused: false, timerStart: Date(), activeMs: 0)
        }
        // trwające najpierw
        return TrackersEntry(date: .now, rows: rows.filter { $0.running } + rows.filter { !$0.running })
    }
}

struct TrackersWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TrackersEntry

    var body: some View {
        let limit = family == .systemSmall ? 1 : 3
        let rows = Array(entry.rows.prefix(limit))
        VStack(alignment: .leading, spacing: 6) {
            if rows.isEmpty {
                Text("Dodaj typ zdarzenia w aplikacji Kreska").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(rows.indices, id: \.self) { i in
                TrackerWidgetRow(row: rows[i], compact: family == .systemSmall)
                if i < rows.count - 1 { Divider() }
            }
            Spacer(minLength: 0)
        }
    }
}

struct TrackerWidgetRow: View {
    let row: TrackerRowData
    let compact: Bool

    var body: some View {
        let t = row.tracker
        let col = Palette.color(t.color)
        let layout = compact ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6)) : AnyLayout(HStackLayout(spacing: 8))
        layout {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    Circle().fill(col).frame(width: 8, height: 8)
                    Text(t.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                }
                if row.running {
                    if row.paused {
                        Text(Fmt.clock(row.activeMs) + " · pauza")
                            .font(.system(.title3, design: .monospaced)).foregroundStyle(.secondary)
                    } else {
                        Text(row.timerStart, style: .timer)
                            .font(.system(.title3, design: .monospaced))
                            .foregroundStyle(Palette.live)
                    }
                } else {
                    Text("nie trwa").font(.caption).foregroundStyle(.secondary)
                }
            }
            if !compact { Spacer(minLength: 4) }
            HStack(spacing: 6) {
                if row.running {
                    if row.paused {
                        Button(intent: ResumeTrackerIntent(trackerId: t.id)) { Image(systemName: "play.fill").frame(minWidth: 26, minHeight: 22) }
                            .tint(.gray)
                    } else {
                        Button(intent: PauseTrackerIntent(trackerId: t.id)) { Image(systemName: "pause.fill").frame(minWidth: 26, minHeight: 22) }
                            .tint(.gray)
                    }
                    Button(intent: StopTrackerIntent(trackerId: t.id)) { Image(systemName: "stop.fill").frame(minWidth: 26, minHeight: 22) }
                        .tint(Palette.live)
                } else {
                    Button(intent: StartTrackerIntent(trackerId: t.id)) {
                        Text("Start").fontWeight(.bold).frame(minWidth: compact ? 80 : 50, minHeight: 22)
                    }
                    .tint(col)
                }
            }
            .buttonStyle(.borderedProminent)
        }
    }
}

struct TrackersWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TrackersWidget", provider: TrackersProvider()) { entry in
            TrackersWidgetView(entry: entry)
                .containerBackground(for: .widget) { Color(.systemBackground) }
        }
        .configurationDisplayName("Zdarzenia")
        .description("Start, pauza i stop zdarzeń.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

/* ---------- Live Activity: stoper na ekranie blokady ---------- */

struct TrackerLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TrackerActivityAttributes.self) { context in
            LiveLockScreenView(state: context.state, trackerId: context.attributes.trackerId)
                .padding(16)
                .activityBackgroundTint(Color(.systemBackground).opacity(0.85))
        } dynamicIsland: { context in
            let st = context.state
            let tid = context.attributes.trackerId
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 6) {
                        Circle().fill(Palette.color(st.color)).frame(width: 8, height: 8)
                        Text(st.name).font(.headline).lineLimit(1)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    LiveTimerText(state: st).font(.system(.title3, design: .monospaced))
                }
                DynamicIslandExpandedRegion(.bottom) {
                    LiveButtons(state: st, trackerId: tid)
                }
            } compactLeading: {
                Image(systemName: st.paused ? "pause.circle" : "timer").foregroundStyle(Palette.live)
            } compactTrailing: {
                LiveTimerText(state: st).font(.system(.caption, design: .monospaced)).frame(maxWidth: 56)
            } minimal: {
                Image(systemName: st.paused ? "pause.circle" : "timer").foregroundStyle(Palette.live)
            }
        }
    }
}

struct LiveTimerText: View {
    let state: TrackerActivityAttributes.ContentState

    var body: some View {
        if state.paused {
            Text(Fmt.clock(state.activeMs))
        } else {
            Text(state.timerStart, style: .timer).monospacedDigit()
        }
    }
}

struct LiveButtons: View {
    let state: TrackerActivityAttributes.ContentState
    let trackerId: Int64

    var body: some View {
        HStack(spacing: 10) {
            if state.paused {
                Button(intent: ResumeTrackerIntent(trackerId: trackerId)) {
                    Label("Wznów", systemImage: "play.fill").frame(maxWidth: .infinity)
                }
                .tint(.gray)
            } else {
                Button(intent: PauseTrackerIntent(trackerId: trackerId)) {
                    Label("Pauza", systemImage: "pause.fill").frame(maxWidth: .infinity)
                }
                .tint(.gray)
            }
            Button(intent: StopTrackerIntent(trackerId: trackerId)) {
                Label("Stop", systemImage: "stop.fill").frame(maxWidth: .infinity)
            }
            .tint(Palette.live)
        }
        .buttonStyle(.borderedProminent)
    }
}

struct LiveLockScreenView: View {
    let state: TrackerActivityAttributes.ContentState
    let trackerId: Int64

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Circle().fill(Palette.color(state.color)).frame(width: 10, height: 10)
                Text(state.name).font(.headline)
                Spacer()
                Text(state.paused ? "PAUZA" : "TRWA").font(.caption.bold())
                    .foregroundStyle(state.paused ? Color.secondary : Palette.live)
            }
            HStack(alignment: .firstTextBaseline) {
                LiveTimerText(state: state)
                    .font(.system(size: 34, weight: .medium, design: .monospaced))
                    .foregroundStyle(state.paused ? Color.secondary : Palette.live)
                Spacer()
                Text("od \(Fmt.hm(state.startedAt.ms))").font(.caption).foregroundStyle(.secondary)
            }
            LiveButtons(state: state, trackerId: trackerId)
        }
    }
}
