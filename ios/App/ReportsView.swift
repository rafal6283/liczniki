import SwiftUI
import CoreTransferable
import UniformTypeIdentifiers

/// Plik CSV tworzony dopiero w chwili udostępniania.
struct CSVExport: Transferable {
    let days: Int

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .commaSeparatedText) { item in
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("kreska-\(Fmt.todayKey).csv")
            let from = Stats.range(days: item.days).first ?? Fmt.today
            try Data(Repo.csv(from: from).utf8).write(to: url, options: .atomic)
            return SentTransferredFile(url)
        }
    }
}

/// Wpisy jednego licznika z godzinami – format „wymiana-zdarzen” v1, źródło "kreska" (czyta go m.in. Dziennik talerza).
/// Kliknięcia „−” cofają ostatnie kliknięcie tego dnia; korekty dnia bez godziny są tylko w day_totals.
enum KreskaExport {
    static func build(_ c: Counter) -> (data: Data, events: Int, days: Int) {
        let today = Fmt.todayKey
        let all = Repo.taps(c.id, from: "2000-01-01", to: today)
        let totals = Repo.dailyTotals(c.id, from: "2000-01-01", to: today)
        var order: [String] = []
        var byDay: [String: [(Int64, String)]] = [:]
        for t in all where !t.manual {
            let key = Fmt.key(ms: t.ts)
            if byDay[key] == nil { byDay[key] = []; order.append(key) }
            if t.amount > 0 {
                for i in 0..<t.amount { byDay[key]!.append((t.ts, "k\(t.id)-\(i)")) }
            } else if t.amount < 0 {
                for _ in 0..<(-t.amount) where !byDay[key]!.isEmpty { byDay[key]!.removeLast() }
            }
        }
        var events: [[String: Any]] = []
        for key in order {
            for (ts, id) in byDay[key] ?? [] { events.append(["id": id, "type": "tap", "start": ts]) }
        }
        let dayTotals: [[String: Any]] = totals.sorted { $0.key < $1.key }.filter { $0.value != 0 }.map { ["date": $0.key, "total": $0.value] }
        var windows: [[String: Any]] = []
        let now = nowMs()
        if let first = all.first?.ts {
            windows.append(["start": Fmt.cal.startOfDay(for: first.date).ms, "end": now])
        }
        let obj: [String: Any] = [
            "format": "wymiana-zdarzen", "version": 1, "source": "kreska", "exported_at": now,
            "counter": c.name, "windows": windows, "events": events, "day_totals": dayTotals,
        ]
        let data = (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data("{}".utf8)
        return (data, events.count, dayTotals.count)
    }
}

/// Plik JSON tworzony dopiero w chwili udostępniania.
struct CounterJSONExport: Transferable {
    let counter: Counter

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .json) { item in
            let safe = item.counter.name.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: "-")
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("kreska-\(safe.isEmpty ? "licznik" : safe)-\(Fmt.todayKey).json")
            try KreskaExport.build(item.counter).data.write(to: url, options: .atomic)
            return SentTransferredFile(url)
        }
    }
}

struct ReportsView: View {
    @ObservedObject private var store = Store.shared
    @AppStorage("report_range") private var range = 30

    var body: some View {
        let _ = store.version
        let days = Stats.range(days: range)
        let counters = Repo.counters()
        let trackers = Repo.trackers()
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    RangePicker(range: $range)
                    if counters.isEmpty && trackers.isEmpty {
                        EmptyCard(eyebrow: "Brak danych", title: "Tu pojawią się wykresy",
                                  text: "Dodaj licznik albo typ zdarzenia, a raporty zbudują się same.") { EmptyView() }
                    }
                    ForEach(counters) { c in CounterReportCard(c: c, days: days, range: $range) }
                    ForEach(trackers) { t in TrackerReportCard(t: t, days: days, range: $range) }
                    if !counters.isEmpty || !trackers.isEmpty {
                        ShareLink(item: CSVExport(days: range), preview: SharePreview("Kreska — eksport CSV")) {
                            Label("Eksportuj CSV (\(range == 365 ? "rok" : "\(range) dni"))", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                    if !counters.isEmpty {
                        Menu {
                            ForEach(counters) { c in
                                ShareLink(item: CounterJSONExport(counter: c), preview: SharePreview("Kreska — \(c.name) (JSON)")) {
                                    Text(c.name)
                                }
                            }
                        } label: {
                            Label("Eksportuj wpisy z godzinami (JSON)", systemImage: "clock.arrow.circlepath")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        Text("Cała historia jednego licznika – każde kliknięcie z godziną. Np. FF do Dziennika talerza.")
                            .font(.caption).foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Raporty")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
        }
    }
}

struct SinceNote: View {
    let since: Date?
    let trimmed: Bool

    var body: some View {
        if trimmed, let s = since {
            Text("Dane od \(Fmt.date(s)) — średnie liczone od tego dnia.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct CounterReportCard: View {
    let c: Counter
    let days: [Date]
    @Binding var range: Int

    var body: some View {
        let st = Stats.counter(c, days)
        let col = Palette.color(c.color)
        let n = st.days.count
        let peak = st.hours.max().flatMap { m in m > 0 ? st.hours.firstIndex(of: m) : nil }
        Card {
            Eyebrow(text: "Licznik")
            HStack { Swatch(color: col); Text(c.name).font(.headline) }
            StatsGrid(items: [
                StatItem(Fmt.num(st.sum), "suma w okresie"),
                StatItem(Fmt.num(st.avgPerDay), "średnio / dzień" + (st.trend.map { " (\($0))" } ?? "")),
                StatItem(st.bestIdx.map { Fmt.num(st.values[$0]) } ?? "–",
                         st.bestIdx.map { "rekord, \(Fmt.day(st.days[$0]))" } ?? "rekord"),
                StatItem(st.goalDays.map { "\($0)/\(n)" } ?? "\(st.activeDays)/\(n)",
                         st.goalDays != nil ? "dni z celem" : "dni z wpisem"),
                StatItem(peak.map { "\($0):00" } ?? "–", "najczęstsza godzina"),
                StatItem("\(st.streakCurrent) d", "obecna seria"),
            ])
            DailyChart(days: st.days, values: st.values, color: col) { Fmt.num($0) }
            SinceNote(since: st.since, trimmed: st.trimmed)
            NavigationLink {
                CounterDetailView(c: c, range: $range)
            } label: {
                Text("Pełne statystyki →").font(.subheadline.weight(.semibold))
            }
        }
    }
}

struct TrackerReportCard: View {
    let t: Tracker
    let days: [Date]
    @Binding var range: Int

    var body: some View {
        let st = Stats.tracker(t, days)
        let col = Palette.color(t.color)
        Card {
            Eyebrow(text: "Zdarzenie")
            HStack { Swatch(color: col); Text(t.name).font(.headline) }
            StatsGrid(items: [
                StatItem("\(st.count)", "razy"),
                StatItem(st.totalMs > 0 ? Fmt.dur(st.totalMs) : "–", "łącznie w okresie"),
                StatItem(Fmt.dur(st.avgPerDayMs), "czasu dziennie" + (st.trendTotal.map { " (\($0))" } ?? "")),
                StatItem(st.count > 0 ? Fmt.dur(st.avgMs) : "–", "średnio jedno"),
                StatItem(Stats.hhmm(st.avgStartMin), "średni start"),
                StatItem("\(st.streakCurrent) d", "obecna seria"),
            ])
            DailyChart(days: st.days, values: st.dailyMinutes, color: col) { Stats.minutesLabel($0) }
            SinceNote(since: st.since, trimmed: st.trimmed)
            NavigationLink {
                TrackerDetailView(t: t, range: $range)
            } label: {
                Text("Pełne statystyki →").font(.subheadline.weight(.semibold))
            }
        }
    }
}

/* ---------- pełne statystyki ---------- */

private func peakText(_ values: [Double], _ format: (Double) -> String) -> String? {
    let total = values.reduce(0, +)
    guard total > 0, let m = values.max(), let i = values.firstIndex(of: m) else { return nil }
    return "Najwięcej o \(i):00–\(i):59 · \(format(m)) (\(Int(m / total * 100))%)"
}

private let hourLabels = (0..<24).map { "\($0)" }

private func weekLabels(_ w: [WeekPoint]) -> [String] {
    w.map { p in
        let c = Fmt.cal.dateComponents([.day, .month], from: p.start)
        return String(format: "%d.%02d", c.day ?? 0, c.month ?? 0)
    }
}

struct CounterDetailView: View {
    let c: Counter
    @Binding var range: Int
    @ObservedObject private var store = Store.shared
    @State private var entriesDay: EntriesDay?
    @State private var setDay = false

    struct EntriesDay: Identifiable {
        let id = UUID()
        let day: Date
    }

    var body: some View {
        let _ = store.version
        let counter = Repo.counter(c.id) ?? c
        let st = Stats.counter(counter, Stats.range(days: range))
        let col = Palette.color(counter.color)
        let n = st.days.count
        let f: (Double) -> String = { Fmt.num($0) }
        ScrollView {
            VStack(spacing: 12) {
                RangePicker(range: $range)

                SectionCard("Podsumowanie", hint: Stats.basisNote(fullDays: st.fullDays, since: st.since, trimmed: st.trimmed, trendShown: st.trend != nil)) {
                    StatsGrid(items: [
                        StatItem(Fmt.num(st.sum), "suma w okresie"),
                        StatItem(Fmt.num(st.avgPerDay), "średnio / dzień" + (st.trend.map { " (\($0))" } ?? "")),
                        StatItem(Fmt.num(st.avgActiveDay), "średnio w dniu z wpisem"),
                        StatItem(Fmt.num(st.median), "mediana dnia z wpisem"),
                        StatItem(st.bestIdx.map { Fmt.num(st.values[$0]) } ?? "–",
                                 st.bestIdx.map { "rekord, \(Fmt.day(st.days[$0]))" } ?? "rekord"),
                        StatItem(st.worstIdx.map { Fmt.num(st.values[$0]) } ?? "–",
                                 st.worstIdx.map { "najmniej, \(Fmt.day(st.days[$0]))" } ?? "najmniej"),
                        StatItem("\(st.activeDays)/\(n)", "dni z wpisem"),
                        StatItem(Fmt.num(st.clicks), "kliknięć +"),
                    ] + (st.goalDays.map { [StatItem("\($0)/\(n)", "dni z osiągniętym celem")] } ?? []))
                }

                SectionCard("Serie", hint: "Liczone z całej historii.") {
                    InfoLine(label: "Obecna seria dni z wpisem", value: "\(st.streakCurrent) d")
                    InfoLine(label: "Najdłuższa seria", value: "\(st.streakBest) d")
                    if let gc = st.goalStreakCurrent {
                        InfoLine(label: "Obecna seria z celem", value: "\(gc) d")
                        InfoLine(label: "Najdłuższa seria z celem", value: "\(st.goalStreakBest ?? 0) d")
                    }
                }

                SectionCard("Dzień po dniu") {
                    DailyChart(days: st.days, values: st.values, color: col, format: f)
                    if n >= 28 && st.weeks.count >= 2 {
                        Eyebrow(text: "Tydzień po tygodniu (od poniedziałku)").padding(.top, 8)
                        CategoryBars(labels: weekLabels(st.weeks), values: st.weeks.map { $0.value }, color: col,
                                     format: f, labelEvery: max(1, st.weeks.count / 6))
                    }
                }

                SectionCard("Pora dnia", hint: peakText(st.hours, f) ?? "Brak kliknięć w tym okresie.") {
                    CategoryBars(labels: hourLabels, values: st.hours, color: col, format: f, labelEvery: 3)
                    ForEach(0..<4, id: \.self) { i in
                        InfoLine(label: Stats.partLabels[i], value: "\(Int(st.parts[i]))%")
                    }
                }

                SectionCard("Rytm dnia", hint: "Średnie liczone z dni, w których były kliknięcia.") {
                    InfoLine(label: "Pierwsze kliknięcie dnia", value: Stats.hhmm(st.avgFirstMin))
                    InfoLine(label: "Ostatnie kliknięcie dnia", value: Stats.hhmm(st.avgLastMin))
                    InfoLine(label: "Średni odstęp", value: st.avgGapMs.map { Fmt.dur($0) } ?? "–")
                    InfoLine(label: "Najdłuższa przerwa w dniu", value: st.longestGapMs.map { Fmt.dur($0) } ?? "–")
                    InfoLine(label: "Kliknięć na dzień z wpisem",
                             value: st.activeDays > 0 ? Fmt.num(Double(st.clicks) / Double(st.activeDays)) : "–")
                }

                SectionCard("Dni tygodnia", hint: weekdayHint(st.weekdayAvg)) {
                    CategoryBars(labels: Stats.weekdays, values: st.weekdayAvg, color: col, format: f, showValues: true)
                }

                SectionCard("Mapa tygodnia", hint: "Dzień tygodnia × godzina. Ciemniejsze pole = więcej kliknięć.") {
                    HeatMapView(heat: st.heat, color: col)
                }

                SectionCard("Historia dni", hint: "Dotknij dnia, aby zobaczyć, dodać lub poprawić jego wpisy.") {
                    HStack(spacing: 16) {
                        Button("Dodaj wpis") { entriesDay = EntriesDay(day: Date()) }
                        Button("Ustaw wartość dnia") { setDay = true }
                    }
                    .font(.subheadline.weight(.semibold))
                    .buttonStyle(.borderless)
                    let rows = st.days.indices.filter { st.values[$0] != 0 }.reversed()
                    if rows.isEmpty {
                        Text("Brak wpisów w tym okresie.").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(Array(rows.prefix(120)), id: \.self) { i in
                        Button { entriesDay = EntriesDay(day: st.days[i]) } label: {
                            HStack {
                                Text(Fmt.day(st.days[i]))
                                Spacer()
                                if let g = counter.goal, st.values[i] >= Double(g) {
                                    Text("cel ✓").font(.caption).foregroundStyle(col)
                                }
                                Text(Fmt.num(st.values[i])).font(.system(.body, design: .monospaced))
                                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        Divider()
                    }
                }
            }
            .padding(16)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(counter.name)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $entriesDay) { e in EntriesView(counter: counter, initialDay: e.day) }
        .sheet(isPresented: $setDay) { DayValueForm(counter: counter, initialDay: Fmt.addDays(Fmt.today, -1)) }
    }

    private func weekdayHint(_ avg: [Double]) -> String {
        guard let m = avg.max(), m > 0, let i = avg.firstIndex(of: m) else { return "Średni wynik w danym dniu tygodnia." }
        return "Średni wynik w danym dniu tygodnia. Najwięcej: \(Stats.weekdaysLong[i])."
    }
}

struct TrackerDetailView: View {
    let t: Tracker
    @Binding var range: Int
    @ObservedObject private var store = Store.shared
    @State private var sessionEdit: SessionEditItem?

    var body: some View {
        let _ = store.version
        let tracker = Repo.tracker(t.id) ?? t
        let st = Stats.tracker(tracker, Stats.range(days: range))
        let col = Palette.color(tracker.color)
        let n = st.days.count
        let fm: (Double) -> String = { Stats.minutesLabel($0) }
        let fc: (Double) -> String = { Fmt.num($0) }
        let parts = partsOf(st.activeHours)
        let partsSum = parts.reduce(0, +)
        ScrollView {
            VStack(spacing: 12) {
                RangePicker(range: $range)

                SectionCard("Podsumowanie", hint: "Czasy bez pauz. " + Stats.basisNote(fullDays: st.fullDays, since: st.since, trimmed: st.trimmed, trendShown: st.trendTotal != nil)) {
                    StatsGrid(items: [
                        StatItem("\(st.count)", "razy w okresie"),
                        StatItem(st.totalMs > 0 ? Fmt.dur(st.totalMs) : "–", "łącznie w okresie"),
                        StatItem(Fmt.num(st.countPerDay), "razy dziennie" + (st.trendCount.map { " (\($0))" } ?? "")),
                        StatItem(Fmt.dur(st.avgPerDayMs), "czasu dziennie" + (st.trendTotal.map { " (\($0))" } ?? "")),
                        StatItem(st.count > 0 ? Fmt.dur(st.avgMs) : "–", "średnio jedno"),
                        StatItem(st.count > 0 ? Fmt.dur(st.medianMs) : "–", "mediana"),
                        StatItem(st.count > 0 ? Fmt.dur(st.shortestMs) : "–", "najkrócej"),
                        StatItem(st.count > 0 ? Fmt.dur(st.longestMs) : "–", "najdłużej"),
                        StatItem("\(st.activeDays)/\(n)", "dni ze zdarzeniem"),
                        StatItem("\(st.pauseCount)", "pauz"),
                        StatItem(st.pausedMs > 0 ? Fmt.dur(st.pausedMs) : "–", "łącznie na pauzie"),
                    ])
                }

                SectionCard("Serie i przerwy") {
                    InfoLine(label: "Obecna seria dni ze zdarzeniem", value: "\(st.streakCurrent) d")
                    InfoLine(label: "Najdłuższa seria", value: "\(st.streakBest) d")
                    InfoLine(label: "Średnia przerwa między zdarzeniami", value: st.avgGapMs.map { Fmt.dur($0) } ?? "–")
                    InfoLine(label: "Najdłuższa przerwa", value: st.longestGapMs.map { Fmt.dur($0) } ?? "–")
                }

                SectionCard("Czas dzień po dniu") {
                    DailyChart(days: st.days, values: st.dailyMinutes, color: col, format: fm)
                    if n >= 28 && st.weeks.count >= 2 {
                        Eyebrow(text: "Tydzień po tygodniu (od poniedziałku)").padding(.top, 8)
                        CategoryBars(labels: weekLabels(st.weeks), values: st.weeks.map { $0.value }, color: col,
                                     format: fm, labelEvery: max(1, st.weeks.count / 6))
                    }
                }

                SectionCard("Kiedy się zaczyna", hint: "Średnio start o \(Stats.hhmm(st.avgStartMin)), koniec o \(Stats.hhmm(st.avgEndMin)).") {
                    CategoryBars(labels: hourLabels, values: st.startHours, color: col, format: fc, labelEvery: 3)
                }

                SectionCard("Kiedy trwa", hint: peakText(st.activeHours, fm) ?? "Brak zdarzeń w tym okresie.") {
                    CategoryBars(labels: hourLabels, values: st.activeHours, color: col, format: fm, labelEvery: 3)
                    ForEach(0..<4, id: \.self) { i in
                        InfoLine(label: Stats.partLabels[i],
                                 value: partsSum > 0 ? "\(Int(parts[i] / partsSum * 100))% · \(fm(parts[i]))" : "–")
                    }
                }

                SectionCard("Dni tygodnia", hint: "Średni czas i średnia liczba zdarzeń w danym dniu tygodnia.") {
                    CategoryBars(labels: Stats.weekdays, values: st.weekdayMinutes, color: col, format: fm, showValues: true)
                    Eyebrow(text: "Średnia liczba zdarzeń").padding(.top, 6)
                    CategoryBars(labels: Stats.weekdays, values: st.weekdayCount, color: col, format: fc, showValues: true, height: 100)
                }

                SectionCard("Mapa tygodnia", hint: "Dzień tygodnia × godzina. Ciemniejsze pole = więcej czasu.") {
                    HeatMapView(heat: st.heat, color: col)
                }

                SectionCard("Długość zdarzeń", hint: "Ile zdarzeń trwało tyle czasu.") {
                    CategoryBars(labels: Stats.bucketLabels, values: st.buckets, color: col, format: fc, showValues: true, height: 120)
                }

                SectionCard("Wpisy (\(st.count))") {
                    Button("Dopisz zdarzenie") { sessionEdit = SessionEditItem(session: nil, trackerId: tracker.id) }
                        .font(.subheadline.weight(.semibold))
                        .buttonStyle(.borderless)
                    if st.sessions.isEmpty {
                        Text("Brak wpisów w tym okresie.").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(Array(st.sessions.prefix(150))) { s in
                        SessionRow(s: s, tracker: tracker, pauses: st.pauses[s.id] ?? []) {
                            sessionEdit = SessionEditItem(session: s, trackerId: tracker.id)
                        }
                        Divider()
                    }
                }
            }
            .padding(16)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(tracker.name)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $sessionEdit) { e in SessionForm(session: e.session, trackerId: e.trackerId) }
    }

    private func partsOf(_ hours: [Double]) -> [Double] {
        var p = Array(repeating: 0.0, count: 4)
        for (h, m) in hours.enumerated() { p[Stats.partOfDay(h)] += m }
        return p
    }
}
