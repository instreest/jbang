#!/usr/bin/env bash
# Brings the files listed in misc/upstream-mirror.txt in from jbangdev/jbang.
#
#   misc/sync-upstream.sh [<ref>]      # default: upstream/main
#
# The mirrored files are never edited locally, so this is always a clean
# overwrite. Afterwards build and test, then commit the result together with
# the updated misc/upstream-ref.txt.
set -euo pipefail
cd "$(dirname "$0")/.."

UPSTREAM_URL=${JBANG_UPSTREAM_URL:-https://github.com/jbangdev/jbang}
REF=${1:-upstream/main}
MIRROR=misc/upstream-mirror.txt
SHIMS=misc/upstream-shims.txt
REFFILE=misc/upstream-ref.txt

if ! git remote get-url upstream > /dev/null 2>&1; then
  echo "Adding the 'upstream' remote: $UPSTREAM_URL"
  git remote add upstream "$UPSTREAM_URL"
fi
git fetch upstream

files=$(grep -vE '^\s*(#|$)' "$MIRROR")
echo "Syncing $(echo "$files" | wc -l | tr -d ' ') files from $REF"
# shellcheck disable=SC2086
git checkout "$REF" -- $files

new=$(git rev-parse "$REF")
old=$(cat "$REFFILE" 2>/dev/null || echo "")
echo "$new" > "$REFFILE"

if [ -n "$old" ] && [ "$old" != "$new" ]; then
  echo
  echo "Changes to the mirrored files between $old and $new:"
  git --no-pager log --oneline "$old..$new" -- $files || true
  echo
  echo "Changes upstream made to the files JBangLite only shims:"
  shims=$(grep -vE '^\s*(#|$)' "$SHIMS")
  # shellcheck disable=SC2086
  git --no-pager log --oneline "$old..$new" -- $shims || true
  echo "(review those by hand, they are not overwritten)"
fi

echo
echo "Now run: ./gradlew build"
