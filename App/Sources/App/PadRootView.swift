import SwiftUI

/// The iPad layout (regular width): a sidebar of sections and books, a list column,
/// and a detail column that opens what the list points at.
///
/// - Survive: every guide by category | the open guide (the Survive home when none is).
/// - Ask: questions and answers | the source, opened next to the answer.
/// - Library: books (sidebar) | articles in the book | the reader.
/// - Map: the mesh (status, chats, nodes) | the map with node positions and pins.
/// - Mesh: the mesh home | the chat or node list.
struct PadRootView: View {
    @Environment(ContentStore.self) private var content
    @Bindable var nav: PadNavigator
    @State private var columns = NavigationSplitViewVisibility.all

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            sidebar
                .navigationSplitViewColumnWidth(min: 220, ideal: 250, max: 300)
        } content: {
            listColumn
                .navigationSplitViewColumnWidth(min: 320, ideal: nav.section == .ask ? 440 : 360, max: 520)
        } detail: {
            detailColumn
        }
        .navigationSplitViewStyle(.balanced)
        .sheet(isPresented: $nav.showSettings) { SettingsView() }
        .onChange(of: content.books.count) {
            // Debug launch: -screen book opens the first book, book:<text> the first whose file name contains it.
            if let launch = LaunchScreen.parts(), launch.0 == "book", nav.book == nil,
               let book = content.books.first(where: { launch.1.isEmpty || $0.url.lastPathComponent.contains(launch.1) }) {
                nav.openBook(book.id)
            }
        }
    }

    // MARK: Sidebar

    private var sidebar: some View {
        List(selection: Binding<PadSidebarItem?>(get: { nav.sidebarSelection },
                                                 set: { if let item = $0 { nav.sidebarSelection = item } })) {
            Section {
                ForEach(AppTab.allCases) { tab in
                    Label(tab.title, systemImage: tab.symbol)
                        .badge(tab == .mesh ? MeshService.shared.totalUnread : 0)
                        .tag(PadSidebarItem.section(tab))
                }
            }
            if !content.books.isEmpty {
                Section("Books") {
                    ForEach(content.books) { book in
                        Label(book.title, systemImage: "book.closed")
                            .lineLimit(1)
                            .tag(PadSidebarItem.book(book.id))
                    }
                }
            }
        }
        .navigationTitle("Nomad")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { nav.showSettings = true } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel("Settings")
            }
        }
    }

    // MARK: List column

    @ViewBuilder
    private var listColumn: some View {
        Group {
            switch nav.section {
            case .survive: PadGuideList(selection: selection(SourceRef.self))
            case .ask: AskView()
            case .library:
                if let id = nav.book, let book = content.book(id: id) {
                    PadArticleList(book: book, selection: selection(SourceRef.self))
                } else {
                    LibraryView()
                }
            // Beside the map: the mesh, so pins and node positions stay in view while chatting.
            // Its links push within this column; the map keeps the detail column.
            case .map: MeshView().environment(\.detailNavigator, nil)
            case .mesh: MeshView()
            }
        }
        .environment(\.detailNavigator, nav)
        .id(nav.section)
    }

    /// A list column's selection, mirrored from what its section's detail column shows.
    private func selection<V: Hashable>(_: V.Type) -> Binding<V?> {
        let tab = nav.section
        return Binding(
            get: { nav.selected(in: tab)?.base as? V },
            set: { value in
                if let value { nav.show(value) } else { nav.clearDetail(tab) }
            })
    }

    // MARK: Detail column

    @ViewBuilder
    private var detailColumn: some View {
        if nav.section == .map {
            OfflineMapView()
        } else {
            NavigationStack(path: nav.path(for: nav.section)) {
                detailRoot
                    .nomadDestinations()
                    .navigationDestination(for: MeshRoute.self) { route in
                        route.destination { nav.push($0) }
                    }
            }
            .id(nav.section)
        }
    }

    @ViewBuilder
    private var detailRoot: some View {
        switch nav.section {
        case .survive:
            ScrollView { SurviveHome().frame(maxWidth: 820).frame(maxWidth: .infinity) }
                .navigationTitle("Survive")
        case .ask:
            ContentUnavailableView {
                Label("Sources open here", systemImage: "doc.text.magnifyingglass")
            } description: {
                Text("Ask a question and its best source opens beside the answer. Tap any numbered source to read it.")
            }
        case .library:
            if let id = nav.book, let book = content.book(id: id) {
                BookReaderView(bookID: id, path: book.archive.mainPagePath ?? "").id(id)
            } else {
                ContentUnavailableView {
                    Label("Choose a book", systemImage: "books.vertical")
                } description: {
                    Text("Pick a book in the sidebar to see its articles here, or search every book at once.")
                }
            }
        case .mesh:
            MeshChatView(conversation: .channel(0))
        case .map:
            EmptyView()
        }
    }
}

/// Survive's list column on iPad: every guide, by category, with search.
struct PadGuideList: View {
    @Binding var selection: SourceRef?
    @State private var query = ""
    @State private var hits: [SearchHit] = []

    var body: some View {
        Group {
            if query.isEmpty {
                List(selection: $selection) {
                    ForEach(GuideStore.categories) { category in
                        Section {
                            ForEach(category.guides) { guide in
                                GuideRow(guide: guide).tag(SourceRef.guide(path: guide.file))
                            }
                        } header: {
                            Label(category.title, systemImage: category.symbol)
                        }
                    }
                }
            } else {
                ScrollView {
                    SearchResultsList(hits: hits, emptyText: "No guides match “\(query)”.").padding(.horizontal)
                }
            }
        }
        .navigationTitle("Guides")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search guides and library")
        .focusSearchOnRequest()
        .articleSearch(query, into: $hits, limit: 25, delay: .milliseconds(200))
    }
}

/// Library's middle column on iPad: the book's main page and a title search.
struct PadArticleList: View {
    @Environment(\.detailNavigator) private var navigator
    let book: Book
    @Binding var selection: SourceRef?
    @State private var query = ""
    @State private var results: [BookHomeView.ZIMEntryHit] = []
    @State private var browse: [BookHomeView.ZIMEntryHit] = []
    private static let browseLimit = 200

    /// Titles then text hits, each article once, in that order.
    static func merge(_ lists: [BookHomeView.ZIMEntryHit]...) -> [BookHomeView.ZIMEntryHit] {
        var seen = Set<String>()
        return lists.joined().filter { seen.insert($0.id).inserted }
    }

    private var mainPage: SourceRef { .book(id: book.id, path: book.archive.mainPagePath ?? "") }

    var body: some View {
        List(selection: Binding<SourceRef?>(get: { selection ?? mainPage },
                                            set: { selection = $0 == mainPage ? nil : $0 })) {
            Section {
                Label("Main page", systemImage: "house").tag(mainPage)
            }
            if !query.isEmpty {
                Section(results.isEmpty ? "No titles match" : "Articles") {
                    ForEach(results) { hit in
                        Text(hit.title).tag(SourceRef.book(id: book.id, path: hit.id))
                    }
                }
            } else if browse.isEmpty {
                Section {} footer: {
                    Text("Search to find this book’s articles and documents.")
                }
            } else {
                Section {
                    ForEach(browse) { hit in
                        Text(hit.title).tag(SourceRef.book(id: book.id, path: hit.id))
                    }
                } header: {
                    Text("Articles A–Z")
                } footer: {
                    if book.articleCount > browse.count + 1 {
                        Text("The first \(browse.count) of \(book.articleCount.formatted()). Search to find the rest.")
                    }
                }
            }
        }
        .navigationTitle(book.title)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Find an article by title")
        .focusSearchOnRequest()
        .task(id: query) {
            guard !query.isEmpty else { results = []; return }
            try? await Task.sleep(for: .milliseconds(200))
            // Titles first, then this book's full-text hits (its PDFs and documents).
            let titles = await BookHomeView.titleSearch(book, query)
            let text = await SearchService.shared.searchArticles(query, limit: 60).compactMap { hit -> BookHomeView.ZIMEntryHit? in
                guard case .book(let id, let path) = hit.ref, id == book.id else { return nil }
                return .init(id: path, title: hit.title)
            }
            results = Self.merge(titles, text)
        }
        .task(id: book.id) {
            let main = book.archive.mainPagePath
            browse = await BookHomeView.firstArticles(book, limit: Self.browseLimit + 1).filter { $0.id != main }
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { navigator?.closeBook() } label: { Label("All books", systemImage: "chevron.backward") }
            }
        }
    }
}
