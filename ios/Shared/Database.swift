import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// Odczyt jednego wiersza wyniku.
struct SQLRow {
    let stmt: OpaquePointer

    func i64(_ i: Int32) -> Int64 { sqlite3_column_int64(stmt, i) }
    func int(_ i: Int32) -> Int { Int(sqlite3_column_int64(stmt, i)) }
    func str(_ i: Int32) -> String {
        guard let p = sqlite3_column_text(stmt, i) else { return "" }
        return String(cString: p)
    }
    func isNull(_ i: Int32) -> Bool { sqlite3_column_type(stmt, i) == SQLITE_NULL }
    func i64OrNil(_ i: Int32) -> Int64? { isNull(i) ? nil : i64(i) }
    func intOrNil(_ i: Int32) -> Int? { isNull(i) ? nil : int(i) }
}

/// Mała nakładka na SQLite. Ten sam schemat co w wersji na Androida.
/// Baza leży we wspólnym folderze aplikacji i widżetów (WAL pozwala na zapis z obu procesów).
final class SQL {
    static let shared = SQL()

    private var db: OpaquePointer?
    private let lock = NSRecursiveLock()

    static var fileURL: URL { AppGroup.containerURL.appendingPathComponent("kreska.sqlite") }

    private init() {
        open()
    }

    private func open() {
        let url = SQL.fileURL
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        if sqlite3_open_v2(url.path, &db, flags, nil) != SQLITE_OK {
            NSLog("Kreska: nie można otworzyć bazy \(url.path)")
        }
        sqlite3_busy_timeout(db, 4000)
        run("PRAGMA journal_mode=WAL")
        migrate()
    }

    /// Spójna kopia bazy (do kopii zapasowej).
    func snapshot() throws -> Data {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("kreska-kopia.sqlite")
        try? FileManager.default.removeItem(at: tmp)
        run("VACUUM INTO ?", [tmp.path])
        defer { try? FileManager.default.removeItem(at: tmp) }
        return try Data(contentsOf: tmp)
    }

    /// Podmiana całej bazy (przywracanie kopii). Obecna zostaje obok jako zapas.
    func replace(with data: Data) throws {
        lock.lock()
        defer { lock.unlock() }
        sqlite3_close(db)
        db = nil
        let url = SQL.fileURL
        let fm = FileManager.default
        let backup = url.appendingPathExtension("przed-przywroceniem")
        try? fm.removeItem(at: backup)
        try? fm.copyItem(at: url, to: backup)
        for suffix in ["-wal", "-shm"] { try? fm.removeItem(at: URL(fileURLWithPath: url.path + suffix)) }
        try data.write(to: url, options: .atomic)
        open()
    }

    private func bind(_ st: OpaquePointer, _ args: [Any?]) {
        for (i, a) in args.enumerated() {
            let idx = Int32(i + 1)
            guard let a = a else {
                sqlite3_bind_null(st, idx)
                continue
            }
            switch a {
            case let v as Int: sqlite3_bind_int64(st, idx, Int64(v))
            case let v as Int64: sqlite3_bind_int64(st, idx, v)
            case let v as Int32: sqlite3_bind_int64(st, idx, Int64(v))
            case let v as Double: sqlite3_bind_double(st, idx, v)
            case let v as String: sqlite3_bind_text(st, idx, v, -1, SQLITE_TRANSIENT)
            default: sqlite3_bind_text(st, idx, "\(a)", -1, SQLITE_TRANSIENT)
            }
        }
    }

    /// Wykonuje polecenie; zwraca id ostatnio wstawionego wiersza.
    @discardableResult
    func run(_ sql: String, _ args: [Any?] = []) -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        var st: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK, let s = st else {
            NSLog("Kreska SQL: \(String(cString: sqlite3_errmsg(db))) — \(sql)")
            return 0
        }
        defer { sqlite3_finalize(s) }
        bind(s, args)
        var rc = sqlite3_step(s)
        while rc == SQLITE_ROW { rc = sqlite3_step(s) }
        if rc != SQLITE_DONE {
            NSLog("Kreska SQL step: \(String(cString: sqlite3_errmsg(db))) — \(sql)")
        }
        return sqlite3_last_insert_rowid(db)
    }

    func query<T>(_ sql: String, _ args: [Any?] = [], _ map: (SQLRow) -> T) -> [T] {
        lock.lock()
        defer { lock.unlock() }
        var st: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK, let s = st else {
            NSLog("Kreska SQL: \(String(cString: sqlite3_errmsg(db))) — \(sql)")
            return []
        }
        defer { sqlite3_finalize(s) }
        bind(s, args)
        var out: [T] = []
        while sqlite3_step(s) == SQLITE_ROW {
            out.append(map(SQLRow(stmt: s)))
        }
        return out
    }

    func scalar(_ sql: String, _ args: [Any?] = []) -> Int64? {
        query(sql, args) { $0.i64OrNil(0) }.first ?? nil
    }

    private func migrate() {
        let v = query("PRAGMA user_version") { $0.int(0) }.first ?? 0
        if v < 1 {
            run("""
                CREATE TABLE IF NOT EXISTS counters(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                step INTEGER NOT NULL DEFAULT 1, goal INTEGER, color INTEGER NOT NULL DEFAULT 0,
                notify INTEGER NOT NULL DEFAULT 1, sort INTEGER NOT NULL DEFAULT 0)
                """)
            run("""
                CREATE TABLE IF NOT EXISTS taps(id INTEGER PRIMARY KEY AUTOINCREMENT, counter_id INTEGER NOT NULL,
                ts INTEGER NOT NULL, amount INTEGER NOT NULL, day TEXT NOT NULL, manual INTEGER NOT NULL DEFAULT 0)
                """)
            run("CREATE INDEX IF NOT EXISTS taps_cd ON taps(counter_id, day)")
            run("""
                CREATE TABLE IF NOT EXISTS trackers(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                color INTEGER NOT NULL DEFAULT 0, notify INTEGER NOT NULL DEFAULT 1, sort INTEGER NOT NULL DEFAULT 0)
                """)
            run("""
                CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, tracker_id INTEGER NOT NULL,
                start INTEGER NOT NULL, end_ts INTEGER, note TEXT NOT NULL DEFAULT '')
                """)
            run("CREATE INDEX IF NOT EXISTS sess_ts ON sessions(tracker_id, start)")
            run("""
                CREATE TABLE IF NOT EXISTS pauses(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL,
                start INTEGER NOT NULL, end_ts INTEGER)
                """)
            run("CREATE INDEX IF NOT EXISTS pauses_s ON pauses(session_id)")
            run("PRAGMA user_version = 1")
        }
    }
}
