import SwiftUI

struct SurviveView: View {
    @State private var query = ""
    @State private var hits: [SearchHit] = []
    @State private var showSettings = LaunchScreen.parts()?.0 == "settings"
    @State private var path = NavigationPath()


    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                if query.isEmpty {
                    // Keeps lines and tiles a readable size on iPad.
                    home.frame(maxWidth: 820).frame(maxWidth: .infinity)
                } else {
                    SearchResultsList(hits: hits, emptyText: "No guides match “\(query)”.")
                        .padding(.horizontal)
                }
            }
            .navigationTitle("Survive")
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search guides and library")
            .task(id: query) {
                guard !query.isEmpty else { hits = []; return }
                try? await Task.sleep(for: .milliseconds(200))
                hits = await SearchService.shared.searchArticles(query, limit: 25)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Settings")
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .navigationDestination(for: Guide.self) { GuideReaderView(guide: $0) }
            .navigationDestination(for: GuideCategory.self) { GuideListView(category: $0) }
            .navigationDestination(for: SourceRef.self) { SourceView(ref: $0) }
            .navigationDestination(for: ToolKind.self) { $0.destination }
            .onAppear(perform: openLaunchScreen)
        }
    }

    private func openLaunchScreen() {
        guard path.isEmpty, let (kind, arg) = LaunchScreen.parts() else { return }
        switch kind {
        case "tool": if let tool = ToolKind(rawValue: arg) { path.append(tool) }
        case "guide": if let guide = GuideStore.guide(file: arg) { path.append(guide) }
        case "category": if let c = GuideStore.categories.first(where: { $0.id == arg }) { path.append(c) }
        case "query": query = arg
        default: break
        }
    }

    /// The most common life-threatening first-aid topics, kept to one screen.
    private var emergencies: [Guide] {
        Array(GuideStore.urgent.filter { GuideStore.categories.first?.guides.contains($0) ?? false }.prefix(10))
    }

    static let allEmergencies = GuideCategory(id: "emergencies", title: "All Emergencies", symbol: "exclamationmark.triangle.fill",
                                              summary: nil, guides: GuideStore.urgent)

    private var home: some View {
        VStack(alignment: .leading, spacing: 24) {
            if !GuideStore.urgent.isEmpty {
                section("Emergency") {
                    TileGrid(columns: 2, spacing: 10) {
                        ForEach(emergencies) { guide in
                            NavigationLink(value: guide) {
                                HStack(spacing: 10) {
                                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.danger)
                                    Text(guide.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                                        .lineLimit(2).minimumScaleFactor(0.85).multilineTextAlignment(.leading)
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 12)
                                .frame(maxWidth: .infinity, minHeight: 56)
                                .background(Theme.danger.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    NavigationLink(value: Self.allEmergencies) {
                        Label("All emergencies (\(GuideStore.urgent.count))", systemImage: "list.bullet")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.accent)
                    }
                }
            }
            section("Tools") {
                TileGrid {
                    ForEach(ToolKind.allCases) { tool in
                        NavigationLink(value: tool) { TileLabel(title: tool.title, symbol: tool.symbol) }
                            .buttonStyle(.plain)
                    }
                }
            }
            section("Guides") {
                VStack(spacing: 10) {
                    ForEach(GuideStore.categories) { category in
                        NavigationLink(value: category) {
                            HStack(spacing: 14) {
                                Image(systemName: category.symbol)
                                    .font(.title2).foregroundStyle(Theme.accent).frame(width: 36)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(category.title).font(.headline).foregroundStyle(.primary)
                                    Text(category.summary ?? "\(category.guides.count) guides")
                                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                            }
                            .padding(14)
                            .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
                        }
                    }
                }
            }
            Text("These guides don't replace training. If you can reach emergency services, call 911 (or your local number) first.")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .padding()
        .buttonStyle(.plain)
    }

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.title3.bold())
            content()
        }
    }
}

struct GuideListView: View {
    let category: GuideCategory

    var body: some View {
        List(category.guides) { guide in
            NavigationLink(value: guide) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        if guide.urgent == true {
                            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.danger)
                        }
                        Text(guide.title).font(.headline)
                    }
                    Text(guide.summary).font(.subheadline).foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }
        }
        .navigationTitle(category.title)
    }
}

struct GuideReaderView: View {
    let guide: Guide

    var body: some View {
        // The page has its own large heading, so the bar stays empty.
        NomadWebView(url: NomadSchemeHandler.guideURL(guide.file))
            .ignoresSafeArea(edges: .bottom)
            .navigationBarTitleDisplayMode(.inline)
    }
}

/// Opens a search hit or AI citation: a guide or a page in a library book.
struct SourceView: View {
    let ref: SourceRef

    var body: some View {
        switch ref {
        case .guide(let path):
            if let guide = GuideStore.guide(file: path) {
                GuideReaderView(guide: guide)
            } else {
                ContentUnavailableView("Guide not found", systemImage: "questionmark.folder")
            }
        case .book(let id, let path):
            BookReaderView(bookID: id, path: path)
        }
    }
}

struct SearchResultsList: View {
    let hits: [SearchHit]
    let emptyText: String

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 10) {
            if hits.isEmpty {
                Text(emptyText).foregroundStyle(.secondary).padding(.top, 24)
            }
            ForEach(hits) { hit in
                NavigationLink(value: hit.ref) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(hit.sourceName.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(Theme.accent)
                        Text(hit.title).font(.headline).foregroundStyle(.primary)
                        Text((try? AttributedString(markdown: hit.snippet.isEmpty ? hit.body : hit.snippet)) ?? AttributedString(hit.body))
                            .font(.subheadline).foregroundStyle(.secondary).lineLimit(3)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                }
            }
        }
        .buttonStyle(.plain)
    }
}
