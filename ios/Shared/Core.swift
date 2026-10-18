import Foundation
import SwiftUI
import UIKit

/* ---------- modele ---------- */

struct Counter: Identifiable, Hashable {
    var id: Int64
    var name: String
    var step: Int
    var goal: Int?
    var color: Int
    /// Na iOS: czy licznik ma być widoczny w widżecie „Liczniki”.
    var notify: Bool
}

struct Tracker: Identifiable, Hashable {
    var id: Int64
    var name: String
    var color: Int
    var notify: Bool
}

struct Session: Identifiable, Hashable {
    var id: Int64
    var trackerId: Int64
    var start: Int64
    var end: Int64?
    var note: String
}

/// Pauza w trakcie zdarzenia; end == nil oznacza, że pauza trwa.
struct Pause: Identifiable, Hashable {
    var id: Int64
    var sessionId: Int64
    var start: Int64
    var end: Int64?
}

/// Pojedyncza zmiana licznika.
struct Tap: Identifiable, Hashable {
    var id: Int64
    var ts: Int64
    var amount: Int
    var manual: Bool
}

func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

extension Int64 {
    var date: Date { Date(timeIntervalSince1970: TimeInterval(self) / 1000) }
}

extension Date {
    var ms: Int64 { Int64(timeIntervalSince1970 * 1000) }
}

extension Notification.Name {
    static let kreskaChanged = Notification.Name("pl.kreska.changed")
}

/* ---------- wspólne miejsce na dane (aplikacja + widżety) ---------- */

enum AppGroup {
    static let baseId = "group.pl.kreska.app"

    /// Narzędzia do instalacji (Sideloadly, AltStore) często zmieniają nazwę grupy,
    /// dlatego najpierw czytamy ją z profilu dołączonego do aplikacji.
    static let identifier: String? = {
        var candidates: [String] = provisionedGroups()
        if let alt = Bundle.main.object(forInfoDictionaryKey: "ALTAppGroups") as? [String] {
            candidates.append(contentsOf: alt)
        }
        candidates.append(baseId)
        for id in candidates {
            if FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) != nil {
                return id
            }
        }
        return nil
    }()

    static var isShared: Bool { identifier != nil }

    static let containerURL: URL = {
        if let id = identifier, let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) {
            return url
        }
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    private static func provisionedGroups() -> [String] {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .isoLatin1),
              let start = text.range(of: "<?xml"),
              let end = text.range(of: "</plist>")
        else { return [] }
        let plistText = String(text[start.lowerBound..<end.upperBound])
        guard let plistData = plistText.data(using: .isoLatin1),
              let obj = try? PropertyListSerialization.propertyList(from: plistData, format: nil),
              let dict = obj as? [String: Any],
              let ent = dict["Entitlements"] as? [String: Any],
              let groups = ent["com.apple.security.application-groups"] as? [String]
        else { return [] }
        return groups
    }
}

/* ---------- ustawienia ---------- */

enum Prefs {
    static var d: UserDefaults {
        if let id = AppGroup.identifier, let u = UserDefaults(suiteName: id) { return u }
        return .standard
    }

    static var reminderOn: Bool {
        get { d.bool(forKey: "rem_on") }
        set { d.set(newValue, forKey: "rem_on") }
    }
    static var reminderMinutes: Int {
        get { d.object(forKey: "rem_min") == nil ? 20 * 60 : d.integer(forKey: "rem_min") }
        set { d.set(newValue, forKey: "rem_min") }
    }
    static var overdueHours: Int {
        get { d.object(forKey: "overdue_h") == nil ? 4 : d.integer(forKey: "overdue_h") }
        set { d.set(newValue, forKey: "overdue_h") }
    }
    static var goalNotify: Bool {
        get { d.object(forKey: "goal_notify") == nil ? true : d.bool(forKey: "goal_notify") }
        set { d.set(newValue, forKey: "goal_notify") }
    }

    static func touch() { d.set(Date().timeIntervalSince1970, forKey: "last_seen") }

    /// Przy uruchomieniu: czy pokazać „Witaj z powrotem” (coś trwa, a aplikacja długo nie była używana).
    static func resumeNeeded(hasRunning: Bool) -> Bool {
        guard hasRunning else { return false }
        let last = d.double(forKey: "last_seen")
        return Date().timeIntervalSince1970 - last > 30 * 60
    }
}

/* ---------- daty, liczby ---------- */

enum Fmt {
    static let pl = Locale(identifier: "pl_PL")
    static let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.locale = pl
        c.timeZone = TimeZone.current
        c.firstWeekday = 2
        return c
    }()

    private static func df(_ f: String) -> DateFormatter {
        let d = DateFormatter()
        d.locale = pl
        d.calendar = cal
        d.timeZone = TimeZone.current
        d.dateFormat = f
        return d
    }

    private static let keyF: DateFormatter = {
        let d = DateFormatter()
        d.locale = Locale(identifier: "en_US_POSIX")
        d.calendar = Calendar(identifier: .gregorian)
        d.timeZone = TimeZone.current
        d.dateFormat = "yyyy-MM-dd"
        return d
    }()
    private static let hmF = df("HH:mm")
    private static let dayF = df("EEE, d MMM")
    private static let longF = df("EEEE, d MMMM")
    private static let wdF = df("EEE")
    private static let dateF = df("d.MM.yyyy")
    private static let fullF = df("yyyy-MM-dd HH:mm")
    private static let numF: NumberFormatter = {
        let f = NumberFormatter()
        f.locale = pl
        f.numberStyle = .decimal
        f.maximumFractionDigits = 1
        return f
    }()

    static func key(_ d: Date) -> String { keyF.string(from: d) }
    static func key(ms: Int64) -> String { keyF.string(from: ms.date) }
    static func parseKey(_ s: String) -> Date? { keyF.date(from: s).map { cal.startOfDay(for: $0) } }
    static var todayKey: String { key(Date()) }
    static var today: Date { cal.startOfDay(for: Date()) }
    static func addDays(_ d: Date, _ n: Int) -> Date { cal.date(byAdding: .day, value: n, to: d) ?? d }

    static func hm(_ ms: Int64) -> String { hmF.string(from: ms.date) }
    static func day(_ d: Date) -> String { dayF.string(from: d) }
    static func day(ms: Int64) -> String { dayF.string(from: ms.date) }
    static func longDay(_ d: Date) -> String { longF.string(from: d) }
    static func weekday(_ d: Date) -> String { wdF.string(from: d) }
    static func date(_ d: Date) -> String { dateF.string(from: d) }
    static func dateTime(_ ms: Int64) -> String { dateF.string(from: ms.date) + ", " + hm(ms) }
    static func full(_ ms: Int64) -> String { fullF.string(from: ms.date) }

    static func num(_ x: Int) -> String { numF.string(from: NSNumber(value: x)) ?? "\(x)" }
    static func num(_ x: Double) -> String { numF.string(from: NSNumber(value: x)) ?? "\(x)" }

    static func dur(_ ms: Int64) -> String {
        if ms < 60_000 { return "\(max(0, ms / 1000)) s" }
        let m = (ms + 30_000) / 60_000
        let h = m / 60
        let r = m % 60
        if h == 0 { return "\(m) min" }
        if r == 0 { return "\(h) h" }
        return "\(h) h \(r) min"
    }

    static func clock(_ ms: Int64) -> String {
        let s = max(0, ms / 1000)
        return String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }
}

/* ---------- kolory ---------- */

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}

enum Palette {
    static let count = 6
    static let light: [UInt32] = [0x0E7466, 0x3456B4, 0xB4600C, 0x8B3CA6, 0xB0334C, 0x5B771A]
    static let dark: [UInt32] = [0x3CC3AC, 0x86A3F2, 0xEDA04A, 0xCD92E2, 0xF08499, 0xA9C75E]

    static func dyn(_ l: UInt32, _ d: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: d) : UIColor(rgb: l) })
    }

    static func color(_ i: Int) -> Color {
        let k = ((i % count) + count) % count
        return dyn(light[k], dark[k])
    }

    static let accent = dyn(0x0E7466, 0x3CC3AC)
    static let live = dyn(0xB4600C, 0xEDA04A)
    static let liveSoft = dyn(0xF7E8D5, 0x3A2A17)
    static let onColor = dyn(0xFFFFFF, 0x0E141A)

    static func nextFree(_ used: [Int]) -> Int {
        for i in 0..<count where !used.contains(i) { return i }
        return used.count % count
    }
}

/* ---------- czas aktywny zdarzenia (bez pauz) ---------- */

struct Interval {
    var start: Int64
    var end: Int64
}

enum Timing {
    static func activeIntervals(_ s: Session, _ pauses: [Pause], now: Int64) -> [Interval] {
        let end = s.end ?? now
        if end <= s.start { return [] }
        let ps = pauses
            .map { Interval(start: max($0.start, s.start), end: min($0.end ?? end, end)) }
            .filter { $0.end > $0.start }
            .sorted { $0.start < $1.start }
        var out: [Interval] = []
        var cur = s.start
        for p in ps {
            if p.start > cur { out.append(Interval(start: cur, end: p.start)) }
            cur = max(cur, p.end)
        }
        if end > cur { out.append(Interval(start: cur, end: end)) }
        return out
    }

    static func activeMs(_ s: Session, _ pauses: [Pause], now: Int64) -> Int64 {
        activeIntervals(s, pauses, now: now).reduce(Int64(0)) { $0 + ($1.end - $1.start) }
    }
}
