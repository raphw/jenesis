#!/usr/bin/env bash
#
# Jenesis bootstrap installer.
#
# Installs or updates Jenesis in a project. Two modes:
#
#   * vendor    - copy the bootstrap sources from a release's sources jar into
#                 build/jenesis (self-contained, no git required). This is the
#                 default for projects that do not track Jenesis as a submodule.
#   * submodule - when the project already tracks Jenesis as a git submodule
#                 (a .gitmodules entry whose URL points at the Jenesis repo), the
#                 existing submodule is checked out to the latest release tag
#                 instead, and the new commit is staged in the superproject.
#
# The mode is detected automatically; override with JENESIS_MODE.
#
# Designed to be piped from a curl command:
#
#     curl -fsSL https://get.jenesis.build | bash
#
# Environment variables:
#   JENESIS_VERSION       Specific version to install (default: latest GitHub release)
#   JENESIS_TARGET        Target project directory (default: current working directory)
#   JENESIS_GITHUB_REPO   Source repository, owner/name (default: raphw/jenesis)
#   JENESIS_MODE          auto (default) | vendor | submodule
#
# After the script completes, build the project with:
#
#     java build/jenesis/Project.java
#
set -e

GITHUB_REPO="${JENESIS_GITHUB_REPO:-raphw/jenesis}"
MODE="${JENESIS_MODE:-auto}"

say() { echo "jenesis-install: $*"; }
die() { echo "jenesis-install: $*" >&2; exit 1; }

case "$MODE" in
    auto|vendor|submodule) ;;
    *) die "JENESIS_MODE must be one of: auto, vendor, submodule (got '$MODE')" ;;
esac

if command -v curl >/dev/null 2>&1; then
    fetch_to()     { curl -fsSL "$1" -o "$2"; }
    fetch_stdout() { curl -fsSL "$1"; }
elif command -v wget >/dev/null 2>&1; then
    fetch_to()     { wget -q "$1" -O "$2"; }
    fetch_stdout() { wget -q "$1" -O -; }
else
    die "neither 'curl' nor 'wget' found - install one and retry"
fi

TARGET="${JENESIS_TARGET:-$PWD}"
[ -d "$TARGET" ] || die "target '$TARGET' is not a directory"

# --- resolve the version to install -----------------------------------------

if [ -n "${JENESIS_VERSION:-}" ]; then
    VERSION="${JENESIS_VERSION#v}"
    say "using pinned version ${VERSION}"
else
    say "resolving latest release from github.com/${GITHUB_REPO}"
    META="$(fetch_stdout "https://api.github.com/repos/${GITHUB_REPO}/releases/latest")" \
        || die "failed to query GitHub API - set JENESIS_VERSION to bypass version lookup"
    VERSION="$(printf '%s' "$META" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"v\{0,1\}\([^"]*\)".*/\1/p' | head -n1)"
    [ -n "$VERSION" ] || die "could not parse latest version from GitHub API response"
    say "latest version is ${VERSION}"
fi

# --- detect whether the project tracks Jenesis as a submodule ----------------

# Prints the submodule path if .gitmodules has an entry whose URL points at the
# Jenesis repo, otherwise prints nothing. Always returns 0 so it is safe to call
# from a command substitution under 'set -e'.
detect_submodule_path() {
    local key url name
    command -v git >/dev/null 2>&1 || return 0
    [ -f "$TARGET/.gitmodules" ] || return 0
    git config -f "$TARGET/.gitmodules" --get-regexp '^submodule\..*\.url$' 2>/dev/null \
    | while IFS=' ' read -r key url; do
        case "$url" in
            *"${GITHUB_REPO}" | *"${GITHUB_REPO}.git" | *"${GITHUB_REPO}/")
                name="${key#submodule.}"; name="${name%.url}"
                git config -f "$TARGET/.gitmodules" --get "submodule.${name}.path"
                break
                ;;
        esac
    done
    return 0
}

SUBMODULE_PATH=""
if [ "$MODE" != "vendor" ]; then
    SUBMODULE_PATH="$(detect_submodule_path)"
fi

if [ "$MODE" = "submodule" ] && [ -z "$SUBMODULE_PATH" ]; then
    command -v git >/dev/null 2>&1 || die "JENESIS_MODE=submodule requires git, which was not found"
    die "JENESIS_MODE=submodule but no Jenesis submodule (a .gitmodules entry for ${GITHUB_REPO}) was found under ${TARGET}"
fi

# --- submodule mode: move the existing submodule to the release tag ----------

if [ -n "$SUBMODULE_PATH" ]; then
    command -v git >/dev/null 2>&1 || die "git is required to update a Jenesis submodule"
    SUB="$TARGET/$SUBMODULE_PATH"
    say "detected Jenesis submodule at '${SUBMODULE_PATH}' - updating to v${VERSION}"

    git -C "$TARGET" submodule update --init -- "$SUBMODULE_PATH" \
        || die "failed to initialize submodule '${SUBMODULE_PATH}'"
    git -C "$SUB" fetch --tags --quiet \
        || die "failed to fetch tags in submodule '${SUBMODULE_PATH}'"
    git -C "$SUB" checkout --quiet "v${VERSION}" \
        || die "failed to check out 'v${VERSION}' in '${SUBMODULE_PATH}' - does the tag exist?"
    git -C "$TARGET" add "$SUBMODULE_PATH" \
        || die "failed to stage the updated submodule pointer"

    say "updated submodule '${SUBMODULE_PATH}' to v${VERSION} (staged in the superproject; commit when ready)"
    say "next: run 'java build/jenesis/Project.java' from ${TARGET}"
    exit 0
fi

# --- vendor mode: copy the bootstrap sources into build/jenesis --------------

if command -v unzip >/dev/null 2>&1; then
    extract() { unzip -q -d "$2" "$1"; }
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/jar" ]; then
    extract() { (cd "$2" && "${JAVA_HOME}/bin/jar" xf "$1"); }
elif command -v jar >/dev/null 2>&1; then
    extract() { (cd "$2" && jar xf "$1"); }
else
    die "no extractor found - install 'unzip' or a JDK (with 'jar' on PATH or via JAVA_HOME)"
fi

JAR_URL="https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/build.jenesis-${VERSION}-sources.jar"

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
