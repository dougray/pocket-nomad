import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @Environment(ContentStore.self) private var content
    @State private var query = ""
    @State private var hits: [SearchHit] = []
    @State private var showPacks = LaunchScreen.parts()?.0 == "packs"
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if !query.isEmpty {
                    ScrollView { SearchResultsList(hits: hits, emptyText: "No matches for “\(query)”.").padding() }
                } else if content.books.isEmpty {
                    ContentUnavailableView {
                        Label("No books yet", systemImage: "books.vertical")
                    } description: {
                        Text("Download offline references like WikiMed and the Post-Disaster Library while you have Wi-Fi. They keep working with no signal.")
                    } actions: {
                        Button("Get content") { showPacks = true }.buttonStyle(.borderedProminent)
                    }
                } else {
                    bookList
                }
            }
            .navigationTitle("Library")
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search every book")
            .task(id: query) {
                guard !query.isEmpty else { hits = []; return }
                try? await Task.sleep(for: .milliseconds(250))
                hits = await SearchService.shared.searchArticles(query, limit: 30)
            }
            .toolbar {
                Button { showPacks = true } label: { Label("Get content", systemImage: "arrow.down.circle") }
            }
            .sheet(isPresented: $showPacks) { PacksView() }
            .navigationDestination(for: SourceRef.self) { SourceView(ref: $0) }
            .navigationDestination(for: String.self) { BookHomeView(bookID: $0) }
            .onChange(of: content.books.count) {
                // Debug launch `-screen book` opens the first book once it has loaded.
                // -screen book opens the first book; -screen book:<text> the first whose file name contains it.
                if let launch = LaunchScreen.parts(), launch.0 == "book", path.isEmpty,
                   let book = content.books.first(where: { launch.1.isEmpty || $0.url.lastPathComponent.contains(launch.1) }) {
                    path.append(book.id)
                }
            }
        }
    }

    private var bookList: some View {
        List {
            Section {
                ForEach(content.books) { book in
                    NavigationLink(value: book.id) { BookRow(book: book) }
                        .swipeActions {
                            Button("Delete", role: .destructive) { Task { await content.delete(book.url) } }
                        }
                }
            } footer: {
                Text("Add .zim files from Kiwix with the Files app, or drag them onto Pocket Nomad in Finder on a Mac.")
            }
            if let error = content.lastError {
                Section { Text(error).foregroundStyle(Theme.danger) }
            }
        }
    }
}

private struct BookRow: View {
    @Environment(ContentStore.self) private var content
    let book: Book

    var body: some View {
        HStack(spacing: 12) {
            BookIcon(book: book)
            VStack(alignment: .leading, spacing: 3) {
                Text(book.title).font(.headline)
                if !book.summary.isEmpty { Text(book.summary).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                HStack(spacing: 8) {
                    Text(book.size.byteString)
                    Text(book.articleCount == 1 ? "1 article" : "\(book.articleCount.formatted()) articles")
                    if let progress = content.indexProgress[book.id] {
                        Text("Indexing \(Int(progress * 100))%").foregroundStyle(Theme.accent)
                    } else if book.indexURL == nil {
                        Text("Title search only").foregroundStyle(.orange)
                    }
                }
                .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct BookIcon: View {
    let book: Book
    var body: some View {
        Group {
            if let data = book.archive.illustration(size: 48), let image = UIImage(data: data) {
                Image(uiImage: image).resizable().interpolation(.high)
            } else {
                Image(systemName: "book.closed.fill").resizable().scaledToFit().padding(8).foregroundStyle(Theme.accent)
            }
        }
        .frame(width: 44, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

/// A book's main page, with a title search for that book.
struct BookHomeView: View {
    @Environment(ContentStore.self) private var content
    let bookID: String
    @State private var query = ""
    @State private var results: [ZIMEntryHit] = []

    struct ZIMEntryHit: Identifiable, Hashable { let id: String; let title: String }

    var body: some View {
        if let book = content.book(id: bookID) {
            Group {
                if query.isEmpty {
                    BookReaderView(bookID: bookID, path: book.archive.mainPagePath ?? "")
                } else {
                    List(results) { hit in
                        NavigationLink(value: SourceRef.book(id: bookID, path: hit.id)) { Text(hit.title) }
                    }
                }
            }
            .navigationTitle(book.title)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Find an article by title")
            .task(id: query) {
                guard !query.isEmpty else { results = []; return }
                let archive = book.archive
                let q = query
                results = await Task.detached {
                    archive.titleSearch(q, limit: 50).map { ZIMEntryHit(id: $0.path, title: $0.title) }
                }.value
            }
            .toolbar {
                if book.indexURL == nil && content.indexProgress[book.id] == nil {
                    Menu {
                        Button("Build full-text search index") { content.enqueueIndex(book.id) }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
        } else {
            ContentUnavailableView("Book not found", systemImage: "book.closed")
        }
    }
}

struct BookReaderView: View {
    let bookID: String
    let path: String
    @State private var title = ""
    @State private var canGoBack = false
    @State private var back = 0

    var body: some View {
        NomadWebView(url: NomadSchemeHandler.bookURL(bookID, path: path), title: $title, canGoBack: $canGoBack, backTrigger: back)
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if canGoBack {
                    ToolbarItem(placement: .bottomBar) {
                        Button { back += 1 } label: { Label("Back", systemImage: "chevron.backward") }
                    }
                }
            }
    }
}
