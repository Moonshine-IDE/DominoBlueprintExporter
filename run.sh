#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run.sh – Launch DominoBlueprintExporter on a Linux Domino server
#
# DominoBlueprintExporter.jar is a self-contained fat JAR – all Java
# dependencies are bundled inside.  This script's only job is to locate the
# Domino *native* libraries (.so files) and set LD_LIBRARY_PATH before
# calling java -jar.
#
# Usage:
#   ./run.sh <server> <database> [outputDir]
#   ./run.sh --server <server> --database <database> [--output <dir>]
#
# Password (in order of preference):
#   1. PASSWORD env var        →  PASSWORD=secret ./run.sh ...
#   2. Interactive prompt      →  ./run.sh ...   (hidden input, no echo)
#   3. No password             →  ID has no password
#
# Override the Domino installation directory:
#   DOMINO_INSTALL=/opt/hcl/domino/notes/12.0.2/linux ./run.sh ...
#
# Examples:
#   ./run.sh "myserver/Org" apps/mydb.nsf ./export
#   PASSWORD=secret ./run.sh "myserver/Org" apps/mydb.nsf ./export
# ---------------------------------------------------------------------------

set -euo pipefail

# ---------------------------------------------------------------------------
# Locate DominoBlueprintExporter.jar (looks next to this script, then build/libs/)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -f "$SCRIPT_DIR/DominoBlueprintExporter.jar" ]]; then
    JAR="$SCRIPT_DIR/DominoBlueprintExporter.jar"
elif [[ -f "$SCRIPT_DIR/build/libs/DominoBlueprintExporter.jar" ]]; then
    JAR="$SCRIPT_DIR/build/libs/DominoBlueprintExporter.jar"
else
    echo "ERROR: DominoBlueprintExporter.jar not found." >&2
    echo "       Run 'gradle shadowJar' (or 'gradle build') to build it first." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Locate the Domino native libraries
#
# The JAR is self-contained (all Java deps bundled), but the Domino JNI
# native libraries (.so files) still need to be on LD_LIBRARY_PATH.
# DominoBlueprintExporter.jar bundles all Java deps via Shadow plugin.
#
# Search order:
#   1. DOMINO_INSTALL env var (explicit override)
#   2. Common Linux Domino install paths
#   3. Recursive search under /opt and /local
# ---------------------------------------------------------------------------
find_domino_native() {
    if [[ -n "${DOMINO_INSTALL:-}" && -d "$DOMINO_INSTALL" ]]; then
        echo "$DOMINO_INSTALL"
        return 0
    fi

    local candidates=(
        "/opt/hcl/domino/notes/latest/linux"
        "/opt/ibm/domino/notes/latest/linux"
        "/local/notesjava"          # Super.Human.Installer
        "/opt/hcl/domino"
        "/opt/ibm/domino"
    )

    for dir in "${candidates[@]}"; do
        # Look for a key native library as a signal that this is the right dir
        if [[ -f "$dir/libnotes.so" || -f "$dir/libnotes.so" ]]; then
            echo "$dir"
            return 0
        fi
    done

    # Fall back to searching for the native library
    local found
    found=$(find /opt /local -name 'libnnotes.so' -o -name 'libnotes.so' 2>/dev/null | head -1)
    if [[ -n "$found" ]]; then
        dirname "$found"
        return 0
    fi

    return 1
}

DOMINO_NATIVE=$(find_domino_native 2>/dev/null) || {
    echo "WARNING: Could not locate Domino native libraries automatically." >&2
    echo "         Set DOMINO_INSTALL=/path/to/domino/linux to specify the path," >&2
    echo "         or ensure LD_LIBRARY_PATH already includes the Domino directory." >&2
    DOMINO_NATIVE=""
}

if [[ -n "$DOMINO_NATIVE" ]]; then
    echo "Using Domino native libs : $DOMINO_NATIVE"
    export LD_LIBRARY_PATH="${DOMINO_NATIVE}:${LD_LIBRARY_PATH:-}"
fi

# ---------------------------------------------------------------------------
# Run – the JAR is self-contained; no -cp needed beyond the JAR itself
# ---------------------------------------------------------------------------
exec java -jar "$JAR" "$@"
