import Foundation
import WidgetKit

/// Wszystkie operacje na danych. Używane przez aplikację, widżety i akcje z ekranu blokady.
enum Repo {
    private static var db: SQL { SQL.shared }

    /// Po każdej zmianie: odśwież ekrany, widżety, Live Activity i przypomnienia.
    static func changed() {
        NotificationCenter.default.post(name: .kreskaChanged, object: nil)
        WidgetCenter.shared.reloadAllTimelines()
        LiveActivityManager.sync()
        Notify.refreshGoalReminders()
    }

    /* ---------- liczniki ---------- */

    private static func readCounter(_ r: SQLRow) -> Counter {
        Counter(id: r.i64(0), name: r.str(1), step: r.int(2), goal: r.intOrNil(3), color: r.int(4), notify: r.int(5) == 1)
    }

    static func counters() -> [Counter] {
        db.query("SELECT id,name,step,goal,color,notify FROM counters ORDER BY sort, id", [], readCounter)
    }

    static func counter(_ id: Int64) -> Counter? {
        db.query("SELECT id,name,step,goal,color,notify FROM counters WHERE id=?", [id], readCounter).first
    }

    @discardableResult
    static func saveCounter(_ c: Counter) -> Int64 {
        var id = c.id
        if c.id == 0 {
            id = db.run(
                "INSERT INTO counters(name,step,goal,color,notify) VALUES(?,?,?,?,?)",
                [c.name, c.step, c.goal, c.color, c.notify ? 1 : 0]
            )
        } else {
            db.run(
                "UPDATE counters SET name=?,step=?,goal=?,color=?,notify=? WHERE id=?",
                [c.name, c.step, c.goal, c.color, c.notify ? 1 : 0, c.id]
            )
        }
        changed()
        return id
    }

    static func deleteCounter(_ id: Int64) {
        db.run("DELETE FROM taps WHERE counter_id=?", [id])
        db.run("DELETE FROM counters WHERE id=?", [id])
        changed()
    }

    static func total(_ counterId: Int64, day: String) -> Int {
        Int(db.scalar("SELECT COALESCE(SUM(amount),0) FROM taps WHERE counter_id=? AND day=?", [counterId, day]) ?? 0)
    }

    static func todayTotal(_ counterId: Int64) -> Int { total(counterId, day: Fmt.todayKey) }

    static func lastTap(_ counterId: Int64) -> Int64? {
        db.scalar(
            "SELECT MAX(ts) FROM taps WHERE counter_id=? AND day=? AND amount>0 AND manual=0",
            [counterId, Fmt.todayKey]
        )
    }

    private static func insertTap(_ counterId: Int64, _ ts: Int64, _ amount: Int, _ day: String, manual: Bool = false) {
        db.run(
            "INSERT INTO taps(counter_id,ts,amount,day,manual) VALUES(?,?,?,?,?)",
            [counterId, ts, amount, day, manual ? 1 : 0]
        )
    }

    @discardableResult
    static func increment(_ counterId: Int64) -> Int {
        guard let c = counter(counterId) else { return 0 }
        let before = todayTotal(counterId)
        insertTap(counterId, nowMs(), c.step, Fmt.todayKey)
        let after = before + c.step
        if let g = c.goal, before < g, after >= g { Notify.goalReached(c, total: after) }
        changed()
        return after
    }

    /// Cofa ostatni dzisiejszy wpis (usuwa go). Gdy ostatni wpis nie pasuje, dopisuje wpis ujemny.
    @discardableResult
    static func decrement(_ counterId: Int64) -> Int {
        guard let c = counter(counterId) else { return 0 }
        let before = todayTotal(counterId)
        if before <= 0 { return before }
        let last = db.query(
            "SELECT id, amount FROM taps WHERE counter_id=? AND day=? ORDER BY ts DESC, id DESC LIMIT 1",
            [counterId, Fmt.todayKey]
        ) { ($0.i64(0), $0.int(1)) }.first
        var amount = 0
        if let last = last, last.1 > 0, last.1 <= before {
            db.run("DELETE FROM taps WHERE id=?", [last.0])
            amount = last.1
        } else {
            amount = min(c.step, before)
            insertTap(counterId, nowMs(), -amount, Fmt.todayKey)
        }
        changed()
        return before - amount
    }

    /// Ustawia wynik wybranego dnia (wpis korygujący).
    static func setDayValue(_ counterId: Int64, day: Date, value: Int) {
        let key = Fmt.key(day)
        let diff = value - total(counterId, day: key)
        if diff == 0 { return }
        let ts = Fmt.cal.isDateInToday(day) ? nowMs() : Fmt.cal.startOfDay(for: day).ms + 12 * 3_600_000
        insertTap(counterId, ts, diff, key, manual: true)
        changed()
    }

    private static func readTap(_ r: SQLRow) -> Tap {
        Tap(id: r.i64(3), ts: r.i64(0), amount: r.int(1), manual: r.int(2) == 1)
    }

    static func tapsForDay(_ counterId: Int64, day: String) -> [Tap] {
        db.query(
            "SELECT ts, amount, manual, id FROM taps WHERE counter_id=? AND day=? ORDER BY ts DESC, id DESC",
            [counterId, day], readTap
        )
    }

    static func taps(_ counterId: Int64, from: String, to: String) -> [Tap] {
        db.query(
            "SELECT ts, amount, manual, id FROM taps WHERE counter_id=? AND day BETWEEN ? AND ? ORDER BY ts",
            [counterId, from, to], readTap
        )
    }

    static func addTap(_ counterId: Int64, ts: Int64, amount: Int) {
        insertTap(counterId, ts, amount, Fmt.key(ms: ts))
        changed()
    }

    static func updateTap(_ id: Int64, ts: Int64, amount: Int) {
        db.run("UPDATE taps SET ts=?, amount=?, day=?, manual=0 WHERE id=?", [ts, amount, Fmt.key(ms: ts), id])
        changed()
    }

    static func deleteTap(_ id: Int64) {
        db.run("DELETE FROM taps WHERE id=?", [id])
        changed()
    }

    static func dailyTotals(_ counterId: Int64, from: String, to: String) -> [String: Int] {
        let rows = db.query(
            "SELECT day, SUM(amount) FROM taps WHERE counter_id=? AND day BETWEEN ? AND ? GROUP BY day",
            [counterId, from, to]
        ) { ($0.str(0), $0.int(1)) }
        var out: [String: Int] = [:]
        for (k, v) in rows { out[k] = v }
        return out
    }

    static func allDailyTotals(_ counterId: Int64) -> [String: Int] {
        let rows = db.query("SELECT day, SUM(amount) FROM taps WHERE counter_id=? GROUP BY day", [counterId]) {
            ($0.str(0), $0.int(1))
        }
        var out: [String: Int] = [:]
        for (k, v) in rows { out[k] = v }
        return out
    }

    static func firstDay(_ counterId: Int64) -> Date? {
        let s: String? = db.query("SELECT MIN(day) FROM taps WHERE counter_id=?", [counterId]) { (r: SQLRow) -> String? in
            r.isNull(0) ? nil : r.str(0)
        }.first ?? nil
        return s.flatMap { Fmt.parseKey($0) }
    }

    /* ---------- typy zdarzeń ---------- */

    private static func readTracker(_ r: SQLRow) -> Tracker {
        Tracker(id: r.i64(0), name: r.str(1), color: r.int(2), notify: r.int(3) == 1)
    }

    static func trackers() -> [Tracker] {
        db.query("SELECT id,name,color,notify FROM trackers ORDER BY sort, id", [], readTracker)
    }

    static func tracker(_ id: Int64) -> Tracker? {
        db.query("SELECT id,name,color,notify FROM trackers WHERE id=?", [id], readTracker).first
    }

    @discardableResult
    static func saveTracker(_ t: Tracker) -> Int64 {
        var id = t.id
        if t.id == 0 {
            id = db.run("INSERT INTO trackers(name,color,notify) VALUES(?,?,?)", [t.name, t.color, t.notify ? 1 : 0])
        } else {
            db.run("UPDATE trackers SET name=?,color=?,notify=? WHERE id=?", [t.name, t.color, t.notify ? 1 : 0, t.id])
        }
        changed()
        return id
    }

    static func deleteTracker(_ id: Int64) {
        for s in sessionsWhere("WHERE tracker_id=?", [id]) { Notify.cancelOverdue(s.id) }
        db.run("DELETE FROM pauses WHERE session_id IN (SELECT id FROM sessions WHERE tracker_id=?)", [id])
        db.run("DELETE FROM sessions WHERE tracker_id=?", [id])
        db.run("DELETE FROM trackers WHERE id=?", [id])
        changed()
    }

    /* ---------- zdarzenia ---------- */

    private static func readSession(_ r: SQLRow) -> Session {
        Session(id: r.i64(0), trackerId: r.i64(1), start: r.i64(2), end: r.i64OrNil(3), note: r.str(4))
    }

    private static func sessionsWhere(_ whereSql: String, _ args: [Any?]) -> [Session] {
        db.query("SELECT id,tracker_id,start,end_ts,note FROM sessions \(whereSql)", args, readSession)
    }

    static func session(_ id: Int64) -> Session? { sessionsWhere("WHERE id=?", [id]).first }
    static func running() -> [Session] { sessionsWhere("WHERE end_ts IS NULL ORDER BY start", []) }
    static func runningFor(_ trackerId: Int64) -> Session? {
        sessionsWhere("WHERE end_ts IS NULL AND tracker_id=? ORDER BY start DESC LIMIT 1", [trackerId]).first
    }
    static func sessions(_ trackerId: Int64, fromMs: Int64) -> [Session] {
        sessionsWhere("WHERE tracker_id=? AND start>=? ORDER BY start DESC", [trackerId, fromMs])
    }
    static func recentSessions(_ limit: Int) -> [Session] { sessionsWhere("ORDER BY start DESC LIMIT \(limit)", []) }
    static func lastFinished(_ trackerId: Int64) -> Session? {
        sessionsWhere("WHERE tracker_id=? AND end_ts IS NOT NULL ORDER BY start DESC LIMIT 1", [trackerId]).first
    }
    static func allSessionDays(_ trackerId: Int64) -> Set<Date> {
        Set(db.query("SELECT start FROM sessions WHERE tracker_id=?", [trackerId]) { Fmt.cal.startOfDay(for: $0.i64(0).date) })
    }
    static func firstSessionDay(_ trackerId: Int64) -> Date? {
        db.scalar("SELECT MIN(start) FROM sessions WHERE tracker_id=?", [trackerId]).map { Fmt.cal.startOfDay(for: $0.date) }
    }

    static func startSession(_ trackerId: Int64) {
        guard let t = tracker(trackerId), runningFor(trackerId) == nil else { return }
        let id = db.run("INSERT INTO sessions(tracker_id,start,end_ts,note) VALUES(?,?,NULL,'')", [trackerId, nowMs()])
        Notify.scheduleOverdue(sessionId: id, tracker: t, activeSoFar: 0)
        changed()
    }

    static func stopSession(_ sessionId: Int64, at endTs: Int64? = nil) {
        let end = endTs ?? nowMs()
        db.run("UPDATE pauses SET end_ts=? WHERE session_id=? AND end_ts IS NULL", [end, sessionId])
        db.run("UPDATE sessions SET end_ts=? WHERE id=? AND end_ts IS NULL", [end, sessionId])
        Notify.cancelOverdue(sessionId)
        changed()
    }

    static func stopTracker(_ trackerId: Int64) {
        if let s = runningFor(trackerId) { stopSession(s.id) }
    }

    @discardableResult
    static func saveSession(_ s: Session) -> Int64 {
        var id = s.id
        if s.id == 0 {
            id = db.run("INSERT INTO sessions(tracker_id,start,end_ts,note) VALUES(?,?,?,?)", [s.trackerId, s.start, s.end, s.note])
        } else {
            db.run("UPDATE sessions SET tracker_id=?, start=?, end_ts=?, note=? WHERE id=?", [s.trackerId, s.start, s.end, s.note, s.id])
        }
        if let end = s.end {
            db.run("UPDATE pauses SET end_ts=? WHERE session_id=? AND end_ts IS NULL", [end, id])
            Notify.cancelOverdue(id)
        } else if let saved = session(id), let t = tracker(saved.trackerId) {
            if isPaused(id) { Notify.cancelOverdue(id) } else { Notify.scheduleOverdue(sessionId: id, tracker: t, activeSoFar: activeMs(saved)) }
        }
        changed()
        return id
    }

    static func deleteSession(_ id: Int64) {
        Notify.cancelOverdue(id)
        db.run("DELETE FROM pauses WHERE session_id=?", [id])
        db.run("DELETE FROM sessions WHERE id=?", [id])
        changed()
    }

    /* ---------- pauzy ---------- */

    private static func readPause(_ r: SQLRow) -> Pause {
        Pause(id: r.i64(0), sessionId: r.i64(1), start: r.i64(2), end: r.i64OrNil(3))
    }

    static func pauses(_ sessionId: Int64) -> [Pause] {
        db.query("SELECT id,session_id,start,end_ts FROM pauses WHERE session_id=? ORDER BY start", [sessionId], readPause)
    }

    static func pausesFor(_ ids: [Int64]) -> [Int64: [Pause]] {
        var out: [Int64: [Pause]] = [:]
        if ids.isEmpty { return out }
        var i = 0
        while i < ids.count {
            let chunk = ids[i..<min(i + 400, ids.count)]
            let list = chunk.map { String($0) }.joined(separator: ",")
            for p in db.query("SELECT id,session_id,start,end_ts FROM pauses WHERE session_id IN (\(list)) ORDER BY start", [], readPause) {
                out[p.sessionId, default: []].append(p)
            }
            i += 400
        }
        return out
    }

    static func isPaused(_ sessionId: Int64) -> Bool {
        (db.scalar("SELECT COUNT(*) FROM pauses WHERE session_id=? AND end_ts IS NULL", [sessionId]) ?? 0) > 0
    }

    static func activeMs(_ s: Session) -> Int64 { Timing.activeMs(s, pauses(s.id), now: nowMs()) }

    static func pauseSession(_ sessionId: Int64) {
        guard let s = session(sessionId), s.end == nil, !isPaused(sessionId) else { return }
        db.run("INSERT INTO pauses(session_id,start,end_ts) VALUES(?,?,NULL)", [sessionId, nowMs()])
        Notify.cancelOverdue(sessionId)
        changed()
    }

    static func resumeSession(_ sessionId: Int64) {
        guard let s = session(sessionId), s.end == nil else { return }
        db.run("UPDATE pauses SET end_ts=? WHERE session_id=? AND end_ts IS NULL", [nowMs(), sessionId])
        if let t = tracker(s.trackerId) { Notify.scheduleOverdue(sessionId: sessionId, tracker: t, activeSoFar: activeMs(s)) }
        changed()
    }

    static func pauseTracker(_ trackerId: Int64) { if let s = runningFor(trackerId) { pauseSession(s.id) } }
    static func resumeTracker(_ trackerId: Int64) { if let s = runningFor(trackerId) { resumeSession(s.id) } }

    static func deletePause(_ id: Int64) {
        let sid = db.scalar("SELECT session_id FROM pauses WHERE id=?", [id])
        db.run("DELETE FROM pauses WHERE id=?", [id])
        if let sid = sid, let s = session(sid), s.end == nil, let t = tracker(s.trackerId), !isPaused(sid) {
            Notify.scheduleOverdue(sessionId: sid, tracker: t, activeSoFar: activeMs(s))
        }
        changed()
    }

    /* ---------- eksport ---------- */

    static func csv(from: Date) -> String {
        var out = "\u{FEFF}typ;nazwa;data;wartość;początek;koniec;czas_min;pauzy_min;notatka\r\n"
        func q(_ s: String) -> String { "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        let fromKey = Fmt.key(from)
        let toKey = Fmt.todayKey
        for c in counters() {
            for (d, v) in dailyTotals(c.id, from: fromKey, to: toKey).sorted(by: { $0.key < $1.key }) where v != 0 {
                out += "licznik;\(q(c.name));\(d);\(v);;;;;\r\n"
            }
        }
        let names = Dictionary(uniqueKeysWithValues: trackers().map { ($0.id, $0.name) })
        let list = sessionsWhere("WHERE start>=? ORDER BY start", [Fmt.cal.startOfDay(for: from).ms])
        let pm = pausesFor(list.map { $0.id })
        let now = nowMs()
        for s in list {
            guard let n = names[s.trackerId] else { continue }
            let active = Timing.activeMs(s, pm[s.id] ?? [], now: now)
            let paused = (s.end ?? now) - s.start - active
            out += "zdarzenie;\(q(n));\(Fmt.key(ms: s.start));;\(Fmt.full(s.start));"
            out += (s.end.map { Fmt.full($0) } ?? "") + ";"
            out += "\(active / 60_000);\(paused / 60_000);\(q(s.note))\r\n"
        }
        return out
    }
}
