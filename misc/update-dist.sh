#!/usr/bin/env bash
#
# Refreshes dist/, the set of files the wrapper installs into a project (see
# dist/install.sh). The launchers, LICENSE and jbanglite.jar are copied there from
# the sources; install.sh, install.cmd, README.md and gitignore are maintained
# in dist/ itself.
#
# Run it after changing a launcher script or anything in src/main/java, and
# commit the result: that is what makes a change reach the projects that
# installed the wrapper.
#
# With --check nothing is written; it only reports whether dist/ is up to date
# with the launchers and LICENSE (the jar is not rebuilt).
#
set -eu
cd "$(dirname "$0")/.."

copied="src/main/scripts/jbanglite src/main/scripts/jbanglite.cmd LICENSE"

if [ "${1:-}" = "--check" ]; then
  stale=
  for from in $copied; do
    cmp -s "$from" "dist/$(basename "$from")" || stale="$stale $(basename "$from")"
  done
  if [ -n "$stale" ]; then
    echo "dist/ is out of date:$stale (run misc/update-dist.sh)" 1>&2
    exit 1
  fi
  echo "dist/ is up to date with the launchers and LICENSE" 1>&2
  exit 0
fi

./gradlew --quiet shadowJar
mkdir -p dist
for from in $copied; do
  cp -f "$from" "dist/$(basename "$from")"
done
cp -f build/libs/jbanglite.jar dist/jbanglite.jar
(cd dist && sha256sum jbanglite.jar > jbanglite.jar.sha256)
cat dist/jbanglite.jar.sha256
