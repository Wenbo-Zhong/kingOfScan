#!/bin/sh
set -eu

if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = "YES" ]; then
    echo "Skipping Kotlin framework build for the IDE integration."
    exit 0
fi

kingscan_usable_java() {
    [ -x "$1/bin/java" ] || return 1
    kingscan_java_major="$("$1/bin/java" -XshowSettings:properties -version 2>&1 |
        /usr/bin/awk -F '= ' '/^[[:space:]]*java.specification.version =/ { print $2 }')"
    case "$kingscan_java_major" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$kingscan_java_major" -ge 17 ]
}

if ! kingscan_usable_java "${JAVA_HOME:-}"; then
    kingscan_registered_java="$(/usr/libexec/java_home -v '17+' 2>/dev/null || true)"
    kingscan_selected_java=""
    for kingscan_candidate in "$kingscan_registered_java" \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "$HOME"/Library/Java/JetBrainsRuntime/*/Contents/Home; do
        if kingscan_usable_java "$kingscan_candidate"; then
            kingscan_selected_java="$kingscan_candidate"
            break
        fi
    done
    if [ -z "$kingscan_selected_java" ]; then
        echo "KingOfScan requires JDK 17 or newer. Set JAVA_HOME or install a supported JDK." >&2
        exit 1
    fi
    JAVA_HOME="$kingscan_selected_java"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

kingscan_repository="$(CDPATH= cd "$(dirname "$0")/../.." && pwd)"
cd "$kingscan_repository"
echo "Building Kotlin framework with JAVA_HOME=$JAVA_HOME"
exec ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
