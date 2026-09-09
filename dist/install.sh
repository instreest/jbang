#!/usr/bin/env bash
#
# Installs the JBangLite wrapper into a project, so that the project can be
# built and run without JBangLite (or a JDK) being installed on the machine:
# only the small launcher scripts are committed, and they fetch jbang.jar - and
# a JDK, if needed - on first use.
#
#   curl -Ls https://raw.githubusercontent.com/instreest/jbang/main/dist/install.sh | bash
#
# Running it again updates an existing installation: the launchers, this script
# and the pinned jar revision are refreshed and the cached jar is dropped, so
# the next run picks up the new one.
#
# Usage: install.sh [<target directory>]   (default: ./jbangw, or the directory
#                                           this script was installed in)
#
# Environment:
#   JBANGLITE_REPO          GitHub repository to install from (default instreest/jbang)
#   JBANGLITE_REF           branch, tag or commit to install (default main)
#   JBANGLITE_RAW_BASEURL   where raw files are served from
#                           (default https://raw.githubusercontent.com)
set -eu

repo=${JBANGLITE_REPO:-instreest/jbang}
ref=${JBANGLITE_REF:-main}
rawBaseUrl=${JBANGLITE_RAW_BASEURL:-https://raw.githubusercontent.com}
base="$rawBaseUrl/$repo/$ref/dist"

# Everything that is installed lives in dist/, as "<file in dist> <name in the
# wrapper>"; jbang.jar is not copied but downloaded on first use
files="
jbang jbang
jbang.cmd jbang.cmd
jbang.ps1 jbang.ps1
install.sh install.sh
install.cmd install.cmd
README.md README.md
gitignore .gitignore
LICENSE LICENSE
"

fetch() {  # $1 = path in the repository, $2 = file to write
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
elif [ -n "${BASH_SOURCE[0]:-}" ] && [ "$(basename "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)")" = jbangw ]; then
  # updating an existing installation
  dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
else
  dir=$PWD/jbangw
fi

# Everything is fetched into a staging directory first, so a failed download
# leaves an existing installation as it was
staging=$(mktemp -d "${TMPDIR:-/tmp}/jbanglite.XXXXXX")
trap 'rm -rf "$staging"' EXIT

echo "Installing the JBangLite wrapper from $repo ($ref) into $dir" 1>&2
echo "$files" | while read -r from to; do
  [ -n "$from" ] || continue
  fetch "$from" "$staging/$to"
done
fetch jbang.jar.sha256 "$staging/jar.sha256"

cat > "$staging/jbanglite.properties" <<PROPS
# Written by install.sh - where the launchers get jbang.jar from.
# Re-run install.sh (or install.cmd) to update; set JBANGLITE_REF to pin
# another revision.
repo=$repo
ref=$ref
jarSha256=$(cut -d' ' -f1 < "$staging/jar.sha256")
PROPS
rm -f "$staging/jar.sha256"

mkdir -p "$dir"
for f in "$staging"/* "$staging"/.gitignore; do
  cp -f "$f" "$dir/$(basename "$f")"
done
chmod +x "$dir/jbang" "$dir/install.sh"
# drop the cached jar so the next run downloads the one this revision pins
rm -rf "$dir/.jbang"

echo "Installed. Commit $(basename "$dir")/ and run '$(basename "$dir")/jbang <script.java>'." 1>&2
