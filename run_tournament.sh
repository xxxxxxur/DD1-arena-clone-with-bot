#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$ROOT/java/out"

find_java() {
    local candidate=""
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        if "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; then
            echo "$JAVA_HOME/bin/java"
            return 0
        fi
    fi

    if command -v java >/dev/null 2>&1; then
        candidate="$(command -v java)"
        if "$candidate" -version >/dev/null 2>&1; then
            echo "$candidate"
            return 0
        fi
    fi

    if [[ -x /usr/libexec/java_home ]]; then
        local jh=""
        jh="$(/usr/libexec/java_home -v 22+ 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
        if [[ -n "$jh" && -x "$jh/bin/java" ]] && "$jh/bin/java" -version >/dev/null 2>&1; then
            echo "$jh/bin/java"
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
        if [[ -x "$dir/java" ]] && "$dir/java" -version >/dev/null 2>&1; then
            echo "$dir/java"
            return 0
        fi
    done

    return 1
}

if ! JAVA_BIN="$(find_java)"; then
    echo "Error: JDK 22+ (recommended: JDK 26) is required, but no working 'java' was found." >&2
    echo "Install via Homebrew:" >&2
    echo "  brew install openjdk" >&2
    echo "Then link it for macOS or add it to PATH:" >&2
    echo "  sudo ln -sfn \"\$(brew --prefix)/opt/openjdk/libexec/openjdk.jdk\" /Library/Java/JavaVirtualMachines/openjdk.jdk" >&2
    exit 1
fi

if [[ ! -f "$OUT_DIR/com/circus/Main.class" ]]; then
    "$ROOT/build.sh"
fi

exec "$JAVA_BIN" --enable-native-access=ALL-UNNAMED -cp "$OUT_DIR" com.circus.Main --tournament "$@"
