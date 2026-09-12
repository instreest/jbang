#!/usr/bin/env bash
#
# Refreshes dist/, which is JBangLite as a project installs it (see
# dist/install.sh): the launchers, the bootstrap scripts and LICENSE are copied
# there from the sources, and jbanglite.properties is written to pin the jar
# that goes with them; install.sh, install.cmd and README.md are maintained in
# dist/ itself.
#
# The jar itself is NOT in dist/ and never enters git: it is a release asset,
# so neither this repository nor the projects that install JBangLite carry a
# 2 MB binary in their history. What a project commits is the properties file,
# which pins the version, the download URL and the SHA-256 the bootstrap
# script verifies.
#
#   misc/update-dist.sh <version>     build the jar, refresh dist/ for it
#   misc/update-dist.sh --check       report whether dist/ is up to date
#
# With a version, the jar is built as build/libs/jbanglite.jar. Publish that
# file as the asset of the release tagged v<version>, then commit dist/:
#
#   misc/update-dist.sh 0.2.0
#   gh release create v0.2.0 build/libs/jbanglite.jar
#   git add dist && git commit -m 'Release 0.2.0'
#
# --check rebuilds nothing and only compares the copied files; the jar it
# cannot check, since it is not here.
#
# Environment:
#   JBANGLITE_REPO          the GitHub repository releases are published to
#                           (default instreest/jbanglite)
#   JBANGLITE_RELEASE_BASEURL  where releases are served from
#                           (default https://github.com)
#
set -eu
cd "$(dirname "$0")/.."

copied="src/main/scripts/jbanglite src/main/scripts/jbanglite.cmd
        src/main/scripts/jbanglite-bootstrap-jdk src/main/scripts/jbanglite-bootstrap-jdk.cmd
        src/main/scripts/jbanglite-bootstrap-jar src/main/scripts/jbanglite-bootstrap-jar.cmd
        LICENSE"

repo=${JBANGLITE_REPO:-instreest/jbanglite}
releaseBaseUrl=${JBANGLITE_RELEASE_BASEURL:-https://github.com}

if [ "${1:-}" = "--check" ]; then
  stale=
  for from in $copied; do
    cmp -s "$from" "dist/$(basename "$from")" || stale="$stale $(basename "$from")"
  done
  if [ -n "$stale" ]; then
    echo "dist/ is out of date:$stale (run misc/update-dist.sh <version>)" 1>&2
    exit 1
  fi
  echo "dist/ is up to date with the launchers and LICENSE" 1>&2
  echo "(the jar is a release asset and is not checked here)" 1>&2
  exit 0
fi

version=${1:-}
if [ -z "$version" ]; then
  echo "Usage: misc/update-dist.sh <version> | --check" 1>&2
  exit 2
fi

./gradlew --quiet shadowJar -PjbangVersion="$version"

jar=build/libs/jbanglite.jar
if command -v sha256sum > /dev/null 2>&1; then
  sha=$(sha256sum "$jar" | cut -d' ' -f1)
else
  sha=$(shasum -a 256 "$jar" | cut -d' ' -f1)
fi

mkdir -p dist
for from in $copied; do
  cp -f "$from" "dist/$(basename "$from")"
done

cat > dist/jbanglite.properties <<EOF
# The JBangLite this project runs. A project commits this file, not the jar:
# jbanglite-bootstrap-jar downloads the jar once per machine into
# ~/.jbanglite/cache/jbanglite/<version> and checks it against the SHA-256 below.
#
# To move to another version, run install.sh again, or edit all three lines
# together. JBANGLITE_DIST_URL overrides the URL for one run, for a mirror.
distributionVersion=$version
distributionUrl=$releaseBaseUrl/$repo/releases/download/v$version/jbanglite.jar
distributionSha256Sum=$sha
EOF

echo "dist/ refreshed for $version" 1>&2
echo "Now publish $jar as the asset of release v$version, then commit dist/" 1>&2
