import SwiftUI
import UIKit

/// Kopia na rodzinnym Koofr i wymiana danych z moimi aplikacjami (Rytm Snu, Polar EKG, Doba, Dziennik talerza).
struct KoofrView: View {
    @ObservedObject private var cloud = KreskaCloud.shared
    @State private var members: [Member] = []
    @State private var error: String?
    @State private var working = false
    @State private var email = ""
    @State private var password = ""
    @State private var folder = "Rodzina"
    @State private var codeFor: Member?
    @State private var codeText = ""
    @State private var askName = false
    @State private var newName = ""
    @State private var newCode: (String, RecoveryCode)?
    @State private var savedCode = false
    @State private var backups: [BackupInfo] = []
    @State private var showBackups = false
    @State private var restoreItem: BackupInfo?
    @State private var info: String?

    private var sync: KoofrSync { cloud.sync }

    var body: some View {
        let _ = cloud.version
        Form {
            if let error { Section { Text(error).foregroundStyle(.red) } }
            if let info { Section { Text(info).foregroundStyle(.secondary) } }
            if !sync.connected { connectSection }
            else if !sync.ready { joinSection }
            else { readySections }
        }
        .navigationTitle("Kopia i wymiana")
        .navigationBarTitleDisplayMode(.inline)
        .overlay { if working || cloud.busy { ProgressView(cloud.progress ?? "").padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
        .task { cloud.load(); await refresh() }
        .alert("Kod odzyskiwania", isPresented: Binding(get: { codeFor != nil }, set: { if !$0 { codeFor = nil } })) {
            TextField("XXXX-XXXX-…", text: $codeText).textInputAutocapitalization(.characters).autocorrectionDisabled()
            Button("Anuluj", role: .cancel) {}
            Button("Dalej") { joinExisting() }
        } message: { Text("Wpisz kod zapisany przy pierwszym podłączeniu osoby „\(codeFor?.name ?? "")”.") }
        .alert("Nowa osoba", isPresented: $askName) {
            TextField("Imię, np. Rafał", text: $newName)
            Button("Anuluj", role: .cancel) {}
            Button("Dalej") { startNew() }
        }
        .sheet(isPresented: Binding(get: { newCode != nil }, set: { if !$0 { newCode = nil } })) { newCodeSheet }
        .confirmationDialog("Wybierz kopię", isPresented: $showBackups, titleVisibility: .visible) {
            ForEach(backups) { b in
                Button("\(Fmt.dateTime(b.at.ms))" + (b.device == sync.deviceId ? "" : " · inny telefon")) { restoreItem = b }
            }
        }
        .alert("Przywrócić tę kopię?", isPresented: Binding(get: { restoreItem != nil }, set: { if !$0 { restoreItem = nil } })) {
            Button("Anuluj", role: .cancel) {}
            Button("Przywróć", role: .destructive) { if let b = restoreItem { act("Przywrócono kopię") { try await cloud.restore(b) } } }
        } message: { Text("Liczniki, zdarzenia i wpisy z kopii zastąpią obecne dane na tym telefonie.") }
    }

    // MARK: - połączenie

    private var connectSection: some View {
        Section {
            Button("Wklej kod konfiguracji z innej aplikacji") {
                if let c = KoofrConfig.fromCode(UIPasteboard.general.string ?? "") {
                    email = c.email; password = c.appPassword; folder = c.root
                    connect()
                } else {
                    error = "W schowku nie ma kodu konfiguracji (KSYNC1:…). Skopiuj go w innej aplikacji: Koofr → Kod konfiguracji."
                }
            }
            TextField("E-mail konta Koofr", text: $email).textInputAutocapitalization(.never).keyboardType(.emailAddress).autocorrectionDisabled()
            SecureField("Hasło aplikacji", text: $password)
            TextField("Folder na Koofr", text: $folder).autocorrectionDisabled()
            Button("Połącz") { connect() }.disabled(email.isEmpty || password.isEmpty || working)
        } header: {
            Text("Rodzinne konto Koofr")
        } footer: {
            Text("Szyfrowana kopia Kreski i wymiana danych z innymi rodzinnymi aplikacjami. Hasło aplikacji utworzysz na app.koofr.net w ustawieniach konta. Domyślnie nic nie jest wysyłane.")
        }
    }

    private func connect() {
        act("Połączono z Koofr") {
            try await sync.connect(KoofrConfig(email: email, appPassword: password, root: folder.isEmpty ? "Rodzina" : folder))
        }
    }

    // MARK: - osoba

    private var joinSection: some View {
        Section {
            ForEach(members) { m in
                Button { codeText = ""; codeFor = m } label: {
                    VStack(alignment: .leading) {
                        Text(m.name)
                        Text(m.keys.isEmpty ? "" : "Podłączona w innej aplikacji — wpisz ten sam kod").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            Button("Nowa osoba") { newName = ""; askName = true }
            Button("Odłącz konto Koofr", role: .destructive) { sync.disconnect(); cloud.changed() }
        } header: {
            Text("Kim jesteś?")
        } footer: {
            Text("Każda osoba ma własny kod odzyskiwania — ten sam we wszystkich rodzinnych aplikacjach.")
        }
    }

    private func joinExisting() {
        guard let m = codeFor else { return }
        guard let code = RecoveryCode.parse(codeText) else { error = "Niepełny kod albo literówka."; return }
        act("Witaj, \(m.name)!") { try await sync.join(existingId: m.id, newName: nil, code: code) }
    }

    private func startNew() {
        let n = newName.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return }
        if members.contains(where: { $0.name.lowercased() == n.lowercased() }) {
            error = "„\(n)” już jest na liście — wybierz tę osobę i wpisz jej kod."
            return
        }
        savedCode = false
        newCode = (n, RecoveryCode.generate())
    }

    private var newCodeSheet: some View {
        NavigationStack {
            Form {
                Section {
                    Text(newCode?.1.format() ?? "").font(.system(.body, design: .monospaced)).textSelection(.enabled)
                    Button("Kopiuj") { UIPasteboard.general.string = newCode?.1.format() }
                } footer: {
                    Text("Zapisz go (np. w menedżerze haseł). Bez niego nie odtworzysz kopii i nie podłączysz innych aplikacji jako ta sama osoba.")
                }
                Toggle("Zapisałem kod", isOn: $savedCode)
            }
            .navigationTitle("Kod: \(newCode?.0 ?? "")")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { newCode = nil } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Dalej") {
                        guard let nc = newCode else { return }
                        let n = nc.0, c = nc.1
                        newCode = nil
                        act("Witaj, \(n)!") { try await sync.join(existingId: nil, newName: n, code: c) }
                    }.disabled(!savedCode)
                }
            }
        }
    }

    // MARK: - połączone

    @ViewBuilder private var readySections: some View {
        let me = members.first { $0.id == sync.userId }
        Section("Konto") {
            LabeledContent(me?.name ?? sync.userId ?? "", value: sync.config?.email ?? "")
            Button("Kopiuj kod konfiguracji") {
                UIPasteboard.general.string = sync.config?.code
                info = "Skopiowano kod konfiguracji (zawiera hasło aplikacji — tylko dla domowników)."
            }
            Button("Kopiuj mój kod odzyskiwania") {
                UIPasteboard.general.string = sync.recoveryCode
                info = "Skopiowano kod odzyskiwania."
            }
            Button("Odłącz ten telefon", role: .destructive) { sync.disconnect(); cloud.changed() }
        }
        Section {
            Toggle("Automatyczna kopia", isOn: Binding(get: { sync.backupEnabled }, set: { sync.backupEnabled = $0; cloud.changed() }))
            Button("Zrób kopię teraz") { act("Kopia zapisana na Koofr") { try await cloud.backupNow() } }
            Button("Przywróć z kopii") {
                act(nil) {
                    backups = try await sync.backups()
                    if backups.isEmpty { info = "Na Koofr nie ma jeszcze kopii z iPhone'a." } else { showBackups = true }
                }
            }
        } header: {
            Text("Kopia zapasowa")
        } footer: {
            Text((sync.lastBackup.map { "Ostatnia: \(Fmt.dateTime($0.ms)). " } ?? "")
                 + "Raz na dobę przy otwarciu aplikacji. Szyfrowana na telefonie. Kopie z iPhone'a są osobno od kopii z Androida.")
        }
        exchangeSection
    }

    @ViewBuilder private var exchangeSection: some View {
        Section {
            Toggle("Automatyczna wymiana danych", isOn: Binding(get: { cloud.exchangeOn }, set: { v in
                cloud.exchangeOn = v
                if v { act(nil) { _ = try await cloud.exchangeNow(force: true) } }
            }))
            if cloud.exchangeOn {
                Toggle("Udostępniaj moim aplikacjom", isOn: Binding(get: { cloud.publishOn }, set: { cloud.publishOn = $0 }))
                ForEach(KreskaCloud.sources.map { $0.0 }, id: \.self) { p in
                    let label = KreskaCloud.sources.first { $0.0 == p }?.1 ?? p
                    Toggle("Pobieraj: \(label)", isOn: Binding(get: { cloud.sourceOn(p) }, set: { cloud.setSource(p, $0) }))
                }
                let cs = Repo.counters(), ts = Repo.trackers()
                if !cs.isEmpty {
                    let on = cloud.sharedCounters(cs)
                    ForEach(cs) { c in
                        Toggle("Licznik: \(c.name)", isOn: Binding(get: { on.contains(c.id) }, set: { v in
                            cloud.setSharedCounters(v ? on.union([c.id]) : on.subtracting([c.id]))
                        }))
                    }
                }
                if !ts.isEmpty {
                    let on = cloud.sharedTrackers(ts)
                    ForEach(ts) { t in
                        Toggle("Zdarzenie: \(t.name)", isOn: Binding(get: { on.contains(t.id) }, set: { v in
                            cloud.setSharedTrackers(v ? on.union([t.id]) : on.subtracting([t.id]))
                        }))
                    }
                }
                Button("Wymień teraz") { act(nil) { info = try await cloud.exchangeNow(force: true) } }
            }
        } header: {
            Text("Wymiana z moimi aplikacjami")
        } footer: {
            Text((cloud.lastExchange.map { "Ostatnio: \($0). " } ?? "")
                 + "Widzą je tylko Twoje aplikacje (ten sam kod odzyskiwania). Przy otwarciu aplikacji, nie częściej niż co 15 min.")
        }
    }

    // MARK: - pomocnicze

    private func refresh() async {
        do {
            if sync.connected { members = try await sync.members() }
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func act(_ ok: String?, _ f: @escaping () async throws -> Void) {
        working = true
        error = nil
        Task {
            do {
                try await f()
                if let ok { info = ok }
                await refresh()
            } catch {
                self.error = error.localizedDescription
            }
            working = false
            cloud.changed()
        }
    }
}
