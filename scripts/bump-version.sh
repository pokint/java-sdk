#!/usr/bin/env bash
# Bump the project version across every pom.xml in the reactor.
#
# Usage:
#   scripts/bump-version.sh <new-version> [--dry]
#
# Examples:
#   scripts/bump-version.sh 2.0-5p-2-SNAPSHOT       # rewrites every pom.xml
#   scripts/bump-version.sh 2.0-5p-2 --dry          # preview only, no writes
#
# Default mode writes the change. Pass --dry to preview without touching files.

set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $0 <new-version> [--dry]" >&2
    exit 64
fi

NEW_VERSION="$1"
DRY="false"
if [[ ${2:-} == "--dry" ]]; then
    DRY="true"
fi

if ! [[ $NEW_VERSION =~ ^[0-9A-Za-z.-]+$ ]]; then
    echo "Error: invalid version '$NEW_VERSION' (allowed: [0-9A-Za-z.-])" >&2
    exit 64
fi

# Locate every pom.xml under the project root, excluding target/ build dirs.
mapfile -t POM_FILES < <(find . -name pom.xml -not -path '*/target/*' -not -path '*/node_modules/*' | sort)

if [[ ${#POM_FILES[@]} -eq 0 ]]; then
    echo "Error: no pom.xml files found" >&2
    exit 1
fi

# Determine the current version from the parent pom (first <version>...</version>
# in the root pom.xml, which is the reactor's <version>).
ROOT_POM="$(find . -maxdepth 2 -name pom.xml -not -path '*/target/*' | head -n1)"
CURRENT_VERSION="$(grep -oE '<version>[^<]+</version>' "$ROOT_POM" | head -n1 | sed -E 's|</?version>||g')"

if [[ -z $CURRENT_VERSION ]]; then
    echo "Error: could not detect current version in $ROOT_POM" >&2
    exit 1
fi

if [[ $CURRENT_VERSION == "$NEW_VERSION" ]]; then
    echo "Nothing to do: current version is already $CURRENT_VERSION" >&2
    exit 0
fi

echo "Bumping version: $CURRENT_VERSION -> $NEW_VERSION"
echo "Files to update: ${#POM_FILES[@]}"
echo

CHANGED=0
for pom in "${POM_FILES[@]}"; do
    if grep -q "<version>$CURRENT_VERSION</version>" "$pom"; then
        if [[ $DRY == "true" ]]; then
            echo "  would update  $pom"
        else
            sed -i "s|<version>$CURRENT_VERSION</version>|<version>$NEW_VERSION</version>|g" "$pom"
            echo "  updated  $pom"
        fi
        CHANGED=$((CHANGED + 1))
    fi
done

echo
if [[ $DRY == "true" ]]; then
    echo "Dry run: $CHANGED file(s) would change. Re-run without --dry to write."
else
    if [[ $CHANGED -eq 0 ]]; then
        echo "No pom.xml contained <version>$CURRENT_VERSION</version>; nothing changed."
        exit 1
    fi
    echo "Updated $CHANGED file(s). Review with: git diff"
    echo "If it looks right, commit and run: mvn -N validate"
fi
