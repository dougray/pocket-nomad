#!/bin/sh
# Downloads the small ZIM files the NomadKit tests use. Tests skip when a file is missing.
set -eu
cd "$(dirname "$0")"
SUITE=https://github.com/openzim/zim-testing-suite/raw/main/data
fetch() { # <url> <local name>
  if [ -s "$2" ]; then echo "have $2"; return; fi
  echo "fetching $2"
  curl -fL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"
}
fetch "$SUITE/nons/small.zim"                                   small-nons.zim
fetch "$SUITE/withns/small.zim"                                 small-withns.zim
fetch "$SUITE/withns/wikibooks_be_all_nopic_2017-02.zim"        wikibooks_be_all_nopic_2017-02.zim
fetch "$SUITE/nons/wikipedia_en_climate_change_mini_2024-06.zim" wikipedia_en_climate_change_mini_2024-06.zim
fetch "https://download.kiwix.org/zim/wikipedia/wikipedia_en_ray-charles_maxi_2026-08.zim" wikipedia_en_ray-charles_maxi.zim
