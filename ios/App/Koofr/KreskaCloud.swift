import Foundation
import Network

/// Kopia Kreski (iOS) i wymiana danych z innymi aplikacjami przez rodzinne konto Koofr.
/// Kopie z iPhone'a trzymane są osobno od kopii z Androida (inny folder: „kreska-ios”).
@MainActor
final class KreskaCloud: ObservableObject {
    static let shared = KreskaCloud()

    let sync = KoofrSync(appId: "kreska-ios")
    @Published var busy = false
    @Published var progress: String?
    @Published var lastError: String?
    @Published var lastExchange: String?
    @Published var version = 0
    private var loaded = false
    private var lastExchangeRun: Date?

    func load() {
        guard !loaded else { return }
        sync.load()
        loaded = true
    }

    func changed() { version += 1 }

    // ---------------------------------------------------------------- ustawienia wymiany

    private var d: UserDefaults { Prefs.d }
    var exchangeOn: Bool {
        get { d.bool(forKey: "ks_ex_on") }
        set { d.set(newValue, forKey: "ks_ex_on"); changed() }
    }
    var publishOn: Bool {
        get { d.object(forKey: "ks_ex_publish") == nil ? true : d.bool(forKey: "ks_ex_publish") }
        set { d.set(newValue, forKey: "ks_ex_publish"); changed() }
    }
    func sourceOn(_ p: String) -> Bool { d.object(forKey: "ks_ex_src_\(p)") == nil ? true : d.bool(forKey: "ks_ex_src_\(p)") }
    func setSource(_ p: String, _ v: Bool) { d.set(v, forKey: "ks_ex_src_\(p)"); changed() }

    static let sources: [(String, String)] = [("rytm-snu", "Rytm Snu (FF w nocy)"), ("polar-ekg", "Polar EKG (epizody AF)")]

    /// Udostępniane liczniki (domyślnie „FF”) i zdarzenia (domyślnie pierwsze z „AF” w nazwie).
    func sharedCounters(_ cs: [Counter]) -> Set<Int64> {
        if let a = d.array(forKey: "ks_ex_counters") as? [Int64] { return Set(a) }
        return Set(cs.filter { $0.name.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare("FF") == .orderedSame }.map { $0.id })
    }
    func setSharedCounters(_ s: Set<Int64>) { d.set(Array(s), forKey: "ks_ex_counters"); changed() }
    func sharedTrackers(_ ts: [Tracker]) -> Set<Int64> {
        if let a = d.array(forKey: "ks_ex_trackers") as? [Int64] { return Set(a) }
        return Set([AfLink.afTrackerId(ts)].compactMap { $0 })
    }
    func setSharedTrackers(_ s: Set<Int64>) { d.set(Array(s), forKey: "ks_ex_trackers"); changed() }

    /// Praca automatyczna tylko przez Wi-Fi (jak na Androidzie; domyślnie włączone).
    var wifiOnly: Bool {
        get { d.object(forKey: "ks_wifi_only") == nil ? true : d.bool(forKey: "ks_wifi_only") }
        set { d.set(newValue, forKey: "ks_wifi_only"); changed() }
    }

    /// Czy obecne połączenie nie jest komórkowe ani oznaczone jako drogie (hotspot, tryb niskiego zużycia).
    static func onWifi() async -> Bool {
        await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
            let m = NWPathMonitor()
            let q = DispatchQueue(label: "kreska.net")
            final class Once: @unchecked Sendable { var done = false }
            let once = Once()
            m.pathUpdateHandler = { p in
                q.async {
                    guard !once.done else { return }
                    once.done = true
                    m.cancel()
                    c.resume(returning: p.status == .satisfied && !p.isExpensive && !p.isConstrained && !p.usesInterfaceType(.cellular))
                }
            }
            m.start(queue: q)
        }
    }

    // ---------------------------------------------------------------- automatyka

    /// Po otwarciu aplikacji: wymiana co najmniej co 15 min, kopia raz na dobę (jeśli włączona).
    func autoRun() async {
        load()
        guard sync.ready, !busy else { return }
        if wifiOnly, !(await KreskaCloud.onWifi()) { return }
        do {
            if exchangeOn, lastExchangeRun.map({ Date().timeIntervalSince($0) > 15 * 60 }) ?? true {
                _ = try await exchangeNow()
            }
            if sync.backupDue { try await backupNow() }
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    // ---------------------------------------------------------------- kopia

    private static let prefKeys = ["rem_on", "rem_min", "overdue_h", "goal_notify"]

    func backupNow() async throws {
        busy = true
        progress = "Wysyłanie…"
        defer { busy = false; progress = nil; changed() }
        var prefs: [String: Any] = [:]
        for k in KreskaCloud.prefKeys { if let v = d.object(forKey: k) { prefs[k] = v } }
        let header = try JSONSerialization.data(withJSONObject: ["format": "kreska-ios-kopia", "version": 1, "prefs": prefs])
        var len = UInt32(header.count).bigEndian
        var out = Data(bytes: &len, count: 4)
        out.append(header)
        out.append(try SQL.shared.snapshot())
        try await sync.backupNow(out)
    }

    func restore(_ b: BackupInfo) async throws {
        busy = true
        progress = "Pobieranie kopii…"
        defer { busy = false; progress = nil; changed() }
        let data = [UInt8](try await sync.restore(b))
        guard data.count > 4 else { throw KsError("Kopia jest uszkodzona.") }
        let len = Int(UInt32(data[0]) << 24 | UInt32(data[1]) << 16 | UInt32(data[2]) << 8 | UInt32(data[3]))
        guard data.count > 4 + len,
              let h = (try? JSONSerialization.jsonObject(with: Data(data[4..<(4 + len)]))) as? [String: Any],
              h["format"] as? String == "kreska-ios-kopia" else { throw KsError("To nie jest kopia Kreski z iPhone'a.") }
        let db = Data(data[(4 + len)...])
        guard db.count > 100, String(decoding: db.prefix(15), as: UTF8.self) == "SQLite format 3" else {
            throw KsError("Kopia nie zawiera bazy Kreski.")
        }
        try SQL.shared.replace(with: db)
        if let p = h["prefs"] as? [String: Any] { for (k, v) in p { d.set(v, forKey: k) } }
        Repo.changed()
    }

    // ---------------------------------------------------------------- wymiana

    func exchangeNow(force: Bool = false) async throws -> String {
        guard sync.ready, exchangeOn else { return "" }
        busy = true
        defer { busy = false; changed() }
        var sent: [String] = []
        // pliki publikowane jak w Kresce na Androidzie: „kreska-<licznik>”, „kreska-z-<zdarzenie>”
        let cs = Repo.counters(), ts = Repo.trackers()
        var files: [String: Data] = [:]
        if publishOn {
            let onC = sharedCounters(cs), onT = sharedTrackers(ts)
            for c in cs where onC.contains(c.id) { files["kreska-" + KsCrypto.slugify(c.name)] = ExchangeFiles.counter(c) }
            for t in ts where onT.contains(t.id) { files["kreska-z-" + KsCrypto.slugify(t.name)] = ExchangeFiles.tracker(t) }
        }
        let prev = Set(d.stringArray(forKey: "ks_ex_published") ?? [])
        for (n, data) in files {
            if try await sync.publishExchange(n, data, force: force) { sent.append(n) }
        }
        for n in prev where files[n] == nil { try? await sync.removeExchange(n) }
        d.set(Array(files.keys), forKey: "ks_ex_published")

        let on = Set(KreskaCloud.sources.map { $0.0 }.filter { sourceOn($0) })
        let got = try await sync.pullExchange(want: { n in
            on.contains { p in n == p || n.hasPrefix(p + "-") }
        }, force: force) { _, data in try ExtStore.importExchange(data) }
        if !got.isEmpty { Repo.changed() }
        lastExchangeRun = Date()
        let parts = (sent.isEmpty ? [] : ["wysłano: " + sent.joined(separator: ", ")]) + (got.isEmpty ? [] : ["pobrano: " + got.joined(separator: ", ")])
        lastExchange = parts.isEmpty ? "bez zmian" : parts.joined(separator: " · ")
        return lastExchange ?? ""
    }
}

/// Pliki „wymiana-zdarzen” v1 z Kreski (ten sam format co na Androidzie).
enum ExchangeFiles {
    private static func json(_ o: [String: Any]) -> Data { (try? JSONSerialization.data(withJSONObject: o)) ?? Data() }

    /// Licznik: każde kliknięcie z godziną (minus cofa ostatni plus z dnia) i sumy dni.
    static func counter(_ c: Counter) -> Data {
        let taps = Repo.taps(c.id, from: "2000-01-01", to: Fmt.todayKey)
        var byDay: [String: [(Int64, String)]] = [:]
        var order: [String] = []
        for t in taps where !t.manual {
            let k = Fmt.key(ms: t.ts)
            if byDay[k] == nil { order.append(k); byDay[k] = [] }
            if t.amount > 0 {
                for i in 0..<t.amount { byDay[k]!.append((t.ts, "k\(t.id)-\(i)")) }
            } else if t.amount < 0 {
                for _ in 0..<(-t.amount) where !(byDay[k]!.isEmpty) { byDay[k]!.removeLast() }
            }
        }
        var events: [[String: Any]] = []
        for k in order { for (ts, id) in byDay[k]! { events.append(["id": id, "type": "tap", "start": ts]) } }
        let totals = Repo.allDailyTotals(c.id).filter { $0.value != 0 }.sorted { $0.key < $1.key }
            .map { ["date": $0.key, "total": $0.value] as [String: Any] }
        let now = nowMs()
        var windows: [[String: Any]] = []
        if let first = taps.first { windows.append(["start": Fmt.cal.startOfDay(for: first.ts.date).ms, "end": now]) }
        return json(["format": "wymiana-zdarzen", "version": 1, "source": "kreska", "exported_at": now,
                     "counter": c.name, "windows": windows, "events": events, "day_totals": totals])
    }

    /// Zdarzenie: aktywne odcinki (bez pauz) i liczba zdarzeń w dniu startu.
    static func tracker(_ t: Tracker) -> Data {
        let now = nowMs()
        let sessions = Repo.sessions(t.id, fromMs: 0).sorted { $0.start < $1.start }
        let pauses = Repo.pausesFor(sessions.map { $0.id })
        var events: [[String: Any]] = []
        var perDay: [String: Int] = [:]
        for s in sessions {
            for (i, a) in Timing.activeIntervals(s, pauses[s.id] ?? [], now: now).enumerated() {
                events.append(["id": "t\(s.id)-\(i)", "type": "session", "start": a.start, "end": a.end])
            }
            perDay[Fmt.key(ms: s.start), default: 0] += 1
        }
        let totals = perDay.sorted { $0.key < $1.key }.map { ["date": $0.key, "total": $0.value] as [String: Any] }
        var windows: [[String: Any]] = []
        if let f = sessions.first { windows.append(["start": Fmt.cal.startOfDay(for: f.start.date).ms, "end": now]) }
        return json(["format": "wymiana-zdarzen", "version": 1, "source": "kreska", "exported_at": now,
                     "counter": t.name, "kind": "tracker", "windows": windows, "events": events, "day_totals": totals])
    }
}

/// Dane z Rytmu Snu i Polar EKG (jak tabele ext_* w Kresce na Androidzie).
enum ExtStore {
    static let night = "nocny-licznik" // Rytm Snu (w bazie dawna nazwa, jak na Androidzie)
    static let ecg = "polar-ekg"

    private static var ready = false
    private static func ensure() {
        guard !ready else { return }
        SQL.shared.run("CREATE TABLE IF NOT EXISTS ext_windows(source TEXT NOT NULL, start INTEGER NOT NULL, end_ts INTEGER NOT NULL)")
        SQL.shared.run("""
            CREATE TABLE IF NOT EXISTS ext_events(source TEXT NOT NULL, type TEXT NOT NULL, start INTEGER NOT NULL,
            end_ts INTEGER, confidence REAL, verified INTEGER)
            """)
        SQL.shared.run("CREATE TABLE IF NOT EXISTS ext_meta(source TEXT PRIMARY KEY, exported_at INTEGER, imported_at INTEGER)")
        ready = true
    }

    static func importExchange(_ data: Data) throws {
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              j["format"] as? String == "wymiana-zdarzen" else { throw KsError("To nie jest plik wymiany.") }
        var source = j["source"] as? String ?? ""
        if source == "rytm-snu" { source = night }
        guard source == night || source == ecg else { return }
        ensure()
        let db = SQL.shared
        db.run("DELETE FROM ext_windows WHERE source=?", [source])
        db.run("DELETE FROM ext_events WHERE source=?", [source])
        for w in j["windows"] as? [[String: Any]] ?? [] {
            if let s = (w["start"] as? NSNumber)?.int64Value, let e = (w["end"] as? NSNumber)?.int64Value {
                db.run("INSERT INTO ext_windows(source,start,end_ts) VALUES(?,?,?)", [source, s, e])
            }
        }
        for e in j["events"] as? [[String: Any]] ?? [] {
            guard let s = (e["start"] as? NSNumber)?.int64Value else { continue }
            let v: Int? = (e["verified"] as? Bool).map { $0 ? 1 : 0 }
            db.run("INSERT INTO ext_events(source,type,start,end_ts,confidence,verified) VALUES(?,?,?,?,?,?)",
                   [source, e["type"] as? String ?? "", s, (e["end"] as? NSNumber)?.int64Value,
                    (e["confidence"] as? NSNumber)?.doubleValue, v])
        }
        db.run("INSERT OR REPLACE INTO ext_meta(source,exported_at,imported_at) VALUES(?,?,?)",
               [source, (j["exported_at"] as? NSNumber)?.int64Value ?? 0, nowMs()])
    }

    static func ecgEpisodes() -> [Interval] {
        ensure()
        return Spans.union(SQL.shared.query("SELECT start, end_ts FROM ext_events WHERE source=? AND type='af' AND end_ts IS NOT NULL",
                                            [ecg]) { Interval(start: $0.i64(0), end: $0.i64(1)) })
    }

    static func nightWindows() -> [Interval] {
        ensure()
        return Spans.union(SQL.shared.query("SELECT start, end_ts FROM ext_windows WHERE source=?", [night]) { Interval(start: $0.i64(0), end: $0.i64(1)) })
    }

    /// Wiatry wykryte przez Rytm Snu (bez odrzuconych ✗).
    static func nightFarts() -> [Int64] {
        ensure()
        return SQL.shared.query("SELECT start FROM ext_events WHERE source=? AND type='fart' AND (verified IS NULL OR verified=1)", [night]) { $0.i64(0) }
    }

    static func exportedAt(_ source: String) -> Int64? {
        ensure()
        return SQL.shared.query("SELECT exported_at FROM ext_meta WHERE source=?", [source]) { $0.i64(0) }.first
    }

    /// Nieregularny rytm z opaski zapisany przez Rytm Snu (w nocy).
    static func nightAf() -> [Interval] {
        ensure()
        return Spans.union(SQL.shared.query("SELECT start, end_ts FROM ext_events WHERE source=? AND type='af' AND end_ts IS NOT NULL",
                                            [night]) { Interval(start: $0.i64(0), end: $0.i64(1)) })
    }

    static func ecgWindows() -> [Interval] {
        ensure()
        return Spans.union(SQL.shared.query("SELECT start, end_ts FROM ext_windows WHERE source=?", [ecg]) { Interval(start: $0.i64(0), end: $0.i64(1)) })
    }
}

/// Operacje na przedziałach czasu.
enum Spans {
    static func union(_ l: [Interval]) -> [Interval] {
        var out: [Interval] = []
        for s in l.filter({ $0.end > $0.start }).sorted(by: { $0.start < $1.start }) {
            if let last = out.last, s.start <= last.end { out[out.count - 1].end = max(last.end, s.end) } else { out.append(s) }
        }
        return out
    }

    static func intersect(_ a: [Interval], _ b: [Interval]) -> [Interval] {
        var out: [Interval] = []
        let ua = union(a), ub = union(b)
        var i = 0, j = 0
        while i < ua.count && j < ub.count {
            let s = max(ua[i].start, ub[j].start), e = min(ua[i].end, ub[j].end)
            if e > s { out.append(Interval(start: s, end: e)) }
            if ua[i].end < ub[j].end { i += 1 } else { j += 1 }
        }
        return out
    }

    static func total(_ l: [Interval]) -> Int64 { l.reduce(0) { $0 + ($1.end - $1.start) } }
}

/// AFIB w Kresce ↔ epizody migotania z Polar EKG.
enum AfLink {
    /// Zdarzenie porównywane z EKG: pierwsze z „AF” w nazwie (jak domyślnie na Androidzie).
    static func afTrackerId(_ ts: [Tracker]) -> Int64? { ts.first { $0.name.range(of: "AF", options: .caseInsensitive) != nil }?.id }

    struct AfData {
        let ecg: [Interval]
        let windows: [Interval]
        var night: [Interval] = []
        var any: Bool { !ecg.isEmpty || !windows.isEmpty || !night.isEmpty }
    }

    static func load() -> AfData { AfData(ecg: ExtStore.ecgEpisodes(), windows: ExtStore.ecgWindows(), night: ExtStore.nightAf()) }

    /// Minuty z przedziałów w każdym dniu.
    static func dailyMinutes(_ spans: [Interval], _ days: [Date]) -> [Double] {
        days.map { d in
            let r = [Interval(start: Fmt.cal.startOfDay(for: d).ms, end: Fmt.cal.startOfDay(for: Fmt.addDays(d, 1)).ms)]
            return Double(Spans.total(Spans.intersect(spans, r))) / 60_000
        }
    }

    static func verdict(_ d: AfData, _ s: Session, _ pauses: [Pause]) -> String? {
        let a = Timing.activeIntervals(s, pauses, now: nowMs())
        let hit = Spans.intersect(a, d.ecg)
        if !hit.isEmpty {
            let eps = d.ecg.filter { ep in hit.contains { $0.start < ep.end && $0.end > ep.start } }
            let from = eps.map { $0.start }.min() ?? hit[0].start, to = eps.map { $0.end }.max() ?? hit[0].end
            return "EKG: AF \(Fmt.hm(from))–\(Fmt.hm(to))" + (eps.count > 1 ? " (\(eps.count) epizody)" : "")
        }
        let rec = Spans.total(Spans.intersect(a, d.windows))
        return rec > 0 ? "EKG: bez AF (nagrywano \(Fmt.dur(rec)))" : nil
    }
}

/// FF w Kresce ↔ wiatry wykryte w nocy przez Rytm Snu (jak `FartMerge` na Androidzie).
enum FartLink {
    struct Window { let span: Interval; let manual: Int; let detected: Int; let matched: Int }

    struct Result {
        let windows: [Window]
        let manualOutside: Int
        var manual: Int { windows.reduce(0) { $0 + $1.manual } }
        var detected: Int { windows.reduce(0) { $0 + $1.detected } }
        var matched: Int { windows.reduce(0) { $0 + $1.matched } }
        /// Najlepsze oszacowanie – suma bez podwójnego liczenia.
        var combined: Int { manual + detected - matched + manualOutside }
        var caughtShare: Double? { manual > 0 ? Double(matched) / Double(manual) : nil }
        var confirmedShare: Double? { detected > 0 ? Double(matched) / Double(detected) : nil }
    }

    /// Licznik porównywany z Rytmem Snu: wybrany w ustawieniach albo pierwszy o nazwie „FF”.
    static func counterId(_ cs: [Counter]) -> Int64? {
        let saved = Int64(Prefs.d.integer(forKey: "cmp_counter"))
        if saved != 0, cs.contains(where: { $0.id == saved }) { return saved }
        return cs.first { $0.name.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare("FF") == .orderedSame }?.id
    }

    static var tolSec: Int {
        let v = Prefs.d.integer(forKey: "cmp_tol")
        return v > 0 ? v : 60
    }

    static func compare(counterId: Int64, from: Date, to: Date) -> Result? {
        let windows = ExtStore.nightWindows()
        if windows.isEmpty { return nil }
        let start = Fmt.cal.startOfDay(for: from).ms
        let end = Fmt.cal.startOfDay(for: Fmt.addDays(to, 1)).ms
        // kliknięcia z godziną (korekty dnia bez godziny pomijamy)
        var manual: [Int64] = []
        for t in Repo.taps(counterId, from: Fmt.key(Fmt.addDays(from, -1)), to: Fmt.key(Fmt.addDays(to, 1)))
            where !t.manual && t.amount > 0 && t.ts >= start && t.ts < end {
            manual.append(contentsOf: Array(repeating: t.ts, count: t.amount))
        }
        manual.sort()
        let det = ExtStore.nightFarts().filter { $0 >= start && $0 < end }.sorted()
        let ws = Spans.intersect(windows, [Interval(start: start, end: end)])
        let tol = Int64(tolSec) * 1000
        var out: [Window] = []
        var inside = 0
        for w in ws {
            let m = manual.filter { $0 >= w.start && $0 < w.end }
            let d = det.filter { $0 >= w.start && $0 < w.end }
            inside += m.count
            out.append(Window(span: w, manual: m.count, detected: d.count, matched: matchCount(m, d, tol)))
        }
        return Result(windows: out, manualOutside: manual.count - inside)
    }

    /// Dopasowanie 1:1 – najpierw najbliższe pary.
    static func matchCount(_ a: [Int64], _ b: [Int64], _ tol: Int64) -> Int {
        if a.isEmpty || b.isEmpty { return 0 }
        var pairs: [(Int64, Int, Int)] = []
        var s = 0
        for i in a.indices {
            while s < b.count && b[s] < a[i] - tol { s += 1 }
            var j = s
            while j < b.count && b[j] <= a[i] + tol { pairs.append((abs(a[i] - b[j]), i, j)); j += 1 }
        }
        pairs.sort { $0.0 < $1.0 }
        var ua = [Bool](repeating: false, count: a.count), ub = [Bool](repeating: false, count: b.count)
        var n = 0
        for (_, i, j) in pairs where !ua[i] && !ub[j] { ua[i] = true; ub[j] = true; n += 1 }
        return n
    }
}

extension AfLink {
    /// AFIB w Kresce ↔ migotanie w EKG w okresie raportu.
    struct Summary {
        let ecgMs: Int64, kreskaMs: Int64, bothMs: Int64, coverageMs: Int64
        let kreskaOutsideMs: Int64, ecgEpisodes: Int, felt: Int
    }

    static func summary(trackerId: Int64, from: Date, to: Date) -> Summary? {
        let d = load()
        if !d.any { return nil }
        let r = [Interval(start: Fmt.cal.startOfDay(for: from).ms, end: Fmt.cal.startOfDay(for: Fmt.addDays(to, 1)).ms)]
        let now = nowMs()
        let sessions = Repo.sessions(trackerId, fromMs: r[0].start - 86_400_000 * 2)
        let pauses = Repo.pausesFor(sessions.map { $0.id })
        let kreska = Spans.intersect(sessions.flatMap { Timing.activeIntervals($0, pauses[$0.id] ?? [], now: now) }, r)
        let ecg = Spans.intersect(d.ecg, r), win = Spans.intersect(d.windows, r)
        let both = Spans.intersect(kreska, ecg)
        let eps = d.ecg.filter { $0.end > r[0].start && $0.start < r[0].end }
        let felt = eps.filter { e in kreska.contains { $0.start < e.end && $0.end > e.start } }.count
        let kInWin = Spans.total(Spans.intersect(kreska, win))
        return Summary(ecgMs: Spans.total(ecg), kreskaMs: Spans.total(kreska), bothMs: Spans.total(both),
                       coverageMs: Spans.total(win), kreskaOutsideMs: Spans.total(kreska) - kInWin,
                       ecgEpisodes: eps.count, felt: felt)
    }
}
