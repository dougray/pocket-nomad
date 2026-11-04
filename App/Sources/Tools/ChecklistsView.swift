import SwiftUI

/// Checked items and the user's own additions, per checklist.
struct ChecklistState: Codable {
    var checked: [String: Set<String>] = [:]
    var custom: [String: [String]] = [:]

    static let url = URL.applicationSupportDirectory.appending(path: "checklists.json")

    static func load() -> ChecklistState {
        guard let data = try? Data(contentsOf: url) else { return ChecklistState() }
        return (try? JSONDecoder().decode(ChecklistState.self, from: data)) ?? ChecklistState()
    }

    func save() {
        try? FileManager.default.createDirectory(at: URL.applicationSupportDirectory, withIntermediateDirectories: true)
        try? JSONEncoder().encode(self).write(to: Self.url, options: .atomic)
    }
}

struct ChecklistsView: View {
    @State private var state = ChecklistState.load()

    var body: some View {
        List(GuideStore.checklists) { list in
            NavigationLink {
                ChecklistDetailView(list: list, state: $state)
            } label: {
                let total = list.items.count + (state.custom[list.id]?.count ?? 0)
                let done = state.checked[list.id]?.count ?? 0
                HStack {
                    Image(systemName: list.symbol).foregroundStyle(Theme.accent).frame(width: 32)
                    Text(list.title).font(.headline)
                    Spacer()
                    Text("\(done)/\(total)").foregroundStyle(done == total && total > 0 ? .green : .secondary).monospacedDigit()
                }
                .padding(.vertical, 6)
            }
        }
        .navigationTitle("Checklists")
    }
}

private struct ChecklistDetailView: View {
    let list: Checklist
    @Binding var state: ChecklistState
    @State private var newItem = ""

    private var items: [String] { list.items + (state.custom[list.id] ?? []) }

    var body: some View {
        List {
            ForEach(items, id: \.self) { item in
                let isChecked = state.checked[list.id]?.contains(item) ?? false
                Button {
                    var set = state.checked[list.id] ?? []
                    if isChecked { set.remove(item) } else { set.insert(item) }
                    state.checked[list.id] = set
                    state.save()
                } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                            .font(.title2).foregroundStyle(isChecked ? .green : .secondary)
                        Text(item).foregroundStyle(isChecked ? .secondary : .primary).strikethrough(isChecked)
                    }
                    .padding(.vertical, 4)
                }
            }
            Section {
                HStack {
                    TextField("Add your own item", text: $newItem).onSubmit(add)
                    Button("Add", action: add).disabled(newItem.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                Button("Uncheck everything", role: .destructive) {
                    state.checked[list.id] = []
                    state.save()
                }
            }
        }
        .navigationTitle(list.title)
    }

    private func add() {
        let item = newItem.trimmingCharacters(in: .whitespaces)
        guard !item.isEmpty, !items.contains(item) else { return }
        state.custom[list.id, default: []].append(item)
        state.save()
        newItem = ""
    }
}
