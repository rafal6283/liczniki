import SwiftUI
import Charts

/* ---------- klocki interfejsu ---------- */

struct Card<Content: View>: View {
    var tint: Color?
    var border: Color?
    let content: Content

    init(tint: Color? = nil, border: Color? = nil, @ViewBuilder content: () -> Content) {
        self.tint = tint
        self.border = border
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(tint ?? Color(.secondarySystemGroupedBackground))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(border ?? Color.clear, lineWidth: 1)
            )
    }
}

struct Swatch: View {
    let color: Color
    var size: CGFloat = 10

    var body: some View {
        RoundedRectangle(cornerRadius: 3).fill(color).frame(width: size, height: size)
    }
}

struct Eyebrow: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.caption2.weight(.semibold))
            .tracking(0.8)
            .foregroundStyle(.secondary)
    }
}

struct StatItem: Identifiable {
    let id = UUID()
    let value: String
    let label: String
    init(_ value: String, _ label: String) {
        self.value = value
        self.label = label
    }
}

struct StatsGrid: View {
    let items: [StatItem]

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), alignment: .topLeading), GridItem(.flexible(), alignment: .topLeading)],
                  alignment: .leading, spacing: 12) {
            ForEach(items) { it in
                VStack(alignment: .leading, spacing: 2) {
                    Text(it.value)
                        .font(.system(.title3, design: .monospaced).weight(.medium))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(it.label)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.vertical, 6)
    }
}

struct InfoLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.system(.body, design: .monospaced).weight(.medium))
        }
        .padding(.vertical, 2)
    }
}

struct SectionCard<Content: View>: View {
    let title: String
    var hint: String?
    let content: Content

    init(_ title: String, hint: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.hint = hint
        self.content = content()
    }

    var body: some View {
        Card {
            Text(title).font(.headline)
            if let hint = hint {
                Text(hint).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            content.padding(.top, 4)
        }
    }
}

struct EmptyCard<Actions: View>: View {
    let eyebrow: String
    let title: String
    let text: String
    let actions: Actions

    init(eyebrow: String, title: String, text: String, @ViewBuilder actions: () -> Actions) {
        self.eyebrow = eyebrow
        self.title = title
        self.text = text
        self.actions = actions()
    }

    var body: some View {
        Card {
            VStack(spacing: 8) {
                Eyebrow(text: eyebrow)
                Text(title).font(.title3.bold())
                Text(text).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                actions.padding(.top, 6)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
    }
}

struct ColorChooser: View {
    @Binding var selected: Int

    var body: some View {
        HStack(spacing: 12) {
            ForEach(0..<Palette.count, id: \.self) { i in
                Circle()
                    .fill(Palette.color(i))
                    .frame(width: 32, height: 32)
                    .overlay(Circle().stroke(Color.primary, lineWidth: i == selected ? 3 : 0).padding(-3))
                    .onTapGesture { selected = i }
                    .accessibilityLabel("Kolor \(i + 1)")
            }
        }
        .padding(.vertical, 4)
    }
}

struct RangePicker: View {
    @Binding var range: Int

    var body: some View {
        Picker("Zakres", selection: $range) {
            Text("7 dni").tag(7)
            Text("30 dni").tag(30)
            Text("90 dni").tag(90)
            Text("rok").tag(365)
        }
        .pickerStyle(.segmented)
    }
}

struct SettingsButton: View {
    @State private var show = false

    var body: some View {
        Button { show = true } label: { Image(systemName: "gearshape") }
            .accessibilityLabel("Ustawienia")
            .sheet(isPresented: $show) { SettingsView() }
    }
}

/// Kreski w grupach po pięć — tak jak liczy się na kartce.
/// Nowa kreska (i przekreślenie piątki) rysuje się płynnie, jakby długopisem.
struct TallyView: View {
    let n: Int
    let color: Color

    var body: some View {
        TallyShape(shown: Double(n))
            .stroke(color, style: StrokeStyle(lineWidth: 1.8, lineCap: .round))
            .frame(height: 20)
            .animation(.spring(response: 0.45, dampingFraction: 0.9), value: n)
    }
}

struct TallyShape: Shape {
    var shown: Double
    var animatableData: Double {
        get { shown }
        set { shown = newValue }
    }

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let total = Int(ceil(shown))
        let groups = (total + 4) / 5
        let gw: CGFloat = 30
        for g in 0..<max(0, groups) {
            let x0 = CGFloat(g) * gw
            if x0 + 22 > rect.width { break }
            for i in 0..<5 {
                let p = CGFloat(min(1, max(0, shown - Double(g * 5 + i))))
                if p <= 0 { break }
                if i < 4 {
                    let x = x0 + 3 + CGFloat(i) * 5
                    path.move(to: CGPoint(x: x, y: 2))
                    path.addLine(to: CGPoint(x: x, y: 2 + 16 * p))
                } else {
                    path.move(to: CGPoint(x: x0, y: 15))
                    path.addLine(to: CGPoint(x: x0 + 21 * p, y: 15 - 10 * p))
                }
            }
        }
        return path
    }
}

/* ---------- wykresy ---------- */

/// Okrągła górna granica osi (1, 2, 2.5, 5, 10 × 10^k).
func niceMax(_ v: Double) -> Double {
    if v <= 0 { return 1 }
    let p = pow(10, floor(log10(v)))
    let m = v / p
    let n: Double = m <= 1 ? 1 : m <= 2 ? 2 : m <= 2.5 ? 2.5 : m <= 5 ? 5 : 10
    return n * p
}

/// Dymek z wartością nad wybranym słupkiem.
struct ChartTip: View {
    let title: String
    let value: String

    var body: some View {
        VStack(spacing: 1) {
            Text(title).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.system(.footnote, design: .monospaced).weight(.semibold))
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 7))
        .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(.quaternary))
    }
}

/// Słupki dzienne. Rosną przy pojawieniu się i płynnie zmieniają przy zmianie okresu;
/// dotknięcie lub przeciągnięcie palcem pokazuje wartość dnia (z lekkim "klikiem").
struct DailyChart: View {
    let days: [Date]
    let values: [Double]
    let color: Color
    let format: (Double) -> String

    @State private var grown = false
    @State private var selected: Date?

    private var selIndex: Int? {
        guard let s = selected else { return nil }
        return days.firstIndex { Fmt.cal.isDate($0, inSameDayAs: s) }
    }

    var body: some View {
        let top = niceMax(values.max() ?? 0)
        let si = selIndex
        Chart {
            ForEach(days.indices, id: \.self) { i in
                BarMark(
                    x: .value("Dzień", days[i], unit: .day),
                    y: .value("Wartość", grown ? values[i] : 0)
                )
                .cornerRadius(days.count <= 31 ? 3 : 0)
                .foregroundStyle(Fmt.cal.isDateInToday(days[i]) ? color.opacity(0.55) : color)
                .opacity(si == nil || si == i ? 1 : 0.4)
            }
            if let i = si {
                RuleMark(x: .value("Dzień", days[i], unit: .day))
                    .foregroundStyle(.secondary.opacity(0.5))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    .annotation(position: .top, spacing: 2, overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                        ChartTip(title: "\(Fmt.weekday(days[i])) \(Fmt.day(days[i]))", value: format(values[i]))
                    }
            }
        }
        .chartYScale(domain: 0...top)
        .chartXSelection(value: $selected)
        .chartGesture { proxy in
            // dotknięcie = pokaż/ukryj; przytrzymaj i przesuwaj = przeglądaj dni (przewijanie ekranu dalej działa)
            SpatialTapGesture()
                .onEnded { v in
                    if let d: Date = proxy.value(atX: v.location.x), let s = selected, Fmt.cal.isDate(d, inSameDayAs: s) {
                        selected = nil
                    } else {
                        proxy.selectXValue(at: v.location.x)
                    }
                }
                .simultaneously(with: scrub(proxy))
        }
        .chartYAxis {
            AxisMarks(position: .leading) { v in
                AxisGridLine()
                AxisValueLabel {
                    if let d = v.as(Double.self) { Text(format(d)).font(.caption2) }
                }
            }
        }
        .frame(height: 170)
        .sensoryFeedback(.selection, trigger: si)
        .animation(.spring(response: 0.55, dampingFraction: 0.85), value: values)
        .animation(.easeOut(duration: 0.15), value: si)
        .onAppear {
            withAnimation(.spring(response: 0.7, dampingFraction: 0.8).delay(0.05)) { grown = true }
        }
    }
}

/// Słupki z etykietami kategorii (godziny, dni tygodnia, tygodnie…). Te same animacje i dotyk co DailyChart.
struct CategoryBars: View {
    let labels: [String]
    let values: [Double]
    let color: Color
    let format: (Double) -> String
    var showValues = false
    var labelEvery = 1
    var height: CGFloat = 140
    var tipLabels: [String]? = nil

    @State private var grown = false
    @State private var selected: String?

    var body: some View {
        let maxV = values.max() ?? 0
        let shown = labels.enumerated().filter { $0.offset % labelEvery == 0 }.map { $0.element }
        let si = selected.flatMap { labels.firstIndex(of: $0) }
        Chart {
            ForEach(values.indices, id: \.self) { i in
                BarMark(
                    x: .value("Kategoria", labels[i]),
                    y: .value("Wartość", grown ? values[i] : 0)
                )
                .cornerRadius(2)
                .foregroundStyle(maxV > 0 && values[i] == maxV ? color : color.opacity(0.7))
                .opacity(si == nil || si == i ? 1 : 0.4)
                .annotation(position: .top) {
                    if showValues && values[i] > 0 && si != i {
                        Text(format(values[i])).font(.system(size: 9, design: .monospaced)).foregroundStyle(.secondary)
                            .opacity(grown ? 1 : 0)
                    }
                }
            }
            if let i = si {
                RuleMark(x: .value("Kategoria", labels[i]))
                    .foregroundStyle(.clear)
                    .annotation(position: .top, spacing: 0, overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                        ChartTip(title: tipLabels?[safe: i] ?? labels[i], value: format(values[i]))
                    }
            }
        }
        .chartYScale(domain: 0...(maxV > 0 ? maxV : 1))
        .chartXSelection(value: $selected)
        .chartGesture { proxy in
            SpatialTapGesture()
                .onEnded { v in
                    let l: String? = proxy.value(atX: v.location.x)
                    if l != nil && l == selected { selected = nil } else { proxy.selectXValue(at: v.location.x) }
                }
                .simultaneously(with: scrub(proxy))
        }
        .chartXAxis {
            AxisMarks(values: shown) { _ in
                AxisValueLabel()
            }
        }
        .chartYAxis(.hidden)
        .frame(height: height)
        .sensoryFeedback(.selection, trigger: si)
        .animation(.spring(response: 0.55, dampingFraction: 0.85), value: values)
        .animation(.easeOut(duration: 0.15), value: si)
        .onAppear {
            withAnimation(.spring(response: 0.7, dampingFraction: 0.8).delay(0.05)) { grown = true }
        }
    }
}

/// Przytrzymaj palec na wykresie i przesuwaj, żeby przeglądać kolejne słupki.
func scrub(_ proxy: ChartProxy) -> some Gesture {
    LongPressGesture(minimumDuration: 0.25)
        .sequenced(before: DragGesture(minimumDistance: 0))
        .onChanged { v in
            if case .second(true, let drag?) = v { proxy.selectXValue(at: drag.location.x) }
        }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

/// Mapa cieplna dzień × godzina. Pola pojawiają się falą; dotknięcie pokazuje wartość pola.
struct HeatMapView: View {
    let heat: [[Double]]
    let color: Color
    var format: (Double) -> String = { Fmt.num($0) }

    @State private var shown = false
    @State private var sel: (d: Int, h: Int)?

    var body: some View {
        let maxV = heat.flatMap { $0 }.max() ?? 0
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                if let s = sel {
                    ChartTip(title: "\(Stats.weekdaysLong[s.d]), \(s.h):00–\(s.h):59", value: format(heat[s.d][s.h]))
                        .transition(.opacity.combined(with: .scale(scale: 0.9)))
                } else {
                    Text("Dotknij pola, aby zobaczyć wartość.")
                        .font(.caption2).foregroundStyle(.secondary)
                        .padding(.vertical, 8)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(minHeight: 36)
            HStack(spacing: 2) {
                Text("").frame(width: 22)
                ForEach(0..<24, id: \.self) { h in
                    Text(h % 3 == 0 ? "\(h)" : "")
                        .font(.system(size: 8, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .fixedSize()
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            HStack(spacing: 2) {
                VStack(spacing: 2) {
                    ForEach(0..<7, id: \.self) { d in
                        Text(Stats.weekdays[d])
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .frame(width: 22, height: 16, alignment: .leading)
                    }
                }
                GeometryReader { geo in
                    VStack(spacing: 2) {
                        ForEach(0..<7, id: \.self) { d in
                            HStack(spacing: 2) {
                                ForEach(0..<24, id: \.self) { h in
                                    let isSel = sel?.d == d && sel?.h == h
                                    RoundedRectangle(cornerRadius: 2)
                                        .fill(cellColor(heat[d][h], maxV))
                                        .overlay(RoundedRectangle(cornerRadius: 2).strokeBorder(isSel ? Color.primary : .clear, lineWidth: 1.5))
                                        .frame(maxWidth: .infinity)
                                        .frame(height: 16)
                                        .scaleEffect(shown ? (isSel ? 1.25 : 1) : 0.3)
                                        .opacity(shown ? 1 : 0)
                                        .zIndex(isSel ? 1 : 0)
                                        .animation(.spring(response: 0.45, dampingFraction: 0.7).delay(shown ? 0 : Double(d + h) * 0.012), value: shown)
                                        .animation(.spring(response: 0.25, dampingFraction: 0.6), value: isSel)
                                }
                            }
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { p in
                        let h = Int(p.x / (geo.size.width / 24))
                        let d = Int(p.y / (geo.size.height / 7))
                        guard (0..<24).contains(h), (0..<7).contains(d) else { return }
                        withAnimation(.easeOut(duration: 0.15)) {
                            sel = (sel?.d == d && sel?.h == h) ? nil : (d, h)
                        }
                    }
                }
                .frame(height: 7 * 16 + 6 * 2)
            }
        }
        .sensoryFeedback(.selection, trigger: sel.map { $0.d * 24 + $0.h })
        .onAppear {
            // jedna zmiana stanu = wszystkie pola animują się z własnym opóźnieniem (fala po przekątnej)
            withAnimation { shown = true }
        }
    }

    private func cellColor(_ v: Double, _ maxV: Double) -> Color {
        if v <= 0 || maxV <= 0 { return Color(.tertiarySystemFill) }
        return color.opacity(0.15 + 0.85 * v / maxV)
    }
}
