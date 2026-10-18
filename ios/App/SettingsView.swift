import SwiftUI
import UserNotifications

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var reminderOn = Prefs.reminderOn
    @State private var reminderTime: Date = {
        let m = Prefs.reminderMinutes
        return Fmt.cal.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
    }()
    @State private var goalNotify = Prefs.goalNotify
    @State private var overdue = Prefs.overdueHours
    @State private var notifStatus = "sprawdzam…"

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Wieczorne przypomnienie", isOn: $reminderOn)
                    if reminderOn {
                        DatePicker("Godzina", selection: $reminderTime, displayedComponents: .hourAndMinute)
                    }
                    Toggle("Powiadom o osiągnięciu celu", isOn: $goalNotify)
                } header: {
                    Text("Cele dzienne")
                } footer: {
                    Text("Przypomni, jeśli któryś licznik z celem dziennym nie doszedł do celu.")
                }

                Section {
                    Picker("Gdy trwa dłużej niż", selection: $overdue) {
                        Text("wyłączone").tag(0)
                        ForEach([1, 2, 4, 8, 12], id: \.self) { h in Text("\(h) h").tag(h) }
                    }
                } header: {
                    Text("Niezatrzymane zdarzenie")
                } footer: {
                    Text("Powiadomienie z przyciskiem „Zatrzymaj teraz”. Czas liczony bez pauz.")
                }

                Section {
                    LabeledContent("Powiadomienia", value: notifStatus)
                    Button("Ustawienia systemowe aplikacji") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                } header: {
                    Text("System")
                }

                Section {
                    Text("• Licznik — jeden licznik z przyciskami + i − (ekran główny i ekran blokady).")
                    Text("• Liczniki — kilka liczników naraz.")
                    Text("• Zdarzenia — Start, Pauza i Stop.")
                    Text("Trwające zdarzenie pokazuje się też jako stoper na ekranie blokady i w Dynamic Island.")
                    LabeledContent("Dane wspólne z widżetami", value: AppGroup.isShared ? "tak" : "nie")
                } header: {
                    Text("Widżety")
                } footer: {
                    Text(AppGroup.isShared
                         ? "Widżet dodasz, przytrzymując palec na ekranie głównym → „Edytuj” → „Dodaj widżet” → Kreska."
                         : "Instalacja nie dała aplikacji wspólnego folderu z widżetami, więc widżety nie widzą danych. Aplikacja działa normalnie. Zobacz README w repozytorium.")
                }
            }
            .navigationTitle("Ustawienia")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Gotowe") {
                        save()
                        dismiss()
                    }
                }
            }
            .task {
                let s = await UNUserNotificationCenter.current().notificationSettings()
                switch s.authorizationStatus {
                case .authorized, .provisional, .ephemeral: notifStatus = "włączone"
                case .denied: notifStatus = "wyłączone"
                default: notifStatus = "nie ustawiono"
                }
            }
        }
    }

    private func save() {
        Prefs.reminderOn = reminderOn
        let c = Fmt.cal.dateComponents([.hour, .minute], from: reminderTime)
        Prefs.reminderMinutes = (c.hour ?? 20) * 60 + (c.minute ?? 0)
        Prefs.goalNotify = goalNotify
        Prefs.overdueHours = overdue
        Notify.refreshGoalReminders()
        Notify.rescheduleAllOverdue()
    }
}
