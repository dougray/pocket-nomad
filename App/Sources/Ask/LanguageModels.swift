import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif
#if canImport(llama)
import llama
#endif

/// A model that turns a prompt into streamed text. Every backend runs on the phone.
protocol AnswerModel: Sendable {
    var displayName: String { get }
    /// About how many characters of source text fit in the prompt.
    var sourceBudget: Int { get }
    /// `onText` receives the full answer so far each time it grows.
    func generate(instructions: String, prompt: String, onText: @escaping @Sendable (String) -> Void) async throws -> String
}

enum ModelChoice: Hashable, Identifiable {
    case apple
    case gguf(URL)
    case none

    var id: String {
        switch self {
        case .apple: "apple"
        case .gguf(let url): url.lastPathComponent
        case .none: "none"
        }
    }
}

enum ModelAvailability {
    /// Apple's on-device model ships with iOS 26 on Apple Intelligence devices
    /// (iPhone 15 Pro and newer) and needs no download.
    static var appleStatus: String? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available: return nil
            case .unavailable(.deviceNotEligible): return "This iPhone doesn't support Apple Intelligence."
            case .unavailable(.appleIntelligenceNotEnabled): return "Turn on Apple Intelligence in Settings to use Apple's built-in model."
            case .unavailable(.modelNotReady): return "Apple's model is still downloading. Keep the phone on Wi-Fi and power."
            default: return "Apple's on-device model isn't available."
            }
        }
        return "Apple's on-device model needs iOS 26 or later."
        #else
        return "Apple's on-device model isn't available in this build."
        #endif
    }

    static var llamaSupported: Bool {
        #if canImport(llama)
        true
        #else
        false
        #endif
    }

    @MainActor
    static func resolve(_ preferred: String, models: [ModelFile]) -> ModelChoice {
        if preferred == "none" { return .none }
        if preferred != "auto" && preferred != "apple", llamaSupported, let file = models.first(where: { $0.id == preferred }) {
            return .gguf(file.url)
        }
        if appleStatus == nil { return .apple }
        if llamaSupported, let file = models.first { return .gguf(file.url) }
        return .none
    }

    static func model(for choice: ModelChoice) -> (any AnswerModel)? {
        switch choice {
        case .apple:
            #if canImport(FoundationModels)
            if #available(iOS 26.0, *) { return AppleModel() }
            #endif
            return nil
        case .gguf(let url):
            #if canImport(llama)
            return LlamaModel(url: url)
            #else
            return nil
            #endif
        case .none:
            return nil
        }
    }
}

#if canImport(FoundationModels)
@available(iOS 26.0, *)
struct AppleModel: AnswerModel {
    var displayName: String { "Apple on-device model" }
    // The model's context is 4–8k tokens; leave room for instructions and the answer.
    var sourceBudget: Int { 7_000 }

    func generate(instructions: String, prompt: String, onText: @escaping @Sendable (String) -> Void) async throws -> String {
        // Ask only rewords passages it is handed, which is the case Apple's permissive
        // guardrails are for. The default ones refuse anything about injuries, which
        // would make first aid unanswerable.
        let model = SystemLanguageModel(guardrails: .permissiveContentTransformations)
        let session = LanguageModelSession(model: model, instructions: instructions)
        var text = ""
        for try await snapshot in session.streamResponse(to: prompt, options: GenerationOptions(temperature: 0.2)) {
            text = snapshot.content
            onText(text)
        }
        return text
    }
}
#endif

#if canImport(llama)
/// Runs a GGUF model with llama.cpp on the GPU. The model stays loaded between
/// questions; loading a 1–2 GB file takes a few seconds.
actor LlamaRuntime {
    static let shared = LlamaRuntime()

    private var model: OpaquePointer?
    private var loadedURL: URL?
    private static let backendReady: Bool = { llama_backend_init(); return true }()

    func load(_ url: URL) throws -> OpaquePointer {
        if let model, loadedURL == url { return model }
        _ = Self.backendReady
        if let model { llama_model_free(model) }
        model = nil
        var params = llama_model_default_params()
        params.n_gpu_layers = 99
        guard let m = llama_model_load_from_file(url.path, params) else { throw LlamaError.loadFailed }
        model = m
        loadedURL = url
        return m
    }

    func generate(url: URL, system: String, user: String, maxTokens: Int, onText: @Sendable (String) -> Void) throws -> String {
        let model = try load(url)
        let vocab = llama_model_get_vocab(model)

        var cparams = llama_context_default_params()
        cparams.n_ctx = 4096
        cparams.n_batch = 512
        let threads = Int32(max(2, min(6, ProcessInfo.processInfo.activeProcessorCount - 2)))
        cparams.n_threads = threads
        cparams.n_threads_batch = threads
        guard let ctx = llama_init_from_model(model, cparams) else { throw LlamaError.contextFailed }
        defer { llama_free(ctx) }

        let prompt = try chatPrompt(model: model, system: system, user: user)
        var tokens = tokenize(vocab, prompt)
        let room = Int(cparams.n_ctx) - maxTokens - 8
        if tokens.count > room { tokens = Array(tokens.suffix(room)) }

        let chain = llama_sampler_chain_init(llama_sampler_chain_default_params())!
        defer { llama_sampler_free(chain) }
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1, 0, 0))
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40))
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9, 1))
        llama_sampler_chain_add(chain, llama_sampler_init_temp(0.3))
        llama_sampler_chain_add(chain, llama_sampler_init_dist(UInt32.random(in: 0...UInt32.max)))

        // Feed the prompt in batch-sized pieces.
        var start = 0
        while start < tokens.count {
            let end = min(start + Int(cparams.n_batch), tokens.count)
            var slice = Array(tokens[start..<end])
            let rc = slice.withUnsafeMutableBufferPointer { buf in
                llama_decode(ctx, llama_batch_get_one(buf.baseAddress, Int32(buf.count)))
            }
            guard rc == 0 else { throw LlamaError.decodeFailed }
            start = end
        }

        var bytes: [UInt8] = []
        var text = ""
        for _ in 0..<maxTokens {
            if Task.isCancelled { break }
            var token = llama_sampler_sample(chain, ctx, -1)
            if llama_vocab_is_eog(vocab, token) { break }
            bytes += piece(vocab, token)
            // Only publish complete UTF-8; multi-byte characters can span tokens.
            if let s = String(bytes: bytes, encoding: .utf8) {
                text = s
                onText(Self.stripThinking(text))
            }
            let rc = withUnsafeMutablePointer(to: &token) { llama_decode(ctx, llama_batch_get_one($0, 1)) }
            guard rc == 0 else { break }
        }
        return Self.stripThinking(String(decoding: bytes, as: UTF8.self))
    }

    /// Qwen3 and similar models may emit a <think>…</think> block; hide it.
    static func stripThinking(_ s: String) -> String {
        guard let open = s.range(of: "<think>") else { return s }
        guard let close = s.range(of: "</think>", range: open.upperBound..<s.endIndex) else { return String(s[..<open.lowerBound]) }
        return (String(s[..<open.lowerBound]) + String(s[close.upperBound...])).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func chatPrompt(model: OpaquePointer, system: String, user: String) throws -> String {
        let template = llama_model_chat_template(model, nil)
        return try system.withCString { sys in
            try user.withCString { usr in
                try "system".withCString { roleS in
                    try "user".withCString { roleU in
                        let messages = [llama_chat_message(role: roleS, content: sys), llama_chat_message(role: roleU, content: usr)]
                        var buffer = [CChar](repeating: 0, count: (system.utf8.count + user.utf8.count) * 2 + 512)
                        var n = llama_chat_apply_template(template, messages, messages.count, true, &buffer, Int32(buffer.count))
                        if n > buffer.count {
                            buffer = [CChar](repeating: 0, count: Int(n) + 1)
                            n = llama_chat_apply_template(template, messages, messages.count, true, &buffer, Int32(buffer.count))
                        }
                        guard n > 0 else { throw LlamaError.templateFailed }
                        return String(decoding: buffer.prefix(Int(n)).map { UInt8(bitPattern: $0) }, as: UTF8.self)
                    }
                }
            }
        }
    }

    private func tokenize(_ vocab: OpaquePointer?, _ text: String) -> [llama_token] {
        let count = text.utf8.count + 16
        var tokens = [llama_token](repeating: 0, count: count)
        let n = llama_tokenize(vocab, text, Int32(text.utf8.count), &tokens, Int32(count), true, true)
        return Array(tokens.prefix(Int(max(0, n))))
    }

    private func piece(_ vocab: OpaquePointer?, _ token: llama_token) -> [UInt8] {
        var buf = [CChar](repeating: 0, count: 64)
        let n = llama_token_to_piece(vocab, token, &buf, Int32(buf.count), 0, false)
        guard n > 0 else { return [] }
        return buf.prefix(Int(n)).map { UInt8(bitPattern: $0) }
    }
}

enum LlamaError: LocalizedError {
    case loadFailed, contextFailed, decodeFailed, templateFailed
    var errorDescription: String? {
        switch self {
        case .loadFailed: "The AI model file couldn't be loaded. It may be damaged or too big for this phone's memory."
        case .contextFailed: "Not enough memory to run the AI model. Close other apps and try again."
        case .decodeFailed: "The AI model stopped unexpectedly."
        case .templateFailed: "This AI model's chat format isn't supported."
        }
    }
}

struct LlamaModel: AnswerModel {
    let url: URL
    var displayName: String { url.deletingPathExtension().lastPathComponent }
    var sourceBudget: Int { 7_000 }

    func generate(instructions: String, prompt: String, onText: @escaping @Sendable (String) -> Void) async throws -> String {
        // Qwen3 thinks out loud unless told not to; other models ignore the tag.
        try await LlamaRuntime.shared.generate(url: url, system: instructions, user: prompt + "\n/no_think", maxTokens: 700, onText: onText)
    }
}
#endif
