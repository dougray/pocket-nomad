// WorksWith.swift
// Pocket Nomad
// SPDX-License-Identifier: Apache-2.0
//
// A small, quiet "Works with" card in Settings pointing at the sister app WarWalker. It is plain
// text and a link: no ad network, no tracking, no count of who saw it. Whether to show it is
// decided here on the phone from what the person already uses, and "Not interested" hides it for good.

import Foundation

enum WorksWith {
    static let warWalkerURL = URL(string: "https://github.com/dougray/war-walking-app")!
    static let dismissedKey = "worksWith.warwalker.dismissed"

    /// Shown to people who use the map or radio features (they walk with a radio and a phone), unless dismissed.
    static func showWarWalker(usesMap: Bool, usesRadio: Bool, dismissed: Bool) -> Bool {
        !dismissed && (usesMap || usesRadio)
    }
}
