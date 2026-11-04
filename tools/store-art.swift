// Builds App Store screenshots and the banner from raw simulator screenshots.
//
//   swift tools/store-art.swift <raw-folder> <out-folder> <logo.png>
//
// Raw files are named iphone-<n>-<name>.png and ipad-<n>-<name>.png (see the README).
// Output sizes are the ones App Store Connect asks for: 1320x2868 (6.9" iPhone)
// and 2064x2752 (13" iPad), opaque PNG.
import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

let captions: [String: (String, String)] = [
    "survive": ("Works with no signal", "First aid, water, shelter and radio guides"),
    "guide": ("Clear steps, fast", "69 guides written for the worst moments"),
    "ask": ("Ask in plain words", "Answers cite your offline library"),
    "library": ("A medical library, offline", "WikiMed, field manuals, research reviews"),
    "map": ("Maps that work offline", "Street maps with no data connection"),
    "water": ("Tools that do the math", "Purifier, compass, SOS light and Morse"),
]

let amber = CGColor(srgbRed: 0.988, green: 0.592, blue: 0.071, alpha: 1)
let orange = CGColor(srgbRed: 0.988, green: 0.518, blue: 0.047, alpha: 1)
let white = CGColor(srgbRed: 0.973, green: 0.965, blue: 0.949, alpha: 1)
let srgb = CGColorSpace(name: CGColorSpace.sRGB)!

func load(_ path: String) -> CGImage {
    CGImageSourceCreateImageAtIndex(CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil)!, 0, nil)!
}

func save(_ image: CGImage, _ path: String) {
    let d = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(d, image, nil)
    CGImageDestinationFinalize(d)
}

/// Opaque canvas with the brand background: near-black with an amber glow at `glow`.
func canvas(_ w: Int, _ h: Int, glow: CGPoint, radius: CGFloat) -> CGContext {
    let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: srgb,
                        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    ctx.setFillColor(CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
    let g = CGGradient(colorsSpace: srgb, colors: [amber.copy(alpha: 0.28)!, amber.copy(alpha: 0)!] as CFArray, locations: [0, 1])!
    ctx.drawRadialGradient(g, startCenter: glow, startRadius: 0, endCenter: glow, endRadius: radius, options: [])
    ctx.interpolationQuality = .high
    return ctx
}

func font(_ size: CGFloat, bold: Bool) -> CTFont {
    let base = CTFontCreateUIFontForLanguage(bold ? .emphasizedSystem : .system, size, nil)!
    return bold ? (CTFontCreateCopyWithSymbolicTraits(base, size, nil, .traitBold, .traitBold) ?? base) : base
}

/// Draws wrapped, centred text whose top edge is `top` (in top-down coordinates). Returns its height.
@discardableResult
func text(_ ctx: CGContext, _ s: String, font f: CTFont, color: CGColor, top: CGFloat, x: CGFloat, width: CGFloat,
          align: CTTextAlignment = .center) -> CGFloat {
    var alignment = align
    let style = withUnsafeBytes(of: &alignment) { raw in
        [CTParagraphStyleSetting(spec: .alignment, valueSize: MemoryLayout<CTTextAlignment>.size, value: raw.baseAddress!)]
    }
    let para = CTParagraphStyleCreate(style, 1)
    let attr = NSAttributedString(string: s, attributes: [
        NSAttributedString.Key(kCTFontAttributeName as String): f,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): color,
        NSAttributedString.Key(kCTParagraphStyleAttributeName as String): para,
    ])
    let setter = CTFramesetterCreateWithAttributedString(attr)
    let size = CTFramesetterSuggestFrameSizeWithConstraints(setter, CFRange(), nil, CGSize(width: width, height: .greatestFiniteMagnitude), nil)
    let h = CGFloat(ctx.height)
    let rect = CGRect(x: x, y: h - top - ceil(size.height), width: width, height: ceil(size.height))
    CTFrameDraw(CTFramesetterCreateFrame(setter, CFRange(), CGPath(rect: rect, transform: nil), nil), ctx)
    return ceil(size.height)
}

/// A screenshot with rounded corners and a thin rim, `top` in top-down coordinates.
func device(_ ctx: CGContext, _ shot: CGImage, x: CGFloat, top: CGFloat, width: CGFloat, corner: CGFloat) {
    let height = width * CGFloat(shot.height) / CGFloat(shot.width)
    let rect = CGRect(x: x, y: CGFloat(ctx.height) - top - height, width: width, height: height)
    let path = CGPath(roundedRect: rect, cornerWidth: corner, cornerHeight: corner, transform: nil)
    ctx.saveGState()
    ctx.setShadow(offset: .zero, blur: 60, color: amber.copy(alpha: 0.35))
    ctx.addPath(path); ctx.setFillColor(CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 1)); ctx.fillPath()
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addPath(path); ctx.clip()
    ctx.draw(shot, in: rect)
    ctx.restoreGState()
    ctx.addPath(path)
    ctx.setStrokeColor(CGColor(srgbRed: 0.3, green: 0.3, blue: 0.3, alpha: 1))
    ctx.setLineWidth(3)
    ctx.strokePath()
}

let args = CommandLine.arguments
let raw = args[1], out = args[2], logo = load(args[3])
try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
let files = (try FileManager.default.contentsOfDirectory(atPath: raw)).filter { $0.hasSuffix(".png") }.sorted()

for file in files {
    let parts = file.dropLast(4).split(separator: "-")
    guard parts.count == 3, let caption = captions[String(parts[2])] else { continue }
    let iPad = parts[0] == "ipad"
    let (W, H) = iPad ? (2064, 2752) : (1320, 2868)
    let ctx = canvas(W, H, glow: CGPoint(x: CGFloat(W) / 2, y: CGFloat(H) * 0.92), radius: CGFloat(W) * 0.9)
    let margin: CGFloat = iPad ? 160 : 80
    var y: CGFloat = iPad ? 120 : 150
    y += text(ctx, caption.0, font: font(iPad ? 112 : 100, bold: true), color: white, top: y, x: margin, width: CGFloat(W) - 2 * margin)
    y += iPad ? 24 : 20
    y += text(ctx, caption.1, font: font(iPad ? 56 : 50, bold: false), color: amber, top: y, x: margin, width: CGFloat(W) - 2 * margin)
    let shotWidth: CGFloat = iPad ? 1600 : 1000
    device(ctx, load("\(raw)/\(file)"), x: (CGFloat(W) - shotWidth) / 2, top: y + (iPad ? 70 : 70), width: shotWidth, corner: iPad ? 44 : 96)
    save(ctx.makeImage()!, "\(out)/\(file)")
    print("wrote \(out)/\(file)")
}

// Banner (2:1): GitHub social preview, website header, press. 2560x1280 and 1280x640.
for (w, h) in [(2560, 1280), (1280, 640)] {
    let s = CGFloat(w) / 2560
    let ctx = canvas(w, h, glow: CGPoint(x: 560 * s, y: 640 * s), radius: 900 * s)
    let logoSize = 760 * s
    ctx.draw(logo, in: CGRect(x: 180 * s, y: (CGFloat(h) - logoSize) / 2, width: logoSize, height: logoSize))
    var y = 330 * s
    y += text(ctx, "The survival kit that works with no signal", font: font(96 * s, bold: true), color: white,
              top: y, x: 1040 * s, width: 980 * s, align: .left)
    y += 36 * s
    text(ctx, "First aid and water guides, an offline medical library, maps, an on-device AI that cites its sources, and mesh messaging. Free and open source.",
         font: font(46 * s, bold: false), color: amber, top: y, x: 1040 * s, width: 980 * s, align: .left)
    let shot = load("\(raw)/iphone-1-survive.png")
    device(ctx, shot, x: 2090 * s, top: 184 * s, width: 420 * s, corner: 42 * s)
    save(ctx.makeImage()!, "\(out)/banner-\(w)x\(h).png")
    print("wrote \(out)/banner-\(w)x\(h).png")
}
