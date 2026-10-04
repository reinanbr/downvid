#!/usr/bin/env bash
# Prints the CHANGELOG.md section of a version (used as GitHub release notes).
# Usage: scripts/changelog_section.sh 0.1.0
set -euo pipefail

version="${1#v}"
file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/CHANGELOG.md"

section="$(awk -v ver="$version" '
  /^## \[/ {
    if (found) exit
    if (index($0, "## [" ver "]") == 1) { found = 1; next }
  }
  found
' "$file")"

if [[ -z "${section//[[:space:]]/}" ]]; then
  echo "No CHANGELOG.md section for version $version" >&2
  exit 1
fi
printf '%s\n' "$section"
