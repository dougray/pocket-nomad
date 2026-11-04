package org.nomadpocket.app.guides

/**
 * Wraps a guide's HTML fragment in a page with the same look as the iOS reader
 * (App/Sources/Web/NomadWebView.swift ReaderTemplate), dark by default.
 */
object GuidePage {
    fun page(title: String, body: String, dark: Boolean): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="color-scheme" content="${if (dark) "dark" else "light"}">
        <title>${escape(title)}</title>
        <style>${if (dark) DARK else LIGHT}$CSS</style></head>
        <body><h1>${escape(title)}</h1>$body</body></html>
    """.trimIndent()

    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val LIGHT =
        ":root { --fg:#16181b; --muted:#555b63; --bg:#ffffff; --card:#f2f2f5; --accent:#c25b00; --danger:#b3261e; --warn:#8a5a00; --tip:#1d6b38; }\n"
    private const val DARK =
        ":root { --fg:#f2f2f2; --muted:#a9adb3; --bg:#000000; --card:#1c1c1e; --accent:#fc9712; --danger:#ff6b5e; --warn:#ffc04d; --tip:#5fd38d; }\n"

    private const val CSS = """
html { -webkit-text-size-adjust: 100%; }
body { font-family: sans-serif; font-size: 18px; line-height: 1.5; color: var(--fg); background: var(--bg); margin: 0 auto; padding: 16px 18px 48px; max-width: 720px; }
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
