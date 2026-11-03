import Charts
import SwiftUI

/// Migotanie z innych aplikacji na wykresach zdarzenia AFIB: Polar EKG (czerwone, pełne)
/// i Rytm Snu (fioletowe, w paski – nieregularny rytm z opaski w nocy). Wpisy z Kreski zostają w kolorze zdarzenia.
enum AfColors {
    static let ecg = Palette.dyn(0xD32F2F, 0xFF6B6B)
    static let night = Palette.dyn(0x6A4FB3, 0xB39DDB)
}

/// Dodatkowa seria na wykresie dziennym (np. AF z Polar EKG).
struct BarExtra {
    let label: String
    let values: [Double]
    let color: Color
    var striped = false
}

extension AfLink {
    /// Serie AF z aplikacji dla wykresu dziennego zdarzenia AFIB (puste, gdy to inne zdarzenie).
    static func extras(trackerId: Int64, days: [Date]) -> [BarExtra] {
        guard afTrackerId(Repo.trackers()) == trackerId else { return [] }
        let d = load()
        return [BarExtra(label: "EKG", values: dailyMinutes(d.ecg, days), color: AfColors.ecg),
                BarExtra(label: "Rytm Snu", values: dailyMinutes(d.night, days), color: AfColors.night, striped: true)]
    }
}

/// Paski – wzór dla danych z Rytmu Snu.
struct Stripes: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        var x = r.minX
        while x < r.maxX {
            p.addRect(CGRect(x: x, y: r.minY, width: min(2.5, r.maxX - x), height: r.height))
            x += 4.5
        }
        return p
    }
}

struct AfSwatch: View {
    let color: Color
    var striped = false
    var outlined = false

    var body: some View {
        ZStack {
            if striped {
                Stripes().fill(color)
                RoundedRectangle(cornerRadius: 2).stroke(color, lineWidth: 1)
            } else {
                RoundedRectangle(cornerRadius: 2).fill(color)
                if outlined { RoundedRectangle(cornerRadius: 2).stroke(Color(.systemBackground), lineWidth: 1) }
            }
        }
        .frame(width: 12, height: 10)
    }
}

struct AfLegend: View {
    let trackerName: String
    let trackerColor: Color
    let ecg: Bool
    let night: Bool

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 4) { AfSwatch(color: trackerColor); Text("\(trackerName) (Kreska)") }
            if ecg { HStack(spacing: 4) { AfSwatch(color: AfColors.ecg, outlined: true); Text("Polar EKG").bold().foregroundStyle(AfColors.ecg) } }
            if night { HStack(spacing: 4) { AfSwatch(color: AfColors.night, striped: true); Text("Rytm Snu").bold().foregroundStyle(AfColors.night) } }
        }
        .font(.caption2)
    }
}

/// Oś czasu AF: każdy dzień to wiersz 0–24 h z trzema pasami – Kreska, Polar EKG, Rytm Snu.
/// Dotknięcie dnia pokazuje start, koniec i czas trwania każdego epizodu.
struct AfTimeline: View {
    let days: [Date]
    let kreska: [Interval]
    let ecg: [Interval]
    let night: [Interval]
    let trackerName: String
    let color: Color
    @State private var sel: Date?

    private static let rowH: CGFloat = 34, head: CGFloat = 16, left: CGFloat = 64

    private func dayRange(_ d: Date) -> [Interval] {
        [Interval(start: Fmt.cal.startOfDay(for: d).ms, end: Fmt.cal.startOfDay(for: Fmt.addDays(d, 1)).ms)]
    }

    private func hits(_ l: [Interval], _ d: Date) -> [Interval] {
        let r = dayRange(d)[0]
        return l.filter { $0.end > r.start && $0.start < r.end }
    }

    var body: some View {
        let rows = Array(days.reversed().filter { d in !hits(kreska, d).isEmpty || !hits(ecg, d).isEmpty || !hits(night, d).isEmpty }.prefix(21))
        if rows.isEmpty {
            Text("Brak AFIB w Kresce ani migotania z aplikacji w tym okresie.").font(.caption).foregroundStyle(.secondary)
        } else {
            let selected = sel.flatMap { s in rows.first { Fmt.cal.isDate($0, inSameDayAs: s) } }
                ?? rows.first { !hits(ecg, $0).isEmpty || !hits(night, $0).isEmpty } ?? rows[0]
            VStack(alignment: .leading, spacing: 8) {
                Canvas { ctx, size in draw(ctx, size, rows, selected) }
                    .frame(height: Self.head + Self.rowH * CGFloat(rows.count))
                    .contentShape(Rectangle())
                    .onTapGesture { p in
                        let i = Int((p.y - Self.head) / Self.rowH)
                        if rows.indices.contains(i) { sel = rows[i] }
                    }
                    .sensoryFeedback(.selection, trigger: sel)
                AfLegend(trackerName: trackerName, trackerColor: color, ecg: !ecg.isEmpty, night: !night.isEmpty)
                details(selected)
            }
        }
    }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize, _ rows: [Date], _ selected: Date) {
        let w = size.width - Self.left
        for h in [0, 6, 12, 18, 24] {
            let x = Self.left + w * CGFloat(h) / 24
            ctx.stroke(Path { $0.move(to: CGPoint(x: x, y: Self.head - 2)); $0.addLine(to: CGPoint(x: x, y: size.height)) },
                       with: .color(.secondary.opacity(0.3)), lineWidth: 1)
            ctx.draw(Text("\(h)").font(.system(size: 10, design: .monospaced)).foregroundStyle(.secondary),
                     at: CGPoint(x: min(max(x, Self.left + 4), size.width - 6), y: 6))
        }
        for (i, d) in rows.enumerated() {
            let y0 = Self.head + CGFloat(i) * Self.rowH
            if Fmt.cal.isDate(d, inSameDayAs: selected) {
                ctx.fill(Path(roundedRect: CGRect(x: 0, y: y0 + 1, width: size.width, height: Self.rowH - 2), cornerRadius: 6),
                         with: .color(Color(.tertiarySystemFill)))
            }
            ctx.draw(Text("\(Fmt.weekday(d)) \(Fmt.cal.component(.day, from: d)).\(String(format: "%02d", Fmt.cal.component(.month, from: d)))")
                        .font(.system(size: 10, design: .monospaced)).foregroundStyle(.secondary),
                     at: CGPoint(x: 4, y: y0 + Self.rowH / 2), anchor: .leading)
            let r = dayRange(d)[0]
            func x(_ t: Int64) -> CGFloat { Self.left + w * CGFloat(min(max(Double(t - r.start) / Double(r.end - r.start), 0), 1)) }
            let lane: CGFloat = 7, gap: CGFloat = 2
            let top = y0 + (Self.rowH - 3 * lane - 2 * gap) / 2
            ctx.fill(Path(roundedRect: CGRect(x: Self.left, y: top, width: w, height: 3 * lane + 2 * gap), cornerRadius: 3),
                     with: .color(.secondary.opacity(0.12)))
            for s in Spans.intersect(kreska, [r]) {
                ctx.fill(Path(roundedRect: CGRect(x: x(s.start), y: top, width: max(2, x(s.end) - x(s.start)), height: lane), cornerRadius: 2),
                         with: .color(color))
            }
            for s in Spans.intersect(ecg, [r]) {
                let rect = CGRect(x: x(s.start), y: top + lane + gap, width: max(3, x(s.end) - x(s.start)), height: lane)
                ctx.fill(Path(rect), with: .color(AfColors.ecg))
                ctx.stroke(Path(rect), with: .color(Color(.systemBackground)), lineWidth: 1)
            }
            for s in Spans.intersect(night, [r]) {
                let rect = CGRect(x: x(s.start), y: top + 2 * (lane + gap), width: max(3, x(s.end) - x(s.start)), height: lane)
                ctx.fill(Stripes().path(in: rect), with: .color(AfColors.night))
                ctx.stroke(Path(rect), with: .color(AfColors.night), lineWidth: 1)
            }
        }
    }

    @ViewBuilder private func details(_ d: Date) -> some View {
        let items: [(Int, Interval)] = (hits(kreska, d).map { (0, $0) } + hits(ecg, d).map { (1, $0) } + hits(night, d).map { (2, $0) })
            .sorted { $0.1.start < $1.1.start }
        VStack(alignment: .leading, spacing: 6) {
            Text(Fmt.longDay(d)).font(.subheadline.weight(.bold))
            ForEach(Array(items.enumerated()), id: \.offset) { _, it in
                let src = it.0, s = it.1
                let c = src == 0 ? color : (src == 1 ? AfColors.ecg : AfColors.night)
                HStack(spacing: 8) {
                    AfSwatch(color: c, striped: src == 2, outlined: src == 1)
                    Text(src == 0 ? trackerName : (src == 1 ? "Polar EKG" : "Rytm Snu"))
                        .fontWeight(src == 0 ? .regular : .bold)
                        .foregroundStyle(src == 0 ? Color.primary : c)
                    Spacer()
                    Text("\(stamp(s.start, d))–\(stamp(s.end, d)) · \(Fmt.dur(s.end - s.start))")
                        .font(.subheadline.monospacedDigit())
                }
                .font(.subheadline)
            }
        }
    }

    private func stamp(_ t: Int64, _ d: Date) -> String {
        (Fmt.cal.isDate(t.date, inSameDayAs: d) ? "" : Fmt.weekday(t.date) + " ") + Fmt.hm(t)
    }
}
