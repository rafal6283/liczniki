import Foundation

struct WeekPoint: Identifiable {
    var id: Date { start }
    let start: Date
    let value: Double
}

struct CounterStats {
    var days: [Date]
    var values: [Double]
    var fullDays: Int
    var since: Date?
    var trimmed: Bool
    var trend: String?
    var sum: Double
    var avgPerDay: Double
    var avgActiveDay: Double
    var median: Double
    var bestIdx: Int?
    var worstIdx: Int?
    var activeDays: Int
    var goalDays: Int?
    var streakCurrent: Int
    var streakBest: Int
    var goalStreakCurrent: Int?
    var goalStreakBest: Int?
    var hours: [Double]
    var heat: [[Double]]
    var weekdayAvg: [Double]
    var parts: [Double]
    var avgFirstMin: Int?
    var avgLastMin: Int?
    var avgGapMs: Int64?
    var longestGapMs: Int64?
    var clicks: Int
    var weeks: [WeekPoint]
}

struct TrackerStats {
    var days: [Date]
    var fullDays: Int
    var since: Date?
    var trimmed: Bool
    var trendTotal: String?
    var trendCount: String?
    var sessions: [Session]
    var pauses: [Int64: [Pause]]
    var activeBySession: [Int64: Int64]
    var count: Int
    var totalMs: Int64
    var avgMs: Int64
    var medianMs: Int64
    var shortestMs: Int64
    var longestMs: Int64
    var pausedMs: Int64
    var pauseCount: Int
    var dailyMinutes: [Double]
    var activeDays: Int
    var avgPerDayMs: Int64
    var countPerDay: Double
    var streakCurrent: Int
    var streakBest: Int
    var startHours: [Double]
    var activeHours: [Double]
    var heat: [[Double]]
    var weekdayCount: [Double]
    var weekdayMinutes: [Double]
    var avgStartMin: Int?
    var avgEndMin: Int?
    var buckets: [Double]
    var avgGapMs: Int64?
    var longestGapMs: Int64?
    var weeks: [WeekPoint]
}

enum Stats {
    static let bucketLabels = ["<15 min", "15–30", "30–60", "1–2 h", "2–4 h", ">4 h"]
    static let partLabels = ["rano 5–12", "popołudnie 12–17", "wieczór 17–22", "noc 22–5"]
    static let weekdays = ["pn", "wt", "śr", "cz", "pt", "sb", "nd"]
    static let weekdaysLong = ["poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela"]

    private static var cal: Calendar { Fmt.cal }

    /// Poniedziałek = 0 … niedziela = 6.
    static func wd(_ d: Date) -> Int { (cal.component(.weekday, from: d) + 5) % 7 }

    static func minuteOfDay(_ ms: Int64) -> Int {
        let c = cal.dateComponents([.hour, .minute], from: ms.date)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }

    static func range(days n: Int) -> [Date] {
        let t = Fmt.today
        return (0..<n).map { Fmt.addDays(t, $0 - (n - 1)) }
    }

    /// Średnia godzina liczona po okręgu, żeby 23:30 i 0:30 dały północ, a nie południe.
    static func circularMean(_ mins: [Int]) -> Int? {
        if mins.isEmpty { return nil }
        var sx = 0.0, sy = 0.0
        for m in mins {
            let a = Double(m) / 1440 * 2 * .pi
            sx += cos(a)
            sy += sin(a)
        }
        var a = atan2(sy, sx)
        if a < 0 { a += 2 * .pi }
        return Int((a / (2 * .pi) * 1440).rounded()) % 1440
    }

    static func median(_ xs: [Double]) -> Double {
        if xs.isEmpty { return 0 }
        let s = xs.sorted()
        let n = s.count
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2
    }

    static func partOfDay(_ h: Int) -> Int {
        switch h {
        case 5...11: return 0
        case 12...16: return 1
        case 17...21: return 2
        default: return 3
        }
    }

    static func streaks(_ ok: Set<Date>, today: Date) -> (current: Int, best: Int) {
        if ok.isEmpty { return (0, 0) }
        var best = 0, run = 0
        var prev: Date?
        for d in ok.sorted() {
            if let p = prev, Fmt.addDays(p, 1) == d { run += 1 } else { run = 1 }
            best = max(best, run)
            prev = d
        }
        var cur = 0
        var d = ok.contains(today) ? today : Fmt.addDays(today, -1)
        while ok.contains(d) {
            cur += 1
            d = Fmt.addDays(d, -1)
        }
        return (cur, best)
    }

    /// Okno liczenia: wybrany okres, ale nie wcześniej niż pierwszy wpis.
    private static func window(_ req: [Date], since: Date?) -> [Date] {
        let start = since ?? Fmt.today
        let eff = req.filter { $0 >= start }
        return eff.isEmpty ? [req.last ?? Fmt.today] : eff
    }

    /// Pełne dni do średnich: bez dzisiejszego (niepełnego), chyba że to jedyny dzień.
    private static func fullDays(_ eff: [Date]) -> [Date] {
        let today = Fmt.today
        let full = eff.filter { $0 != today }
        return full.isEmpty ? eff : full
    }

    private static func prevWindow(_ eff: [Date], since: Date?) -> [Date] {
        guard let since = since, let first = eff.first else { return [] }
        return Array((1...eff.count).map { Fmt.addDays(first, -$0) }.filter { $0 >= since }.reversed())
    }

    private static func weekStart(_ d: Date) -> Date {
        cal.dateInterval(of: .weekOfYear, for: d)?.start ?? d
    }

    private static func weeks(_ days: [Date], _ values: [Double]) -> [WeekPoint] {
        var order: [Date] = []
        var sums: [Date: Double] = [:]
        for (i, d) in days.enumerated() {
            let w = weekStart(d)
            if sums[w] == nil { order.append(w) }
            sums[w, default: 0] += values[i]
        }
        return order.map { WeekPoint(start: $0, value: sums[$0] ?? 0) }
    }

    private static func weekdayCounts(_ days: [Date]) -> [Int] {
        var n = Array(repeating: 0, count: 7)
        for d in days { n[wd(d)] += 1 }
        return n
    }

    /* ---------- licznik ---------- */

    static func counter(_ c: Counter, _ requested: [Date]) -> CounterStats {
        let since = Repo.firstDay(c.id)
        let days = window(requested, since: since)
        let fromKey = Fmt.key(days.first!)
        let toKey = Fmt.key(days.last!)
        let totals = Repo.dailyTotals(c.id, from: fromKey, to: toKey)
        let values = days.map { Double(totals[Fmt.key($0)] ?? 0) }
        let sum = values.reduce(0, +)
        let activeDays = values.filter { $0 > 0 }.count
        let full = Set(fullDays(days))
        let fullValues = days.indices.filter { full.contains(days[$0]) }.map { values[$0] }
        let fullActive = fullValues.filter { $0 > 0 }
        let avgPerDay = fullValues.isEmpty ? 0 : fullValues.reduce(0, +) / Double(fullValues.count)

        var bestIdx: Int?, worstIdx: Int?
        for (i, v) in values.enumerated() where v > 0 {
            if bestIdx == nil || v > values[bestIdx!] { bestIdx = i }
            if worstIdx == nil || v < values[worstIdx!] { worstIdx = i }
        }
        let goalDays = c.goal.map { g in values.filter { $0 >= Double(g) }.count }

        let prevDays = prevWindow(days, since: since)
        var trendStr: String?
        if let pf = prevDays.first, let pl = prevDays.last {
            let prevSum = Double(Repo.dailyTotals(c.id, from: Fmt.key(pf), to: Fmt.key(pl)).values.reduce(0, +))
            trendStr = trend(avgPerDay, prevSum / Double(prevDays.count))
        }

        let all = Repo.allDailyTotals(c.id)
        let today = Fmt.today
        let activeSet = Set(all.filter { $0.value > 0 }.keys.compactMap { Fmt.parseKey($0) })
        let st = streaks(activeSet, today: today)
        var gsc: Int?, gsb: Int?
        if let g = c.goal {
            let gs = streaks(Set(all.filter { $0.value >= g }.keys.compactMap { Fmt.parseKey($0) }), today: today)
            gsc = gs.current
            gsb = gs.best
        }

        var hours = Array(repeating: 0.0, count: 24)
        var heat = Array(repeating: Array(repeating: 0.0, count: 24), count: 7)
        var parts = Array(repeating: 0.0, count: 4)
        var firstByDay: [String: Int64] = [:]
        var lastByDay: [String: Int64] = [:]
        var positiveByDay: [String: [Int64]] = [:]
        var clicks = 0
        for t in Repo.taps(c.id, from: fromKey, to: toKey) where !t.manual {
            let d = t.ts.date
            let h = cal.component(.hour, from: d)
            let a = Double(t.amount)
            hours[h] += a
            heat[wd(d)][h] += a
            parts[partOfDay(h)] += a
            if t.amount > 0 {
                clicks += 1
                let k = Fmt.key(d)
                if firstByDay[k] == nil { firstByDay[k] = t.ts }
                lastByDay[k] = t.ts
                positiveByDay[k, default: []].append(t.ts)
            }
        }
        hours = hours.map { max(0, $0) }
        heat = heat.map { row in row.map { max(0, $0) } }
        parts = parts.map { max(0, $0) }
        let ps = parts.reduce(0, +)
        if ps > 0 { parts = parts.map { $0 / ps * 100 } }

        var gaps: [Int64] = []
        for list in positiveByDay.values where list.count > 1 {
            for i in 1..<list.count { gaps.append(list[i] - list[i - 1]) }
        }

        let wdN = weekdayCounts(days.filter { full.contains($0) })
        var wdTotal = Array(repeating: 0.0, count: 7)
        for (i, d) in days.enumerated() where full.contains(d) { wdTotal[wd(d)] += values[i] }
        let wdAvg = (0..<7).map { wdN[$0] > 0 ? wdTotal[$0] / Double(wdN[$0]) : 0 }

        return CounterStats(
            days: days, values: values, fullDays: full.count, since: since,
            trimmed: days.count < requested.count, trend: trendStr,
            sum: sum, avgPerDay: avgPerDay,
            avgActiveDay: fullActive.isEmpty ? 0 : fullActive.reduce(0, +) / Double(fullActive.count),
            median: median(fullActive),
            bestIdx: bestIdx, worstIdx: worstIdx, activeDays: activeDays, goalDays: goalDays,
            streakCurrent: st.current, streakBest: st.best, goalStreakCurrent: gsc, goalStreakBest: gsb,
            hours: hours, heat: heat, weekdayAvg: wdAvg, parts: parts,
            avgFirstMin: circularMean(firstByDay.values.map { minuteOfDay($0) }),
            avgLastMin: circularMean(lastByDay.values.map { minuteOfDay($0) }),
            avgGapMs: gaps.isEmpty ? nil : gaps.reduce(0, +) / Int64(gaps.count),
            longestGapMs: gaps.max(),
            clicks: clicks,
            weeks: weeks(days, values)
        )
    }

    /* ---------- zdarzenia ---------- */

    private static func bucket(_ ms: Int64) -> Int {
        let m = ms / 60_000
        switch m {
        case ..<15: return 0
        case ..<30: return 1
        case ..<60: return 2
        case ..<120: return 3
        case ..<240: return 4
        default: return 5
        }
    }

    static func tracker(_ t: Tracker, _ requested: [Date]) -> TrackerStats {
        let since = Repo.firstSessionDay(t.id)
        let days = window(requested, since: since)
        let n = days.count
        let now = nowMs()
        let fromTs = days.first!.ms
        let full = Set(fullDays(days))
        let sessions = Repo.sessions(t.id, fromMs: fromTs)
        let pauses = Repo.pausesFor(sessions.map { $0.id })
        var dayIndex: [Date: Int] = [:]
        for (i, d) in days.enumerated() { dayIndex[d] = i }

        var activeBy: [Int64: Int64] = [:]
        var dailyMin = Array(repeating: 0.0, count: n)
        var activeHours = Array(repeating: 0.0, count: 24)
        var heat = Array(repeating: Array(repeating: 0.0, count: 24), count: 7)
        var startHours = Array(repeating: 0.0, count: 24)
        var wdCount = Array(repeating: 0.0, count: 7)
        var wdMin = Array(repeating: 0.0, count: 7)
        var buckets = Array(repeating: 0.0, count: 6)
        var pausedMs: Int64 = 0
        var pauseCount = 0

        for s in sessions {
            let ps = pauses[s.id] ?? []
            let ivs = Timing.activeIntervals(s, ps, now: now)
            let active = ivs.reduce(Int64(0)) { $0 + ($1.end - $1.start) }
            activeBy[s.id] = active
            pausedMs += (s.end ?? now) - s.start - active
            pauseCount += ps.count
            let sd = s.start.date
            startHours[cal.component(.hour, from: sd)] += 1
            if full.contains(cal.startOfDay(for: sd)) { wdCount[wd(sd)] += 1 }
            buckets[bucket(active)] += 1
            // czas aktywny rozkładamy na godziny i dni (zdarzenie przez północ trafia do obu dni)
            for iv in ivs {
                var cur = iv.start
                while cur < iv.end {
                    let d = cur.date
                    let hourEnd = cal.dateInterval(of: .hour, for: d)?.end.ms ?? (cur + 3_600_000)
                    let e = min(hourEnd, iv.end)
                    let m = Double(e - cur) / 60_000
                    let h = cal.component(.hour, from: d)
                    let w = wd(d)
                    activeHours[h] += m
                    heat[w][h] += m
                    let dayStart = cal.startOfDay(for: d)
                    if full.contains(dayStart) { wdMin[w] += m }
                    if let di = dayIndex[dayStart] { dailyMin[di] += m }
                    cur = max(e, cur + 1)
                }
            }
        }

        let actives = sessions.map { activeBy[$0.id] ?? 0 }
        let total = actives.reduce(0, +)
        let count = sessions.count

        let chrono = sessions.sorted { $0.start < $1.start }
        var gaps: [Int64] = []
        if chrono.count > 1 {
            for i in 1..<chrono.count {
                guard let pe = chrono[i - 1].end else { continue }
                let g = chrono[i].start - pe
                if g > 0 { gaps.append(g) }
            }
        }

        let fullIdx = days.indices.filter { full.contains(days[$0]) }
        let fullMinutes = fullIdx.reduce(0.0) { $0 + dailyMin[$1] }
        let avgPerDayMs = fullIdx.isEmpty ? 0 : Int64(fullMinutes / Double(fullIdx.count) * 60_000)
        let fullCount = sessions.filter { full.contains(cal.startOfDay(for: $0.start.date)) }.count
        let countPerDay = fullIdx.isEmpty ? 0 : Double(fullCount) / Double(fullIdx.count)

        var trendTotal: String?, trendCount: String?
        let prevDays = prevWindow(days, since: since)
        if let pf = prevDays.first {
            let prev = Repo.sessions(t.id, fromMs: pf.ms).filter { $0.start < fromTs }
            let pp = Repo.pausesFor(prev.map { $0.id })
            let prevTotal = prev.reduce(Int64(0)) { $0 + Timing.activeMs($1, pp[$1.id] ?? [], now: now) }
            trendTotal = trend(Double(avgPerDayMs), Double(prevTotal) / Double(prevDays.count))
            trendCount = trend(countPerDay, Double(prev.count) / Double(prevDays.count))
        }

        let wdN = weekdayCounts(days.filter { full.contains($0) })
        for i in 0..<7 {
            if wdN[i] > 0 {
                wdCount[i] /= Double(wdN[i])
                wdMin[i] /= Double(wdN[i])
            } else {
                wdCount[i] = 0
                wdMin[i] = 0
            }
        }

        let st = streaks(Repo.allSessionDays(t.id), today: Fmt.today)

        return TrackerStats(
            days: days, fullDays: full.count, since: since, trimmed: days.count < requested.count,
            trendTotal: trendTotal, trendCount: trendCount,
            sessions: sessions, pauses: pauses, activeBySession: activeBy,
            count: count, totalMs: total,
            avgMs: count > 0 ? total / Int64(count) : 0,
            medianMs: Int64(median(actives.map { Double($0) })),
            shortestMs: actives.min() ?? 0, longestMs: actives.max() ?? 0,
            pausedMs: pausedMs, pauseCount: pauseCount,
            dailyMinutes: dailyMin, activeDays: dailyMin.filter { $0 > 0 }.count,
            avgPerDayMs: avgPerDayMs, countPerDay: countPerDay,
            streakCurrent: st.current, streakBest: st.best,
            startHours: startHours, activeHours: activeHours, heat: heat,
            weekdayCount: wdCount, weekdayMinutes: wdMin,
            avgStartMin: circularMean(sessions.map { minuteOfDay($0.start) }),
            avgEndMin: circularMean(sessions.compactMap { $0.end }.map { minuteOfDay($0) }),
            buckets: buckets,
            avgGapMs: gaps.isEmpty ? nil : gaps.reduce(0, +) / Int64(gaps.count),
            longestGapMs: gaps.max(),
            weeks: weeks(days, dailyMin)
        )
    }

    /* ---------- formatowanie ---------- */

    static func hhmm(_ m: Int?) -> String {
        guard let m = m else { return "–" }
        return String(format: "%d:%02d", m / 60, m % 60)
    }

    static func trend(_ cur: Double, _ prev: Double) -> String? {
        if prev <= 0 { return cur > 0 ? "nowe" : nil }
        let pct = Int(((cur - prev) / prev * 100).rounded())
        if pct > 0 { return "+\(pct)%" }
        if pct < 0 { return "−\(-pct)%" }
        return "bez zmian"
    }

    static func minutesLabel(_ m: Double) -> String {
        m >= 90 ? Fmt.num(m / 60) + " h" : "\(Int(m.rounded())) min"
    }

    static func basisNote(fullDays: Int, since: Date?, trimmed: Bool, trendShown: Bool) -> String {
        var s = "Średnie z \(fullDays) \(fullDays == 1 ? "dnia" : "dni")"
        if let since = since, since != Fmt.today { s += " (bez dzisiejszego, który jeszcze trwa)" }
        s += "."
        if trimmed, let since = since { s += " Dane od \(Fmt.date(since)), wcześniejsze dni okresu pominięte." }
        if trendShown { s += " W nawiasie zmiana średniej względem poprzedniego okresu tej samej długości." }
        return s
    }
}
