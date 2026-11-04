import SwiftUI
import WebKit
import UniformTypeIdentifiers
import os

private let webLog = Logger(subsystem: "org.nomadpocket", category: "web")

/// Serves the built-in guides and ZIM book pages to WebKit with no network:
///   nomad://guide/<file>            a bundled guide, wrapped in the reader template
///   nomad://book/<zim-uuid>/<path>  any entry inside a ZIM file
final class NomadSchemeHandler: NSObject, WKURLSchemeHandler {
    static let scheme = "nomad"

    static func guideURL(_ file: String) -> URL { URL(string: "nomad://guide/\(file)")! }
    static func bookURL(_ id: String, path: String) -> URL {
        var c = URLComponents()
        c.scheme = scheme
        c.host = "book"
        c.path = "/\(id)/\(path)"
        return c.url!
    }

    func webView(_ webView: WKWebView, start task: any WKURLSchemeTask) {
        guard let url = task.request.url else { return }
        let parts = Self.pathParts(url)
        switch url.host {
        case "guide":
            let file = String(url.path.dropFirst())
            if let guide = GuideStore.guide(file: file) {
                respond(task, url: url, data: Data(ReaderTemplate.page(title: guide.title, body: GuideStore.html(for: guide)).utf8), mime: "text/html")
            } else if let data = try? Data(contentsOf: GuideStore.root.appending(path: file)) {
                respond(task, url: url, data: data, mime: UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream")
            } else {
                fail(task)
            }
        case "book":
            guard parts.count == 2 else { return fail(task) }
            let (id, path) = (parts[0], parts[1])
            Task { @MainActor in
                guard let archive = ContentStore.shared.book(id: id)?.archive else { return self.fail(task) }
                let content = await Task.detached { try? archive.content(path: path) }.value
                guard let content else {
                    webLog.error("book entry not found: \(path, privacy: .public)")
                    return self.fail(task)
                }
                self.respond(task, url: url, data: content.data, mime: content.mimeType)
            }
        default:
            fail(task)
        }
    }

    func webView(_ webView: WKWebView, stop task: any WKURLSchemeTask) {}

    /// "nomad://book/<id>/<path>" → [id, path]. URL.path drops a trailing slash,
    /// but many ZIM entries end in one ("www.nhs.uk/medicines/"), so this reads
    /// the raw, decoded path instead.
    nonisolated static func pathParts(_ url: URL) -> [String] {
        let raw = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath.removingPercentEncoding ?? url.path
        return raw.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: true).map(String.init)
    }

    private func respond(_ task: any WKURLSchemeTask, url: URL, data: Data, mime: String) {
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": mime, "Content-Length": "\(data.count)"])!
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }

    private func fail(_ task: any WKURLSchemeTask) {
        task.didFailWithError(URLError(.fileDoesNotExist))
    }
}

/// A WKWebView that only loads from the nomad:// scheme. Web links open in Safari
/// (useful when there happens to be signal), everything else stays offline.
struct NomadWebView: UIViewRepresentable {
    let url: URL
    @Binding var title: String
    @Binding var canGoBack: Bool
    var backTrigger: Int = 0

    init(url: URL, title: Binding<String> = .constant(""), canGoBack: Binding<Bool> = .constant(false), backTrigger: Int = 0) {
        self.url = url
        _title = title
        _canGoBack = canGoBack
        self.backTrigger = backTrigger
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(NomadSchemeHandler(), forURLScheme: NomadSchemeHandler.scheme)
        // Books built as small web apps (the zimgit collections) keep their document
        // list in IndexedDB via PouchDB, which hangs in a non-persistent store
        // (no blob support). This store only ever holds offline book data, on the phone.
        config.websiteDataStore = .default()
        // Let new-window requests reach createWebViewWith (handled below) even when
        // a page opens them from script.
        config.preferences.javaScriptCanOpenWindowsAutomatically = true
        #if DEBUG
        // Forward the page's errors to the system log while debugging.
        let forward = WKUserScript(source: """
            (function(){ const post = m => { try { webkit.messageHandlers.nomadLog.postMessage(String(m)); } catch(e) {} };
              window.addEventListener('error', e => post('error: ' + e.message + ' @ ' + e.filename + ':' + e.lineno));
              window.addEventListener('unhandledrejection', e => post('rejection: ' + (e.reason && (e.reason.stack || e.reason.message) || e.reason)));
              const ce = console.error; console.error = function(){ post('console.error: ' + Array.from(arguments).join(' ')); ce.apply(console, arguments); };
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        config.userContentController.addUserScript(forward)
        config.userContentController.add(context.coordinator, name: "nomadLog")
        #endif
        let view = WKWebView(frame: .zero, configuration: config)
        #if DEBUG
        view.isInspectable = true
        #endif
        view.navigationDelegate = context.coordinator
        view.uiDelegate = context.coordinator
        view.allowsBackForwardNavigationGestures = true
        view.isOpaque = false
        view.backgroundColor = .systemBackground
        context.coordinator.observe(view)
        view.load(URLRequest(url: url))
        context.coordinator.loadedURL = url
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        if context.coordinator.loadedURL != url {
            context.coordinator.loadedURL = url
            view.load(URLRequest(url: url))
        }
        if backTrigger != context.coordinator.backTrigger {
            context.coordinator.backTrigger = backTrigger
            view.goBack()
        }
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        /// Links that ask for a new window (target="_blank", window.open), which the
        /// zimgit books use for every document: open book pages and files here, and
        /// web links in Safari. Without this, tapping them did nothing.
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            guard let url = action.request.url else { return nil }
            if url.scheme == NomadSchemeHandler.scheme {
                webView.load(action.request)
            } else if ["http", "https", "mailto", "tel"].contains(url.scheme ?? "") {
                UIApplication.shared.open(url)
            }
            return nil
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            webLog.error("page: \(String(describing: message.body), privacy: .public)")
        }

        var parent: NomadWebView
        var loadedURL: URL?
        var backTrigger = 0
        private var observations: [NSKeyValueObservation] = []

        init(_ parent: NomadWebView) {
            self.parent = parent
            self.backTrigger = parent.backTrigger
        }

        func observe(_ view: WKWebView) {
            observations = [
                view.observe(\.title, options: [.new]) { [weak self] view, _ in
                    MainActor.assumeIsolated { self?.parent.title = view.title ?? "" }
                },
                view.observe(\.canGoBack, options: [.new]) { [weak self] view, _ in
                    MainActor.assumeIsolated { self?.parent.canGoBack = view.canGoBack }
                },
            ]
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
            guard let url = action.request.url else { return .cancel }
            if url.scheme == NomadSchemeHandler.scheme || url.scheme == "about" { return .allow }
            if ["http", "https", "mailto", "tel"].contains(url.scheme ?? ""), action.navigationType == .linkActivated {
                await UIApplication.shared.open(url)
            }
            return .cancel
        }
    }
}

enum ReaderTemplate {
    static func page(title: String, body: String) -> String {
        """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="color-scheme" content="light dark">
        <title>\(title.htmlEscaped)</title>
        <style>\(css)</style></head>
        <body><h1>\(title.htmlEscaped)</h1>\(body)</body></html>
        """
    }

    static let css = """
    :root { --fg:#16181b; --muted:#555b63; --bg:#ffffff; --card:#f2f2f5; --accent:#c25b00; --danger:#b3261e; --warn:#8a5a00; --tip:#1d6b38; }
    @media (prefers-color-scheme: dark) { :root { --fg:#f2f2f2; --muted:#a9adb3; --bg:#000000; --card:#1c1c1e; --accent:#fc9712; --danger:#ff6b5e; --warn:#ffc04d; --tip:#5fd38d; } }
    html { -webkit-text-size-adjust: 100%; }
    body { font: -apple-system-body; font-size: 18px; line-height: 1.5; color: var(--fg); background: var(--bg); margin: 0 auto; padding: 16px 18px 48px; max-width: 720px; }
    h1 { font-size: 28px; line-height: 1.2; margin: 4px 0 16px; }
    h2 { font-size: 22px; margin: 28px 0 8px; color: var(--accent); }
    h3 { font-size: 19px; margin: 22px 0 6px; }
    p, li { max-width: 40em; }
    ol, ul { padding-left: 1.4em; } li { margin: 6px 0; }
    ol > li::marker { font-weight: 700; color: var(--accent); }
    strong { font-weight: 700; }
    table { border-collapse: collapse; width: 100%; font-size: 16px; margin: 12px 0; display: block; overflow-x: auto; }
    th, td { border: 1px solid color-mix(in srgb, var(--muted) 40%, transparent); padding: 6px 8px; text-align: left; vertical-align: top; }
    th { background: var(--card); }
    .callout { background: var(--card); border-left: 5px solid var(--accent); border-radius: 10px; padding: 10px 14px; margin: 14px 0; }
    .callout.danger { border-color: var(--danger); } .callout.warn { border-color: var(--warn); } .callout.tip { border-color: var(--tip); }
    .callout > :first-child { margin-top: 0; } .callout > :last-child { margin-bottom: 0; }
    a { color: var(--accent); }
    """
}

extension String {
    var htmlEscaped: String {
        replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
    }
}
