import SwiftUI

struct Exchange: Identifiable {
    let id = UUID()
    let question: String
    var sources: [SearchHit] = []
    var answer = ""
    var state: State = .searching
    var modelName: String?

    enum State: Equatable { case searching, answering, done, failed(String) }
}

/// Retrieval first, then generation: the model only sees passages pulled from the
/// guides and the user's library, and is told to cite them. Small on-device models
/// make things up when asked from memory; grounding them is what makes this safe-ish.
@MainActor @Observable
final class AskEngine {
    var exchanges: [Exchange] = []
    private var task: Task<Void, Never>?

    nonisolated static let notFound = "I couldn't find that in your offline library"

    static let instructions = """
    You are Pocket Nomad, an offline emergency and survival assistant on someone's phone. \
    They may have no internet and no help nearby. Answer ONLY from the numbered sources you are given. \
    Cite every fact with its source number in brackets, like [1] or [2]. \
    Only if no source helps at all, say "\(notFound)" and suggest a better search; never say it before an answer. \
    Put urgent actions first as short numbered steps. Use plain words. \
    Only when the question is about an injury or medical emergency, start by saying to call 911 or local emergency services if possible; otherwise don't mention 911. \
    Use only sources about the exact situation asked (for example, drinking water versus cleaning surfaces) and ignore numbers from sources about something else. \
    Don't calculate new amounts; quote the source's own rows and numbers. \
    Copy every number exactly as the source gives it, together with its conditions (such as bleach strength, amount of water, or age), and never mix numbers from different conditions. \
    Sources marked "Survival Guide" were written for this app and checked; when sources disagree on an amount or a step, follow the Survival Guide. \
    Never invent medicine doses, numbers or facts that are not in the sources.
    """

    var isBusy: Bool { exchanges.last.map { $0.state == .searching || $0.state == .answering } ?? false }

    func ask(_ question: String, model: (any AnswerModel)?) {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        // When the app can work out the exact answer itself (a bleach dose), it shows
        // that instead of letting a small model attempt the arithmetic.
        let model = WaterCalculatorView.dose(for: q) == nil ? model : nil
        exchanges.append(Exchange(question: q, modelName: model?.displayName))
        let index = exchanges.count - 1
        task = Task {
            let hits = await SearchService.shared.search(q, limit: 8)
            let picked = Self.pick(hits, budget: model?.sourceBudget ?? 0)
            exchanges[index].sources = model == nil ? hits : picked
            guard let model, !picked.isEmpty else {
                exchanges[index].state = .done
                return
            }
            exchanges[index].state = .answering
            let prompt = Self.prompt(question: q, sources: picked)
            do {
                let answer = try await model.generate(instructions: Self.instructions, prompt: prompt) { [weak self] text in
                    Task { @MainActor in
                        guard let self, self.exchanges.indices.contains(index) else { return }
                        self.exchanges[index].answer = Self.tidy(text)
                    }
                }
                exchanges[index].answer = Self.tidy(answer)
                exchanges[index].state = .done
            } catch is CancellationError {
                exchanges[index].state = .done
            } catch {
                exchanges[index].state = .failed(error.localizedDescription)
            }
        }
    }

    /// Small models sometimes open with the not-found sentence and then answer from
    /// the sources anyway. Drop that sentence when the rest cites a source.
    nonisolated static func tidy(_ answer: String) -> String {
        // Lines that are nothing but citations ("[1], [2], [3]") add nothing.
        let answer = answer.components(separatedBy: "\n").filter { line in
            let t = line.trimmingCharacters(in: .whitespaces)
            return t.isEmpty || t.range(of: #"^(\[\d+\][\s,;.]*)+$"#, options: .regularExpression) == nil
        }.joined(separator: "\n")
        let trimmed = answer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.lowercased().hasPrefix(notFound.lowercased()) else { return answer }
        var rest = trimmed.dropFirst(notFound.count)
        // Skip the rest of that sentence ("… library, but here is what I found:").
        if let end = rest.firstIndex(where: { ".!:\n".contains($0) }) { rest = rest[rest.index(after: end)...] }
        let remainder = rest.trimmingCharacters(in: .whitespacesAndNewlines)
        guard remainder.range(of: #"\[\d+\]"#, options: .regularExpression) != nil else { return answer }
        return remainder
    }

    func stop() { task?.cancel() }

    func clear() {
        stop()
        exchanges = []
    }

    /// Passages scoring under this share of the best hit stay out of the prompt. A small
    /// model blends whatever it is given: asked how to stop severe bleeding with "Survival
    /// Priorities" (a third of the top score) among the sources, Qwen3 1.7B turned its STOP
    /// acronym into "1. Stop – do not touch the wound". Same rule as the Android app.
    nonisolated static let minRelativeScore = 0.5

    /// Takes the best passages that fit, at most two from the same article, and none far
    /// weaker than the best (`minRelativeScore`). `hits` are best first.
    nonisolated static func pick(_ hits: [SearchHit], budget: Int) -> [SearchHit] {
        var out: [SearchHit] = []
        var used = 0
        var perTitle: [String: Int] = [:]
        let floor = (hits.first?.score ?? 0) * minRelativeScore
        for hit in hits {
            if hit.score < floor { break }
            let cost = hit.body.count + hit.title.count + 20
            guard used + cost <= budget || out.isEmpty, perTitle[hit.title, default: 0] < 2 else { continue }
            out.append(hit)
            used += min(cost, budget)
            perTitle[hit.title, default: 0] += 1
            if out.count == 5 { break }
        }
        return out
    }

    nonisolated static func prompt(question: String, sources: [SearchHit]) -> String {
        let blocks = sources.enumerated().map { i, s in
            "[\(i + 1)] \(s.title) — \(s.sourceName)\n\(s.body.prefix(2_000))"
        }
        return "Sources:\n\n" + blocks.joined(separator: "\n\n") + "\n\nQuestion: \(question)"
    }
}

struct AskView: View {
    @Environment(ContentStore.self) private var content
    @Environment(AppSettings.self) private var settings
    @Environment(\.detailNavigator) private var navigator
    @State private var engine = AskEngine()
    @State private var question = ""
    @FocusState private var focused: Bool

    private var choice: ModelChoice { ModelAvailability.resolve(settings.preferredBackend, models: content.models) }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 20) {
                        if engine.exchanges.isEmpty { intro }
                        ForEach(engine.exchanges) { ExchangeView(exchange: $0).id($0.id) }
                    }
                    .padding()
                    .frame(maxWidth: 820)
                    .frame(maxWidth: .infinity)
                }
                .onChange(of: engine.exchanges.last?.answer) {
                    if let id = engine.exchanges.last?.id { proxy.scrollTo(id, anchor: .top) }
                }
            }
            .safeAreaInset(edge: .bottom) { inputBar }
            .navigationTitle("Ask")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: SourceRef.self) { SourceView(ref: $0) }
            .navigationDestination(for: ToolKind.self) { $0.destination }
            // iPad: the best source opens beside the answer once it is done.
            .onChange(of: engine.exchanges.last?.state) {
                if let navigator, let last = engine.exchanges.last, last.state == .done, let top = last.sources.first {
                    navigator.show(top.ref)
                }
            }
            .onChange(of: navigator?.searchRequest) { if navigator?.takeSearchRequest() == true { focused = true } }
            .task {
                if let (kind, q) = LaunchScreen.parts(), kind == "ask", !q.isEmpty, engine.exchanges.isEmpty {
                    try? await Task.sleep(for: .seconds(1))   // let the guide index load
                    send(q)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { modelMenu }
                ToolbarItem(placement: .topBarTrailing) {
                    if !engine.exchanges.isEmpty { Button("Clear") { engine.clear() } }
                }
            }
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Ask a question").font(.title2.bold())
            Text("Answers come only from the guides in this app and the books in your library, with links to the source. Nothing leaves your phone.")
                .foregroundStyle(.secondary)
            ForEach(["How do I stop severe bleeding?", "How much bleach purifies a gallon of water?", "Signs of heat stroke vs heat exhaustion", "How do I build an emergency toilet?"], id: \.self) { sample in
                Button(sample) { send(sample) }
                    .buttonStyle(.bordered)
            }
            if choice == .none {
                Text(ModelAvailability.llamaSupported
                     ? "No AI model is available, so you'll get the best matching passages instead. Download a model in Library › Get Content."
                     : "No AI model is available, so you'll get the best matching passages instead.")
                    .font(.footnote).foregroundStyle(.secondary).padding(.top, 8)
            }
        }
    }

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Ask about first aid, water, shelter…", text: $question, axis: .vertical)
                .lineLimit(1...4)
                .focused($focused)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
                .onSubmit { send(question) }
            if engine.isBusy {
                Button { engine.stop() } label: { Image(systemName: "stop.circle.fill").font(.system(size: 34)) }
                    .accessibilityLabel("Stop")
            } else {
                Button { send(question) } label: { Image(systemName: "arrow.up.circle.fill").font(.system(size: 34)) }
                    .disabled(question.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityLabel("Ask")
            }
        }
        .padding(.horizontal).padding(.vertical, 8)
        .background(.bar)
    }

    private var modelMenu: some View {
        Menu {
            Picker("Answer with", selection: Binding(get: { settings.preferredBackend }, set: { settings.preferredBackend = $0 })) {
                Text("Automatic").tag("auto")
                if ModelAvailability.appleStatus == nil { Text("Apple on-device model").tag("apple") }
                if ModelAvailability.llamaSupported {
                    ForEach(content.models) { Text($0.name).tag($0.id) }
                }
                Text("No AI (passages only)").tag("none")
            }
        } label: {
            Label(label(for: choice), systemImage: "cpu").labelStyle(.titleAndIcon).font(.footnote)
        }
    }

    private func label(for choice: ModelChoice) -> String {
        switch choice {
        case .apple: "Apple model"
        case .gguf(let url): url.deletingPathExtension().lastPathComponent.prefix(18).description
        case .none: "Passages only"
        }
    }

    private func send(_ text: String) {
        guard !engine.isBusy else { return }
        engine.ask(text, model: ModelAvailability.model(for: choice))
        question = ""
        focused = false
    }
}

private struct ExchangeView: View {
    let exchange: Exchange

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(exchange.question)
                .font(.headline)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.accent.opacity(0.15), in: RoundedRectangle(cornerRadius: 14))

            switch exchange.state {
            case .searching:
                ProgressView("Searching your library…")
            case .answering where exchange.answer.isEmpty:
                ProgressView("Thinking on-device…")
            case .failed(let message):
                Label(message, systemImage: "exclamationmark.triangle").foregroundStyle(Theme.danger)
            default:
                EmptyView()
            }

            if let dose = WaterCalculatorView.dose(for: exchange.question) {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("CALCULATED BY THE APP").font(.caption.weight(.semibold)).foregroundStyle(Theme.accent)
                        Text(dose).font(.headline)
                    }
                } icon: { Image(systemName: "drop.fill").foregroundStyle(Theme.accent) }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            }

            if let tool = ToolKind.related(to: exchange.question) {
                DetailLink(value: tool) {
                    Label("Exact amounts: open \(tool.title)", systemImage: tool.symbol).font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.borderedProminent)
            }

            if !exchange.answer.isEmpty {
                Text(markdown(exchange.answer))
                    .textSelection(.enabled)
                if exchange.state == .done, let top = exchange.sources.first {
                    // Small models can drop a condition or mix up a number, so the best
                    // vetted passage is always shown word for word next to the answer.
                    VStack(alignment: .leading, spacing: 6) {
                        Text("SOURCE [1], WORD FOR WORD: \(top.title.uppercased())")
                            .font(.caption.weight(.semibold)).foregroundStyle(Theme.accent)
                        Text(top.body).font(.subheadline).lineLimit(14)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                }
            } else if exchange.state == .done && exchange.sources.isEmpty {
                Text("Nothing in your offline library matches that. Try different words, or add more books in Library.")
                    .foregroundStyle(.secondary)
            }

            if !exchange.sources.isEmpty {
                Text(exchange.answer.isEmpty ? "BEST MATCHES" : "SOURCES")
                    .font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                ForEach(Array(exchange.sources.enumerated()), id: \.element.id) { i, hit in
                    DetailLink(value: hit.ref) {
                        HStack(alignment: .top, spacing: 10) {
                            Text("\(i + 1)").font(.caption.bold()).frame(width: 22, height: 22)
                                .background(Theme.accent.opacity(0.25), in: Circle())
                            VStack(alignment: .leading, spacing: 2) {
                                Text(hit.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                                Text(hit.sourceName).font(.caption).foregroundStyle(.secondary)
                                if exchange.answer.isEmpty {
                                    Text(hit.body).font(.subheadline).foregroundStyle(.secondary).lineLimit(8)
                                }
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                        .padding(10)
                        .multilineTextAlignment(.leading)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                }
            }
            if exchange.state == .done && !exchange.answer.isEmpty {
                Text("AI answers can be wrong. Check the sources, and get trained help when you can.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func markdown(_ s: String) -> AttributedString {
        (try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(s)
    }
}
