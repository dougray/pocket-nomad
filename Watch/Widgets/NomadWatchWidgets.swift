import SwiftUI
import WidgetKit

/// Complications: one tap into the SOS light or the compass. They show no data,
/// so the timeline never needs to refresh.
@main
struct NomadWatchWidgets: WidgetBundle {
    var body: some Widget {
        SOSComplication()
        CompassComplication()
    }
}

struct StaticEntry: TimelineEntry { let date = Date() }

struct StaticProvider: TimelineProvider {
    func placeholder(in context: Context) -> StaticEntry { StaticEntry() }
    func getSnapshot(in context: Context, completion: @escaping (StaticEntry) -> Void) { completion(StaticEntry()) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<StaticEntry>) -> Void) {
        completion(Timeline(entries: [StaticEntry()], policy: .never))
    }
}

private let amber = Color(red: 0.988, green: 0.592, blue: 0.071)
private let danger = Color(red: 0.95, green: 0.26, blue: 0.21)

struct SOSComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "org.nomadpocket.sos", provider: StaticProvider()) { _ in
            ComplicationView(symbol: "sos", title: "SOS", label: "SOS light", tint: danger)
                .widgetURL(URL(string: "nomadpocket-watch://sos"))
        }
        .configurationDisplayName("SOS")
        .description("Opens the flashing SOS light.")
        .supportedFamilies([.accessoryCircular, .accessoryCorner, .accessoryInline, .accessoryRectangular])
    }
}

struct CompassComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "org.nomadpocket.compass", provider: StaticProvider()) { _ in
            ComplicationView(symbol: "location.north.line.fill", title: "Compass", label: "Compass and position", tint: amber)
                .widgetURL(URL(string: "nomadpocket-watch://compass"))
        }
        .configurationDisplayName("Compass")
        .description("Opens the compass and your coordinates.")
        .supportedFamilies([.accessoryCircular, .accessoryCorner, .accessoryInline, .accessoryRectangular])
    }
}

struct ComplicationView: View {
    @Environment(\.widgetFamily) private var family
    let symbol: String
    let title: String
    let label: String
    let tint: Color

    var body: some View {
        Group {
            switch family {
            case .accessoryCircular:
                ZStack {
                    AccessoryWidgetBackground()
                    Image(systemName: symbol).font(.title2.weight(.bold)).foregroundStyle(tint)
                }
            case .accessoryCorner:
                Image(systemName: symbol).font(.title2.weight(.bold)).foregroundStyle(tint)
                    .widgetLabel(title)
            case .accessoryInline:
                Label(title, systemImage: symbol)
            default:
                HStack {
                    Image(systemName: symbol).font(.title2.weight(.bold)).foregroundStyle(tint)
                    VStack(alignment: .leading) {
                        Text(title).font(.headline)
                        Text("Pocket Nomad").font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .containerBackground(.clear, for: .widget)
        .accessibilityLabel(label)
    }
}
