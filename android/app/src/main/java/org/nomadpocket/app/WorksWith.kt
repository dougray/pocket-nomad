// WorksWith.kt
// Pocket Nomad
// SPDX-License-Identifier: Apache-2.0
//
// A small, quiet "Works with" card in Settings pointing at the sister app WarWalker: plain text and a
// link, no tracking. Whether to show it is decided on the phone from what the person already uses.

package org.nomadpocket.app

object WorksWith {
    const val WARWALKER_URL = "https://github.com/dougray/war-walking-app"

    fun showWarWalker(usesMap: Boolean, usesRadio: Boolean, dismissed: Boolean) = !dismissed && (usesMap || usesRadio)
}
