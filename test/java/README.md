Run `test/java/run-spy-tests.sh` from any directory. It compiles the Java tools and
runs the DPI, message inspector, chart, and rendering regression tests without a display or a running ZCM transport.
Set `JCHART2D_JAR` if jchart2d is installed outside `/usr/share/java`.

The tests cover collection between repaints, equal timestamps, nested fields,
changing array lengths, subscription cleanup, concurrent collection and chart
updates, and bounded history with correct axis limits. Add `--benchmark` to
compare ingestion of 100,000 constant-valued samples using the old and new trace
implementations, with 15,000 samples retained. This benchmark excludes rendering.

Inspector tests cover every primitive type, exact 64-bit integer text, byte
representations, Unicode and full-string access, nulls, enums, constants,
embedded objects, object arrays, ragged/multidimensional arrays, and recursive
references. They verify that array values are expanded in the main table with
indentation, that only numeric values receive a monospaced font, and that row
selection survives changing array lengths. A 1.4-million-row object array checks
arithmetic indexing without per-element metadata; large variable structures
exercise background indexing and cancellation on disposal. If `build/gen/zcm-gen`
exists, the runner also generates and round-trips real ZCM messages and bitfields.

The same `--benchmark` run also measures rendering at 2x display scale:
1,000- and 100,000-element signal arrays in a 600-pixel viewport, and eight
detailed traces with 15,000 samples each. The detailed benchmark compares the
original anti-aliased lines and per-sample markers with the streaming renderer.
These are CPU rendering benchmarks using buffered images, not GPU measurements.

Rendering tests cover scrolling and shrinking large arrays, full-rate collection
for selected offscreen fields, per-column extrema, clipping/zoom boundaries,
visible single-sample spikes, and preservation of full trace history after paint.
Marker tests check the column cap, individual markers after zooming, and isolated
samples at both 1x and 2x display scaling.
Wheel tests cover Shift/Ctrl axis selection, separate Y axes, cursor anchoring,
fractional/multiple wheel steps, and preservation of automatic scaling on untouched axes.

Workspace tests cover pause/resume with buffer overflow, time presets, nearest
sample lookup after ring-buffer rollover, shared time navigation and cursors,
independent Y ranges, hide/solo restoration, reassignment of shared Y axes,
search through large arrays and nested fields, hidden inspector subscriptions,
duplicate selection, and favorites persistence using temporary preferences.
Set `SPY_WORKSPACE_SCREENSHOT=/tmp/spy-chart.png` to save a sample workspace image.
The `--benchmark` option also measures the full chart, legend, and moving cursor
readouts with eight 15,000-point traces at 2x scale, reporting median and p95
CPU frame times after warmup.

For the optional display-backed smoke test, run
`SPY_GUI_TESTS=1 test/java/run-spy-tests.sh` with access to an X11 display.
It opens temporary synthetic inspector and chart windows using the desktop theme,
exercises array-index jumps, embedded objects, value dialogs, plotting, the picker and toolbar,
checks selections across searches and favorites, then closes its windows.
The inspector screenshot defaults to `/tmp/spy-message-inspector.png`; override
it with `SPY_INSPECTOR_SCREENSHOT`.
It does not require JNI or a running ZCM transport.

Spy refreshes inspectors and detailed charts at up to 30 Hz. Hidden inspectors
do not schedule repaints. Arrays of primitive values, strings, and objects with
fixed field layouts only build rows for the viewport. Variable object/array
hierarchies build their expanded row index in the background when large;
detailed charts render a bounded number of points per screen column using
device-pixel lines. This changes rendering only: selected chart signals still
collect every received sample up to the configured history limit
(`-Dzcm.spy.chartSize`, default 15,000).
