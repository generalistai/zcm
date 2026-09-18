package zcm.spy;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.util.*;
import javax.swing.*;
import info.monitorenter.gui.chart.Chart2D;

/** Decoded type coverage, expanded hierarchy, virtual indexing, and live shape changes. */
public class MessageInspectorTest
{
    public enum Mode { READY, RUNNING }
    public static class Position { public double x = 1.25, y = -2.5, z = 3.75; }
    public static class Embedded { public long timestamp = Long.MAX_VALUE; public Position position = new Position(); public boolean valid = true; }
    public static class Dynamic { public String name = "nested"; public Embedded pose = new Embedded(); public double[] samples = {5, 6}; }
    public static class Cycle { public int value = 1; public Cycle next; }
    public static class Message {
        public static final long ZCM_FINGERPRINT = 1, ZCM_FINGERPRINT_BASE = 2;
        public static final boolean IS_LITTLE_ENDIAN = true;
        public static final int CONSTANT = 42;
        public byte octet = (byte)255;
        public short small = Short.MIN_VALUE;
        public int integer = Integer.MIN_VALUE;
        public long big = Long.MAX_VALUE;
        public float fraction = Float.MIN_VALUE;
        public double precise = -Double.MAX_VALUE;
        public boolean enabled = true;
        public char character = '\u03bb';
        public String text = "Hello\n\"世界\"\t😀", nullable = null;
        public Mode mode = Mode.READY;
        public byte[] bytes = {0, -1};
        public short[] shorts = {Short.MIN_VALUE, Short.MAX_VALUE};
        public int[] ints = {Integer.MIN_VALUE, Integer.MAX_VALUE};
        public long[] longs = {Long.MIN_VALUE, Long.MAX_VALUE};
        public float[] floats = {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY};
        public double[] doubles = {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        public boolean[] flags = {false, true};
        public char[] characters = {'a', '\t'};
        public String[] strings = {"first", null, "last"};
        public Mode[] modes = {Mode.READY, Mode.RUNNING};
        public double[][] matrix = {{1, 2}, null, {}, {3}};
        public Embedded embedded = new Embedded();
        public Embedded[] objects = {new Embedded(), null, new Embedded()};
        public Dynamic[] dynamic = {new Dynamic(), new Dynamic()};
        public Object[] mixed = {"string", 10, new Position(), new int[] {8, 9}};
        public Cycle cycle = new Cycle();
        public double tail = 99;
        Message() { cycle.next = cycle; }
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container) layout((Container)child);
    }
    static MessageValue.Path path(Object root, String expression) {
        MessageValue.Path path = MessageValue.Path.ROOT;
        java.util.regex.Matcher tokens = java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)|\\[(\\d+)\\]").matcher(expression);
        Object value = root; Class<?> type = root.getClass();
        try {
            while (tokens.find()) {
                Object step;
                if (tokens.group(1) != null) {
                    Field field = type.getField(tokens.group(1)); step = field; type = field.getType();
                } else { step = Integer.parseInt(tokens.group(2)); type = type.getComponentType(); }
                path = path.append(step, type); value = MessageValue.child(value, step);
                if (value != null && value != MessageValue.MISSING && !type.isPrimitive()) type = value.getClass();
            }
            return path;
        } catch (Exception e) { throw new AssertionError(expression, e); }
    }
    static void paint(ObjectPanel panel) {
        panel.refreshView(); layout(panel);
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); try { panel.paint(g); } finally { g.dispose(); }
    }
    private static int row(ObjectPanel panel, Object message, String expression) {
        long index = panel.values.layout.indexOf(path(message, expression));
        check(index >= 0 && index < panel.values.totalRows(), "Missing expanded row: " + expression);
        ObjectPanel.Row row = panel.values.layout.row(index);
        check(row.path.equals(path(message, expression)), "Wrong index mapping: " + expression);
        return (int)index;
    }
    private static void allTypes() {
        Message message = new Message();
        ChartData charts = new ChartData(0); ObjectPanel panel = new ObjectPanel("ROBOT_STATE", charts);
        Chart2D renderer = new Chart2D(); StreamingTrace trace = null;
        try {
            panel.setSize(1100, 700); panel.setObject(message, 0); paint(panel);
            for (String field : new String[] {"bytes[1]", "shorts[1]", "ints[1]", "longs[1]", "floats[2]", "doubles[2]", "flags[1]",
                    "characters[1]", "strings[2]", "modes[1]", "matrix[0][1]", "matrix[3][0]", "embedded.position.z",
                    "objects[2].position.y", "objects[1]", "dynamic[1].pose.position.z", "dynamic[1].samples[1]", "mixed[3][1]", "tail"})
                row(panel, message, field);
            check(panel.values.getValueAt(row(panel, message, "big"), 2).equals("9223372036854775807"), "64-bit value rounded");
            check(panel.values.getValueAt(row(panel, message, "octet"), 2).toString().contains("255 unsigned  /  0xFF"), "Byte representations lost");
            check(panel.values.getValueAt(row(panel, message, "nullable"), 2).equals("null"), "Null string lost");
            check(panel.values.getValueAt(row(panel, message, "text"), 2).toString().contains("\\n\\\"世界"), "String escaping broken");
            check(MessageValue.format(Double.NaN).equals("NaN") && MessageValue.format(Double.POSITIVE_INFINITY).equals("Infinity"), "Nonfinite numbers lost");
            check(MessageValue.format(Mode.READY).equals("READY"), "Enum name missing");
            check(panel.values.row(row(panel, message, "cycle.next")).recursive, "Cycle was traversed indefinitely");
            check(!panel.values.row(row(panel, message, "CONSTANT")).plottable(), "Constant became a signal");
            for (Field field : MessageValue.fields(Message.class)) check(!field.getName().startsWith("ZCM_") && !field.getName().equals("IS_LITTLE_ENDIAN"), "Internal metadata exposed");
            String full = String.join("", Collections.nCopies(2000, "世界😀\n"));
            check(MessageValue.format(full).length() < 4096 && MessageValue.copyValue(full).equals(full), "Long string preview unbounded or full copy truncated");
            int array = row(panel, message, "bytes"), element = row(panel, message, "bytes[0]");
            Insets outer = ((JComponent)panel.table.prepareRenderer(panel.table.getCellRenderer(array, 0), array, 0)).getInsets();
            Insets inner = ((JComponent)panel.table.prepareRenderer(panel.table.getCellRenderer(element, 0), element, 0)).getInsets();
            check(inner.left == outer.left + 16, "Array elements are not indented");
            int child = row(panel, message, "embedded.position.z");
            check(((JComponent)panel.table.prepareRenderer(panel.table.getCellRenderer(child, 0), child, 0)).getInsets().left == outer.left + 32, "Embedded object hierarchy indentation wrong");
            check(panel.values.getValueAt(child, 0).equals("z"), "Field name repeats its whole ancestry");
            int numeric = row(panel, message, "big"), string = row(panel, message, "text");
            check(panel.table.prepareRenderer(panel.table.getCellRenderer(numeric, 2), numeric, 2).getFont().getFamily().equals(Font.MONOSPACED), "Numeric value is not monospaced");
            check(panel.table.prepareRenderer(panel.table.getCellRenderer(string, 2), string, 2).getFont().equals(panel.table.getFont()), "String font was changed");
            check(panel.table.prepareRenderer(panel.table.getCellRenderer(numeric, 0), numeric, 0).getFont().equals(panel.table.getFont()), "Field label font was changed");
            ObjectPanel.SignalData enabled = panel.signal(path(message, "enabled")); trace = panel.createDetailedTrace(enabled);
            renderer.addTrace(trace); charts.flush();
            for (int i = 0; i < 100; i++) { Message next = new Message(); next.enabled = i % 2 == 0; panel.setObject(next, i + 1); }
            charts.flush(); check(trace.getSize() == 101 && trace.getMinY() == 0 && trace.getMaxY() == 1, "Boolean full-rate plotting broken");
            ChannelData channel = new ChannelData(); channel.name = "BOOL"; channel.last = message;
            check(!SignalCatalog.search(Collections.singletonList(channel), "enabled", Collections.emptySet(), false, () -> false).signals.isEmpty(), "Boolean missing from signal picker");
        } finally { if (trace != null) charts.stopTrace(trace); panel.dispose(); renderer.destroy(); }
    }

    private static void largeAndChangingArrays() {
        ChartData charts = new ChartData(0); ObjectPanel panel = new ObjectPanel("LARGE", charts);
        try {
            panel.setSize(1100, 700);
            for (int count : new int[] {127, 128, 129, 16384, 16385, 100000, 2}) {
                RenderPerformanceTest.Signals message = new RenderPerformanceTest.Signals(count);
                panel.setObject(message, count); paint(panel);
                check(panel.values.rows == count + 2, "Main view does not immediately contain all array values");
                check(panel.values.cachedRows() < 45, "Virtual view creates hidden metadata");
                MessageValue.Path last = path(message, "signals[" + (count - 1) + "]");
                panel.scrollToPath(last);
                check(panel.values.row(panel.table.getSelectedRow()).path.equals(last), "Array element navigation failed");
                int tail = row(panel, message, "tail"); panel.table.setRowSelectionInterval(tail, tail);
            }
            RenderPerformanceTest.Signals resized = new RenderPerformanceTest.Signals(20);
            panel.setObject(resized, 999); paint(panel);
            check(panel.values.row(panel.table.getSelectedRow()).path.name.equals("tail"), "Array resize changed selected field");
            panel.scrollToPath(path(resized, "signals")); panel.scrollToIndex(19);
            check(panel.values.row(panel.table.getSelectedRow()).path.name.equals("signals[19]"), "Go to index failed");
            check(panel.values.rows == 22, "Array jump hid other message fields");
            Embedded[] millionRows = new Embedded[200000]; millionRows[199999] = new Embedded();
            MessageLayout virtual = new MessageLayout(millionRows, MessageValue.Path.ROOT, 0, Integer.MAX_VALUE, 20, () -> false);
            check(virtual.rows == 1400000, "Fixed object array should expose every nested field arithmetically");
            check(virtual.row(virtual.rows - 2).path.name.equals("[199999].position.z"), "Large object array indexing wrong");
            check(virtual.row(virtual.rows - 2).value.equals(3.75), "Embedded value in large array wrong");
            String[] strings = new String[100000]; strings[99999] = "last";
            virtual = new MessageLayout(strings, MessageValue.Path.ROOT, 0, Integer.MAX_VALUE, 20, () -> false);
            check(virtual.row(99999).value.equals("last"), "Large string array truncated");
        } finally { panel.dispose(); }
    }

    private static void backgroundLayout() throws Exception {
        final ObjectPanel[] panel = new ObjectPanel[1];
        Dynamic[] values = new Dynamic[3000]; Arrays.setAll(values, i -> new Dynamic());
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ObjectPanel("DYNAMIC", new ChartData(0)); panel[0].setSize(1100, 700);
            panel[0].setObject(values, 0); panel[0].refreshView();
        });
        try {
            long deadline = System.nanoTime() + 10000000000L; boolean[] ready = {false};
            do {
                SwingUtilities.invokeAndWait(() -> ready[0] = panel[0].values.totalRows() == 36000);
                if (ready[0]) break;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            check(ready[0], "Background layout did not expose every dynamic object field");
            SwingUtilities.invokeAndWait(() -> {
                check(panel[0].values.layout.row(35999).path.name.equals("[2999].samples[1]"), "Dynamic object last row missing");
                panel[0].setObject(new Dynamic[10000], 1); panel[0].refreshView();
                panel[0].dispose();
            });
            Thread.sleep(100);
            SwingUtilities.invokeAndWait(() -> check(panel[0].values.totalRows() == 36000, "Cancelled worker updated disposed inspector"));
        } finally { SwingUtilities.invokeAndWait(() -> panel[0].dispose()); }
    }

    private static void generatedTypes() throws Exception {
        Class<?> type;
        try { type = Class.forName("inspector_test.example_t"); }
        catch (ClassNotFoundException missing) { System.out.println("Generated wire-type check skipped (build zcm-gen to enable)"); return; }
        Object source = type.getConstructor().newInstance();
        type.getField("utime").setLong(source, Long.MAX_VALUE);
        type.getField("name").set(source, "Wire message 世界");
        type.getField("enabled").setBoolean(source, true);
        type.getField("num_ranges").setInt(source, 2); type.getField("ranges").set(source, new short[] {-32768, 32767});
        zcm.zcm.ZCMDataOutputStream output = new zcm.zcm.ZCMDataOutputStream();
        type.getMethod("encode", zcm.zcm.ZCMDataOutputStream.class).invoke(source, output);
        Object decoded = type.getConstructor(byte[].class).newInstance(output.toByteArray());
        MessageLayout layout = new MessageLayout(decoded, MessageValue.Path.ROOT, 0, Integer.MAX_VALUE, 2048, () -> false);
        check(layout.row(layout.indexOf(path(decoded, "utime"))).value.equals(Long.MAX_VALUE), "Decoded int64 changed");
        check(layout.row(layout.indexOf(path(decoded, "ranges[1]"))).value.equals((short)32767), "Decoded variable array missing");
        type = Class.forName("inspector_test.bitfield_t"); source = type.getConstructor().newInstance();
        type.getField("field8").set(source, new byte[0][0]);
        type.getField("field10").setLong(source, -1234567890123L); type.getField("field22").setByte(source, (byte)255);
        output = new zcm.zcm.ZCMDataOutputStream(); type.getMethod("encode", zcm.zcm.ZCMDataOutputStream.class).invoke(source, output);
        decoded = type.getConstructor(byte[].class).newInstance(output.toByteArray());
        layout = new MessageLayout(decoded, MessageValue.Path.ROOT, 0, Integer.MAX_VALUE, 2048, () -> false);
        check(layout.row(layout.indexOf(path(decoded, "field10"))).value.equals(-1234567890123L), "Signed bitfield missing");
        check(layout.row(layout.indexOf(path(decoded, "field22"))).value.equals((byte)255), "Unsigned byte bitfield missing");
        check(layout.indexOf(path(decoded, "field12[2][1][1][1]")) >= 0, "Four-dimensional bitfield array missing");
        System.out.println("Generated ZCM wire-type round trips passed");
    }

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> { allTypes(); largeAndChangingArrays(); });
            backgroundLayout(); generatedTypes();
            System.out.println("Spy message inspector tests passed"); System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
