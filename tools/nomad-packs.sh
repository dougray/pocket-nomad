#!/bin/sh
# Prepares offline content on a Mac and copies it to Pocket Nomad on an iPhone.
#
#   tools/nomad-packs.sh map <name> <minLon,minLat,maxLon,maxLat> [maxzoom]
#   tools/nomad-packs.sh book <kiwix-name> [flavour]       e.g. book wikipedia_en_medicine nopic
#   tools/nomad-packs.sh essentials                         the app's recommended survival books
#   tools/nomad-packs.sh pubmed                             PubMed: Survival Medicine (built from NCBI, ~9,600 abstracts)
#   tools/nomad-packs.sh model small|large                  an AI model for Ask (llama.cpp)
#   tools/nomad-packs.sh index <file.zim>                   (re)build a search index
#   tools/nomad-packs.sh install [device]                   copy everything in packs/ to the phone
#   tools/nomad-packs.sh list
#
# Everything lands in ./packs. Books get a prebuilt .nomadidx search index so the
# phone doesn't spend hours indexing. Needs: curl, pmtiles (brew install pmtiles)
# for maps, Xcode for `install`. All sources are free: Kiwix, Protomaps, Hugging Face.
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)
PACKS="$ROOT/packs"
BUNDLE_ID=${BUNDLE_ID:-org.nomadpocket.NomadPocket}
OPDS=https://opds.library.kiwix.org/catalog/v2/entries
mkdir -p "$PACKS"

die() { echo "error: $*" >&2; exit 1; }

nomad_index() {
  bin="$ROOT/NomadKit/.build/release/nomad-index"
  if [ ! -x "$bin" ]; then
    echo "Building nomad-index (one time)..."
    (cd "$ROOT/NomadKit" && swift build -c release --product nomad-index >/dev/null)
  fi
  "$bin" "$@"
}

cmd_map() {
  name=$1; bbox=$2; maxzoom=${3:-15}
  [ -n "$name" ] && [ -n "$bbox" ] || die "usage: map <name> <minLon,minLat,maxLon,maxLat> [maxzoom]"
  command -v pmtiles >/dev/null || die "pmtiles not found: brew install pmtiles"
  build=$(curl -fsSL https://build-metadata.protomaps.dev/builds.json | python3 -c 'import json,sys; print(json.load(sys.stdin)[-1]["key"])')
  echo "Extracting $name ($bbox, zoom 0-$maxzoom) from Protomaps build $build..."
  pmtiles extract "https://build.protomaps.com/$build" "$PACKS/$name.pmtiles" --bbox="$bbox" --maxzoom="$maxzoom" --download-threads=8
  ls -lh "$PACKS/$name.pmtiles"
}

# Prints "url size" for the newest Kiwix file matching name (+ flavour).
resolve_book() {
  curl -fsSL "$OPDS?name=$1&count=20" | python3 -c '
import sys, re
flavour = sys.argv[1] if len(sys.argv) > 1 else ""
for e in re.findall(r"<entry>(.*?)</entry>", sys.stdin.read(), re.S):
    f = re.search(r"<flavour>(.*?)</flavour>", e)
    if flavour and (not f or f.group(1) != flavour): continue
    m = re.search(r"type=\"application/x-zim\" href=\"([^\"]+?)(\.meta4)?\" length=\"(\d+)\"", e)
    if m: print(m.group(1), m.group(3)); break
' "$2"
}

cmd_book() {
  [ -n "$1" ] || die "usage: book <kiwix-name> [flavour]"
  set -- $(resolve_book "$1" "$2")
  [ -n "$1" ] || die "not found in the Kiwix catalog"
  file="$PACKS/$(basename "$1")"
  if [ "$(stat -f %z "$file" 2>/dev/null)" = "$2" ]; then
    echo "Have $(basename "$1")"
  else
    echo "Downloading $(basename "$1") ($(($2 / 1000000)) MB)..."
    curl -fL -C - --progress-bar -o "$file" "$1"
  fi
  # Delete the .nomadidx to force a rebuild.
  [ -f "${file%.zim}.nomadidx" ] || nomad_index "$file" -o "${file%.zim}.nomadidx"
}

cmd_essentials() {
  for spec in zimgit-post-disaster_en zimgit-water_en zimgit-medicine_en irp.fas.org_en_military-medicine \
              "wikipedia_en_medicine nopic" nhs.uk_en_medicines zimgit-food-preparation_en zimgit-knots_en; do
    cmd_book $spec
  done
}

cmd_pubmed() {
  work="$PACKS/pubmed-work"
  python3 -I "$ROOT/tools/pubmed-pack.py" "$work"
  out="$PACKS/pocketnomad_en_pubmed-survival_$(date +%Y-%m).zim"
  nomad_index zim "$work" -o "$out"
  nomad_index "$out" -o "${out%.zim}.nomadidx"
}

cmd_model() {
  case "$1" in
    small) url=https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf ;;
    large) url=https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf ;;
    *) die "usage: model small|large" ;;
  esac
  curl -fL -C - --progress-bar -o "$PACKS/$(basename "$url")" "$url"
}

cmd_install() {
  device=$1
  if [ -z "$device" ]; then
    # The first real (not simulated) device that is paired and reachable.
    device=$(xcrun devicectl list devices 2>/dev/null | awk '/ physical *$/ && /available|connected/ {
      for (i = 1; i <= NF; i++) if ($(i+1) == "(UDID)") { print $i; exit } }')
  fi
  [ -n "$device" ] || die "no iPhone found. Connect it by cable (or the same Wi-Fi) and trust this Mac."
  for f in "$PACKS"/*.zim "$PACKS"/*.nomadidx "$PACKS"/*.pmtiles "$PACKS"/*.gguf; do
    [ -f "$f" ] || continue
    echo "Copying $(basename "$f")..."
    xcrun devicectl device copy to --device "$device" --source "$f" --destination "Documents/$(basename "$f")" \
      --domain-type appDataContainer --domain-identifier "$BUNDLE_ID" >/dev/null
  done
  echo "Done. Open Pocket Nomad to see the new content."
}

case "$1" in
  map) shift; cmd_map "$@" ;;
  book) shift; cmd_book "$@" ;;
  essentials) cmd_essentials ;;
  pubmed) cmd_pubmed ;;
  model) shift; cmd_model "$@" ;;
  index) shift; nomad_index "$@" ;;
  install) shift; cmd_install "$@" ;;
  list) ls -lh "$PACKS" ;;
  *) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
