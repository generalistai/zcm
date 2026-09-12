Run `test/java/run-spy-tests.sh` from any directory. It compiles the Java tools and
runs the chart regression tests without a display or a running ZCM transport.
Set `JCHART2D_JAR` if jchart2d is installed outside `/usr/share/java`.

The tests cover collection between repaints, equal timestamps, nested fields,
changing array lengths, subscription cleanup, concurrent collection and chart
updates, and bounded history with correct axis limits. Add `--benchmark` to
compare ingestion of 100,000 constant-valued samples using the old and new trace
implementations, with 15,000 samples retained. This benchmark excludes rendering.
