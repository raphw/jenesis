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
#                 existing submodule is checked out to the requested ref instead,
#                 and the new commit is staged in the superproject.
#
# The mode is detected automatically; override with JENESIS_MODE.
#
# By default the latest published release is installed. An optional argument
# pins an arbitrary git ref instead - a tag, a commit, or a branch:
#
#     curl -fsSL https://get.jenesis.build | bash               # latest release
#     curl -fsSL https://get.jenesis.build | bash -s -- v0.6.1  # a tag
#     curl -fsSL https://get.jenesis.build | bash -s -- main    # a branch
#     curl -fsSL https://get.jenesis.build | bash -s -- 1f48f48 # a commit
#
# When a ref is given, vendor mode copies the bootstrap sources straight from
# the repository tree at that ref (so unreleased branches and commits work too),
# and submodule mode checks the submodule out to it.
#
# Environment variables:
#   JENESIS_REF           Git ref to install (tag, commit, or branch); same as
#                         the positional argument, for when passing one is awkward
#   JENESIS_VERSION       Release version to install (default: latest GitHub release);
#                         ignored when a ref is given
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

# --- resolve the ref or release version to install --------------------------
#
# A positional argument (or JENESIS_REF) pins an arbitrary git ref: a tag, a
# commit, or a branch. When given, that ref is installed verbatim - the
# submodule is checked out to it, and vendor mode copies the bootstrap sources
# from the repository tree at that ref. Without it, the latest release (or
# JENESIS_VERSION) is installed from its published sources jar. RELEASE marks
# which path applies; VERSION is only set on the release path.

REF="${1:-${JENESIS_REF:-}}"
if [ -n "$REF" ]; then
    RELEASE=0
    say "using ref ${REF}"
elif [ -n "${JENESIS_VERSION:-}" ]; then
    RELEASE=1
    VERSION="${JENESIS_VERSION#v}"
    REF="v${VERSION}"
    say "using pinned version ${VERSION}"
else
    RELEASE=1
    say "resolving latest release from github.com/${GITHUB_REPO}"
    META="$(fetch_stdout "https://api.github.com/repos/${GITHUB_REPO}/releases/latest")" \
        || die "failed to query GitHub API - set JENESIS_VERSION or pass a ref to bypass version lookup"
    VERSION="$(printf '%s' "$META" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"v\{0,1\}\([^"]*\)".*/\1/p' | head -n1)"
    [ -n "$VERSION" ] || die "could not parse latest version from GitHub API response"
    REF="v${VERSION}"
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

# --- submodule mode: move the existing submodule to the requested ref --------

if [ -n "$SUBMODULE_PATH" ]; then
    command -v git >/dev/null 2>&1 || die "git is required to update a Jenesis submodule"
    SUB="$TARGET/$SUBMODULE_PATH"
    say "detected Jenesis submodule at '${SUBMODULE_PATH}' - updating to ${REF}"

    git -C "$TARGET" submodule update --init -- "$SUBMODULE_PATH" \
        || die "failed to initialize submodule '${SUBMODULE_PATH}'"
    git -C "$SUB" fetch --quiet --tags origin \
        || die "failed to fetch in submodule '${SUBMODULE_PATH}'"
    git -C "$SUB" checkout --quiet "$REF" \
        || die "failed to check out '${REF}' in '${SUBMODULE_PATH}' - does the ref exist?"
    git -C "$TARGET" add "$SUBMODULE_PATH" \
        || die "failed to stage the updated submodule pointer"

    say "updated submodule '${SUBMODULE_PATH}' to ${REF} (staged in the superproject; commit when ready)"
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

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT
mkdir -p "$TMPDIR/extract"

if [ "$RELEASE" = "1" ]; then
    # A published release: take the blessed sources jar from the release assets.
    JAR_URL="https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/build.jenesis-${VERSION}-sources.jar"
    say "downloading ${JAR_URL}"
    fetch_to "$JAR_URL" "$TMPDIR/sources.jar" \
        || die "failed to download $JAR_URL - check that the release exists on GitHub"
    extract "$TMPDIR/sources.jar" "$TMPDIR/extract" \
        || die "failed to extract sources jar"
    SRC_DIR="$TMPDIR/extract/build/jenesis"
    STAMP="$VERSION"
else
    # An arbitrary ref: take the repository tree at that ref as a zip archive
    # (works for tags, commits, and branches that have no published release).
    ZIP_URL="https://github.com/${GITHUB_REPO}/archive/${REF}.zip"
    say "downloading ${ZIP_URL}"
    fetch_to "$ZIP_URL" "$TMPDIR/source.zip" \
        || die "failed to download $ZIP_URL - does the ref '${REF}' exist on github.com/${GITHUB_REPO}?"
    extract "$TMPDIR/source.zip" "$TMPDIR/extract" \
        || die "failed to extract the source archive"
    SRC_DIR="$(find "$TMPDIR/extract" -type d -path '*/sources/build/jenesis' 2>/dev/null | head -n1)"
    STAMP="$REF"
fi

[ -n "$SRC_DIR" ] && [ -d "$SRC_DIR" ] \
    || die "could not locate build/jenesis in the downloaded sources - artifact layout unexpected"

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
cp -R "$SRC_DIR" "$DEST"
printf '%s' "$STAMP" > "$DEST/jenesis.version"
say "installed Jenesis ${STAMP} to ${DEST}"
say "next: run 'java build/jenesis/Project.java' from ${TARGET}"
