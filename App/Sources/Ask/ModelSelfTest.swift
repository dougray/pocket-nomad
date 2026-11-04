#if DEBUG && canImport(llama)
import Foundation
import os

/// Debug builds accept `-modelSelfTest <file.gguf>`, or a Documents/selftest-request.txt
/// naming the model (for a locked phone: it runs the next time the app is opened). It
/// loads that model from Documents, answers one question, and writes timings and free
/// memory to Documents/selftest.txt. Used to check which models a given iPhone can run.
enum ModelSelfTest {
    static func runIfRequested() {
        let request = URL.documentsDirectory.appending(path: "selftest-request.txt")
        var name = UserDefaults.standard.string(forKey: "modelSelfTest")
        if name == nil, let text = try? String(contentsOf: request, encoding: .utf8) {
            try? FileManager.default.removeItem(at: request)   // run once
            name = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard let name, !name.isEmpty else { return }
        Task.detached { await run(name) }
    }

    private static func run(_ name: String) async {
        let docs = URL.documentsDirectory
        let out = docs.appending(path: "selftest.txt")
        var lines: [String] = ["model: \(name)", "free before load: \(mb(os_proc_available_memory())) MB"]
        func save() { try? lines.joined(separator: "\n").appending("\n").write(to: out, atomically: true, encoding: .utf8) }
        save()
        let url = docs.appending(path: name)
        guard FileManager.default.fileExists(atPath: url.path) else { lines.append("result: FAIL file not found"); save(); return }
        let start = Date()
        let firstToken = OSAllocatedUnfairLock<TimeInterval?>(initialState: nil)
        do {
            let answer = try await LlamaRuntime.shared.generate(
                url: url,
                system: "You answer first-aid questions in short numbered steps.",
                user: "How do I treat a minor burn on my hand?",
                maxTokens: 120,
                onText: { _ in firstToken.withLock { if $0 == nil { $0 = Date().timeIntervalSince(start) } } })
            let total = Date().timeIntervalSince(start)
            lines += [
                "free after answer: \(mb(os_proc_available_memory())) MB",
                String(format: "first text after: %.1f s (includes loading)", firstToken.withLock { $0 } ?? total),
                String(format: "total: %.1f s for about %d words", total, answer.split(separator: " ").count),
                "result: OK",
                "answer:", answer,
            ]
        } catch {
            lines += ["free after failure: \(mb(os_proc_available_memory())) MB", "result: FAIL \(error.localizedDescription)"]
        }
        save()
    }

    private static func mb(_ bytes: Int) -> Int { bytes / 1_048_576 }
}
#endif
