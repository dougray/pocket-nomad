Pocket Nomad is a free, open-source (Apache 2.0) offline survival app. No account or sign-in is needed, and it has no servers, ads, analytics or in-app purchases.

HOW TO REVIEW
- Survive, the Water Purifier and the other tools, and the Map work immediately, with no network.
- Library: tap the download button (top right) › Get Content to download a small book from the Kiwix library (for example "A Library of Knots", 28 MB).
- Ask: on Apple Intelligence devices it uses Apple's on-device model with no download. On other devices, download "Qwen3 1.7B" (1.1 GB, from Hugging Face) in Library › Get Content first. Answers cite the guide or book they came from.
- Map: offline map regions are downloaded the same way; the app includes a small built-in map.
- Mesh: needs a Meshtastic or MeshCore LoRa radio (third-party hardware, about $30) paired over Bluetooth, so it can't be fully tested without one. Without a radio the tab explains what's needed. Messages are radio-to-radio between people nearby; no server is involved. Any radio can be blocked from the chat or node list, which hides its messages on this device.

MEDICAL CONTENT
The first-aid guides are written from published first-aid guidance (American Heart Association / Red Cross, WHO, CDC, NHS) and were checked against those sources; the sources are listed in the app's source repository. The app says throughout that it is not a medical device and does not replace professional care or training, and tells users to call their local emergency number first.

DOWNLOADED CONTENT
Books (.zim), maps (.pmtiles) and AI model weights (.gguf) are data files, not executable code. Users can also add their own through the Files app.

LOCATION AND BLUETOOTH
Location is used on the device only: the map, the compass, the user's choice to add coordinates to an SOS message, and the optional coverage scanner (off by default). Bluetooth connects to the user's own radio.

Support: https://github.com/dougray/pocket-nomad/issues · Privacy: https://www.pocketnomad.us/PRIVACY.html
