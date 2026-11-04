// MeshInfoView.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Plain-language explainer: what a mesh radio is and how to get one going.

import SwiftUI

struct MeshInfoView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                section("What is it?", symbol: "antenna.radiowaves.left.and.right",
                        "A mesh radio is a small, cheap LoRa radio that runs free mesh firmware: Meshtastic or MeshCore. Your phone talks to it over Bluetooth, and it sends your text over the air to other radios. Radios pass messages along, so texts can hop across several radios to reach people far away. No cell towers, Wi-Fi or internet are involved, and messages are encrypted. Pocket Nomad works with both kinds, but a Meshtastic radio only talks to other Meshtastic radios and a MeshCore radio only to MeshCore ones, so use the same kind as the people you want to reach.")

                section("Getting a radio", symbol: "cart",
                        "Look for a LoRa board for the 915 MHz band if you are in the US. Common ones are made by Heltec, RAK Wireless (WisBlock), LilyGO (T-Beam, T-Echo) and Seeed (SenseCAP T1000-E). Most cost about $20 to $80. Get one with a battery or battery holder, and an antenna that is always attached. Never power a radio on without its antenna, which can damage it. Everyone you want to reach needs a radio too.")

                section("Set it up first", symbol: "gearshape.2",
                        "Before using it here, set the radio up once. Meshtastic: install the official Meshtastic app, connect, and set the LoRa region to US (915 MHz); a radio with no region will not transmit. MeshCore: flash the Companion (Bluetooth) firmware with the MeshCore web flasher and pick your area's radio settings in the MeshCore app. Keep the default public channel to reach people nearby, or make a private one and share it with your group. Then come back here and tap Find a radio.")

                section("How far does it reach?", symbol: "ruler",
                        "Expect about 1 to 3 miles between handheld radios in towns or woods, and 5 to 10 miles or more with a clear line of sight, such as from a hilltop. Hills, buildings and bodies block the signal: hold the radio high, away from your body, or put it on a pack strap. Each extra radio in between extends the range.")

                section("What to expect", symbol: "clock",
                        "Messages are short (about 200 letters) and can take seconds to a minute to arrive. \"Heard by mesh\" means another radio repeated your message. \"Delivered\" means the person's radio confirmed it. Keep the app open or in the background to keep receiving messages.")

                section("SOS", symbol: "sos",
                        "The SOS button sends a short help message, with your GPS position if you allow it, to every radio on the primary channel. It does not reach emergency services unless someone on the mesh passes it on.")
            }
            .padding(16)
        }
        .navigationTitle("What is a mesh radio?")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func section(_ title: String, symbol: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(title, systemImage: symbol)
                .font(.title3.weight(.bold))
                .foregroundStyle(Theme.accent)
            Text(text)
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
