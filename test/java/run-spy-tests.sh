#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
chart_jar=${JCHART2D_JAR:-/usr/share/java/jchart2d-3.2.2.jar}
if [[ ! -f "$chart_jar" ]]; then
    echo "Set JCHART2D_JAR to the jchart2d jar used by zcm-spy." >&2
    exit 1
fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
mapfile -t sources < <(find "$root/tools/java/zcm" "$root/zcm/java/zcm" -name '*.java')
javac -cp "$chart_jar" -d "$classes" "${sources[@]}" \
    "$root/test/java/zcm/spy/ChartStreamingTest.java" \
    "$root/test/java/zcm/spy/RenderPerformanceTest.java" \
    "$root/test/java/zcm/spy/SpyUiScaleTest.java"
java -Djava.awt.headless=true -ea -cp "$classes:$chart_jar" zcm.spy.SpyUiScaleTest

# Exercise startup detection in fresh JVMs using deterministic X11 responses.
mkdir "$classes/bin"
cat > "$classes/bin/xrdb" <<'EOF'
#!/bin/sh
printf 'Xft.dpi: %s\n' "$SPY_TEST_XFT_DPI"
EOF
cat > "$classes/bin/xdpyinfo" <<'EOF'
#!/bin/sh
printf '  resolution: %sx%s dots per inch\n' "$SPY_TEST_DPI" "$SPY_TEST_DPI"
EOF
cat > "$classes/bin/xrandr" <<'EOF'
#!/bin/sh
printf '%s\n' "$SPY_TEST_MONITORS"
EOF
chmod +x "$classes/bin/xrdb" "$classes/bin/xdpyinfo" "$classes/bin/xrandr"
check_dpi() {
    local xft=$1 dpi=$2 expected=$3
    shift 3
    env -u GDK_SCALE -u QT_SCALE_FACTOR -u QT_SCREEN_SCALE_FACTORS \
        -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS \
        DISPLAY=:test PATH="$classes/bin:$PATH" SPY_TEST_XFT_DPI="$xft" SPY_TEST_DPI="$dpi" \
        SPY_TEST_MONITORS="${SPY_TEST_MONITORS:-}" \
        QT_SCALE_FACTOR=1.5 QT_SCREEN_SCALE_FACTORS='eDP-1=1.5;DP-1=3;' \
        java -Djava.awt.headless=true -Dos.name=Linux "$@" -ea \
        -cp "$classes:$chart_jar" zcm.spy.SpyUiScaleTest "$expected"
}
check_dpi 192 96 2
check_dpi 96 192 automatic
check_dpi 144 96 automatic
check_dpi invalid 148 automatic
check_dpi invalid 288 3
check_dpi invalid invalid automatic
check_dpi 192 192 1 -Dsun.java2d.uiScale=1
check_dpi 192 192 automatic -Dsun.java2d.uiScale.enabled=false
check_dpi 192 192 automatic -Dos.name=Windows
SPY_TEST_MONITORS='0: +*eDP-1 1920/301x1200/188+0+0 eDP-1' check_dpi invalid 96 automatic
SPY_TEST_MONITORS='0: +*eDP-1 3840/301x2400/188+0+0 eDP-1' check_dpi invalid 96 3

java -Djava.awt.headless=true -ea -cp "$classes:$chart_jar" zcm.spy.ChartStreamingTest "$@"
java -Djava.awt.headless=true -ea -cp "$classes:$chart_jar" zcm.spy.RenderPerformanceTest "$@"
