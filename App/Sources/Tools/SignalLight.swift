import SwiftUI
import AVFoundation

enum Morse {
    static let table: [Character: String] = [
        "A": ".-", "B": "-...", "C": "-.-.", "D": "-..", "E": ".", "F": "..-.", "G": "--.", "H": "....",
        "I": "..", "J": ".---", "K": "-.-", "L": ".-..", "M": "--", "N": "-.", "O": "---", "P": ".--.",
        "Q": "--.-", "R": ".-.", "S": "...", "T": "-", "U": "..-", "V": "...-", "W": ".--", "X": "-..-",
        "Y": "-.--", "Z": "--..", "0": "-----", "1": ".----", "2": "..---", "3": "...--", "4": "....-",
        "5": ".....", "6": "-....", "7": "--...", "8": "---..", "9": "----.", ".": ".-.-.-", ",": "--..--",
        "?": "..--..", "/": "-..-.", "-": "-....-", "@": ".--.-.", "=": "-...-",
    ]

    static func encode(_ text: String) -> String {
        text.uppercased().split(separator: " ").map { word in
            word.compactMap { table[$0] }.joined(separator: " ")
        }.joined(separator: " / ")
    }

    /// On/off durations in Morse units: dot 1, dash 3, gap inside a letter 1,
    /// between letters 3, between words 7. SOS is sent as one run (…---…).
    static func timeline(_ text: String) -> [(on: Bool, units: Int)] {
        var out: [(Bool, Int)] = []
        let upper = text.uppercased()
        let words = upper == "SOS" ? [["...---..."]] : upper.split(separator: " ").map { $0.compactMap { table[$0] } }
        for (w, letters) in words.enumerated() {
            if w > 0 { out.append((false, 7)) }
            for (l, code) in letters.enumerated() {
                if l > 0 { out.append((false, 3)) }
                for (s, symbol) in code.enumerated() {
                    if s > 0 { out.append((false, 1)) }
                    out.append((true, symbol == "-" ? 3 : 1))
                }
            }
        }
        out.append((false, 7))
        return out
    }
}

/// Drives the camera flash, the screen and an audio tone with the same Morse
/// timing, so a signal can be seen at night or heard through walls.
@MainActor @Observable
final class SignalPlayer {
    var useTorch = true
    var useScreen = false
    var useSound = false
    var wpm = 8.0
    private(set) var isOn = false
    private(set) var isPlaying = false
    private var task: Task<Void, Never>?
    private let tone = ToneGenerator()

    var hasTorch: Bool { AVCaptureDevice.default(for: .video)?.hasTorch ?? false }

    func play(_ text: String, repeating: Bool) {
        stop()
        let steps = Morse.timeline(text)
        let unit = 1.2 / wpm   // PARIS standard: dot length in seconds
        isPlaying = true
        UIApplication.shared.isIdleTimerDisabled = true
        task = Task {
            repeat {
                for step in steps {
                    if Task.isCancelled { break }
                    set(step.on)
                    try? await Task.sleep(for: .seconds(unit * Double(step.units)))
                }
            } while repeating && !Task.isCancelled
            set(false)
            isPlaying = false
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        set(false)
        isPlaying = false
    }

    private func set(_ on: Bool) {
        isOn = on
        if useTorch { torch(on) }
        if useSound { on ? tone.start() : tone.stop() } else { tone.stop() }
    }

    private func torch(_ on: Bool) {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            if on { try device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel) } else { device.torchMode = .off }
            device.unlockForConfiguration()
        } catch {}
    }
}

/// A plain sine tone around 800 Hz, the usual pitch for Morse.
final class ToneGenerator: @unchecked Sendable {
    private let engine = AVAudioEngine()
    private var node: AVAudioSourceNode?
    private let lock = NSLock()
    private var playing = false
    private var phase = 0.0

    func start() {
        lock.withLock { playing = true }
        guard node == nil else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        let format = engine.outputNode.inputFormat(forBus: 0)
        let rate = format.sampleRate
        let node = AVAudioSourceNode { [unowned self] _, _, frameCount, abl in
            let buffers = UnsafeMutableAudioBufferListPointer(abl)
            let on = self.lock.withLock { self.playing }
            for frame in 0..<Int(frameCount) {
                let sample = on ? Float(sin(self.phase) * 0.6) : 0
                self.phase += 2 * .pi * 800 / rate
                if self.phase > 2 * .pi { self.phase -= 2 * .pi }
                for buffer in buffers { buffer.mData?.assumingMemoryBound(to: Float.self)[frame] = sample }
            }
            return noErr
        }
        self.node = node
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1))
        try? engine.start()
    }

    func stop() { lock.withLock { playing = false } }
}

struct SignalLightView: View {
    let initialText: String
    let title: String
    @State private var player = SignalPlayer()
    @State private var text: String
    @State private var repeating = true

    init(initialText: String, title: String) {
        self.initialText = initialText
        self.title = title
        _text = State(initialValue: initialText)
    }

    var body: some View {
        ZStack {
            if player.useScreen && player.isPlaying {
                (player.isOn ? Color.white : Color.black).ignoresSafeArea()
                    .onTapGesture { player.stop() }
                    .overlay(alignment: .bottom) {
                        Text("Tap to stop").foregroundStyle(.gray).padding(.bottom, 40)
                    }
            } else {
                controls
            }
        }
        .navigationTitle(title)
        .toolbar(player.useScreen && player.isPlaying ? .hidden : .automatic, for: .navigationBar, .tabBar)
        .onDisappear { player.stop() }
    }

    private var controls: some View {
        Form {
            Section {
                Button {
                    player.isPlaying ? player.stop() : player.play(text, repeating: repeating)
                } label: {
                    Label(player.isPlaying ? "Stop" : "Send \(text.uppercased())", systemImage: player.isPlaying ? "stop.fill" : "play.fill")
                        .font(.title2.bold())
                        .frame(maxWidth: .infinity, minHeight: 64)
                }
                .buttonStyle(.borderedProminent)
                .tint(player.isPlaying ? .gray : Theme.danger)
                .listRowInsets(EdgeInsets())
                Circle()
                    .fill(player.isOn ? Theme.accent : Color.secondary.opacity(0.2))
                    .frame(width: 28, height: 28)
                    .frame(maxWidth: .infinity)
            }
            Section("Message") {
                TextField("Text to send", text: $text)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Text(Morse.encode(text)).font(.body.monospaced()).foregroundStyle(.secondary)
                Toggle("Repeat until stopped", isOn: $repeating)
            }
            Section("Signal with") {
                Toggle("Flashlight", isOn: $player.useTorch).disabled(!player.hasTorch)
                Toggle("Whole screen (white flash)", isOn: $player.useScreen)
                Toggle("Sound", isOn: $player.useSound)
                VStack(alignment: .leading) {
                    Text("Speed: \(Int(player.wpm)) words per minute")
                    Slider(value: $player.wpm, in: 4...20, step: 1)
                }
            }
            Section {
                Text("SOS is three short, three long, three short (· · · — — — · · ·), repeated. Any signal in threes (flashes, whistle blasts, fires) means distress.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

struct MorseView: View {
    @State private var showSender = false
    private let letters = Morse.table.sorted { $0.key < $1.key }

    var body: some View {
        List {
            Section {
                NavigationLink("Send a message by light or sound") {
                    SignalLightView(initialText: "HELP", title: "Morse Sender")
                }
            }
            Section("Alphabet") {
                ForEach(letters, id: \.key) { letter, code in
                    HStack {
                        Text(String(letter)).font(.title3.bold().monospaced()).frame(width: 36)
                        Text(code.replacingOccurrences(of: ".", with: "·").replacingOccurrences(of: "-", with: "—"))
                            .font(.title3.monospaced())
                    }
                }
            }
        }
        .navigationTitle("Morse Code")
    }
}
