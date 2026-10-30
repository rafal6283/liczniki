import Foundation

/// Zgodność wersji Swift z pakietem koofr_sync (Dart) i wersją Kotlin — uruchamiane w CI na macOS.
@main
struct VectorsMain {
    static func hex(_ s: String) -> Data {
        var d = Data()
        var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            d.append(UInt8(s[i..<j], radix: 16)!)
            i = j
        }
        return d
    }

    static func main() throws {
        let v = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [String: Any]
        let app = v["app"] as! String
        let o = v["owner"] as! [String: Any], w = v["viewer"] as! [String: Any]
        var fails = 0
        func eq(_ a: String, _ b: String, _ what: String) {
            if a == b { print("ok   \(what)") } else { print("FAIL \(what): \(a) != \(b)"); fails += 1 }
        }
        let oc = RecoveryCode.parse(o["code"] as! String)!
        eq(oc.format(), o["code"] as! String, "kod")
        let ok = try KsCrypto.fromCode(oc, appId: app)
        eq(ok.codeCheck, o["check"] as! String, "codeCheck")
        eq(ok.publicKeyB64, o["pub"] as! String, "klucz publiczny")
        eq(ok.backupKey.withUnsafeBytes { KsCrypto.hex(Data($0)) }, o["backupKey"] as! String, "klucz kopii")
        let wk = try KsCrypto.fromCode(RecoveryCode.parse((w["code"] as! String).lowercased())!, appId: app)
        eq(wk.publicKeyB64, w["pub"] as! String, "klucz widza")
        eq(String(decoding: try ok.openBackup(hex(v["backup"] as! String)), as: UTF8.self), v["backupPlain"] as! String, "kopia z Dart/Pythona")
        let other = try KsCrypto.fromCode(oc, appId: "kreska-ios")
        eq(String(decoding: try other.openExchange(hex(v["exchange"] as! String)), as: UTF8.self), v["exchangePlain"] as! String, "wymiana (inna aplikacja)")
        eq(ok.blobId(Data("załącznik".utf8)), v["blobId"] as! String, "nazwa załącznika")
        let rt = try ok.openBackup(try ok.sealBackup(Data("abc".utf8)))
        eq(String(decoding: rt, as: UTF8.self), "abc", "kopia w obie strony")
        eq(KsCrypto.slugify("Rafał Szarek"), "rafal-szarek", "slugify")
        if RecoveryCode.parse("0410-6105-0R3G-G28A-1C60-T3GF-208H-5WKF") != nil { print("FAIL literówka"); fails += 1 }
        print(fails == 0 ? "ALL OK" : "\(fails) FAILED")
        exit(fails == 0 ? 0 : 1)
    }
}
