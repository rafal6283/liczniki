import SwiftUI
import UserNotifications

@main
struct KreskaApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        Notify.registerCategories()
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if response.actionIdentifier == Notify.stopAction,
           let n = response.notification.request.content.userInfo["trackerId"] as? NSNumber {
            Repo.stopTracker(n.int64Value)
        }
        completionHandler()
    }
}

/// Odświeża ekrany po każdej zmianie danych.
final class Store: ObservableObject {
    static let shared = Store()
    @Published var version = 0
    private var token: NSObjectProtocol?

    private init() {
        token = NotificationCenter.default.addObserver(forName: .kreskaChanged, object: nil, queue: .main) { [weak self] _ in
            self?.version += 1
        }
    }

    func bump() { version += 1 }
}

struct ContentView: View {
    @ObservedObject private var store = Store.shared
    @Environment(\.scenePhase) private var phase
    @State private var tab = 0
    @State private var showResume = false
    @State private var started = false

    var body: some View {
        let _ = store.version
        let runningCount = Repo.running().count
        TabView(selection: $tab) {
            CountersView()
                .tabItem { Label("Liczniki", systemImage: "number.square") }
                .tag(0)
            EventsView()
                .tabItem { Label("Zdarzenia", systemImage: "timer") }
                .badge(runningCount)
                .tag(1)
            ReportsView()
                .tabItem { Label("Raporty", systemImage: "chart.bar.xaxis") }
                .tag(2)
        }
        .tint(Palette.accent)
        .sheet(isPresented: $showResume) { ResumeView() }
        .onAppear {
            guard !started else { return }
            started = true
            Notify.requestAuthorization()
            // Powrót po zamknięciu aplikacji: coś wciąż trwa, a aplikacja dawno nie była używana.
            if Prefs.resumeNeeded(hasRunning: runningCount > 0) {
                tab = 1
                showResume = true
            }
            Prefs.touch()
            LiveActivityManager.sync()
            Notify.refreshGoalReminders()
        }
        .onChange(of: phase) { _, newPhase in
            switch newPhase {
            case .active:
                // dane mogły się zmienić z widżetu albo minęła północ
                store.bump()
                Task { await KreskaCloud.shared.autoRun() }
                LiveActivityManager.sync()
                Notify.refreshGoalReminders()
                Prefs.touch()
            case .background:
                Prefs.touch()
            default:
                break
            }
        }
    }
}
