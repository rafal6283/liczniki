import CryptoKit
import Foundation

/// Kopia i wymiana przez rodzinne konto Koofr — wersja Swift, zgodna bajt w bajt z pakietem
/// koofr_sync (Dart) i wersją Kotlin (Android). Sprawdzane w CI wektorami z test/vectors.json.

struct KsError: LocalizedError {
    let message: String
    var status: Int = 0
    init(_ m: String, status: Int = 0) { message = m; self.status = status }
    var errorDescription: String? { message }

    static func http(_ s: Int, _ what: String) -> KsError {
        switch s {
        case 401: return KsError("Koofr odrzucił logowanie — sprawdź e-mail i hasło aplikacji.", status: s)
        case 403: return KsError("Brak dostępu do folderu na Koofr.", status: s)
        case 404: return KsError("Nie znaleziono: \(what)", status: s)
        case 507: return KsError("Brak wolnego miejsca na koncie Koofr.", status: s)
        case 500...: return KsError("Koofr ma chwilowe problemy (\(s)), spróbuj później.", status: s)
        default: return KsError("Błąd Koofr \(s) przy: \(what)", status: s)
        }
    }
}

/// Kod odzyskiwania: 18 B losowe + 2 B SHA-256, base32 Crockford, 8 grup po 4 znaki.
struct RecoveryCode {
    static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    let entropy: [UInt8]

    static func generate() -> RecoveryCode {
        var b = [UInt8](repeating: 0, count: 18)
        _ = SecRandomCopyBytes(kSecRandomDefault, 18, &b)
        return RecoveryCode(entropy: b)
    }

    static func normalize(_ t: String) -> String {
        let up = t.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
        return String(up.map { c -> Character in
            switch c {
            case "O": return "0"
            case "I", "L": return "1"
            case "U": return "V"
            default: return c
            }
        })
    }

    static func parse(_ text: String) -> RecoveryCode? {
        let s = normalize(text)
        guard s.count == 32, let b = decode(s), b.count == 20 else { return nil }
        let ent = Array(b[0..<18])
        let sum = checksum(ent)
        guard b[18] == sum[0], b[19] == sum[1] else { return nil }
        return RecoveryCode(entropy: ent)
    }

    func format() -> String {
        let s = Array(RecoveryCode.encode(entropy + RecoveryCode.checksum(entropy)))
        return stride(from: 0, to: s.count, by: 4).map { String(s[$0..<min($0 + 4, s.count)]) }.joined(separator: "-")
    }

    static func checksum(_ e: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: Data(e)))[0..<2].map { $0 } }

    static func encode(_ bytes: [UInt8]) -> String {
        var out = ""
        var buf = 0, bits = 0
        for b in bytes {
            buf = (buf << 8) | Int(b)
            bits += 8
            while bits >= 5 {
                bits -= 5
                out.append(alphabet[(buf >> bits) & 31])
            }
            buf &= (1 << bits) - 1
        }
        if bits > 0 { out.append(alphabet[(buf << (5 - bits)) & 31]) }
        return out
    }

    static func decode(_ s: String) -> [UInt8]? {
        var out: [UInt8] = []
        var buf = 0, bits = 0
        for c in s {
            guard let v = alphabet.firstIndex(of: c) else { return nil }
            buf = (buf << 5) | v
            bits += 5
            if bits >= 8 {
                bits -= 8
                out.append(UInt8((buf >> bits) & 255))
            }
            buf &= (1 << bits) - 1
        }
        return out
    }
}

/// gzip (nagłówek + DEFLATE + CRC32) — jak w Dart/Javie.
enum KsGzip {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ d: [UInt8]) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in d { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }

    static func compress(_ d: Data) throws -> Data {
        let deflated = try (d as NSData).compressed(using: .zlib) as Data
        var out = Data([0x1F, 0x8B, 8, 0, 0, 0, 0, 0, 0, 0xFF])
        out.append(deflated)
        let crc = crc32([UInt8](d))
        let size = UInt32(truncatingIfNeeded: d.count)
        for v in [crc, size] { out.append(contentsOf: [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)]) }
        return out
    }

    static func decompress(_ d: Data) throws -> Data {
        let b = [UInt8](d)
        guard b.count >= 18, b[0] == 0x1F, b[1] == 0x8B, b[2] == 8 else { throw KsError("Uszkodzone dane (gzip).") }
        let flg = b[3]
        var i = 10
        if flg & 4 != 0 { i += 2 + (Int(b[i]) | Int(b[i + 1]) << 8) }
        if flg & 8 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }
        if flg & 16 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }
        if flg & 2 != 0 { i += 2 }
        guard i < b.count - 8 else { throw KsError("Uszkodzone dane (gzip).") }
        return try (Data(b[i..<(b.count - 8)]) as NSData).decompressed(using: .zlib) as Data
    }
}

struct KsCrypto {
    static let magic: [UInt8] = [0x4B, 0x53, 0x59, 0x31] // KSY1
    static let salt = Data("koofr_sync v1".utf8)

    let appId: String
    let backupKey: SymmetricKey
    let exchangeKey: SymmetricKey
    let publicKey: Data
    let codeCheck: String

    static func derive(_ ikm: Data, _ info: String) -> Data {
        derive(ikm, Data(info.utf8))
    }

    static func derive(_ ikm: Data, _ info: Data) -> Data {
        let k = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: 32)
        return k.withUnsafeBytes { Data($0) }
    }

    static func fromCode(_ c: RecoveryCode, appId: String) throws -> KsCrypto {
        let master = derive(Data(c.entropy), "master")
        let seed = derive(master, "x25519/\(appId)")
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: seed)
        return KsCrypto(
            appId: appId,
            backupKey: SymmetricKey(data: derive(master, "backup/\(appId)")),
            exchangeKey: SymmetricKey(data: derive(master, "exchange")),
            publicKey: priv.publicKey.rawRepresentation,
            codeCheck: checkOf(c))
    }

    static func checkOf(_ c: RecoveryCode) -> String {
        hex(derive(derive(Data(c.entropy), "master"), "check").prefix(8))
    }

    /// base64url z dopełnieniem — jak base64Url.encode w Dart i Base64.getUrlEncoder w Javie.
    var publicKeyB64: String {
        publicKey.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
    }

    private func seal(_ kind: UInt8, _ key: SymmetricKey, aadTail: Data, _ data: Data) throws -> Data {
        let header = Data(KsCrypto.magic + [kind])
        let box = try AES.GCM.seal(try KsGzip.compress(data), using: key, nonce: AES.GCM.Nonce(), authenticating: header + aadTail)
        guard let combined = box.combined else { throw KsError("Błąd szyfrowania") }
        return header + combined
    }

    private func open(_ kind: UInt8, _ key: SymmetricKey, aadTail: Data, _ file: Data, _ err: String) throws -> Data {
        let b = [UInt8](file)
        guard b.count >= 5 + 28, Array(b[0..<4]) == KsCrypto.magic else { throw KsError("To nie jest plik kopii/wymiany.") }
        guard b[4] == kind else { throw KsError("Nieoczekiwany rodzaj pliku.") }
        do {
            let box = try AES.GCM.SealedBox(combined: Data(b[5...]))
            return try KsGzip.decompress(try AES.GCM.open(box, using: key, authenticating: Data(b[0..<5]) + aadTail))
        } catch let e as KsError {
            throw e
        } catch {
            throw KsError(err)
        }
    }

    func sealBackup(_ d: Data) throws -> Data { try seal(1, backupKey, aadTail: Data(appId.utf8), d) }
    func openBackup(_ f: Data) throws -> Data {
        try open(1, backupKey, aadTail: Data(appId.utf8), f, "Nie da się odszyfrować kopii — to kopia innej osoby albo inny kod odzyskiwania.")
    }

    func sealExchange(_ d: Data) throws -> Data { try seal(3, exchangeKey, aadTail: Data("wymiana".utf8), d) }
    func openExchange(_ f: Data) throws -> Data {
        try open(3, exchangeKey, aadTail: Data("wymiana".utf8), f, "Nie da się odczytać danych wymiany — inny kod odzyskiwania.")
    }

    func blobId(_ d: Data) -> String {
        KsCrypto.hex(Data(HMAC<SHA256>.authenticationCode(for: d, using: backupKey)).prefix(16))
    }

    static func hex<D: Sequence>(_ d: D) -> String where D.Element == UInt8 {
        d.map { String(format: "%02x", $0) }.joined()
    }

    static func sha256Hex16(_ d: Data) -> String { hex(Data(SHA256.hash(data: d)).prefix(16)) }

    static func slugify(_ name: String) -> String {
        let map: [Character: Character] = ["ą": "a", "ć": "c", "ę": "e", "ł": "l", "ń": "n", "ó": "o", "ś": "s", "ź": "z", "ż": "z"]
        var out = ""
        var dash = false
        for ch in name.lowercased() {
            let c = map[ch] ?? ch
            if c.isASCII && (c.isLetter || c.isNumber) {
                out.append(c)
                dash = false
            } else if !dash {
                out.append("-")
                dash = true
            }
        }
        let t = out.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        if t.isEmpty { return "osoba" }
        return String(t.prefix(24))
    }
}
