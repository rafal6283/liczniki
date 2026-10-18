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
struct TallyView: View {
    let n: Int
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let groups = (n + 4) / 5
            let gw: CGFloat = 30
            for g in 0..<groups {
                let x0 = CGFloat(g) * gw
                if x0 + 22 > size.width { break }
                let k = min(5, n - g * 5)
                var path = Path()
                for i in 0..<min(k, 4) {
                    let x = x0 + 3 + CGFloat(i) * 5
                    path.move(to: CGPoint(x: x, y: 2))
                    path.addLine(to: CGPoint(x: x, y: 18))
                }
                if k == 5 {
                    path.move(to: CGPoint(x: x0, y: 15))
                    path.addLine(to: CGPoint(x: x0 + 21, y: 5))
                }
                ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 1.8, lineCap: .round))
            }
        }
        .frame(height: 20)
    }
}

/* ---------- wykresy ---------- */

struct DailyChart: View {
    let days: [Date]
    let values: [Double]
    let color: Color
    let format: (Double) -> String

    var body: some View {
        Chart(days.indices, id: \.self) { i in
            BarMark(
                x: .value("Dzień", days[i], unit: .day),
                y: .value("Wartość", values[i])
            )
            .foregroundStyle(Fmt.cal.isDateInToday(days[i]) ? color.opacity(0.55) : color)
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
    }
}

struct CategoryBars: View {
    let labels: [String]
    let values: [Double]
    let color: Color
    let format: (Double) -> String
    var showValues = false
    var labelEvery = 1
    var height: CGFloat = 140

    var body: some View {
        let maxV = values.max() ?? 0
        let shown = labels.enumerated().filter { $0.offset % labelEvery == 0 }.map { $0.element }
        Chart(values.indices, id: \.self) { i in
            BarMark(
                x: .value("Kategoria", labels[i]),
                y: .value("Wartość", values[i])
            )
            .foregroundStyle(maxV > 0 && values[i] == maxV ? color : color.opacity(0.7))
            .annotation(position: .top) {
                if showValues && values[i] > 0 {
                    Text(format(values[i])).font(.system(size: 9, design: .monospaced)).foregroundStyle(.secondary)
                }
            }
        }
        .chartXAxis {
            AxisMarks(values: shown) { _ in
                AxisValueLabel()
            }
        }
        .chartYAxis(.hidden)
        .frame(height: height)
    }
}

struct HeatMapView: View {
    let heat: [[Double]]
    let color: Color

    var body: some View {
        let maxV = heat.flatMap { $0 }.max() ?? 0
        VStack(alignment: .leading, spacing: 2) {
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
            ForEach(0..<7, id: \.self) { d in
                HStack(spacing: 2) {
                    Text(Stats.weekdays[d])
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .frame(width: 22, alignment: .leading)
                    ForEach(0..<24, id: \.self) { h in
                        RoundedRectangle(cornerRadius: 2)
                            .fill(cellColor(heat[d][h], maxV))
                            .frame(maxWidth: .infinity)
                            .frame(height: 16)
                    }
                }
            }
        }
    }

    private func cellColor(_ v: Double, _ maxV: Double) -> Color {
        if v <= 0 || maxV <= 0 { return Color(.tertiarySystemFill) }
        return color.opacity(0.15 + 0.85 * v / maxV)
    }
}
