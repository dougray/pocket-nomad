import SwiftUI

extension AppTab: CaseIterable, Identifiable {
    static let allCases: [AppTab] = [.survive, .ask, .library, .map, .mesh]

    var id: Self { self }

    var title: String {
        switch self {
        case .survive: "Survive"
        case .ask: "Ask"
        case .library: "Library"
        case .map: "Map"
        case .mesh: "Mesh"
        }
    }

    var symbol: String {
        switch self {
        case .survive: "cross.case.fill"
        case .ask: "bubble.left.and.text.bubble.right.fill"
        case .library: "books.vertical.fill"
        case .map: "map.fill"
        case .mesh: "antenna.radiowaves.left.and.right"
        }
    }

    /// Cmd-1 … Cmd-5, in sidebar order.
    var shortcutKey: Character { Character(String((Self.allCases.firstIndex(of: self) ?? 0) + 1)) }

    /// Where Cmd-F searches: sections without a search field send it to Survive,
    /// which searches the guides and every book.
    var searchSection: AppTab {
        switch self {
        case .survive, .ask, .library: self
        case .map, .mesh: .survive
        }
    }
}

/// What the iPad sidebar can select: a section, or one book under Library.
enum PadSidebarItem: Hashable {
    case section(AppTab)
    case book(String)
}

/// Navigation state for one window. On iPad it drives the split view: the section,
/// the open book, and each section's detail column. Links in the list columns call
/// `show(_:)` through `DetailLink`, so what they open lands beside them instead of
/// replacing the list. The iPhone layout only uses `section`.
@MainActor @Observable
final class PadNavigator {
    var section: AppTab
    /// The book open in the Library columns; nil shows every book.
    private(set) var book: String?
    var showSOS = false
    var showSettings = false
    /// Bumped by Cmd-F; the visible search field takes focus when it changes.
    private(set) var searchRequest = 0
    /// Cmd-F that no search field has taken yet (it may switch sections, so the
    /// field only appears after the request).
    private(set) var searchPending = false

    private struct Detail {
        var path = NavigationPath()
        var selected: AnyHashable?
    }
    private var details: [AppTab: Detail] = [:]

    /// Shown at the root of a section's detail column. Opening it again just pops back to it.
    static func rootValue(for tab: AppTab) -> AnyHashable? {
        tab == .mesh ? AnyHashable(MeshRoute.chat(.channel(0))) : nil
    }

    init(section: AppTab = LaunchScreen.tab, launch: (String, String)? = LaunchScreen.parts()) {
        self.section = section
        guard let (kind, arg) = launch else { return }
        switch kind {
        case "guide": show(SourceRef.guide(path: arg), in: .survive)
        case "tool": if let tool = ToolKind(rawValue: arg) { show(tool, in: .survive) }
        case "category": if let c = GuideStore.categories.first(where: { $0.id == arg }) { show(c, in: .survive) }
        case "settings": showSettings = true
        default: break
        }
    }

    var sidebarSelection: PadSidebarItem {
        get { if section == .library, let book { .book(book) } else { .section(section) } }
        set {
            switch newValue {
            case .section(let tab):
                if tab == .library { closeBook() }
                section = tab
            case .book(let id): openBook(id)
            }
        }
    }

    func openBook(_ id: String) {
        section = .library
        guard book != id else { return }
        book = id
        details[.library] = Detail()
    }

    func closeBook() {
        book = nil
        details[.library] = Detail()
    }

    /// Opens a value in the current section's detail column, replacing what was there.
    /// In Library a book ID (a `String`) opens that book in the columns instead.
    func show<V: Hashable>(_ value: V) {
        if section == .library, let id = value as? String { return openBook(id) }
        show(value, in: section)
    }

    func show<V: Hashable>(_ value: V, in tab: AppTab) {
        var detail = Detail()
        if AnyHashable(value) != Self.rootValue(for: tab) { detail.path.append(value) }
        detail.selected = AnyHashable(value)
        details[tab] = detail
    }

    /// Pushes onto the current detail column, keeping what is under it.
    func push<V: Hashable>(_ value: V) {
        details[section, default: Detail()].path.append(value)
    }

    func clearDetail(_ tab: AppTab) { details[tab] = Detail() }

    func selected(in tab: AppTab) -> AnyHashable? { details[tab]?.selected }

    func depth(in tab: AppTab) -> Int { details[tab]?.path.count ?? 0 }

    func path(for tab: AppTab) -> Binding<NavigationPath> {
        Binding(
            get: { self.details[tab]?.path ?? NavigationPath() },
            set: { path in
                self.details[tab, default: Detail()].path = path
                // Back to the root: nothing is selected in the list any more.
                if path.isEmpty { self.details[tab]?.selected = Self.rootValue(for: tab) }
            })
    }

    func select(_ tab: AppTab) { sidebarSelection = .section(tab) }

    func requestSearch() {
        section = section.searchSection
        searchRequest += 1
        searchPending = true
    }

    /// True once per Cmd-F, for the search field that takes focus.
    func takeSearchRequest() -> Bool {
        defer { searchPending = false }
        return searchPending
    }
}

private struct DetailNavigatorKey: EnvironmentKey {
    static var defaultValue: PadNavigator? { nil }
}

private struct PadNavigatorFocusKey: FocusedValueKey {
    typealias Value = PadNavigator
}

extension EnvironmentValues {
    /// Set only on the iPad's list columns: links there open beside the list.
    var detailNavigator: PadNavigator? {
        get { self[DetailNavigatorKey.self] }
        set { self[DetailNavigatorKey.self] = newValue }
    }
}

extension FocusedValues {
    var padNavigator: PadNavigator? {
        get { self[PadNavigatorFocusKey.self] }
        set { self[PadNavigatorFocusKey.self] = newValue }
    }
}

/// A `NavigationLink(value:)` that, in an iPad list column, opens its value in the
/// detail column beside it. Everywhere else (all of iPhone) it is a plain link.
struct DetailLink<Value: Hashable, Label: View>: View {
    @Environment(\.detailNavigator) private var navigator
    let value: Value
    @ViewBuilder let label: () -> Label

    init(value: Value, @ViewBuilder label: @escaping () -> Label) {
        self.value = value
        self.label = label
    }

    var body: some View {
        if let navigator {
            Button { navigator.show(value) } label: { label() }
        } else {
            NavigationLink(value: value, label: label)
        }
    }
}

/// Gives the nearest search field focus when Cmd-F is pressed (iPadOS 18 and later).
struct FocusSearchOnRequest: ViewModifier {
    @Environment(\.detailNavigator) private var navigator
    @FocusState private var focused: Bool

    func body(content: Content) -> some View {
        if #available(iOS 18, *) {
            content
                .searchFocused($focused)
                .onChange(of: navigator?.searchRequest) { focusIfRequested() }
                .task {
                    // A field that appeared because of the request (Cmd-F from Map).
                    try? await Task.sleep(for: .milliseconds(400))
                    focusIfRequested()
                }
        } else {
            content
        }
    }

    private func focusIfRequested() {
        if navigator?.takeSearchRequest() == true { focused = true }
    }
}

extension View {
    func focusSearchOnRequest() -> some View { modifier(FocusSearchOnRequest()) }
}

/// Go menu (iPadOS menu bar and the Cmd-hold shortcut list) for the focused window.
struct PadCommands: Commands {
    @FocusedValue(\.padNavigator) private var navigator

    var body: some Commands {
        CommandMenu("Go") {
            ForEach(AppTab.allCases) { tab in
                Button(tab.title) { navigator?.select(tab) }
                    .keyboardShortcut(KeyEquivalent(tab.shortcutKey), modifiers: .command)
            }
            Divider()
            Button("Search") { navigator?.requestSearch() }
                .keyboardShortcut("f", modifiers: .command)
            // Shift as well, so a stray Cmd-S never starts flashing the screen.
            Button("SOS Light") { navigator?.showSOS = true }
                .keyboardShortcut("s", modifiers: [.command, .shift])
        }
    }
}
