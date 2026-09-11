#!/usr/bin/env bash
#
# Refreshes dist/, which is JBangLite as a project installs it (see
# dist/install.sh): the launchers and LICENSE are copied there from the sources
# and jbanglite.jar is rebuilt; install.sh, install.cmd and README.md are
# maintained in dist/ itself.
#
# Run it after changing a launcher script or anything in src/main/java, and
# commit the result: that is what makes a change reach the projects that
# installed JBangLite.
#
# The jar is stamped with the last commit that touched its inputs
# (`jbanglite version` prints it, e.g. 0.1.0-lite+a1b2c3d), so a project can
# tell which revision it has. The build is reproducible, so with --check the
# jar is rebuilt with the same stamp and compared byte for byte, and nothing is
# written: the exit status says whether dist/ is up to date.
#
set -eu
cd "$(dirname "$0")/.."

copied="src/main/scripts/jbanglite src/main/scripts/jbanglite-bootstrap-jdk src/main/scripts/jbanglite.cmd src/main/scripts/jbanglite-bootstrap-jdk.cmd LICENSE"
jarInputs="src/main/java build.gradle settings.gradle gradle LICENSE THIRD-PARTY.md"

# the revision the jar is built from: the last commit touching its inputs,
# marked when the working tree has uncommitted changes to them
revision=$(git log -1 --format=%h -- $jarInputs)
if [ -n "$(git status --porcelain -- $jarInputs)" ]; then
  revision="$revision-dirty"
fi
version="0.1.0-lite+$revision"

./gradlew --quiet shadowJar -PjbangVersion="$version"

if [ "${1:-}" = "--check" ]; then
  stale=
  for from in $copied; do
    cmp -s "$from" "dist/$(basename "$from")" || stale="$stale $(basename "$from")"
  done
  cmp -s build/libs/jbanglite.jar dist/jbanglite.jar || stale="$stale jbanglite.jar"
  if [ -n "$stale" ]; then
    echo "dist/ is out of date:$stale (run misc/update-dist.sh)" 1>&2
    exit 1
  fi
  echo "dist/ is up to date ($version)" 1>&2
  exit 0
fi

mkdir -p dist
for from in $copied; do
  cp -f "$from" "dist/$(basename "$from")"
done
cp -f build/libs/jbanglite.jar dist/jbanglite.jar
echo "dist/ refreshed ($version)" 1>&2
