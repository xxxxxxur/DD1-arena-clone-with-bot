#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$ROOT/java/out"
TEST_OUT_DIR="$ROOT/java/out-test"

find_javac() {
    local candidate=""
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
        if "$JAVA_HOME/bin/javac" -version >/dev/null 2>&1; then
            echo "$JAVA_HOME/bin/javac"
            return 0
        fi
    fi

    if command -v javac >/dev/null 2>&1; then
        candidate="$(command -v javac)"
        if "$candidate" -version >/dev/null 2>&1; then
            echo "$candidate"
            return 0
        fi
    fi

    if [[ -x /usr/libexec/java_home ]]; then
        local jh=""
        jh="$(/usr/libexec/java_home -v 22+ 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
        if [[ -n "$jh" && -x "$jh/bin/javac" ]] && "$jh/bin/javac" -version >/dev/null 2>&1; then
            echo "$jh/bin/javac"
            return 0
        fi
    fi

    local dir
    for dir in \
        /opt/homebrew/opt/openjdk/bin \
        /opt/homebrew/opt/openjdk@26/bin \
        /opt/homebrew/opt/openjdk@25/bin \
        /opt/homebrew/opt/openjdk@24/bin \
        /opt/homebrew/opt/openjdk@23/bin \
        /opt/homebrew/opt/openjdk@22/bin \
        /usr/local/opt/openjdk/bin \
        /usr/local/opt/openjdk@26/bin \
        /usr/local/opt/openjdk@25/bin \
        /usr/local/opt/openjdk@24/bin \
        /usr/local/opt/openjdk@23/bin \
        /usr/local/opt/openjdk@22/bin \
        "${HOME}/.sdkman/candidates/java/current/bin"
    do
        if [[ -x "$dir/javac" ]] && "$dir/javac" -version >/dev/null 2>&1; then
            echo "$dir/javac"
            return 0
        fi
    done

    return 1
}

if ! JAVAC_BIN="$(find_javac)"; then
    echo "Error: JDK 22+ (recommended: JDK 26) is required, but no working 'javac' was found." >&2
    echo "Install via Homebrew:" >&2
    echo "  brew install openjdk" >&2
    echo "Then link it for macOS or add it to PATH:" >&2
    echo "  sudo ln -sfn \"\$(brew --prefix)/opt/openjdk/libexec/openjdk.jdk\" /Library/Java/JavaVirtualMachines/openjdk.jdk" >&2
    exit 1
fi

JAVAC_VERSION_STR="$("$JAVAC_BIN" -version 2>&1)"
JAVA_MAJOR="$(printf '%s\n' "$JAVAC_VERSION_STR" | sed -E 's/^[^0-9]*([0-9]+).*/\1/')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 22 ]]; then
    echo "Error: JDK 22+ is required for java.lang.foreign (FFM API). Found: $JAVAC_VERSION_STR" >&2
    exit 1
fi

RELEASE_FLAGS=()
if [[ "$JAVA_MAJOR" -ge 26 ]]; then
    RELEASE_FLAGS=(--release 26)
else
    RELEASE_FLAGS=(--release "$JAVA_MAJOR")
fi

mkdir -p "$OUT_DIR" "$TEST_OUT_DIR"

SRC_FILES=()
while IFS= read -r -d '' f; do
    SRC_FILES+=("$f")
done < <(find "$ROOT/java/src" -type f -name "*.java" -print0)

if [[ -d "$ROOT/bot" ]]; then
    while IFS= read -r -d '' f; do
        SRC_FILES+=("$f")
    done < <(find "$ROOT/bot" -type f -name "*.java" -print0)
fi

echo "Compiling ${#SRC_FILES[@]} Java source files (java/src + bot/) with $JAVAC_VERSION_STR..."
"$JAVAC_BIN" "${RELEASE_FLAGS[@]}" -encoding UTF-8 -d "$OUT_DIR" "${SRC_FILES[@]}"

TEST_FILES=()
if [[ -d "$ROOT/java/test" ]]; then
    while IFS= read -r -d '' f; do
        TEST_FILES+=("$f")
    done < <(find "$ROOT/java/test" -type f -name "*.java" -print0)
fi

if [[ "${#TEST_FILES[@]}" -gt 0 ]]; then
    echo "Compiling ${#TEST_FILES[@]} Java test files..."
    "$JAVAC_BIN" "${RELEASE_FLAGS[@]}" -encoding UTF-8 -cp "$OUT_DIR" -d "$TEST_OUT_DIR" "${TEST_FILES[@]}"
fi

echo "Build succeeded -> $OUT_DIR"
