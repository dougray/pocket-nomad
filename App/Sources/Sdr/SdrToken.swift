// SdrToken.swift
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0
//
// The SDR box's pairing token, kept in the Keychain (this device only).

import Foundation
import Security

enum SdrToken {
    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "org.nomadpocket.sdr", kSecAttrAccount as String: "pairing-token"]
    }

    static func load() -> String {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return "" }
        return String(decoding: d, as: UTF8.self)
    }

    static func save(_ token: String) {
        SecItemDelete(query as CFDictionary)
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        var q = query
        q[kSecValueData as String] = Data(t.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
}
