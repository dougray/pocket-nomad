#!/bin/sh
# Makes or updates the public copy of Pocket Nomad: the tracked files at HEAD,
# committed in a separate repository, so this repository's history (which has
# private test data and device names) is never published.
#
#   tools/export-public.sh [dest] ["commit message"]     default dest: ../pocket-nomad
#
# Refuses to run unless the working tree is clean and both the secret scan
# (gitleaks) and the personal-string denylist (private/denylist.txt) pass.
# It never pushes; publishing is a separate, deliberate step.
set -e
cd "$(dirname "$0")/.."
SRC=$(pwd)
DEST=${1:-"$SRC/../pocket-nomad"}
VERSION=$(sed -n 's/^MARKETING_VERSION = //p' Config/Shared.xcconfig)
MESSAGE=${2:-"Pocket Nomad $VERSION"}

die() { echo "export-public: $*" >&2; exit 1; }

[ -z "$(git status --porcelain)" ] || die "commit or stash your changes first"
[ -f private/denylist.txt ] || die "private/denylist.txt is missing; refusing to export without the personal-string check"
command -v gitleaks >/dev/null || die "gitleaks not installed (brew install gitleaks)"

echo "Scanning tracked files..."
.githooks/check-denylist.sh tree || die "denylist matched; fix before exporting"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
git archive HEAD | tar -x -C "$TMP"
gitleaks dir --no-banner --redact --config .gitleaks.toml "$TMP" || die "gitleaks found something"

mkdir -p "$DEST"
cd "$DEST"
[ -d .git ] || { git init -q -b main; git config core.hooksPath .githooks; }
# Replace the working tree with the export (keeping .git).
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
(cd "$TMP" && tar -cf - .) | tar -xf -
git add --all .
if git diff --cached --quiet; then
  echo "Public copy already matches HEAD; nothing to commit."
else
  git -c user.name="$(git -C "$SRC" config user.name)" -c user.email="$(git -C "$SRC" config user.email)" \
      commit -q -m "$MESSAGE"
  echo "Committed in $DEST: $(git log --oneline -1)"
fi
echo "Not pushed. Review it, then publish deliberately."
