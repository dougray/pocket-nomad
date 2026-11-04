import SwiftUI
import UniformTypeIdentifiers

/// Get content while there's still Wi-Fi: books, AI models and map regions.
struct PacksView: View {
    @Environment(ContentStore.self) private var content
    @Environment(\.dismiss) private var dismiss
    @State private var downloads = DownloadManager.shared
    @State private var recommended: [(CatalogItem, String)] = []
    @State private var bigger: [(CatalogItem, String)] = []
    @State private var loading = true
    @State private var offline = false
    @State private var query = ""
    @State private var results: [CatalogItem] = []
    @State private var importing = false
    @State private var confirmBulk = false

    var body: some View {
        NavigationStack {
            List {
                storageSection
                if !downloads.active.isEmpty { activeSection }
                if offline {
                    Section {
                        Label("No internet right now. You can still add files with the Files app or from a Mac.", systemImage: "wifi.slash")
                    }
                }
                if !query.isEmpty {
                    Section("Kiwix catalog") {
                        ForEach(results) { row($0, note: $0.summary) }
                    }
                } else {
                    Section {
                        if loading { ProgressView() }
                        if !recommended.isEmpty {
                            Button("Download all essentials (\(recommendedSize.byteString))") { confirmBulk = true }
                                .font(.headline)
                        }
                        ForEach(recommended, id: \.0.id) { row($0.0, note: $0.1) }
                    } header: { Text("Survival essentials") } footer: {
                        Text("From the free Kiwix library. Downloads continue in the background.")
                    }
                    pubmedSection
                    if ModelAvailability.llamaSupported {
                        Section {
                            ForEach(ModelCatalog.items) { row($0, note: $0.summary) }
                        } header: { Text("AI models") } footer: {
                            Text(ModelAvailability.appleStatus == nil
                                 ? "This iPhone already has Apple's on-device model. A downloaded model is optional."
                                 : "Lets Ask write answers from your library instead of only listing passages.")
                        }
                    }
                    Section("Bigger references") {
                        ForEach(bigger, id: \.0.id) { row($0.0, note: $0.1) }
                    }
                    mapsSection
                }
                Section {
                    Button { importing = true } label: { Label("Import from Files…", systemImage: "folder") }
                }
            }
            .navigationTitle("Get Content")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "Search the Kiwix library")
            .task(id: query) {
                guard !query.isEmpty else { results = []; return }
                try? await Task.sleep(for: .milliseconds(400))
                results = (try? await KiwixCatalog.search(query)) ?? []
            }
            .task { await loadCatalog() }
            .toolbar { Button("Done") { dismiss() } }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.data], allowsMultipleSelection: true) { result in
                if case .success(let urls) = result {
                    Task { for url in urls { await content.importFile(at: url) } }
                }
            }
            .confirmationDialog("Download \(recommendedSize.byteString) of survival references?", isPresented: $confirmBulk, titleVisibility: .visible) {
                Button("Download all") { recommended.map(\.0).filter { !installed($0) }.forEach(downloads.start) }
            } message: {
                Text("Use Wi-Fi if you can. Make sure the phone has room to spare.")
            }
        }
    }

    private var recommendedSize: Int64 { recommended.map(\.0).filter { !installed($0) }.reduce(0) { $0 + $1.size } }

    private var storageSection: some View {
        Section {
            let free = (try? URL.documentsDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? 0
            LabeledContent("Free space on phone", value: Int64(free).byteString)
            LabeledContent("Books", value: "\(content.books.count) · \(content.books.reduce(0) { $0 + $1.size }.byteString)")
            LabeledContent("Maps", value: "\(content.maps.count) · \(content.maps.reduce(0) { $0 + $1.size }.byteString)")
            LabeledContent("AI models", value: "\(content.models.count) · \(content.models.reduce(0) { $0 + $1.size }.byteString)")
            if let error = downloads.lastError { Text(error).font(.footnote).foregroundStyle(Theme.danger) }
        }
    }

    private var activeSection: some View {
        Section("Downloading") {
            ForEach(downloads.active.values.sorted { $0.title < $1.title }) { a in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(a.title).font(.subheadline.weight(.semibold))
                        Spacer()
                        Button(role: .destructive) { downloads.cancel(a.id) } label: { Image(systemName: "xmark.circle.fill") }
                            .buttonStyle(.borderless)
                    }
                    ProgressView(value: a.fraction)
                    Text("\(a.received.byteString) of \(a.expected > 0 ? a.expected.byteString : "?")").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    /// PubMed has no Kiwix book, so this pack is built on a Mac from NCBI's free service.
    private var pubmedSection: some View {
        Section {
            if let book = content.books.first(where: { $0.url.lastPathComponent.hasPrefix("pocketnomad_en_pubmed-survival") }) {
                LabeledContent("PubMed: Survival Medicine", value: book.size.byteString)
            } else {
                VStack(alignment: .leading, spacing: 3) {
                    Text("PubMed: Survival Medicine").font(.headline)
                    Text("About 9,600 guideline and systematic-review abstracts on emergency, wilderness and disaster medicine (about 55 MB with its search index).")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        } header: { Text("Research") } footer: {
            Text("Build it on a Mac with tools/nomad-packs.sh pubmed, then tools/nomad-packs.sh install with the iPhone connected. It is made for your own use, because publishers may own the abstracts.")
        }
    }

    private var mapsSection: some View {
        Section {
            ForEach(content.maps) { map in
                LabeledContent(map.name, value: map.size.byteString)
                    .swipeActions { Button("Delete", role: .destructive) { Task { await content.delete(map.url) } } }
            }
            ForEach(content.models) { model in
                LabeledContent(model.name, value: model.size.byteString)
                    .swipeActions { Button("Delete", role: .destructive) { Task { await content.delete(model.url) } } }
            }
        } header: { Text("Map regions") } footer: {
            Text("A world overview map is built in. For street-level maps of your area, make a region file on a Mac with tools/nomad-packs.sh (it uses the free Protomaps OpenStreetMap build), then drag it onto Pocket Nomad in Finder or import it with the Files app.")
        }
    }

    private func installed(_ item: CatalogItem) -> Bool {
        let path = URL.documentsDirectory.appending(path: item.fileName).path
        if FileManager.default.fileExists(atPath: path) { return true }
        // A newer or older edition of the same book counts as installed.
        let stem = item.fileName.replacingOccurrences(of: #"_\d{4}-\d{2}\.zim$"#, with: "", options: .regularExpression)
        return content.books.contains { $0.url.lastPathComponent.hasPrefix(stem + "_") }
    }

    private func row(_ item: CatalogItem, note: String) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title).font(.headline)
                Text(note).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                Text(item.size.byteString).font(.caption2).foregroundStyle(.secondary)
            }
            Spacer()
            if installed(item) {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).accessibilityLabel("Installed")
            } else if downloads.isDownloading(item) {
                ProgressView()
            } else {
                Button { downloads.start(item) } label: { Image(systemName: "arrow.down.circle.fill").font(.title2) }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Download \(item.title)")
            }
        }
    }

    private func loadCatalog() async {
        loading = true
        defer { loading = false }
        func resolve(_ list: [(name: String, flavour: String?, note: String)]) async -> [(CatalogItem, String)] {
            await withTaskGroup(of: (Int, CatalogItem?, String).self) { group in
                for (i, entry) in list.enumerated() {
                    group.addTask { (i, try? await KiwixCatalog.lookup(name: entry.name, flavour: entry.flavour), entry.note) }
                }
                var out: [(Int, CatalogItem, String)] = []
                for await (i, item, note) in group { if let item { out.append((i, item, note)) } }
                return out.sorted { $0.0 < $1.0 }.map { ($0.1, $0.2) }
            }
        }
        recommended = await resolve(KiwixCatalog.recommended)
        offline = recommended.isEmpty
        bigger = await resolve(KiwixCatalog.bigger)
    }
}
