#!/usr/bin/env bash
#
# Rebuilds dist/jbang.jar, the jar the wrapper (see src/main/wrapper) downloads
# from this repository, and its checksum. Run it whenever a change should reach
# the projects that installed the wrapper, and commit the result.
#
set -eu
cd "$(dirname "$0")/.."
./gradlew --quiet shadowJar
mkdir -p dist
cp -f build/libs/jbang.jar dist/jbang.jar
(cd dist && sha256sum jbang.jar > jbang.jar.sha256)
cat dist/jbang.jar.sha256
