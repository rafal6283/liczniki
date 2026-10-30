import Foundation

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

    // ---------------------------------------------------------------- automatyka

    /// Po otwarciu aplikacji: wymiana co najmniej co 15 min, kopia raz na dobę (jeśli włączona).
    func autoRun() async {
        load()
        guard sync.ready, !busy else { return }
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
        var any: Bool { !ecg.isEmpty || !windows.isEmpty }
    }

    static func load() -> AfData { AfData(ecg: ExtStore.ecgEpisodes(), windows: ExtStore.ecgWindows()) }

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
