import Foundation

/// Morse timing shared by the iPhone signal light and the Apple Watch SOS screen.
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
