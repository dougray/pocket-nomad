// AppLanguage.swift
// Pocket Nomad
// SPDX-License-Identifier: Apache-2.0
//
// An in-app language choice (English, Spanish, French) that overrides the phone's language,
// so a family can read the app in the language they know best. The interface and menus are
// translated; the guides and books stay in English until a qualified person can translate
// first-aid and survival text.

import Foundation

enum AppLanguage: String, CaseIterable, Identifiable, Sendable {
    case system, en, es, fr
    var id: Self { self }

    /// Each language is named in itself, so you can find yours even when the app is in a language you can't read.
    var nativeName: String {
        switch self { case .system: "System"; case .en: "English"; case .es: "Español"; case .fr: "Français" }
    }

    /// The language code to use, or nil for "follow the phone".
    var code: String? { self == .system ? nil : rawValue }

    var locale: Locale { code.map { Locale(identifier: $0) } ?? .autoupdatingCurrent }

    /// The translation bundle for this language (the main bundle when following the phone or for English).
    var bundle: Bundle {
        guard let code, code != "en", let path = Bundle.main.path(forResource: code, ofType: "lproj"), let b = Bundle(path: path) else { return .main }
        return b
    }
}

/// Looks up a translated string for text that isn't a SwiftUI literal (a `String` you build in code).
@MainActor
func tr(_ key: String, _ language: AppLanguage = AppSettings.shared.language) -> String {
    language.bundle.localizedString(forKey: key, value: key, table: nil)
}
