import Foundation
import Security

/// Hasło i kod odzyskiwania w Pęku kluczy.
enum Keychain {
    private static let service = "pl.kreska.koofr"

    static func read(_ key: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: key, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func write(_ key: String, _ value: String) {
        delete(key)
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: key, kSecValueData as String: Data(value.utf8),
                                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func delete(_ key: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
        SecItemDelete(q as CFDictionary)
    }
}

struct KoofrConfig {
    var email: String
    var appPassword: String
    var root: String = "Rodzina"

    var json: [String: Any] { ["e": email, "p": appPassword, "r": root] }

    static func from(_ j: [String: Any]) -> KoofrConfig {
        let r = (j["r"] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        return KoofrConfig(email: j["e"] as? String ?? "", appPassword: j["p"] as? String ?? "", root: r.isEmpty ? "Rodzina" : r)
    }

    /// Kod konfiguracji wspólny ze wszystkimi rodzinnymi aplikacjami (zawiera hasło aplikacji!).
    var code: String {
        let d = (try? JSONSerialization.data(withJSONObject: json)) ?? Data()
        return "KSYNC1:" + d.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func fromCode(_ text: String) -> KoofrConfig? {
        let t = text.filter { !$0.isWhitespace }
        guard t.hasPrefix("KSYNC1:") else { return nil }
        var b = String(t.dropFirst(7)).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        guard let d = Data(base64Encoded: b), let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return nil }
        let c = from(j)
        return c.email.isEmpty || c.appPassword.isEmpty ? nil : c
    }
}

struct Member: Identifiable {
    let id: String
    let name: String
    let codeCheck: String?
    let keys: [String: String]
}

struct BackupInfo: Identifiable {
    var id: String { file }
    let file: String
    let at: Date
    let device: String
    let size: Int

    static func parse(_ e: DavEntry) -> BackupInfo? {
        let re = try! NSRegularExpression(pattern: "^(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})?_([\\w-]+)\\.ksy$")
        let n = e.name
        guard !e.isDir, let m = re.firstMatch(in: n, range: NSRange(n.startIndex..., in: n)) else { return nil }
        func g(_ i: Int) -> Int? { Range(m.range(at: i), in: n).flatMap { Int(n[$0]) } }
        var c = DateComponents()
        c.year = g(1); c.month = g(2); c.day = g(3); c.hour = g(4); c.minute = g(5); c.second = g(6) ?? 0
        guard let d = Calendar.current.date(from: c), let dr = Range(m.range(at: 7), in: n) else { return nil }
        return BackupInfo(file: n, at: d, device: String(n[dr]), size: e.size)
    }

    static func name(_ t: Date, _ device: String) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: t)
        return String(format: "%04d%02d%02d-%02d%02d%02d_%@.ksy", c.year!, c.month!, c.day!, c.hour!, c.minute!, c.second!, device)
    }
}

/// Kopia i wymiana na rodzinnym koncie Koofr (bez podglądu — w Kresce niepotrzebny).
final class KoofrSync {
    let appId: String
    private(set) var config: KoofrConfig?
    private(set) var userId: String?
    private(set) var recoveryCode: String?
    private(set) var crypto: KsCrypto?
    private(set) var deviceId = ""
    private var dav_: WebDav?

    init(appId: String) { self.appId = appId }

    var connected: Bool { config != nil }
    var ready: Bool { config != nil && userId != nil && crypto != nil }

    private func dav() throws -> WebDav {
        guard let c = config else { throw KsError("Najpierw połącz z kontem Koofr.") }
        if let d = dav_ { return d }
        let d = WebDav(user: c.email, password: c.appPassword)
        dav_ = d
        return d
    }

    private var root: String { config?.root ?? "Rodzina" }
    private var app: String { "\(root)/\(appId)" }
    private func memberPath(_ id: String) -> String { "\(root)/_czlonkowie/\(id).json" }

    func store(_ k: String) -> String? { Keychain.read("ks.\(k)") }
    func store(_ k: String, _ v: String?) { if let v { Keychain.write("ks.\(k)", v) } else { Keychain.delete("ks.\(k)") } }

    func load() {
        if let s = store("config"), let d = s.data(using: .utf8), let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
            config = KoofrConfig.from(j)
        } else {
            config = nil
        }
        userId = store("user")
        if let d = store("device") { deviceId = d } else {
            let chars = Array("abcdefghijklmnopqrstuvwxyz0123456789")
            deviceId = String((0..<6).map { _ in chars.randomElement()! })
            store("device", deviceId)
        }
        recoveryCode = store("code")
        crypto = recoveryCode.flatMap { RecoveryCode.parse($0) }.flatMap { try? KsCrypto.fromCode($0, appId: appId) }
        dav_ = nil
    }

    func connect(_ c: KoofrConfig) async throws {
        let cfg = KoofrConfig(email: c.email.trimmingCharacters(in: .whitespaces),
                              appPassword: c.appPassword.trimmingCharacters(in: .whitespaces), root: c.root)
        let t = WebDav(user: cfg.email, password: cfg.appPassword)
        try await t.check()
        try await t.ensureDir("\(cfg.root)/_czlonkowie")
        config = cfg
        dav_ = nil
        let d = try JSONSerialization.data(withJSONObject: cfg.json)
        store("config", String(decoding: d, as: UTF8.self))
    }

    func disconnect() {
        for k in ["config", "user", "code", "backup.enabled", "backup.last"] { store(k, nil) }
        config = nil
        userId = nil
        recoveryCode = nil
        crypto = nil
        dav_ = nil
    }

    // ---------------------------------------------------------------- członkowie

    func members() async throws -> [Member] {
        var out: [Member] = []
        for e in try await dav().list("\(root)/_czlonkowie") where !e.isDir && e.name.hasSuffix(".json") {
            if let m = try await member(String(e.name.dropLast(5))) { out.append(m) }
        }
        return out.sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    func member(_ id: String) async throws -> Member? {
        guard let d = try await dav().get(memberPath(id)),
              let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any], let mid = j["id"] as? String else { return nil }
        return Member(id: mid, name: j["name"] as? String ?? mid, codeCheck: j["codeCheck"] as? String,
                      keys: j["keys"] as? [String: String] ?? [:])
    }

    func join(existingId: String?, newName: String?, code: RecoveryCode) async throws {
        let check = KsCrypto.checkOf(code)
        let keys = try KsCrypto.fromCode(code, appId: appId)
        var m: Member?
        var id: String
        if let existingId {
            id = existingId
            m = try await member(id)
            if let m, let cc = m.codeCheck, cc != check {
                throw KsError("Ten kod odzyskiwania nie pasuje do osoby „\(m.name)”. Wpisz kod zapisany przy pierwszym podłączeniu tej osoby.")
            }
        } else {
            let base = KsCrypto.slugify(newName ?? "osoba")
            let taken = Set(try await members().map { $0.id })
            id = base
            var i = 2
            while taken.contains(id) { id = "\(base)-\(i)"; i += 1 }
        }
        var k = m?.keys ?? [:]
        k[appId] = keys.publicKeyB64
        let name = m?.name ?? (newName ?? id).trimmingCharacters(in: .whitespaces)
        let j: [String: Any] = ["v": 1, "id": id, "name": name, "codeCheck": check, "keys": k, "updatedAt": Int64(Date().timeIntervalSince1970 * 1000)]
        try await dav().put(memberPath(id), try JSONSerialization.data(withJSONObject: j))
        let text = code.format()
        store("user", id)
        store("code", text)
        userId = id
        recoveryCode = text
        crypto = keys
    }

    // ---------------------------------------------------------------- kopie

    var backupEnabled: Bool {
        get { store("backup.enabled") == "1" }
        set { store("backup.enabled", newValue ? "1" : "0") }
    }

    var lastBackup: Date? { store("backup.last").flatMap { Double($0) }.map { Date(timeIntervalSince1970: $0 / 1000) } }

    var backupDue: Bool {
        guard ready, backupEnabled else { return false }
        guard let l = lastBackup else { return true }
        return Date().timeIntervalSince(l) >= 20 * 3600
    }

    private var myBackups: String { "\(app)/kopie/\(userId ?? "")" }

    func backupNow(_ data: Data) async throws {
        guard ready, let crypto else { throw KsError("Najpierw połącz z Koofr i wybierz osobę.") }
        let header = try JSONSerialization.data(withJSONObject: ["v": 2, "blobs": [String: String]()])
        var len = UInt32(header.count).bigEndian
        var payload = Data([0x4B, 0x53, 0x42, 0x31])
        payload.append(Data(bytes: &len, count: 4))
        payload.append(header)
        payload.append(data)
        let now = Date()
        try await dav().putAtomic("\(myBackups)/\(BackupInfo.name(now, deviceId))", try crypto.sealBackup(payload))
        store("backup.last", String(Int64(now.timeIntervalSince1970 * 1000)))
        try? await prune()
    }

    func backups() async throws -> [BackupInfo] {
        try await dav().list(myBackups).compactMap(BackupInfo.parse).sorted { $0.at > $1.at }
    }

    /// Dane kopii (bez nagłówka z załącznikami).
    func restore(_ b: BackupInfo) async throws -> Data {
        guard let crypto else { throw KsError("Najpierw wybierz osobę.") }
        guard let f = try await dav().get("\(myBackups)/\(b.file)") else { throw KsError("Kopia zniknęła z Koofr.") }
        let p = [UInt8](try crypto.openBackup(f))
        guard p.count >= 8, Array(p[0..<4]) == [0x4B, 0x53, 0x42, 0x31] else { return Data(p) }
        let len = Int(UInt32(p[4]) << 24 | UInt32(p[5]) << 16 | UInt32(p[6]) << 8 | UInt32(p[7]))
        return Data(p[(8 + len)...])
    }

    /// Zostaje najnowsza kopia z każdego z 7 ostatnich dni i z każdego z 4 wcześniejszych tygodni.
    func prune() async throws {
        let all = try await backups()
        let cal = Calendar(identifier: .iso8601)
        func day(_ d: Date) -> String { let c = cal.dateComponents([.year, .month, .day], from: d); return "\(c.year!)-\(c.month!)-\(c.day!)" }
        func week(_ d: Date) -> String { let c = cal.dateComponents([.yearForWeekOfYear, .weekOfYear], from: d); return "\(c.yearForWeekOfYear!)-\(c.weekOfYear!)" }
        var days = Set<String>(), weeks = Set<String>(), keep = Set<String>()
        for b in all where days.count < 7 && !days.contains(day(b.at)) {
            days.insert(day(b.at)); weeks.insert(week(b.at)); keep.insert(b.file)
        }
        var weekly = 0
        for b in all where !keep.contains(b.file) && !weeks.contains(week(b.at)) && weekly < 4 {
            weeks.insert(week(b.at)); weekly += 1; keep.insert(b.file)
        }
        for b in all where !keep.contains(b.file) {
            try await dav().delete("\(myBackups)/\(b.file.replacingOccurrences(of: ".ksy", with: ".refs"))")
            try await dav().delete("\(myBackups)/\(b.file)")
        }
    }

    // ---------------------------------------------------------------- wymiana między aplikacjami

    private var myExchange: String { "\(root)/_wymiana/\(userId ?? "")" }

    @discardableResult
    func publishExchange(_ name: String, _ data: Data, force: Bool = false) async throws -> Bool {
        guard ready, let crypto, let u = userId else { return false }
        let digest = KsCrypto.sha256Hex16(data)
        let key = "ex.pub.\(u).\(name)"
        if !force && store(key) == digest { return false }
        try await dav().putAtomic("\(myExchange)/\(name).ksy", try crypto.sealExchange(data))
        store(key, digest)
        return true
    }

    func removeExchange(_ name: String) async throws {
        guard ready, let u = userId else { return }
        try await dav().delete("\(myExchange)/\(name).ksy")
        store("ex.pub.\(u).\(name)", nil)
    }

    /// Pobiera zmienione pliki wymiany przepuszczone przez filtr; zwraca nazwy przetworzonych.
    func pullExchange(want: (String) -> Bool, force: Bool = false, handle: (String, Data) throws -> Void) async throws -> [String] {
        guard ready, let crypto, let u = userId else { return [] }
        var done: [String] = []
        for e in try await dav().list(myExchange) where !e.isDir && e.name.hasSuffix(".ksy") {
            let n = String(e.name.dropLast(4))
            guard want(n) else { continue }
            let stamp = "\(Int64((e.modified?.timeIntervalSince1970 ?? 0) * 1000))/\(e.size)"
            let key = "ex.seen.\(u).\(n)"
            if !force && store(key) == stamp { continue }
            guard let f = try await dav().get("\(myExchange)/\(e.name)") else { continue }
            try handle(n, try crypto.openExchange(f))
            store(key, stamp)
            done.append(n)
        }
        return done
    }
}
