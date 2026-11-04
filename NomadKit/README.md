# NomadKit

Swift package behind Pocket Nomad's offline library (Apache-2.0). It reads Kiwix ZIM
files in pure Swift, so there is no libzim/GPL dependency, and builds SQLite FTS5
passage indexes for search and retrieval-augmented answers.

Platforms: iOS 17+, macOS 14+. Swift 6 language mode.
Dependency: [facebook/zstd](https://github.com/facebook/zstd) (BSD), from 1.5.7.

## Targets

| Target | What it does |
| --- | --- |
| `ZIMKit` | ZIM reader for major versions 5 and 6. Uses `pread` and small caches, never maps the file. Handles cluster compression none, xz (Apple `Compression`), zstd and zlib, plus extended clusters. Supports old (`A`/`I`/`-`/`M`) and new (`C`/`M`/`W`/`X`) namespaces. |
| `NomadSearch` | `HTMLText.extract` (HTML to text), `IndexBuilder` (FTS5 index from a ZIM or from your own HTML), `SearchIndex` (read-only queries with a safe query sanitizer). |
| `nomad-index` | macOS CLI: `nomad-index <file.zim> [-o out.nomadidx] [--max N]`, `nomad-index info <file.zim>`, `nomad-index search <idx> <query…>`. |

## Paths

- **New-layout ZIMs (6.1+):** paths have no namespace prefix, e.g. `Ray_Charles`.
- **Old-layout ZIMs:** every path the API returns includes the namespace, e.g. `A/Foo`,
  `I/foo.png` or `-/style.css`, so relative links in the HTML (`../I/foo.png`) resolve.
  `entry(path:)` and `content(path:)` accept these, and also try a bare path in `A`, `I`, then `-`.

## Usage

```swift
import ZIMKit
import NomadSearch

let zim = try ZIMArchive(url: zimURL)
zim.metadata("Title")                       // "Ray Charles"
let home = try zim.content(path: zim.mainPagePath!)   // follows redirects
let hits = zim.titleSearch("georgia on", limit: 10)   // case-insensitive prefix

try IndexBuilder.build(archive: zim, output: idxURL,
                       progress: { done, total in }, isCancelled: { false })
let index = try SearchIndex(url: idxURL)
for p in try index.search("how did he lose his sight?", limit: 5) {
    print(p.title, p.path, p.score, p.snippet)   // score: higher is better (-bm25)
}

// The app's bundled guides use the same schema:
try IndexBuilder.build(documents: [IndexDocument(path: "water.html", title: "Water", html: data)],
                       output: guidesIdx, progress: { _, _ in }, isCancelled: { false })
```

## Index format (`.nomadidx`, schema_version 1)

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);  -- zim_uuid, schema_version, articles, passages, built_at, complete
CREATE VIRTUAL TABLE passages USING fts5(title, body, path UNINDEXED, tokenize='porter unicode61');
```

Articles are split into passages of about 900–1200 characters on paragraph boundaries.
The builder writes `<output>.tmp` and renames it when done. `complete=1` is written last.
Search ranks with `bm25(passages, 4.0, 1.0, 0.0)`, so title matches count four times as
much as body matches. Snippets mark matched terms with `**`.

## Tests

```sh
Tests/Fixtures/fetch-fixtures.sh   # ~12 MB of small real ZIMs (gitignored)
swift test
```

Tests that need a fixture skip when the file is missing.
