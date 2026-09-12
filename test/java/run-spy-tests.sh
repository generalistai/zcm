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
    "$root/test/java/zcm/spy/ChartStreamingTest.java"
java -Djava.awt.headless=true -ea -cp "$classes:$chart_jar" zcm.spy.ChartStreamingTest "$@"
