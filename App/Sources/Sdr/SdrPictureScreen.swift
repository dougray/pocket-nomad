// SdrPictureScreen.swift
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0

import SwiftUI

/// One weather-satellite picture from the box, fetched over Wi-Fi.
struct SdrPictureScreen: View {
    let image: SdrImage
    @State private var data: Data?
    @State private var failed = false

    var body: some View {
        ScrollView([.horizontal, .vertical]) {
            if let data, let ui = UIImage(data: data) {
                Image(uiImage: ui).resizable().interpolation(.high).scaledToFit().frame(minWidth: 320)
            } else if failed {
                Text("Couldn't get the picture. Pictures come over Wi-Fi from the box: check the address and pairing token.").padding()
            } else { ProgressView().padding(40) }
        }
        .navigationTitle(image.sat)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { if let data, let ui = UIImage(data: data) { ShareLink(item: Image(uiImage: ui), preview: SharePreview(image.sat, image: Image(uiImage: ui))) } }
        .task { data = await SdrClient.shared.fetchImage(image); failed = data == nil }
    }
}
