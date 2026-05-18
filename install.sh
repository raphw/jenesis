#!/usr/bin/env bash
#
# Jenesis bootstrap installer.
#
# Initializes a project's build/jenesis directory from the sources jar
# attached to a GitHub release. Designed to be piped from a curl command:
#
#     curl -fsSL https://get.jenesis.build | bash
#
# Environment variables:
#   JENESIS_VERSION       Specific version to install (default: latest GitHub release)
#   JENESIS_TARGET        Target project directory (default: current working directory)
#   JENESIS_GITHUB_REPO   Source repository, owner/name (default: raphw/jenesis)
#
# After the script completes, build the project with:
#
#     java build/jenesis/Project.java
#
set -e

GITHUB_REPO="${JENESIS_GITHUB_REPO:-raphw/jenesis}"

say() { echo "jenesis-install: $*"; }
die() { echo "jenesis-install: $*" >&2; exit 1; }

if command -v curl >/dev/null 2>&1; then
    fetch_to()     { curl -fsSL "$1" -o "$2"; }
    fetch_stdout() { curl -fsSL "$1"; }
elif command -v wget >/dev/null 2>&1; then
    fetch_to()     { wget -q "$1" -O "$2"; }
    fetch_stdout() { wget -q "$1" -O -; }
else
    die "neither 'curl' nor 'wget' found - install one and retry"
fi

if command -v unzip >/dev/null 2>&1; then
    extract() { unzip -q -d "$2" "$1"; }
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/jar" ]; then
    extract() { (cd "$2" && "${JAVA_HOME}/bin/jar" xf "$1"); }
elif command -v jar >/dev/null 2>&1; then
    extract() { (cd "$2" && jar xf "$1"); }
else
    die "no extractor found - install 'unzip' or a JDK (with 'jar' on PATH or via JAVA_HOME)"
fi

if [ -n "${JENESIS_VERSION:-}" ]; then
    VERSION="${JENESIS_VERSION#v}"
    say "installing pinned version ${VERSION}"
else
    say "resolving latest release from github.com/${GITHUB_REPO}"
    META="$(fetch_stdout "https://api.github.com/repos/${GITHUB_REPO}/releases/latest")" \
        || die "failed to query GitHub API - set JENESIS_VERSION to bypass version lookup"
    VERSION="$(printf '%s' "$META" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"v\{0,1\}\([^"]*\)".*/\1/p' | head -n1)"
    [ -n "$VERSION" ] || die "could not parse latest version from GitHub API response"
    say "latest version is ${VERSION}"
fi

JAR_URL="https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/build.jenesis-${VERSION}-sources.jar"

TARGET="${JENESIS_TARGET:-$PWD}"
[ -d "$TARGET" ] || die "target '$TARGET' is not a directory"

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

say "downloading ${JAR_URL}"
fetch_to "$JAR_URL" "$TMPDIR/sources.jar" \
    || die "failed to download $JAR_URL - check that the release exists on GitHub"

mkdir -p "$TMPDIR/extract"
extract "$TMPDIR/sources.jar" "$TMPDIR/extract" \
    || die "failed to extract sources jar"

JAR_BUILD="$TMPDIR/extract/build/jenesis"
[ -d "$JAR_BUILD" ] || die "extracted jar does not contain build/jenesis - artifact layout unexpected"

DEST="$TARGET/build/jenesis"
if [ -d "$DEST" ]; then
    if [ -f "$DEST/jenesis.version" ]; then
        say "replacing existing build/jenesis (was version $(cat "$DEST/jenesis.version"))"
    else
        say "replacing existing build/jenesis (unknown previous version)"
    fi
    rm -rf "$DEST"
fi
mkdir -p "$TARGET/build"
cp -R "$JAR_BUILD" "$DEST"
printf '%s' "$VERSION" > "$DEST/jenesis.version"
say "installed Jenesis ${VERSION} to ${DEST}"
say "next: run 'java build/jenesis/Project.java' from ${TARGET}"
