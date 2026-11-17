// KISS.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// KISS framing between the phone and a TNC (the radio's packet modem).
// One frame: FEND, a command byte (port << 4 | command), the escaped payload, FEND.
// Spec: "KISS: A Simple Interface for Packet Radio" (Chepponis & Karn, 1987).

import Foundation

enum KISS {
    static let fend: UInt8 = 0xC0
    static let fesc: UInt8 = 0xDB
    static let tfend: UInt8 = 0xDC
    static let tfesc: UInt8 = 0xDD

    enum Command: UInt8, Sendable {
        case data = 0x00
        case txDelay = 0x01
        case persistence = 0x02
        case slotTime = 0x03
        case txTail = 0x04
        case fullDuplex = 0x05
        case setHardware = 0x06
    }

    /// One KISS frame for `payload` (an AX.25 frame for `.data`, one parameter byte otherwise).
    static func encode(_ payload: [UInt8], command: Command = .data, port: UInt8 = 0) -> [UInt8] {
        var out: [UInt8] = [fend, (port & 0x0F) << 4 | command.rawValue]
        out.reserveCapacity(payload.count + 4)
        for b in payload {
            switch b {
            case fend: out += [fesc, tfend]
            case fesc: out += [fesc, tfesc]
            default: out.append(b)
            }
        }
        out.append(fend)
        return out
    }

    /// The TNC timing parameters, sent once after connecting.
    /// txDelay and slotTime are in 10 ms units; persistence is 0-255 (p = (P+1)/256).
    static func parameterFrames(txDelay: UInt8, persistence: UInt8, slotTime: UInt8, port: UInt8 = 0) -> [UInt8] {
        encode([txDelay], command: .txDelay, port: port)
            + encode([persistence], command: .persistence, port: port)
            + encode([slotTime], command: .slotTime, port: port)
    }
}

/// Reassembles KISS frames from a byte stream that arrives in arbitrary chunks
/// (Bluetooth notifications are often 20-180 bytes and split frames anywhere).
struct KISSDecoder: Sendable {
    struct Frame: Equatable, Sendable {
        var port: UInt8
        var command: UInt8
        var payload: [UInt8]
    }

    private var buffer: [UInt8] = []
    private var inFrame = false
    private var escaped = false
    /// Frames longer than this are dropped (garbage on the line).
    var maxFrame = 4096

    mutating func feed(_ bytes: some Sequence<UInt8>) -> [Frame] {
        var frames: [Frame] = []
        for b in bytes {
            if b == KISS.fend {
                if inFrame, let first = buffer.first {
                    frames.append(Frame(port: first >> 4, command: first & 0x0F, payload: Array(buffer.dropFirst())))
                }
                buffer.removeAll(keepingCapacity: true)
                inFrame = true
                escaped = false
                continue
            }
            guard inFrame else { continue }
            if escaped {
                escaped = false
                switch b {
                case KISS.tfend: buffer.append(KISS.fend)
                case KISS.tfesc: buffer.append(KISS.fesc)
                default: buffer.append(b) // protocol violation: keep the byte
                }
            } else if b == KISS.fesc {
                escaped = true
            } else {
                buffer.append(b)
            }
            if buffer.count > maxFrame {
                buffer.removeAll(keepingCapacity: true)
                inFrame = false
            }
        }
        return frames
    }
}
