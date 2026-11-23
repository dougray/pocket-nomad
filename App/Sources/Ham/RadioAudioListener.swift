import AVFoundation
import Observation

/// Listens to a radio through the phone's headset input and decodes 1200 baud APRS, NOAA weather alerts and FT8 from the audio.
/// Receive only: nothing is ever sent and no sound is kept. The audio goes through `AfskDemodulator` and is thrown away;
/// only the decoded packets reach `HamService` and weather alerts reach the SDR screen's weather radio list. Works while the app is open.
@MainActor
@Observable
final class RadioAudioListener {
    static let shared = RadioAudioListener()

    private(set) var running = false
    /// Loudness of the incoming audio, 0 to 1, so you can set the radio's volume.
    private(set) var level: Float = 0
    private(set) var frames = 0
    private(set) var error: String?

    private let engine = AVAudioEngine()

    func toggle() { running ? stop() : start() }

    func start() {
        guard !running else { return }
        error = nil; frames = 0
        AVAudioApplication.requestRecordPermission { [weak self] granted in
            Task { @MainActor in
                guard let self else { return }
                if granted { self.begin() } else { self.error = "Allow the microphone in Settings to listen to a radio." }
            }
        }
    }

    private func begin() {
        do {
            let session = AVAudioSession.sharedInstance()
            // .measurement turns off the phone's voice processing (noise suppression, gain control), which wrecks data tones.
            try session.setCategory(.record, mode: .measurement, options: [])
            try session.setActive(true)
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else { error = "No audio input found. Plug the radio into the headset input."; return }
            let demod = AfskDemodulator(sampleRate: format.sampleRate) { frame in
                Task { @MainActor in
                    RadioAudioListener.shared.frames += 1
                    HamService.shared.receive(frame)
                }
            }
            let same = SameDecoder(sampleRate: format.sampleRate) { alert in
                Task { @MainActor in
                    RadioAudioListener.shared.frames += 1
                    SdrState.shared.apply(.alert(SdrAlert(source: "audio", mhz: 0, same: alert.header, text: alert.text, receivedAt: Date())))
                }
            }
            let ft8 = Ft8SlotRunner(inputRate: format.sampleRate) { messages in
                Task { @MainActor in
                    RadioAudioListener.shared.frames += messages.count
                    Ft8Heard.shared.add(messages)
                    for m in messages {
                        guard let spot = ft8Spot(m.text), let c = Coordinates.fromMaidenhead(spot.grid) else { continue }
                        SdrState.shared.apply(.spot(SdrSpot(mode: "ft8", call: spot.call, grid: spot.grid, snr: Int(m.snr), mhz: 0, latitude: c.latitude, longitude: c.longitude, heardAt: Date())))
                    }
                }
            }
            input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
                guard let data = buffer.floatChannelData?[0] else { return }
                let n = Int(buffer.frameLength)
                var peak: Float = 0
                for i in 0..<n { peak = max(peak, abs(data[i])) }
                let samples = UnsafeBufferPointer(start: data, count: n)
                demod.feed(samples); same.feed(samples); ft8.feed(samples)
                Task { @MainActor in RadioAudioListener.shared.level = peak }
            }
            try engine.start()
            running = true
        } catch {
            self.error = "Couldn't open the audio input."
            stop()
        }
    }

    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        running = false; level = 0
    }
}
