// AX25Link.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// One AX.25 connected-mode link (what a BBS, a node or a Winlink gateway
// speaks): SABM/UA to connect, numbered I frames with a window of k, RR/RNR/REJ
// acknowledgements, T1 retries up to N2, a T3 keep-alive poll, DISC/UA to end.
// Modulo 8, AX.25 v2.0 behaviour, simplified the way most KISS clients are:
// go-back-N on loss, one REJ per gap, poll-and-retransmit on T1.
//
// The link never touches the radio itself: it hands frames to `send` and is
// driven by `receive(_:now:)` and `tick(now:)`, so tests run it on a fake clock.

import Foundation

struct AX25LinkConfig: Sendable, Equatable {
    /// Frames sent before waiting for an ack (1-7).
    var window = 4
    /// Largest info field in one I frame.
    var paclen = 128
    /// Retries before giving up.
    var n2 = 10
    /// Retry timer. Grows with the digipeater path (each hop repeats the frame).
    var t1Base: TimeInterval = 8
    var t1PerHop: TimeInterval = 6
    /// Idle keep-alive poll.
    var t3: TimeInterval = 180
    /// How long to hold an ack hoping to piggyback it on data.
    var ackDelay: TimeInterval = 1.0
}

@MainActor
final class AX25Link {
    enum State: Equatable, Sendable {
        case disconnected
        case connecting
        case connected
        case disconnecting
    }

    let local: AX25Address
    let remote: AX25Address
    let path: [AX25Address]
    let config: AX25LinkConfig

    private(set) var state: State = .disconnected
    /// Why the link last went down, in words; nil for a normal end.
    private(set) var endReason: String?

    var send: (AX25Frame) -> Void = { _ in }
    var onData: ([UInt8]) -> Void = { _ in }
    var onStateChange: (State) -> Void = { _ in }

    private var vs: UInt8 = 0
    private var vr: UInt8 = 0
    private var va: UInt8 = 0
    private var queue: [[UInt8]] = []
    private var outstanding: [UInt8: [UInt8]] = [:]
    private var t1: Date?
    private var t3: Date?
    private var ackDue: Date?
    private var retries = 0
    private var rejSent = false
    private var peerBusy = false
    private var disconnectWhenFlushed = false
    private var now = Date()

    init(local: AX25Address, remote: AX25Address, path: [AX25Address], config: AX25LinkConfig = AX25LinkConfig()) {
        self.local = local.base
        self.remote = remote.base
        self.path = path.map(\.base)
        self.config = config
    }

    var t1Interval: TimeInterval { config.t1Base + config.t1PerHop * Double(path.count) }
    /// Bytes waiting to go out or waiting for an ack.
    var pendingBytes: Int { queue.reduce(0) { $0 + $1.count } + outstanding.values.reduce(0) { $0 + $1.count } }

    // MARK: User actions

    func connect(now: Date) {
        self.now = now
        guard state == .disconnected else { return }
        reset()
        endReason = nil
        retries = 0
        set(.connecting)
        sendU(.sabm(poll: true), command: true)
        t1 = now + t1Interval
    }

    /// Answers a SABM from a station connecting to us.
    func accept(_ sabm: AX25Frame, now: Date) {
        self.now = now
        reset()
        endReason = nil
        sendU(.ua(final: sabmPoll(sabm)), command: false)
        set(.connected)
        t3 = now + config.t3
    }

    func write(_ bytes: [UInt8], now: Date) {
        self.now = now
        guard !bytes.isEmpty else { return }
        var i = 0
        while i < bytes.count {
            let end = min(i + max(1, config.paclen), bytes.count)
            queue.append(Array(bytes[i..<end]))
            i = end
        }
        pump()
    }

    /// Ends the link once everything queued has been acknowledged (or at once with `flush: false`).
    func disconnect(now: Date, flush: Bool = true) {
        self.now = now
        switch state {
        case .connected:
            if flush, pendingBytes > 0 {
                disconnectWhenFlushed = true
                return
            }
            startDisconnect()
        case .connecting:
            sendU(.disc(poll: true), command: true)
            finish(nil)
        case .disconnecting, .disconnected:
            break
        }
    }

    // MARK: Radio input

    /// A frame from `remote` addressed to `local`.
    func receive(_ f: AX25Frame, now: Date) {
        self.now = now
        switch f.control {
        case .sabm(let p):
            reset()
            sendU(.ua(final: p), command: false)
            set(.connected)
            t3 = now + config.t3
        case .disc(let p):
            if state == .disconnected {
                sendU(.dm(final: p), command: false)
            } else {
                sendU(.ua(final: p), command: false)
                finish(state == .disconnecting ? nil : "The other station ended the connection.")
            }
        case .ua:
            switch state {
            case .connecting:
                reset()
                retries = 0
                set(.connected)
                t3 = now + config.t3
                pump()
            case .disconnecting:
                finish(nil)
            default: break
            }
        case .dm:
            switch state {
            case .connecting: finish("Connection refused: the station is busy or not accepting connects.")
            case .disconnecting: finish(nil)
            case .connected: finish("The other station reset the link.")
            case .disconnected: break
            }
        case .frmr:
            if state != .disconnected {
                sendU(.disc(poll: true), command: true)
                finish("The other station rejected a frame (FRMR).")
            }
        case let .i(ns, nr, p):
            guard state == .connected || state == .disconnecting else {
                if f.isCommand { sendU(.dm(final: p), command: false) }
                return
            }
            handleNR(nr)
            if ns == vr {
                vr = (vr + 1) & 7
                rejSent = false
                onData(f.info)
                if p {
                    sendS(.rr(nr: vr, pf: true), command: false)
                    ackDue = nil
                } else if ackDue == nil {
                    ackDue = now + config.ackDelay
                }
            } else if !rejSent {
                rejSent = true
                sendS(.rej(nr: vr, pf: p), command: false)
                ackDue = nil
            } else if p {
                sendS(.rr(nr: vr, pf: true), command: false)
            }
            pump()
        case let .rr(nr, pf), let .rnr(nr, pf), let .rej(nr, pf):
            guard state == .connected || state == .disconnecting else {
                if f.isCommand, pf { sendU(.dm(final: true), command: false) }
                return
            }
            if case .rnr = f.control { peerBusy = true } else { peerBusy = false }
            handleNR(nr)
            if case .rej = f.control { resendOutstanding() }
            if f.isCommand, pf {
                sendS(.rr(nr: vr, pf: true), command: false)
                ackDue = nil
            } else if !f.isCommand, pf {
                // The answer to our poll: anything it didn't ack is lost, send it again.
                resendOutstanding()
            }
            pump()
        case .ui, .unknown:
            break
        }
    }

    // MARK: Timers

    func tick(now: Date) {
        self.now = now
        if let due = ackDue, now >= due {
            ackDue = nil
            if state == .connected { sendS(.rr(nr: vr, pf: false), command: false) }
        }
        if let t = t1, now >= t {
            retries += 1
            switch state {
            case .connecting:
                if retries > config.n2 { finish("No answer from \(remote). Check the callsign, path and frequency.") }
                else { sendU(.sabm(poll: true), command: true); t1 = now + t1Interval }
            case .disconnecting:
                if retries > config.n2 { finish(nil) }
                else { sendU(.disc(poll: true), command: true); t1 = now + t1Interval }
            case .connected:
                if retries > config.n2 {
                    sendU(.disc(poll: true), command: true)
                    finish("Link lost: no acknowledgement after \(config.n2) tries.")
                } else {
                    // Poll with the oldest unacknowledged frame (or an RR) and wait for the answer.
                    if let data = outstanding[va] {
                        send(frame(.i(ns: va, nr: vr, poll: true), command: true, info: data))
                        ackDue = nil
                    } else {
                        sendS(.rr(nr: vr, pf: true), command: true)
                    }
                    t1 = now + t1Interval
                }
            case .disconnected:
                t1 = nil
            }
        }
        if let t = t3, now >= t, state == .connected, t1 == nil {
            t3 = nil
            retries = 0
            sendS(.rr(nr: vr, pf: true), command: true)
            t1 = now + t1Interval
        }
    }

    // MARK: Internals

    private func reset() {
        vs = 0; vr = 0; va = 0
        outstanding = [:]
        t1 = nil; t3 = nil; ackDue = nil
        rejSent = false
        peerBusy = false
        retries = 0
    }

    private func set(_ s: State) {
        guard s != state else { return }
        state = s
        onStateChange(s)
    }

    private func finish(_ reason: String?) {
        endReason = reason
        queue = []
        reset()
        disconnectWhenFlushed = false
        set(.disconnected)
    }

    private func startDisconnect() {
        disconnectWhenFlushed = false
        queue = []
        outstanding = [:]
        retries = 0
        set(.disconnecting)
        sendU(.disc(poll: true), command: true)
        t1 = now + t1Interval
        t3 = nil
    }

    private func sabmPoll(_ f: AX25Frame) -> Bool {
        if case .sabm(let p) = f.control { return p }
        return true
    }

    /// Acknowledges our frames up to (not including) nr.
    private func handleNR(_ nr: UInt8) {
        let nr = nr & 7
        // Valid when va <= nr <= vs (mod 8).
        let inFlight = (vs &- va) & 7
        let acked = (nr &- va) & 7
        guard acked <= inFlight else { return }
        if acked > 0 {
            var n = va
            while n != nr {
                outstanding[n] = nil
                n = (n + 1) & 7
            }
            va = nr
            retries = 0
        }
        if outstanding.isEmpty {
            t1 = nil
            t3 = now + config.t3
            if disconnectWhenFlushed, queue.isEmpty { startDisconnect() }
        } else if acked > 0 {
            t1 = now + t1Interval
        }
    }

    private func resendOutstanding() {
        var n = va
        while n != vs {
            if let data = outstanding[n] {
                send(frame(.i(ns: n, nr: vr, poll: false), command: true, info: data))
            }
            n = (n + 1) & 7
        }
        if !outstanding.isEmpty {
            ackDue = nil
            t1 = now + t1Interval
        }
    }

    private func pump() {
        guard state == .connected, !peerBusy else { return }
        while !queue.isEmpty, outstanding.count < max(1, min(7, config.window)) {
            let chunk = queue.removeFirst()
            outstanding[vs] = chunk
            send(frame(.i(ns: vs, nr: vr, poll: false), command: true, info: chunk))
            ackDue = nil // piggybacked
            vs = (vs + 1) & 7
            if t1 == nil { t1 = now + t1Interval }
        }
        if t1 != nil { t3 = nil }
    }

    private func frame(_ c: AX25Control, command: Bool, info: [UInt8] = []) -> AX25Frame {
        AX25Frame(destination: remote, source: local, path: path, isCommand: command, control: c,
                  pid: AX25Frame.noLayer3, info: info)
    }

    private func sendU(_ c: AX25Control, command: Bool) { send(frame(c, command: command)) }
    private func sendS(_ c: AX25Control, command: Bool) { send(frame(c, command: command)) }
}
