#!/usr/bin/env bash
#
# Installs JBangLite into a project: the launcher scripts, this installer and
# jbanglite.properties go into jbanglite/, which is committed, so the project
# can be built and run without JBangLite (or a JDK) being installed on the
# machine.
#
#   curl -Ls https://raw.githubusercontent.com/instreest/jbanglite/main/dist/install.sh | bash
#
# jbanglite.jar itself is not installed and not committed: jbanglite.properties
# pins its version, URL and SHA-256, and the launcher has
# jbanglite-bootstrap-jar download it once per machine into
# ~/.jbang/cache/jbanglite/<version>. So a project's history carries about
# 50 kB of scripts rather than a 2 MB binary per update. A project that would
# rather vendor the jar can drop it into jbanglite/ next to the launcher, and
# then nothing is downloaded.
#
# Running it again updates an existing installation: every file, the properties
# included, is replaced by the one from the chosen revision.
#
# Usage: install.sh [<target directory>]   (default: ./jbanglite, or the directory
#                                           this script was installed in)
#
# Environment:
#   JBANGLITE_REPO          GitHub repository to install from (default instreest/jbanglite)
#   JBANGLITE_REF           branch, tag or commit to install (default main)
#   JBANGLITE_RAW_BASEURL   where raw files are served from
#                           (default https://raw.githubusercontent.com)
set -eu

repo=${JBANGLITE_REPO:-instreest/jbanglite}
ref=${JBANGLITE_REF:-main}
rawBaseUrl=${JBANGLITE_RAW_BASEURL:-https://raw.githubusercontent.com}
base="$rawBaseUrl/$repo/$ref/dist"

# dist/ in the repository is exactly what a project gets
files="jbanglite jbanglite.cmd jbanglite-bootstrap-jdk jbanglite-bootstrap-jdk.cmd
       jbanglite-bootstrap-jar jbanglite-bootstrap-jar.cmd jbanglite.properties
       install.sh install.cmd README.md LICENSE"

fetch() {  # $1 = file in dist/, $2 = file to write
  if command -v curl > /dev/null 2>&1; then
    curl -fsSL "$base/$1" -o "$2"
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

echo "Installing JBangLite from $repo ($ref) into $dir" 1>&2
for f in $files; do
  fetch "$f" "$staging/$f"
done

mkdir -p "$dir"
for f in $files; do
  cp -f "$staging/$f" "$dir/$f"
done
chmod +x "$dir/jbanglite" "$dir/jbanglite-bootstrap-jdk" "$dir/jbanglite-bootstrap-jar" "$dir/install.sh"

echo "Installed. Commit $(basename "$dir")/ and run '$(basename "$dir")/jbanglite <script.java>'." 1>&2
