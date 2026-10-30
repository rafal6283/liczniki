import SwiftUI

struct SessionEditItem: Identifiable {
    let id = UUID()
    let session: Session?
    let trackerId: Int64
}

struct EventsView: View {
    @ObservedObject private var store = Store.shared
    @State private var editingTracker: Tracker?
    @State private var creating = false
    @State private var sessionEdit: SessionEditItem?

    var body: some View {
        let _ = store.version
        let trackers = Repo.trackers()
        let running = Dictionary(Repo.running().map { ($0.trackerId, $0) }, uniquingKeysWith: { a, _ in a })
        let recent = Repo.recentSessions(12)
        let pauses = Repo.pausesFor(Array(Set(running.values.map { $0.id } + recent.map { $0.id })))
        let byId = Dictionary(uniqueKeysWithValues: trackers.map { ($0.id, $0) })
        let afId = AfLink.afTrackerId(trackers)
        let af = afId == nil ? nil : AfLink.load()
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Zdarzenia").font(.title2.bold())
                        Spacer()
                        Text("start, pauza, stop").font(.subheadline).foregroundStyle(.secondary)
                    }
                    if trackers.isEmpty {
                        EmptyCard(
                            eyebrow: "Brak typów zdarzeń",
                            title: "Co chcesz mierzyć w czasie?",
                            text: "Naciśnij Start, gdy coś się zaczyna, i Stop, gdy się kończy. W trakcie możesz zrobić pauzę. Stoper pokaże się też na ekranie blokady."
                        ) {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack {
                                    ForEach(["Praca", "Sen", "Ból głowy", "Trening"], id: \.self) { n in
                                        Button("+ \(n)") {
                                            Repo.saveTracker(Tracker(id: 0, name: n, color: Palette.nextFree(trackers.map { $0.color }), notify: true))
                                        }
                                        .buttonStyle(.bordered)
                                    }
                                }
                            }
                            Button("Nowy typ zdarzenia") { creating = true }.buttonStyle(.borderedProminent)
                        }
                    }
                    ForEach(trackers) { t in
                        if let s = running[t.id] {
                            RunningCard(t: t, s: s, pauses: pauses[s.id] ?? []) {
                                sessionEdit = SessionEditItem(session: s, trackerId: t.id)
                            }
                        } else {
                            TrackerCard(t: t,
                                        onEdit: { editingTracker = t },
                                        onAdd: { sessionEdit = SessionEditItem(session: nil, trackerId: t.id) })
                        }
                    }
                    if !trackers.isEmpty {
                        Button { creating = true } label: {
                            Label("Nowy typ zdarzenia", systemImage: "plus").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                    if !recent.isEmpty {
                        Card {
                            Text("Ostatnie wpisy").font(.headline)
                            ForEach(recent) { s in
                                SessionRow(s: s, tracker: byId[s.trackerId], pauses: pauses[s.id] ?? [],
                                           ecg: (af?.any == true && s.trackerId == afId) ? AfLink.verdict(af!, s, pauses[s.id] ?? []) : nil) {
                                    sessionEdit = SessionEditItem(session: s, trackerId: s.trackerId)
                                }
                                if s.id != recent.last?.id { Divider() }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Zdarzenia")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .sheet(item: $editingTracker) { t in TrackerForm(existing: t, defaultColor: t.color) }
            .sheet(isPresented: $creating) {
                TrackerForm(existing: nil, defaultColor: Palette.nextFree(trackers.map { $0.color }))
            }
            .sheet(item: $sessionEdit) { e in SessionForm(session: e.session, trackerId: e.trackerId) }
        }
    }
}

struct RunningCard: View {
    let t: Tracker
    let s: Session
    let pauses: [Pause]
    let onEdit: () -> Void

    var body: some View {
        let open = pauses.first { $0.end == nil }
        let paused = open != nil
        let accent: Color = paused ? .secondary : Palette.live
        let startedToday = Fmt.cal.isDateInToday(s.start.date)
        Card(tint: paused ? Color(.tertiarySystemGroupedBackground) : Palette.liveSoft, border: accent) {
            HStack {
                Swatch(color: Palette.color(t.color))
                Text(t.name).font(.headline)
                Spacer()
                Text(paused ? "PAUZA" : "TRWA").font(.caption2.bold()).foregroundStyle(accent)
            }
            TimelineView(.periodic(from: .now, by: 1)) { ctx in
                Text(Fmt.clock(Timing.activeMs(s, pauses, now: ctx.date.ms)))
                    .font(.system(size: 34, weight: .medium, design: .monospaced))
                    .foregroundStyle(accent)
            }
            Text(subtitle(open: open, startedToday: startedToday))
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack(spacing: 8) {
                Button("Edytuj", action: onEdit).buttonStyle(.bordered)
                Button {
                    if paused { Repo.resumeSession(s.id) } else { Repo.pauseSession(s.id) }
                } label: {
                    Text(paused ? "Wznów" : "Pauza").fontWeight(.semibold).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Button {
                    Repo.stopSession(s.id)
                } label: {
                    Text("Stop").fontWeight(.bold).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Palette.live)
            }
        }
    }

    private func subtitle(open: Pause?, startedToday: Bool) -> String {
        var text = "od \(Fmt.hm(s.start))" + (startedToday ? "" : ", \(Fmt.day(ms: s.start))")
        if let o = open {
            text += " · wstrzymane o \(Fmt.hm(o.start))"
        } else if !pauses.isEmpty {
            let pausedMs = nowMs() - s.start - Timing.activeMs(s, pauses, now: nowMs())
            text += " · pauzy: \(pauses.count) (\(Fmt.dur(pausedMs)))"
        }
        return text
    }
}

struct TrackerCard: View {
    let t: Tracker
    let onEdit: () -> Void
    let onAdd: () -> Void

    var body: some View {
        let last = Repo.lastFinished(t.id)
        let lastActive = last.map { Repo.activeMs($0) }
        Card {
            HStack(spacing: 8) {
                Swatch(color: Palette.color(t.color))
                VStack(alignment: .leading, spacing: 2) {
                    Text(t.name).font(.headline)
                    Text(last != nil && lastActive != nil
                         ? "ostatnio \(Fmt.day(ms: last!.start)), \(Fmt.dur(lastActive!))"
                         : "brak wpisów")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button(action: onEdit) {
                    Image(systemName: "ellipsis.circle").font(.title3).foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Ustawienia: \(t.name)")
            }
            HStack(spacing: 8) {
                Button("Dopisz", action: onAdd).buttonStyle(.bordered)
                Button {
                    Repo.startSession(t.id)
                } label: {
                    Text("Start").fontWeight(.bold).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }
}

struct SessionRow: View {
    let s: Session
    let tracker: Tracker?
    let pauses: [Pause]
    var ecg: String? = nil
    let onEdit: () -> Void

    var body: some View {
        let active = Timing.activeMs(s, pauses, now: nowMs())
        Button(action: onEdit) {
            HStack(spacing: 10) {
                Swatch(color: tracker.map { Palette.color($0.color) } ?? .gray)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tracker?.name ?? "(usunięty)").font(.subheadline.weight(.semibold))
                    Text("\(Fmt.day(ms: s.start)), \(Fmt.hm(s.start))" + (s.end.map { "–\(Fmt.hm($0))" } ?? "")
                         + (pauses.isEmpty ? "" : " · pauzy: \(pauses.count)"))
                        .font(.caption).foregroundStyle(.secondary)
                    if !s.note.isEmpty {
                        Text(s.note).font(.caption).foregroundStyle(.secondary)
                    }
                    if let ecg { ImportedTag(text: ecg) }
                }
                Spacer()
                Text(s.end != nil ? Fmt.dur(active) : "trwa \(Fmt.dur(active))")
                    .font(.system(.subheadline, design: .monospaced))
                    .foregroundStyle(s.end != nil ? Color.primary : Palette.live)
                Image(systemName: "pencil").font(.caption).foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/* ---------- formularze ---------- */

struct TrackerForm: View {
    let existing: Tracker?
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var color: Int
    @State private var notify: Bool
    @State private var error: String?
    @State private var confirmDelete = false

    init(existing: Tracker?, defaultColor: Int) {
        self.existing = existing
        _name = State(initialValue: existing?.name ?? "")
        _color = State(initialValue: existing?.color ?? defaultColor)
        _notify = State(initialValue: existing?.notify ?? true)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section { TextField("Nazwa, np. Praca, Sen", text: $name) }
                Section("Kolor") { ColorChooser(selected: $color) }
                Section {
                    Toggle("Pokaż w widżecie i na ekranie blokady", isOn: $notify)
                } footer: {
                    Text("Trwające zdarzenie zawsze ma stoper na ekranie blokady z przyciskami Pauza i Stop.")
                }
                if let error = error { Section { Text(error).foregroundStyle(.red) } }
                if existing != nil {
                    Section { Button("Usuń", role: .destructive) { confirmDelete = true } }
                }
            }
            .navigationTitle(existing == nil ? "Nowy typ zdarzenia" : "Ustawienia zdarzenia")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Zapisz") {
                        let n = name.trimmingCharacters(in: .whitespaces)
                        if n.isEmpty { error = "Podaj nazwę."; return }
                        Repo.saveTracker(Tracker(id: existing?.id ?? 0, name: n, color: color, notify: notify))
                        dismiss()
                    }
                }
            }
            .confirmationDialog("Usunąć razem z całą historią?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Usuń", role: .destructive) {
                    if let e = existing { Repo.deleteTracker(e.id) }
                    dismiss()
                }
            }
        }
    }
}

struct SessionForm: View {
    let session: Session?
    let trackerId: Int64
    @ObservedObject private var store = Store.shared
    @Environment(\.dismiss) private var dismiss
    @State private var start: Date
    @State private var hasEnd: Bool
    @State private var end: Date
    @State private var note: String
    @State private var error: String?
    @State private var confirmDelete = false

    init(session: Session?, trackerId: Int64) {
        self.session = session
        self.trackerId = trackerId
        let now = Date()
        _start = State(initialValue: session?.start.date ?? now.addingTimeInterval(-3600))
        _hasEnd = State(initialValue: session == nil ? true : session?.end != nil)
        _end = State(initialValue: session?.end?.date ?? now)
        _note = State(initialValue: session?.note ?? "")
    }

    var body: some View {
        let _ = store.version
        let tracker = Repo.tracker(session?.trackerId ?? trackerId)
        let pauses = session.map { Repo.pauses($0.id) } ?? []
        NavigationStack {
            Form {
                Section {
                    DatePicker("Początek", selection: $start, in: ...Date())
                    Toggle(session?.end == nil && session != nil ? "Zakończ (ustaw koniec)" : "Zakończone", isOn: $hasEnd)
                    if hasEnd {
                        DatePicker("Koniec", selection: $end, in: ...Date())
                    }
                }
                Section("Notatka") {
                    TextField("opcjonalnie", text: $note, axis: .vertical)
                }
                if !pauses.isEmpty {
                    Section {
                        ForEach(pauses) { p in
                            HStack {
                                Text("\(Fmt.hm(p.start)) – \(p.end.map { Fmt.hm($0) } ?? "trwa")")
                                Spacer()
                                Text(Fmt.dur((p.end ?? nowMs()) - p.start)).foregroundStyle(.secondary)
                            }
                        }
                        .onDelete { idx in for i in idx { Repo.deletePause(pauses[i].id) } }
                    } header: {
                        Text("Pauzy")
                    } footer: {
                        Text("Czas zdarzenia nie obejmuje pauz. Przesuń pauzę w lewo, aby ją usunąć.")
                    }
                }
                if let error = error { Section { Text(error).foregroundStyle(.red) } }
                if session != nil {
                    Section { Button("Usuń wpis", role: .destructive) { confirmDelete = true } }
                }
            }
            .navigationTitle((session == nil ? "Dopisz: " : "Edytuj: ") + (tracker?.name ?? "zdarzenie"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Zapisz") { save() } }
            }
            .confirmationDialog("Usunąć ten wpis?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Usuń", role: .destructive) {
                    if let s = session { Repo.deleteSession(s.id) }
                    dismiss()
                }
            }
        }
    }

    private func save() {
        if hasEnd && end <= start { error = "Koniec musi być po początku."; return }
        if start.timeIntervalSinceNow > 60 { error = "Początek nie może być w przyszłości."; return }
        Repo.saveSession(Session(
            id: session?.id ?? 0,
            trackerId: session?.trackerId ?? trackerId,
            start: start.ms,
            end: hasEnd ? end.ms : nil,
            note: note.trimmingCharacters(in: .whitespacesAndNewlines)
        ))
        dismiss()
    }
}

/* ---------- powrót po zamknięciu aplikacji ---------- */

struct ResumeView: View {
    @ObservedObject private var store = Store.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let _ = store.version
        let running = Repo.running()
        let trackers = Dictionary(uniqueKeysWithValues: Repo.trackers().map { ($0.id, $0) })
        NavigationStack {
            List {
                Section {
                    Text("Aplikacja była zamknięta, ale te zdarzenia nadal się liczą. Sprawdź, czy wszystko się zgadza.")
                        .font(.subheadline)
                }
                if running.isEmpty {
                    Section { Text("Nic już nie trwa.").foregroundStyle(.secondary) }
                }
                ForEach(running) { s in
                    ResumeRow(s: s, tracker: trackers[s.trackerId])
                }
            }
            .navigationTitle("Witaj z powrotem")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Kontynuuj") { dismiss() } } }
        }
    }
}

private struct ResumeRow: View {
    let s: Session
    let tracker: Tracker?
    @State private var endAt = Date()

    var body: some View {
        let pauses = Repo.pauses(s.id)
        let paused = pauses.contains { $0.end == nil }
        let active = Timing.activeMs(s, pauses, now: nowMs())
        Section {
            VStack(alignment: .leading, spacing: 2) {
                Text(tracker?.name ?? "Zdarzenie").font(.headline)
                Text((paused ? "Pauza · " : "Trwa · ") + "\(Fmt.dur(active)) (od \(Fmt.dateTime(s.start)))")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Button(paused ? "Wznów" : "Pauza") {
                if paused { Repo.resumeSession(s.id) } else { Repo.pauseSession(s.id) }
            }
            Button("Zatrzymaj teraz") { Repo.stopSession(s.id) }
            DatePicker("Zakończ o", selection: $endAt, in: s.start.date...Date())
            Button("Zakończ o wybranej godzinie") { Repo.stopSession(s.id, at: endAt.ms) }
        }
    }
}

/// Delikatne wyróżnienie danych z innych aplikacji (np. „EKG: AF 22:10–22:40” z Polar EKG).
struct ImportedTag: View {
    let text: String
    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: "link").font(.caption2)
            Text(text).font(.caption2)
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .overlay(Capsule().stroke(Color(.separator)))
    }
}
