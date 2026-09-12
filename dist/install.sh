#!/usr/bin/env bash
#
# Installs JBangLite into a project: the launcher scripts, this installer and
# jbanglite.properties go into jbanglite/, which is committed, so the project
# can be built and run without JBangLite (or a JDK) being installed on the
# machine.
#
#   curl -fsSL https://github.com/instreest/jbanglite/releases/latest/download/install.sh | bash
#
# Everything comes from a GitHub release, over https. jbanglite.jar and a JDK
# are not installed here: jbanglite.properties pins the version, URL and
# SHA-256 of each, and the launcher downloads and verifies them once per
# machine, into ~/.jbanglite. So a project's history carries about 50 kB of
# scripts rather than binaries. A project that would rather vendor the jar can
# drop a jbanglite.jar into jbanglite/ next to the launcher, and then only a
# JDK is ever fetched.
#
# Running it again updates an existing installation: every file, the properties
# included, is replaced by the one from the chosen release.
#
# Usage: install.sh [<target directory>]   (default: ./jbanglite, or the directory
#                                           this script was installed in)
#
# Environment:
#   JBANGLITE_REPO          GitHub repository to install from (default instreest/jbanglite)
#   JBANGLITE_REF           release tag to install (default: the latest release)
#   JBANGLITE_DIST_BASEURL  install from here instead of from a GitHub release
set -eu

repo=${JBANGLITE_REPO:-instreest/jbanglite}
ref=${JBANGLITE_REF:-}
if [ -n "${JBANGLITE_DIST_BASEURL:-}" ]; then
  base=$JBANGLITE_DIST_BASEURL
elif [ -n "$ref" ]; then
  base="https://github.com/$repo/releases/download/$ref"
else
  # GitHub redirects this to the newest release, so no release has to be looked
  # up and no API has to be called
  base="https://github.com/$repo/releases/latest/download"
fi

# A plaintext install would let anyone on the path replace the scripts a
# project is about to commit. A loopback address is allowed so that the tests
# can serve a release locally.
case "$base" in
  https://*) ;;
  http://127.0.0.1[:/]*|http://localhost[:/]*) ;;
  *) echo "Refusing to install over anything but https: $base" 1>&2; exit 1;;
esac

# what a project gets; dist/ in the repository holds the same set
files="jbanglite jbanglite.cmd jbanglite-bootstrap-jdk jbanglite-bootstrap-jdk.cmd
       jbanglite-bootstrap-jar jbanglite-bootstrap-jar.cmd jbanglite.properties
       install.sh install.cmd README.md LICENSE"

fetch() {  # $1 = file to fetch, $2 = file to write
  if command -v curl > /dev/null 2>&1; then
    curl -fsSL --proto '=https,http' --proto-redir '=https' "$base/$1" -o "$2"
  elif command -v wget > /dev/null 2>&1; then
    wget -q "$base/$1" -O "$2"
  else
    echo "Neither curl nor wget is available" 1>&2
    exit 1
  fi
}

if [ $# -gt 0 ]; then
  dir=$1
elif [ -n "${BASH_SOURCE[0]:-}" ] && [ "$(basename "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)")" = jbanglite ]; then
  # updating an existing installation
  dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
else
  dir=$PWD/jbanglite
fi

# Everything is fetched into a staging directory first, so a failed download
# leaves an existing installation as it was
staging=$(mktemp -d "${TMPDIR:-/tmp}/jbanglite.XXXXXX")
trap 'rm -rf "$staging"' EXIT

echo "Installing JBangLite from $base into $dir" 1>&2
for f in $files; do
  fetch "$f" "$staging/$f"
done

mkdir -p "$dir"
for f in $files; do
  cp -f "$staging/$f" "$dir/$f"
done
chmod +x "$dir/jbanglite" "$dir/jbanglite-bootstrap-jdk" "$dir/jbanglite-bootstrap-jar" "$dir/install.sh"

echo "Installed. Commit $(basename "$dir")/ and run '$(basename "$dir")/jbanglite <script.java>'." 1>&2
