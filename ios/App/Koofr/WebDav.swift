import Foundation

struct DavEntry {
    let name: String
    let isDir: Bool
    let size: Int
    let modified: Date?
}

/// Minimalny klient WebDAV (Koofr: https://app.koofr.net/dav/Koofr/).
final class WebDav {
    static let koofr = "https://app.koofr.net/dav/Koofr/"
    let base: String
    private let auth: String
    private var dirs = Set<String>()
    private let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 60
        c.timeoutIntervalForResource = 300
        return URLSession(configuration: c)
    }()

    init(user: String, password: String, base: String = WebDav.koofr) {
        self.base = base.hasSuffix("/") ? base : base + "/"
        auth = "Basic " + Data("\(user):\(password)".utf8).base64EncodedString()
    }

    private static let allowed: CharacterSet = {
        var s = CharacterSet.alphanumerics
        s.insert(charactersIn: "-._~")
        return s
    }()

    func url(_ path: String) -> String {
        let segs = path.split(separator: "/").map { String($0).addingPercentEncoding(withAllowedCharacters: WebDav.allowed) ?? String($0) }
        return base + segs.joined(separator: "/") + (path.hasSuffix("/") && !segs.isEmpty ? "/" : "")
    }

    private func send(_ method: String, _ path: String, headers: [String: String] = [:], body: Data? = nil) async throws -> (Int, Data) {
        guard let u = URL(string: url(path)) else { throw KsError("Nieprawidłowa ścieżka: \(path)") }
        var r = URLRequest(url: u)
        r.httpMethod = method
        r.setValue(auth, forHTTPHeaderField: "Authorization")
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        r.httpBody = body
        do {
            let (d, resp) = try await session.data(for: r)
            return ((resp as? HTTPURLResponse)?.statusCode ?? 0, d)
        } catch let e as URLError where e.code == .notConnectedToInternet || e.code == .networkConnectionLost {
            throw KsError("Brak połączenia z internetem.")
        } catch let e as URLError where e.code == .timedOut {
            throw KsError("Koofr nie odpowiada (przekroczony czas).")
        } catch {
            throw KsError("Błąd połączenia z Koofr: \(error.localizedDescription)")
        }
    }

    func check() async throws {
        let (s, _) = try await send("PROPFIND", "", headers: ["Depth": "0"])
        if s != 207 && s != 200 { throw KsError.http(s, "katalog główny") }
    }

    func list(_ dir: String) async throws -> [DavEntry] {
        let path = dir.hasSuffix("/") ? dir : dir + "/"
        let (s, body) = try await send("PROPFIND", path, headers: ["Depth": "1", "Content-Type": "application/xml"])
        if s == 404 { return [] }
        if s != 207 { throw KsError.http(s, dir) }
        let selfPath = WebDav.trim((URL(string: url(path))?.path).map { $0 } ?? "")
        return WebDav.parse(String(decoding: body, as: UTF8.self)).filter { $0.0 != selfPath }.map { $0.1 }
    }

    private static func trim(_ p: String) -> String {
        let d = p.removingPercentEncoding ?? p
        return d.hasSuffix("/") ? String(d.dropLast()) : d
    }

    private static func tag(_ block: String, _ name: String) -> String? {
        let re = try! NSRegularExpression(pattern: "<(?:[\\w-]+:)?\(name)\\b[^>]*>(.*?)</(?:[\\w-]+:)?\(name)>", options: [.dotMatchesLineSeparators])
        guard let m = re.firstMatch(in: block, range: NSRange(block.startIndex..., in: block)),
              let r = Range(m.range(at: 1), in: block) else { return nil }
        return block[r].trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static let httpDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return f
    }()

    /// Odpowiedź PROPFIND → (ścieżka bez końcowego „/”, wpis).
    static func parse(_ xml: String) -> [(String, DavEntry)] {
        let re = try! NSRegularExpression(pattern: "<(?:[\\w-]+:)?response\\b[^>]*>(.*?)</(?:[\\w-]+:)?response>", options: [.dotMatchesLineSeparators])
        let coll = try! NSRegularExpression(pattern: "<(?:[\\w-]+:)?collection\\b")
        var out: [(String, DavEntry)] = []
        for m in re.matches(in: xml, range: NSRange(xml.startIndex..., in: xml)) {
            guard let r = Range(m.range(at: 1), in: xml) else { continue }
            let block = String(xml[r])
            guard var href = tag(block, "href") else { continue }
            if href.hasPrefix("http"), let u = URL(string: href) { href = u.path }
            let t = trim(href)
            let name = String(t.split(separator: "/").last ?? "")
            let isDir = coll.firstMatch(in: block, range: NSRange(block.startIndex..., in: block)) != nil
            let size = Int(tag(block, "getcontentlength") ?? "") ?? 0
            let mod = tag(block, "getlastmodified").flatMap { httpDate.date(from: $0) }
            out.append((t, DavEntry(name: name, isDir: isDir, size: size, modified: mod)))
        }
        return out
    }

    func get(_ path: String) async throws -> Data? {
        let (s, b) = try await send("GET", path)
        if s == 404 { return nil }
        if s != 200 { throw KsError.http(s, path) }
        return b
    }

    func put(_ path: String, _ data: Data) async throws {
        if let i = path.lastIndex(of: "/") { try await ensureDir(String(path[..<i])) }
        let (s, _) = try await send("PUT", path, headers: ["Content-Type": "application/octet-stream"], body: data)
        if ![200, 201, 204].contains(s) { throw KsError.http(s, path) }
    }

    func putAtomic(_ path: String, _ data: Data) async throws {
        let tmp = path + ".tmp"
        try await put(tmp, data)
        do {
            try await move(tmp, path)
        } catch {
            try? await delete(tmp)
            throw error
        }
    }

    func ensureDir(_ dir: String) async throws {
        var cur = ""
        for seg in dir.split(separator: "/") {
            cur = cur.isEmpty ? String(seg) : cur + "/" + seg
            if dirs.contains(cur) { continue }
            let (s, _) = try await send("MKCOL", cur + "/")
            if ![201, 405, 200].contains(s) { throw KsError.http(s, cur) }
            dirs.insert(cur)
        }
    }

    func delete(_ path: String) async throws {
        let (s, _) = try await send("DELETE", path)
        if ![200, 204, 404].contains(s) { throw KsError.http(s, path) }
        dirs = dirs.filter { $0 != path && !$0.hasPrefix(path + "/") }
    }

    func move(_ from: String, _ to: String) async throws {
        let (s, _) = try await send("MOVE", from, headers: ["Destination": url(to), "Overwrite": "T"])
        if ![200, 201, 204].contains(s) { throw KsError.http(s, "\(from) → \(to)") }
    }
}
