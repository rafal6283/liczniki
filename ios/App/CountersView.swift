import SwiftUI
import UIKit

struct CountersView: View {
    @ObservedObject private var store = Store.shared
    @State private var editing: Counter?
    @State private var creating = false
    @State private var entriesFor: Counter?

    var body: some View {
        let _ = store.version
        let counters = Repo.counters()
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Dziś").font(.title2.bold())
                        Spacer()
                        Text(Fmt.longDay(Date())).font(.subheadline).foregroundStyle(.secondary)
                    }
                    if counters.isEmpty {
                        EmptyCard(
                            eyebrow: "Brak liczników",
                            title: "Co chcesz liczyć?",
                            text: "Każde stuknięcie w duży przycisk dodaje kreskę do dzisiejszego wyniku. Licznik możesz też dodać jako widżet z przyciskami + i −."
                        ) {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack {
                                    ForEach(["Szklanki wody", "Pompki", "Kawa", "Przerwy"], id: \.self) { n in
                                        Button("+ \(n)") {
                                            Repo.saveCounter(Counter(id: 0, name: n, step: 1, goal: nil,
                                                                     color: Palette.nextFree(counters.map { $0.color }), notify: true))
                                        }
                                        .buttonStyle(.bordered)
                                    }
                                }
                            }
                            Button("Nowy licznik") { creating = true }.buttonStyle(.borderedProminent)
                        }
                    }
                    ForEach(counters) { c in
                        CounterCard(c: c, onEdit: { editing = c }, onEntries: { entriesFor = c })
                    }
                    if !counters.isEmpty {
                        Button { creating = true } label: {
                            Label("Nowy licznik", systemImage: "plus").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Liczniki")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .sheet(item: $editing) { c in CounterForm(existing: c, defaultColor: c.color) }
            .sheet(isPresented: $creating) {
                CounterForm(existing: nil, defaultColor: Palette.nextFree(counters.map { $0.color }))
            }
            .sheet(item: $entriesFor) { c in EntriesView(counter: c, initialDay: Date()) }
        }
    }
}

struct CounterCard: View {
    let c: Counter
    let onEdit: () -> Void
    let onEntries: () -> Void
    @ObservedObject private var store = Store.shared

    var body: some View {
        let _ = store.version
        let total = Repo.todayTotal(c.id)
        let last = Repo.lastTap(c.id)
        let col = Palette.color(c.color)
        let meta = (last.map { "ostatnio \(Fmt.hm($0))" } ?? "jeszcze nic dziś") + (c.step != 1 ? " · krok \(Fmt.num(c.step))" : "")
        Card {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Swatch(color: col)
                        Text(c.name).font(.headline).lineLimit(2)
                        Spacer(minLength: 0)
                        Button(action: onEdit) {
                            Image(systemName: "ellipsis.circle").font(.title3).foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Ustawienia licznika \(c.name)")
                    }
                    HStack(alignment: .firstTextBaseline, spacing: 0) {
                        Text(Fmt.num(total)).font(.system(size: 44, weight: .medium, design: .monospaced))
                        if let g = c.goal {
                            Text(" / \(Fmt.num(g))").font(.system(.title3, design: .monospaced)).foregroundStyle(.secondary)
                        }
                    }
                    Text(meta).font(.caption).foregroundStyle(.secondary)
                    Button("Wpisy · dodaj / edytuj", action: onEntries)
                        .font(.caption.weight(.semibold))
                        .buttonStyle(.borderless)
                }
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    Repo.decrement(c.id)
                } label: {
                    Image(systemName: "minus").font(.title2.weight(.semibold)).frame(width: 36, height: 36)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.circle)
                .disabled(total <= 0)
                .accessibilityLabel("Cofnij ostatni wpis")
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    Repo.increment(c.id)
                } label: {
                    Text("+\(Fmt.num(c.step))")
                        .font(.system(.title2, design: .monospaced).weight(.semibold))
                        .minimumScaleFactor(0.5)
                        .frame(width: 64, height: 64)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.circle)
                .tint(col)
                .accessibilityLabel("Dodaj \(c.step) do \(c.name)")
            }
            if let g = c.goal {
                ProgressView(value: min(1, Double(total) / Double(max(1, g)))).tint(col)
            }
            if c.step == 1 && total > 0 && total <= 40 {
                TallyView(n: total, color: col)
            }
        }
    }
}

/* ---------- formularz licznika ---------- */

struct CounterForm: View {
    let existing: Counter?
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var step: String
    @State private var goal: String
    @State private var color: Int
    @State private var notify: Bool
    @State private var error: String?
    @State private var confirmDelete = false

    init(existing: Counter?, defaultColor: Int) {
        self.existing = existing
        _name = State(initialValue: existing?.name ?? "")
        _step = State(initialValue: String(existing?.step ?? 1))
        _goal = State(initialValue: existing?.goal.map { String($0) } ?? "")
        _color = State(initialValue: existing?.color ?? defaultColor)
        _notify = State(initialValue: existing?.notify ?? true)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Nazwa, np. Szklanki wody", text: $name)
                    HStack {
                        Text("Krok (o ile rośnie)")
                        Spacer()
                        TextField("1", text: $step).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 90)
                    }
                    HStack {
                        Text("Cel dzienny")
                        Spacer()
                        TextField("brak", text: $goal).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 90)
                    }
                }
                Section("Kolor") { ColorChooser(selected: $color) }
                Section {
                    Toggle("Pokaż w widżecie „Liczniki”", isOn: $notify)
                } footer: {
                    Text("Widżet dodasz, przytrzymując palec na ekranie głównym → „Edytuj” → „Dodaj widżet” → Kreska.")
                }
                if let error = error {
                    Section { Text(error).foregroundStyle(.red) }
                }
                if existing != nil {
                    Section {
                        Button("Usuń licznik", role: .destructive) { confirmDelete = true }
                    }
                }
            }
            .navigationTitle(existing == nil ? "Nowy licznik" : "Ustawienia licznika")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Zapisz") { save() } }
            }
            .confirmationDialog("Usunąć licznik razem z całą historią?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Usuń", role: .destructive) {
                    if let e = existing { Repo.deleteCounter(e.id) }
                    dismiss()
                }
            }
        }
    }

    private func save() {
        let n = name.trimmingCharacters(in: .whitespaces)
        let s = Int(step) ?? 0
        let g = goal.isEmpty ? nil : Int(goal)
        if n.isEmpty { error = "Podaj nazwę licznika."; return }
        if s < 1 { error = "Krok musi wynosić co najmniej 1."; return }
        if !goal.isEmpty && (g ?? 0) < 1 { error = "Cel musi być większy od zera."; return }
        Repo.saveCounter(Counter(id: existing?.id ?? 0, name: n, step: s, goal: g, color: color, notify: notify))
        dismiss()
    }
}

/* ---------- wpisy licznika ---------- */

struct TapEditItem: Identifiable {
    let id = UUID()
    let tap: Tap?
}

struct EntriesView: View {
    let counter: Counter
    @ObservedObject private var store = Store.shared
    @Environment(\.dismiss) private var dismiss
    @State private var day: Date
    @State private var editing: TapEditItem?
    @State private var setDay = false

    init(counter: Counter, initialDay: Date) {
        self.counter = counter
        _day = State(initialValue: Fmt.cal.startOfDay(for: initialDay))
    }

    var body: some View {
        let _ = store.version
        let taps = Repo.tapsForDay(counter.id, day: Fmt.key(day))
        let total = taps.reduce(0) { $0 + $1.amount }
        let isToday = Fmt.cal.isDateInToday(day)
        let dayBinding = Binding<Date>(get: { day }, set: { day = Fmt.cal.startOfDay(for: $0) })
        NavigationStack {
            List {
                Section {
                    HStack {
                        Button { day = Fmt.addDays(day, -1) } label: { Image(systemName: "chevron.left") }
                            .buttonStyle(.borderless)
                        Spacer()
                        DatePicker("Dzień", selection: dayBinding, in: ...Date(), displayedComponents: .date)
                            .labelsHidden()
                        Spacer()
                        Button { day = Fmt.addDays(day, 1) } label: { Image(systemName: "chevron.right") }
                            .buttonStyle(.borderless)
                            .disabled(isToday)
                    }
                    HStack {
                        Text(isToday ? "Suma dziś" : "Suma dnia")
                        Spacer()
                        Text(Fmt.num(total) + (counter.goal.map { " / \(Fmt.num($0))" } ?? ""))
                            .font(.system(.body, design: .monospaced).weight(.semibold))
                    }
                }
                Section {
                    if taps.isEmpty {
                        Text("Brak wpisów tego dnia.").foregroundStyle(.secondary)
                    }
                    ForEach(taps) { t in
                        Button { editing = TapEditItem(tap: t) } label: { TapRow(tap: t, color: Palette.color(counter.color)) }
                            .buttonStyle(.plain)
                    }
                    .onDelete { idx in
                        for i in idx { Repo.deleteTap(taps[i].id) }
                    }
                } header: {
                    Text("Wpisy")
                } footer: {
                    Text("Dotknij wpisu, aby zmienić godzinę lub ilość. Przesuń w lewo, aby usunąć.")
                }
                Section {
                    Button { editing = TapEditItem(tap: nil) } label: { Label("Dodaj wpis", systemImage: "plus.circle.fill") }
                    Button { setDay = true } label: { Label("Ustaw wartość dnia", systemImage: "equal.circle") }
                }
            }
            .navigationTitle(counter.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Gotowe") { dismiss() } } }
            .sheet(item: $editing) { e in TapForm(counter: counter, tap: e.tap, day: day) }
            .sheet(isPresented: $setDay) { DayValueForm(counter: counter, initialDay: day) }
        }
    }
}

struct TapRow: View {
    let tap: Tap
    let color: Color

    var body: some View {
        HStack {
            Text(Fmt.hm(tap.ts)).font(.system(.body, design: .monospaced)).frame(width: 58, alignment: .leading)
            Text(tap.manual ? "korekta dnia" : (tap.amount < 0 ? "cofnięcie" : ""))
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Text((tap.amount > 0 ? "+" : "−") + Fmt.num(abs(tap.amount)))
                .font(.system(.body, design: .monospaced).weight(.medium))
                .foregroundStyle(tap.amount > 0 ? color : Color.red)
            Image(systemName: "pencil").font(.caption).foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
    }
}

struct TapForm: View {
    let counter: Counter
    let tap: Tap?
    @Environment(\.dismiss) private var dismiss
    @State private var when: Date
    @State private var amount: Int
    @State private var error: String?
    @State private var confirmDelete = false

    init(counter: Counter, tap: Tap?, day: Date) {
        self.counter = counter
        self.tap = tap
        let initial: Date
        if let t = tap {
            initial = t.ts.date
        } else if Fmt.cal.isDateInToday(day) {
            initial = Date()
        } else {
            initial = Fmt.cal.startOfDay(for: day).addingTimeInterval(12 * 3600)
        }
        _when = State(initialValue: initial)
        _amount = State(initialValue: tap?.amount ?? counter.step)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Kiedy", selection: $when, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                    Stepper(value: $amount, in: -99_999...99_999) {
                        HStack {
                            Text("Ilość")
                            Spacer()
                            TextField("", value: $amount, format: .number)
                                .keyboardType(.numbersAndPunctuation)
                                .multilineTextAlignment(.trailing)
                                .frame(width: 80)
                        }
                    }
                }
                if tap?.manual == true {
                    Section {
                        Text("To korekta z „Ustaw wartość dnia”. Po zapisaniu stanie się zwykłym wpisem o wybranej godzinie.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                if let error = error {
                    Section { Text(error).foregroundStyle(.red) }
                }
                if tap != nil {
                    Section { Button("Usuń wpis", role: .destructive) { confirmDelete = true } }
                }
            }
            .navigationTitle(tap == nil ? "Nowy wpis" : "Edytuj wpis")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Zapisz") { save() } }
            }
            .confirmationDialog("Usunąć ten wpis?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Usuń", role: .destructive) {
                    if let t = tap { Repo.deleteTap(t.id) }
                    dismiss()
                }
            }
        }
    }

    private func save() {
        if amount == 0 { error = "Podaj ilość różną od zera."; return }
        if when.timeIntervalSinceNow > 60 { error = "Wpis nie może być w przyszłości."; return }
        if let t = tap {
            Repo.updateTap(t.id, ts: when.ms, amount: amount)
        } else {
            Repo.addTap(counter.id, ts: when.ms, amount: amount)
        }
        dismiss()
    }
}

struct DayValueForm: View {
    let counter: Counter
    @Environment(\.dismiss) private var dismiss
    @State private var day: Date
    @State private var value: Int

    init(counter: Counter, initialDay: Date) {
        self.counter = counter
        let d = Fmt.cal.startOfDay(for: initialDay)
        _day = State(initialValue: d)
        _value = State(initialValue: Repo.total(counter.id, day: Fmt.key(d)))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Dzień", selection: $day, in: ...Date(), displayedComponents: .date)
                    Stepper(value: $value, in: 0...999_999) {
                        HStack {
                            Text("Wartość")
                            Spacer()
                            TextField("", value: $value, format: .number)
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.trailing)
                                .frame(width: 90)
                        }
                    }
                } footer: {
                    Text("Użyj, gdy zapomnisz coś policzyć albo wynik się pomylił. Różnica zapisze się jako korekta dnia.")
                }
            }
            .navigationTitle("Wartość dnia")
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: day) { _, d in value = Repo.total(counter.id, day: Fmt.key(d)) }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Zapisz") {
                        Repo.setDayValue(counter.id, day: day, value: max(0, value))
                        dismiss()
                    }
                }
            }
        }
    }
}
