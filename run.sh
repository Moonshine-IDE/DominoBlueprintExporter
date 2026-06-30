#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run.sh – Launch DominoBlueprint on a Linux Domino server
#
# DominoBlueprint.jar bundles the project's own classes, but the licensed HCL
# Domino JARs (Notes.jar, websvc.jar, lwpd.*) are deliberately NOT bundled --
# they are supplied at runtime via the jar's manifest Class-Path.  That manifest
# lists each Domino JAR both at its absolute install path (detected at build
# time) and as ./<name>.  So either:
#   - run the jar on the same machine it was built on (absolute paths match), or
#   - copy DominoBlueprint.jar next to the Domino JARs (e.g. /local/notesjava),
#     so the ./<name> entries resolve.
#
# This script's job is to locate the Domino *native* libraries (.so files) and
# set LD_LIBRARY_PATH before calling java -jar.
#
# Usage:
#   ./run.sh <subcommand> [options]
#     export    -d <database> [-s <server>] [-o <outputDir>]
#     createdb  -d <database> [-s <server>]
#     import    -d <database> -i <dxl-file-or-dir> [-s <server>] [--acl-import=<mode>]
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
#   ./run.sh export   -s "myserver/Org" -d apps/mydb.nsf -o ./export
#   ./run.sh createdb -s "myserver/Org" -d apps/restored.nsf
#   PASSWORD=secret ./run.sh import -s "myserver/Org" -d apps/restored.nsf -i ./export
# ---------------------------------------------------------------------------

set -euo pipefail

# ---------------------------------------------------------------------------
# Locate DominoBlueprint.jar (looks next to this script, then build/libs/)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -f "$SCRIPT_DIR/DominoBlueprint.jar" ]]; then
    JAR="$SCRIPT_DIR/DominoBlueprint.jar"
elif [[ -f "$SCRIPT_DIR/build/libs/DominoBlueprint.jar" ]]; then
    JAR="$SCRIPT_DIR/build/libs/DominoBlueprint.jar"
else
    echo "ERROR: DominoBlueprint.jar not found." >&2
    echo "       Run 'gradle shadowJar' (or 'gradle build') to build it first." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Locate the Domino native libraries
#
# The Domino JNI native libraries (.so files) need to be on LD_LIBRARY_PATH.
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
        if [[ -f "$dir/libnotes.so" || -f "$dir/libnnotes.so" ]]; then
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
# Run – Domino JARs are resolved via the jar's manifest Class-Path
# ---------------------------------------------------------------------------
exec java -jar "$JAR" "$@"
