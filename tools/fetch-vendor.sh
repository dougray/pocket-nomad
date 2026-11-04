#!/bin/sh
# Downloads third-party binaries that are too big to commit, verifying checksums.
set -e
cd "$(dirname "$0")/.."
LLAMA_TAG=b11976
LLAMA_SHA=3e69f01ad8ca7d36b213395e516c1cf51d1c10153a0c2a734babca5d4af8fdfa
if [ ! -d Vendor/llama.xcframework ]; then
  mkdir -p Vendor && tmp=$(mktemp -d)
  echo "Fetching llama.cpp $LLAMA_TAG xcframework..."
  curl -fL --progress-bar -o "$tmp/llama.zip" \
    "https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_TAG/llama-$LLAMA_TAG-xcframework.zip"
  echo "$LLAMA_SHA  $tmp/llama.zip" | shasum -a 256 -c -
  unzip -q "$tmp/llama.zip" -d "$tmp/x"
  mv "$tmp/x/build-apple/llama.xcframework" Vendor/
  rm -rf "$tmp"
fi
echo "Vendor ready."
