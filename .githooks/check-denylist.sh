#!/bin/sh
# Blocks the owner's real hostnames, IPs, numbers and usernames, read from the
# gitignored private/denylist.txt (one string per line, # comments allowed).
# Usage: check-denylist.sh staged   -> scan lines being added in this commit
#        check-denylist.sh tree     -> scan every tracked file (used before push)
# Contributors without the file get a warning; gitleaks still runs. DENYLIST=<path>
# points at a list elsewhere (the public export commits with the private one).
root=$(git rev-parse --show-toplevel)
list=${DENYLIST:-"$root/private/denylist.txt"}
if [ ! -f "$list" ]; then
  echo "denylist: $list not found, skipping personal-string check" >&2
  exit 0
fi
patterns=$(mktemp)
trap 'rm -f "$patterns"' EXIT
grep -v '^[[:space:]]*#' "$list" | grep -v '^[[:space:]]*$' > "$patterns"
[ -s "$patterns" ] || exit 0

case "$1" in
  staged) files=$(git diff --cached --name-only --diff-filter=ACMR); src=":" ;;
  tree)   files=$(git ls-files); src="HEAD:" ;;
  *) echo "usage: $0 staged|tree" >&2; exit 2 ;;
esac

found=0
IFS='
'
# Paths themselves.
for f in $(echo "$files" | grep -i -F -f "$patterns"); do
  echo "  path: (name matches a denylist entry)" >&2; found=1
done
# Contents: report file and line numbers only, never the matched text.
for f in $files; do
  lines=$(git show "$src$f" 2>/dev/null | grep -I -i -n -F -f "$patterns" | cut -d: -f1 | tr '\n' ' ')
  if [ -n "$lines" ]; then
    echo "  $f: line(s) $lines" >&2; found=1
  fi
done

if [ "$found" = 1 ]; then
  echo "denylist: personal identifier found; refusing." >&2
  exit 1
fi
exit 0
