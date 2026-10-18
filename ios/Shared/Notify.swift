import Foundation
import UserNotifications

/// Powiadomienia lokalne: osiągnięty cel, wieczorne przypomnienie, zbyt długie zdarzenie.
enum Notify {
    static let stopAction = "STOP"
    static let overdueCategory = "OVERDUE"

    private static var center: UNUserNotificationCenter { UNUserNotificationCenter.current() }

    static func requestAuthorization() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    static func registerCategories() {
        let stop = UNNotificationAction(identifier: stopAction, title: "Zatrzymaj teraz", options: [])
        let cat = UNNotificationCategory(identifier: overdueCategory, actions: [stop], intentIdentifiers: [], options: [])
        center.setNotificationCategories([cat])
    }

    static func goalReached(_ c: Counter, total: Int) {
        guard Prefs.goalNotify else { return }
        let content = UNMutableNotificationContent()
        content.title = "Cel osiągnięty: \(c.name)"
        content.body = "Dziś \(Fmt.num(total)) z \(Fmt.num(c.goal ?? total)). Dobra robota!"
        content.sound = .default
        let req = UNNotificationRequest(identifier: "goal-\(c.id)-\(Fmt.todayKey)", content: content, trigger: nil)
        center.add(req)
    }

    /* ---------- zbyt długie zdarzenie ---------- */

    private static func overdueId(_ sessionId: Int64) -> String { "overdue-\(sessionId)" }

    /// Planuje powiadomienie na moment, w którym czas aktywny (bez pauz) przekroczy próg z ustawień.
    static func scheduleOverdue(sessionId: Int64, tracker: Tracker, activeSoFar: Int64) {
        cancelOverdue(sessionId)
        let h = Prefs.overdueHours
        guard h > 0 else { return }
        let remaining = Double(Int64(h) * 3_600_000 - activeSoFar) / 1000
        guard remaining > 1 else { return }
        let content = UNMutableNotificationContent()
        content.title = "\(tracker.name) trwa już \(h) h"
        content.body = "Czy zapomniałeś zatrzymać? Czas liczony bez pauz."
        content.sound = .default
        content.categoryIdentifier = overdueCategory
        content.userInfo = ["trackerId": NSNumber(value: tracker.id)]
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: remaining, repeats: false)
        center.add(UNNotificationRequest(identifier: overdueId(sessionId), content: content, trigger: trigger))
    }

    static func cancelOverdue(_ sessionId: Int64) {
        center.removePendingNotificationRequests(withIdentifiers: [overdueId(sessionId)])
        center.removeDeliveredNotifications(withIdentifiers: [overdueId(sessionId)])
    }

    /// Przelicza wszystkie zaplanowane przypomnienia o długich zdarzeniach (np. po zmianie progu).
    static func rescheduleAllOverdue() {
        for s in Repo.running() {
            guard let t = Repo.tracker(s.trackerId) else { continue }
            if Repo.isPaused(s.id) { cancelOverdue(s.id) } else { scheduleOverdue(sessionId: s.id, tracker: t, activeSoFar: Repo.activeMs(s)) }
        }
    }

    /* ---------- wieczorne przypomnienie o celach ---------- */

    /// iOS nie pozwala sprawdzić warunku w chwili wysłania, więc przypomnienia planujemy
    /// na 7 dni naprzód i przeliczamy je przy każdej zmianie (dzisiejsze ma aktualny stan).
    static func refreshGoalReminders() {
        let cal = Fmt.cal
        let today = Fmt.today
        let ids = (0..<8).map { "remind-" + Fmt.key(Fmt.addDays(today, $0)) }
        center.removePendingNotificationRequests(withIdentifiers: ids)
        guard Prefs.reminderOn else { return }
        let goals = Repo.counters().filter { $0.goal != nil }
        guard !goals.isEmpty else { return }
        let m = Prefs.reminderMinutes
        for i in 0..<7 {
            let day = Fmt.addDays(today, i)
            var comps = cal.dateComponents([.year, .month, .day], from: day)
            comps.hour = m / 60
            comps.minute = m % 60
            guard let fire = cal.date(from: comps), fire > Date() else { continue }
            let content = UNMutableNotificationContent()
            content.sound = .default
            if i == 0 {
                let missing: [String] = goals.compactMap { c in
                    guard let g = c.goal else { return nil }
                    let v = Repo.todayTotal(c.id)
                    return v < g ? "\(c.name): \(Fmt.num(v)) / \(Fmt.num(g))" : nil
                }
                if missing.isEmpty { continue }
                content.title = missing.count == 1 ? "Do celu jeszcze brakuje" : "Cele na dziś nie są jeszcze osiągnięte"
                content.body = missing.joined(separator: "\n")
            } else {
                content.title = "Cele na dziś"
                content.body = "Sprawdź: " + goals.map { $0.name }.joined(separator: ", ")
            }
            let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
            center.add(UNNotificationRequest(identifier: "remind-" + Fmt.key(day), content: content, trigger: trigger))
        }
    }
}
