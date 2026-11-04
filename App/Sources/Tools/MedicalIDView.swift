import SwiftUI

struct MedicalInfo: Codable, Equatable {
    struct Contact: Codable, Equatable, Identifiable {
        var id = UUID()
        var name = ""
        var relation = ""
        var phone = ""
    }
    var name = ""
    var birthDate = ""
    var bloodType = ""
    var allergies = ""
    var medications = ""
    var conditions = ""
    var notes = ""
    var contacts: [Contact] = []

    var isEmpty: Bool { self == MedicalInfo() }

    static let url = URL.applicationSupportDirectory.appending(path: "medical-id.json")

    static func load() -> MedicalInfo {
        guard let data = try? Data(contentsOf: url) else { return MedicalInfo() }
        return (try? JSONDecoder().decode(MedicalInfo.self, from: data)) ?? MedicalInfo()
    }

    func save() {
        try? FileManager.default.createDirectory(at: URL.applicationSupportDirectory, withIntermediateDirectories: true)
        try? JSONEncoder().encode(self).write(to: Self.url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

/// What a helper or medic needs to know about you (and your family) when you can't say it.
struct MedicalIDView: View {
    @State private var info = MedicalInfo.load()
    @State private var editing = false

    var body: some View {
        Group {
            if editing || info.isEmpty {
                editor
            } else {
                card
            }
        }
        .navigationTitle("Medical ID")
        .toolbar {
            Button(editing ? "Done" : "Edit") {
                if editing { info.save() }
                editing.toggle()
            }
        }
        .onChange(of: info) { info.save() }
    }

    private var card: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Label("MEDICAL ID", systemImage: "staroflife.fill").font(.headline).foregroundStyle(Theme.danger)
                Text(info.name.isEmpty ? "Name not set" : info.name).font(.largeTitle.bold())
                field("Born", info.birthDate)
                field("Blood type", info.bloodType)
                field("Allergies", info.allergies)
                field("Medications", info.medications)
                field("Conditions", info.conditions)
                field("Notes", info.notes)
                if !info.contacts.isEmpty {
                    Text("EMERGENCY CONTACTS").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    ForEach(info.contacts) { c in
                        VStack(alignment: .leading) {
                            Text("\(c.name)\(c.relation.isEmpty ? "" : " (\(c.relation))")").font(.headline)
                            Text(c.phone).font(.title3.monospacedDigit())
                        }
                    }
                }
                Text("Also set up Medical ID in Apple's Health app so it shows on the lock screen.")
                    .font(.footnote).foregroundStyle(.secondary).padding(.top)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
    }

    @ViewBuilder
    private func field(_ label: String, _ value: String) -> some View {
        if !value.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text(label.uppercased()).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Text(value).font(.title3)
            }
        }
    }

    private var editor: some View {
        Form {
            Section("You") {
                TextField("Full name", text: $info.name)
                TextField("Date of birth", text: $info.birthDate)
                TextField("Blood type (e.g. O+)", text: $info.bloodType)
            }
            Section("Health") {
                TextField("Allergies", text: $info.allergies, axis: .vertical)
                TextField("Medications and doses", text: $info.medications, axis: .vertical)
                TextField("Medical conditions", text: $info.conditions, axis: .vertical)
                TextField("Other notes (organ donor, implants, DNR…)", text: $info.notes, axis: .vertical)
            }
            Section("Emergency contacts") {
                ForEach($info.contacts) { $c in
                    VStack {
                        TextField("Name", text: $c.name)
                        TextField("Relationship", text: $c.relation)
                        TextField("Phone", text: $c.phone).keyboardType(.phonePad)
                    }
                }
                .onDelete { info.contacts.remove(atOffsets: $0) }
                Button("Add contact") { info.contacts.append(.init()) }
            }
            Section {
                Text("Stored only on this phone.").font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}
