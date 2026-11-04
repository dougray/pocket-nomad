import SwiftUI

/// The urgent guides, read from the same HTML the phone ships (bundled into the
/// watch app at build time) so they work with no phone.
enum FirstAidLibrary {
    static let cards: [FirstAidCard] = {
        guard let root = Bundle.main.url(forResource: "Guides", withExtension: nil) else { return [] }
        return FirstAidSteps.load(guidesRoot: root)
    }()

    static var byCategory: [(String, [FirstAidCard])] {
        var order: [String] = []
        var groups: [String: [FirstAidCard]] = [:]
        for card in cards {
            if groups[card.category] == nil { order.append(card.category) }
            groups[card.category, default: []].append(card)
        }
        return order.map { ($0, groups[$0] ?? []) }
    }
}

struct FirstAidListView: View {
    var body: some View {
        List {
            ForEach(FirstAidLibrary.byCategory, id: \.0) { category, cards in
                Section(category) {
                    ForEach(cards) { card in
                        NavigationLink(value: WatchRoute.guide(card.id)) {
                            Text(card.title).font(.headline)
                        }
                    }
                }
            }
            if FirstAidLibrary.cards.isEmpty {
                Text("The guides are missing from this build.").foregroundStyle(.secondary)
            }
        }
        .navigationTitle("First aid")
    }
}

struct FirstAidDetailView: View {
    let card: FirstAidCard

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("Do this now").font(.caption.weight(.bold)).foregroundStyle(WatchTheme.accent)
                ForEach(Array(card.steps.enumerated()), id: \.offset) { i, step in
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text("\(i + 1)")
                            .font(.system(.footnote, design: .rounded).weight(.heavy))
                            .foregroundStyle(.black)
                            .frame(width: 20, height: 20)
                            .background(WatchTheme.accent, in: Circle())
                        Text(step).font(.footnote)
                    }
                    .accessibilityElement(children: .combine)
                }
                ForEach(card.warnings, id: \.self) { warning in
                    Text(warning)
                        .font(.caption2)
                        .padding(6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(WatchTheme.danger.opacity(0.25), in: RoundedRectangle(cornerRadius: 8))
                }
                Text("The full guide, with pictures and details, is on your iPhone in Survive.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(card.title)
    }
}
