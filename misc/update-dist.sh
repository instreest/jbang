#!/usr/bin/env bash
#
# Refreshes dist/, which is JBangLite as a project installs it (see
# dist/install.sh): the launcher scripts and LICENSE are copied there from the
# sources, and jbanglite.properties is written to pin both downloads a project
# can need - jbanglite.jar and, for a machine with no Java at all, a JDK to
# start it with. install.sh, install.cmd and README.md are maintained in dist/
# itself.
#
# Neither download is decided at run time. The jar is a release asset and the
# JDK is Eclipse Temurin, and the version, URL and SHA-256 of each is resolved
# here, once, and committed with the project. So a project's history carries
# about 50 kB of scripts instead of a binary, the launcher scripts have nothing
# to parse but a properties file, and what a checkout installs is the same
# thing every time.
#
#   misc/update-dist.sh <version>     build the jar, refresh dist/ for it
#   misc/update-dist.sh --check       report whether dist/ is up to date
#
# Publish build/libs/jbanglite.jar as the asset of the release tagged
# v<version>, then commit dist/:
#
#   misc/update-dist.sh 0.2.0
#   gh release create v0.2.0 build/libs/jbanglite.jar
#   git add dist && git commit -m 'Release 0.2.0'
#
# --check rebuilds nothing and only compares the copied scripts; the jar and
# the JDK it cannot check, as neither is here.
#
# Needs curl, unzip and awk on top of what the build needs.
#
# Environment:
#   JBANGLITE_REPO             the GitHub repository releases are published to
#                              (default instreest/jbanglite)
#   JBANGLITE_RELEASE_BASEURL  where releases are served from
#                              (default https://github.com)
#   JBANGLITE_JVM_INDEX_BASEURL  a mirror of Maven Central for the JVM index
#
set -eu
cd "$(dirname "$0")/.."

copied="src/main/scripts/jbanglite src/main/scripts/jbanglite.cmd
        src/main/scripts/jbanglite-bootstrap-jdk src/main/scripts/jbanglite-bootstrap-jdk.cmd
        src/main/scripts/jbanglite-bootstrap-jar src/main/scripts/jbanglite-bootstrap-jar.cmd
        LICENSE"

repo=${JBANGLITE_REPO:-instreest/jbanglite}
releaseBaseUrl=${JBANGLITE_RELEASE_BASEURL:-https://github.com}
jvmIndexBaseUrl=${JBANGLITE_JVM_INDEX_BASEURL:-https://repo1.maven.org/maven2}

# The JDK the launchers install when the machine has none. It only has to run
# jbanglite.jar; the JDK a script asks for with //JAVA is installed by the jar.
bootstrapJavaVersion=25

# The platforms a project may be checked out on. Named as the JVM index names
# them, which is also what the launcher scripts compute from uname. "a:b" pins
# platform a from b's entry, for a platform Temurin does not build: Windows on
# ARM runs the x64 build under emulation, and this JDK only has to start
# jbanglite.jar. Drop the mapping once Temurin publishes windows-arm64.
platforms="linux-amd64 linux-arm64 darwin-amd64 darwin-arm64 windows-amd64 windows-arm64:windows-amd64"

if [ "${1:-}" = "--check" ]; then
  stale=
  for from in $copied; do
    cmp -s "$from" "dist/$(basename "$from")" || stale="$stale $(basename "$from")"
  done
  if [ -n "$stale" ]; then
    echo "dist/ is out of date:$stale (run misc/update-dist.sh <version>)" 1>&2
    exit 1
  fi
  echo "dist/ is up to date with the launcher scripts and LICENSE" 1>&2
  echo "(the jar and the JDK are downloads and are not checked here)" 1>&2
  exit 0
fi

version=${1:-}
if [ -z "$version" ]; then
  echo "Usage: misc/update-dist.sh <version> | --check" 1>&2
  exit 2
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/jbanglite-dist.XXXXXX")
trap 'rm -rf "$work"' EXIT

sha256_of() {
  if command -v sha256sum > /dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# Prints "<version> <archive type> <url>" of the newest Temurin
# $bootstrapJavaVersion the JVM index lists for the platform $1.
jvm_index_entry() {
  local plat=$1 base metaVersion
  base="$jvmIndexBaseUrl/io/get-coursier/jvm/indices/index-$plat"
  curl -fsSL "$base/maven-metadata.xml" -o "$work/meta.xml"
  metaVersion=$(grep -o '<release>[^<]*' "$work/meta.xml" | head -1 | cut -d'>' -f2)
  [ -n "$metaVersion" ] || { echo "No released JVM index for $plat" 1>&2; return 1; }
  curl -fsSL "$base/$metaVersion/index-$plat-$metaVersion.jar" -o "$work/index.jar"
  unzip -p "$work/index.jar" "coursier/jvm/indices/v1/$plat.json" > "$work/index.json"
  # the index lists, per distribution, a version and a "<type>+<url>" value
  awk -v want="$bootstrapJavaVersion" '
    /^[[:space:]]*"temurin"[[:space:]]*:[[:space:]]*\{/ { inside = 1; next }
    inside && /^[[:space:]]*\}/ { exit }
    inside && match($0, /"[0-9][^"]*"[[:space:]]*:[[:space:]]*"[a-z]+\+[^"]+"/) {
      split(substr($0, RSTART, RLENGTH), field, "\"")
      if (field[2] == want || index(field[2], want ".") == 1) {
        plus = index(field[4], "+")
        print field[2] " " substr(field[4], 1, plus - 1) " " substr(field[4], plus + 1)
      }
    }
  ' "$work/index.json" | sort -V | tail -1
}

echo "Resolving Temurin $bootstrapJavaVersion for: $platforms" 1>&2
jdkProperties=$work/jdk.properties
: > "$jdkProperties"
jdkVersion=
for spec in $platforms; do
  plat=${spec%%:*}
  from=${spec#*:}
  entry=$(jvm_index_entry "$from")
  [ -n "$entry" ] || { echo "No Temurin $bootstrapJavaVersion for $from in the JVM index" 1>&2; exit 1; }
  set -- $entry
  jdkVersion=$1
  type=$2
  url=$3
  # The launchers unpack without asking what the archive is: a .tar.gz on
  # POSIX, a .zip through the tar Windows ships with. Refuse to pin anything
  # else rather than let a run find out.
  case "$plat:$type" in
    windows-*:zip|linux-*:tgz|darwin-*:tgz) ;;
    *) echo "Unexpected archive type '$type' for $plat; the launchers cannot unpack it" 1>&2; exit 1;;
  esac
  curl -fsSL "$url.sha256.txt" -o "$work/jdk.sha256" \
    || { echo "Temurin publishes no SHA-256 next to $url" 1>&2; exit 1; }
  sha=$(cut -d' ' -f1 < "$work/jdk.sha256")
  [ -n "$sha" ] || { echo "Empty SHA-256 for $url" 1>&2; exit 1; }
  printf 'bootstrapJdkUrl.%s=%s\nbootstrapJdkSha256Sum.%s=%s\n' "$plat" "$url" "$plat" "$sha" >> "$jdkProperties"
  if [ "$plat" = "$from" ]; then
    echo "  $plat  $jdkVersion" 1>&2
  else
    echo "  $plat  $jdkVersion (the $from build)" 1>&2
  fi
done

./gradlew --quiet shadowJar -PjbangVersion="$version"
jarSha=$(sha256_of build/libs/jbanglite.jar)

mkdir -p dist
for from in $copied; do
  cp -f "$from" "dist/$(basename "$from")"
done

{
  cat <<EOF
# What this project runs, and what it needs to run it. A project commits this
# file, not the binaries: the launcher scripts download each one once per
# machine, into ~/.jbanglite, and check it against the SHA-256 here.
#
# Written by misc/update-dist.sh; to move to another version run install.sh
# again rather than editing by hand. JBANGLITE_DIST_URL overrides the jar's URL
# for one run, for a machine that cannot reach GitHub releases.

distributionVersion=$version
distributionUrl=$releaseBaseUrl/$repo/releases/download/v$version/jbanglite.jar
distributionSha256Sum=$jarSha

# The JDK a launcher installs when the machine has no usable Java, one entry
# per platform. It only has to run jbanglite.jar; the JDK a script asks for
# with //JAVA is installed by the jar itself.
bootstrapJdkVersion=$jdkVersion
EOF
  sort "$jdkProperties"
} > dist/jbanglite.properties

echo "dist/ refreshed for $version (bootstrap JDK $jdkVersion)" 1>&2
echo "Now publish build/libs/jbanglite.jar as the asset of release v$version, then commit dist/" 1>&2
