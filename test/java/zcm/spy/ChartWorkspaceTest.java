package zcm.spy;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;
import javax.swing.*;
import info.monitorenter.gui.chart.IAxis;
import info.monitorenter.gui.chart.IRangePolicy;
import info.monitorenter.gui.chart.ITrace2D;

/** Behavior tests for interactive chart workspaces, without JNI or a display. */
public class ChartWorkspaceTest
{
    public static class Message {
        public static final long FINGERPRINT = 100;
        public double[] samples = new double[100000];
        public ChartStreamingTest.Child[] nested = {new ChartStreamingTest.Child(42)};
        public double[][] matrix = {{3, 4}};
        public Message cycle;
        public String text = "ignored";
        public boolean flag;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void close(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-8, message + ": " + actual + " != " + expected);
    }
    private static ZoomableChartScrollWheel chart(ChartData data) {
        ZoomableChartScrollWheel chart = new ZoomableChartScrollWheel(data);
        chart.setSize(900, 500); data.getCharts().add(chart); return chart;
    }
    private static StreamingTrace trace(ZoomableChartScrollWheel chart, String name, int count) {
        StreamingTrace trace = new StreamingTrace(128, name);
        trace.setColor(chart.popColor()); trace.setTracePainter(new PixelTracePainter(trace)); chart.addTrace(trace);
        chart.chartData.startTrace(trace, () -> {});
        for (int i = 0; i < count; i++) trace.record(i, i * 2);
        trace.flush(); chart.updateRightClickMenu(); return trace;
    }
    private static void dispose(ChartData data) {
        for (ZoomableChartScrollWheel chart : new ArrayList<ZoomableChartScrollWheel>(data.getCharts())) {
            for (ITrace2D trace : new ArrayList<ITrace2D>(chart.getTraces())) {
                data.stopTrace(trace); chart.removeTrace(trace);
            }
            chart.destroy();
        }
        data.getCharts().clear();
    }
    private static void paint(Component component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); component.paint(graphics); graphics.dispose();
    }
    private static void wheel(ZoomableChartScrollWheel chart, int modifiers) {
        paint(chart);
        int x = (chart.getXChartStart() + chart.getXChartEnd()) / 2;
        int y = (chart.getYChartStart() + chart.getYChartEnd()) / 2;
        chart.dispatchEvent(new MouseWheelEvent(chart, MouseEvent.MOUSE_WHEEL, 1, modifiers,
            x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1));
    }
    private static JTable table(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JTable) return (JTable)child;
            if (child instanceof Container) { JTable found = table((Container)child); if (found != null) return found; }
        }
        return null;
    }

    private static class CountedChart extends ZoomableChartScrollWheel {
        int immediateRepaints;
        CountedChart(ChartData data) { super(data); }
        public void repaint() { immediateRepaints++; super.repaint(); }
        boolean queued() { return isRequestedRepaint(); }
    }

    private static void hoverRate() {
        ChartData data = new ChartData(0); CountedChart a = new CountedChart(data), b = new CountedChart(data);
        data.getCharts().add(a); data.getCharts().add(b);
        try {
            trace(a, "a", 100); trace(b, "b", 100); a.controls(); b.controls();
            a.setTimeLinked(true); b.setTimeLinked(true);
            a.immediateRepaints = b.immediateRepaints = 0;
            for (int i = 0; i < 1000; i++) a.setCursorTime(i / 100.0);
            check(a.immediateRepaints == 0 && b.immediateRepaints == 0, "Hover bypassed 30 Hz repaint limit");
            check(a.queued() && b.queued(), "Hover did not schedule a repaint");
            close(b.getCursorTime(), 9.99, "Coalesced hover lost latest position");
        } finally { dispose(data); }
    }

    private static void pauseAndCursors() throws Exception {
        ChartData data = new ChartData(0);
        ZoomableChartScrollWheel chart = chart(data);
        try {
            StreamingTrace trace = trace(chart, "signal", 100);
            chart.goLive(); close(chart.getAxisX().getMin(), 94, "Initial live window");
            close(trace.sampleAt(23.7).value, 48, "Nearest sample");
            close(trace.sampleAt(23.5).time, 23, "Equidistant cursor prefers earlier sample");
            check(trace.sampleAt(-1) == null && trace.sampleAt(100) == null && trace.sampleAt(Double.NaN) == null,
                  "Out-of-history cursors should be blank");
            chart.setPaused(true);
            Thread producer = new Thread(() -> { for (int i = 100; i < 10000; i++) trace.record(i, i * 2); });
            synchronized (chart) { producer.start(); producer.join(5000); }
            check(!producer.isAlive(), "Paused collection blocked on chart");
            data.flush();
            close(trace.getMaxX(), 99, "Pause lost the displayed snapshot");
            close(trace.sampleAt(20).value, 40, "Pause changed cursor history");
            chart.setTimeWindow(30); close(chart.getAxisX().getMin(), 69, "Paused preset");
            chart.pinCursor(1, 20); chart.pinCursor(2, 30); chart.setCursorTime(40);
            JTable legend = table(chart.controls().legendPanel());
            close(Double.parseDouble(legend.getValueAt(0, 4).toString()), 80, "Hover value");
            close(Double.parseDouble(legend.getValueAt(0, 7).toString()), 20, "Measurement delta");
            chart.goLive();
            check(!chart.isPaused() && chart.isFollowing(), "Live did not resume");
            close(trace.getMinX(), 9872, "Pause buffer is bounded");
            close(trace.getMaxX(), 9999, "Resume missed collected samples");
            close(trace.sampleAt(9998.4).value, 19996, "Cursor ring rollover");
            check(trace.sampleAt(20) == null, "Expired cursor returned unrelated data");
            close(chart.getAxisX().getMin(), 9969, "Resume follows selected window");
            chart.setTimeWindow(120); close(chart.getAxisX().getMin(), 9879, "Two minute window");
            chart.setTimeWindow(0); close(chart.getAxisX().getMin(), 9872, "All retained");
            trace.record(10000, 1); trace.record(10000, 2); data.flush();
            close(trace.sampleAt(10000).value, 1, "Equal timestamps choose first sample");
            chart.setCursorTime(Double.NaN);
            close(Double.parseDouble(legend.getValueAt(0, 4).toString()), 2, "Latest readout lost final sample at equal timestamp");
            close(chart.retainedSeconds(), 126, "Retained duration");
            IRangePolicy policy = chart.getAxisX().getRangePolicy(); data.flush();
            check(chart.getAxisX().getRangePolicy() == policy, "Idle refresh replaces axis policy");
            chart.clearCursors(); check(Double.isNaN(chart.getCursorA()), "Clear cursor");
        } finally { dispose(data); }
    }

    private static void linkingAndLegend() {
        ChartData data = new ChartData(0);
        ZoomableChartScrollWheel a = chart(data), b = chart(data), c = chart(data);
        try {
            StreamingTrace first = trace(a, "first", 80), second = trace(a, "second", 70), third = trace(a, "third", 60);
            StreamingTrace peer = trace(b, "peer", 100); trace(c, "unlinked", 50);
            a.goLive(); b.goLive(); c.goLive();
            a.setTimeLinked(true); b.setTimeLinked(true); data.flush();
            close(a.getAxisX().getMax(), 99, "Linked live clock");
            close(a.getAxisX().getMin(), b.getAxisX().getMin(), "Linked live range");
            IRangePolicy y = b.getAxisY().getRangePolicy(), untouched = c.getAxisX().getRangePolicy();
            wheel(a, InputEvent.SHIFT_DOWN_MASK);
            check(!a.isFollowing() && !b.isFollowing(), "Linked navigation stayed live");
            close(a.getAxisX().getMin(), b.getAxisX().getMin(), "Linked horizontal zoom");
            check(b.getAxisY().getRangePolicy() == y, "Link changed peer Y range");
            check(c.getAxisX().getRangePolicy() == untouched, "Link changed unlinked chart");
            paint(a);
            int x = (a.getXChartStart() + a.getXChartEnd()) / 2, yPixel = (a.getYChartStart() + a.getYChartEnd()) / 2;
            a.dispatchEvent(new MouseEvent(a, MouseEvent.MOUSE_PRESSED, 1, InputEvent.BUTTON1_DOWN_MASK, x, yPixel, 1, false, MouseEvent.BUTTON1));
            a.dispatchEvent(new MouseEvent(a, MouseEvent.MOUSE_DRAGGED, 2, InputEvent.BUTTON1_DOWN_MASK, x + 50, yPixel + 10, 0, false, MouseEvent.NOBUTTON));
            a.dispatchEvent(new MouseEvent(a, MouseEvent.MOUSE_RELEASED, 3, 0, x + 50, yPixel + 10, 1, false, MouseEvent.BUTTON1));
            close(a.getAxisX().getMin(), b.getAxisX().getMin(), "Linked horizontal pan");
            check(b.getAxisY().getRangePolicy() == y && Double.isNaN(a.getCursorA()), "Pan changed peer Y or placed a cursor");
            a.goLive(); wheel(a, InputEvent.CTRL_DOWN_MASK);
            check(a.isFollowing() && b.isFollowing(), "Y-only zoom stopped live time");
            a.setCursorTime(40); a.pinCursor(1, 30); a.pinCursor(2, 50);
            close(b.getCursorTime(), 40, "Shared hover"); close(b.getCursorB() - b.getCursorA(), 20, "Shared measurement");
            check(Double.isNaN(c.getCursorA()), "Shared cursor leaked to unlinked chart");
            a.setPaused(true); check(b.isPaused() && peer.isPaused(), "Linked pause");
            peer.record(101, 999); data.flush(); close(peer.getMaxX(), 99, "Peer kept scrolling during pause");
            a.goLive(); data.flush(); close(a.getAxisX().getMax(), 101, "Linked resume latest sample");
            a.setTimeWindow(0); data.flush(); close(a.getAxisX().getMin(), b.getAxisX().getMin(), "Linked all-retained start");
            b.setTimeLinked(false); a.pinCursor(1, 45); close(b.getCursorA(), 30, "Unlink cursor");
            double min = b.getAxisX().getMin(); a.setTimeWindow(30); close(b.getAxisX().getMin(), min, "Unlink range");

            a.setTraceVisible(third, false); a.solo(first);
            check(first.isVisible() && !second.isVisible() && !third.isVisible(), "Solo");
            a.solo(second); check(!first.isVisible() && second.isVisible(), "Switch solo");
            a.solo(second); check(first.isVisible() && second.isVisible() && !third.isVisible(), "Solo visibility restoration");
            a.assignAxis(first, "New axis"); a.assignAxis(second, "Y2");
            IAxis<?> right = a.getAxisY(first);
            check(a.getAxisY(second) == right, "Assign existing Y axis");
            a.assignAxis(first, "Main");
            check(a.getAxisY(second) == right && right.getTraces().contains(second), "Moving one trace removed shared axis");
            a.assignAxis(third, "Y2");
            a.detachTrace(second); b.addTrace(second); b.updateRightClickMenu();
            check(a.getAxisY(third) == right && right.getTraces().contains(third), "Moving window removed another signal");
            a.assignAxis(third, "Main"); check(a.axisNames().length == 2, "Empty Y axis remained");
            a.setPaused(true); a.assignAxis(first, "New axis");
            check(first.isPaused(), "Changing axis resumed paused trace");
            a.solo(first); a.detachTrace(first); b.addTrace(first); b.updateRightClickMenu();
            check(!a.isSolo(first) && !third.isVisible(), "Removing solo lost original visibility");
            check(!first.isPaused(), "Moving to live chart left stream paused");
            first.record(102, 1000); data.flush(); close(first.getMaxY(), 1000, "Moving lost subscription");
            JTable legend = table(b.controls().legendPanel());
            legend.setValueAt(false, 0, 0); check(Boolean.FALSE.equals(legend.getValueAt(0, 0)), "Legend checkbox");
            legend.setValueAt(true, 0, 1); check(Boolean.TRUE.equals(legend.getValueAt(0, 1)), "Legend solo checkbox");
        } finally { dispose(data); }
    }

    private static void picker() throws Exception {
        ChartData data = new ChartData(1000);
        ZoomableChartScrollWheel chart = chart(data), other = chart(data);
        ObjectPanel inspector = new ObjectPanel("TEST", data);
        ChannelData channel = new ChannelData(); channel.name = "TEST";
        Message message = new Message(); message.samples[99999] = 123; message.cycle = message; channel.last = message;
        SignalCatalog.Source source = new SignalCatalog.Source() {
            public List<ChannelData> channels() { return Collections.singletonList(channel); }
            public ObjectPanel inspector(ChannelData ignored) { return inspector; }
        };
        inspector.setObject(message, 1000); data.signalSource = source;
        try {
            SignalCatalog.Result result = SignalCatalog.search(source.channels(), "test samples[99999]", Collections.emptySet(), false, () -> false);
            check(result.signals.size() == 1 && result.signals.get(0).value == 123, "Search missed large array tail");
            SignalCatalog.Signal signal = result.signals.get(0);
            check(SignalPicker.addSignal(chart, source, signal), "Picker add");
            check(SignalPicker.addSignal(chart, source, signal), "Picker repeat add");
            check(!SignalPicker.addSignal(other, source, signal) && other.getTraces().isEmpty(), "Picker attached same trace twice");
            StreamingTrace trace = inspector.traceForSignal(signal);
            check(chart.getTraces().size() == 1 && trace.getSize() == 1, "Picker duplicate subscription/seed");
            message = new Message(); message.samples[99999] = 456; inspector.setObject(message, 1001000); data.flush();
            close(trace.sampleAt(1).value, 456, "Hidden inspector collection");
            message.samples = new double[2]; inspector.setObject(message, 2001000); data.flush();
            check(trace.getSize() == 2, "Array shrink changed picker binding");
            message.samples = new double[100000]; message.samples[99999] = 789; inspector.setObject(message, 3001000); data.flush();
            close(trace.getMaxY(), 789, "Picker array recovery");
            for (String query : new String[] {"nested[0].value", "matrix[0][1]"}) {
                result = SignalCatalog.search(source.channels(), query, Collections.emptySet(), false, () -> false);
                check(result.signals.size() == 1, "Nested path search " + query);
                SignalPicker.addSignal(chart, source, result.signals.get(0));
            }
            result = SignalCatalog.search(source.channels(), "FINGERPRINT", Collections.emptySet(), false, () -> false);
            check(result.signals.isEmpty(), "Static fingerprint is a signal");
            result = SignalCatalog.search(source.channels(), "", Collections.emptySet(), false, () -> false);
            check(result.limited && result.signals.size() == 2000, "Unbounded search results");
            result = SignalCatalog.search(source.channels(), "", Collections.singleton(signal.id), true, () -> false);
            check(result.signals.size() == 1 && result.signals.get(0).id.equals(signal.id), "Favorite filter");
            check(SignalCatalog.search(source.channels(), "", Collections.emptySet(), false, () -> true).signals.isEmpty(), "Search cancellation");
            inspector.setSize(1000, 600); paint(inspector); paint(inspector);
            check(inspector.traceForSignal(signal) == trace, "Opening inspector replaced picker trace");
            data.stopTrace(trace); chart.detachTrace(trace);
            StreamingTrace reopened = inspector.traceForSignal(signal); chart.addTrace(reopened);
            check(reopened != trace, "Picker cleanup did not release subscription");
        } finally { dispose(data); inspector.sparklineRenderer.destroy(); }
    }

    private static void favorites() throws Exception {
        Preferences node = Preferences.userRoot().node("spy-workspace-test");
        try {
            SignalCatalog.Favorites first = new SignalCatalog.Favorites(node);
            first.set("TEST\tmatrix[0][1]", true); first.set("TEST\tnested[0].value", true); first.save();
            SignalCatalog.Favorites second = new SignalCatalog.Favorites(node);
            check(second.ids.equals(first.ids), "Favorites did not persist");
            second.set("TEST\tmatrix[0][1]", false); second.save();
            check(new SignalCatalog.Favorites(node).ids.equals(second.ids), "Removed favorite persisted");
        } finally { node.removeNode(); Preferences.userRoot().flush(); }
    }

    private static void layout(String file) throws Exception {
        ChartData data = new ChartData(0); ZoomableChartScrollWheel chart = chart(data);
        try {
            for (int i = 0; i < 8; i++) {
                StreamingTrace trace = trace(chart, "ROBOT: joints[" + i + "].velocity", 0);
                for (int j = 0; j < 128; j++) trace.record(j / 25.0, Math.sin(j / 10.0 + i) * (i + 1));
                trace.flush();
            }
            chart.goLive(); chart.setPaused(true); chart.pinCursor(1, 1.1); chart.pinCursor(2, 3.3); chart.setCursorTime(2.5);
            JPanel panel = new JPanel(new BorderLayout()); panel.add(chart.controls(), BorderLayout.NORTH);
            panel.add(chart, BorderLayout.CENTER); panel.add(chart.controls().legendPanel(), BorderLayout.SOUTH);
            panel.setSize(1100, 750); layoutTree(panel);
            check(chart.getHeight() > 450, "Controls crowd out plot");
            BufferedImage image = new BufferedImage(1100, 750, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            panel.paint(graphics); panel.paint(graphics); graphics.dispose();
            if (file != null) ImageIO.write(image, "png", new File(file));
        } finally { dispose(data); }
    }
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container) layoutTree((Container)child);
    }

    private static void benchmark() {
        ChartData data = new ChartData(0); ZoomableChartScrollWheel chart = chart(data);
        try {
            for (int i = 0; i < 8; i++) {
                StreamingTrace trace = new StreamingTrace(15000, "LOAD: joint[" + i + "].velocity");
                trace.setColor(chart.popColor()); trace.setStroke(new BasicStroke(0));
                trace.setTracePainter(new PixelTracePainter(trace)); chart.addTrace(trace);
                for (int j = 0; j < 15000; j++) trace.record(j / 1000.0, Math.sin(j / 20.0 + i) * (i + 1));
                trace.flush();
            }
            chart.setTimeWindow(0); chart.pinCursor(1, 10); chart.pinCursor(2, 12);
            JPanel panel = new JPanel(new BorderLayout()); panel.add(chart.controls(), BorderLayout.NORTH);
            panel.add(chart, BorderLayout.CENTER); panel.add(chart.controls().legendPanel(), BorderLayout.SOUTH);
            panel.setSize(1100, 750); layoutTree(panel);
            BufferedImage image = new BufferedImage(2200, 1500, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); graphics.scale(2, 2);
            double[] times = new double[60];
            for (int i = -20; i < times.length; i++) {
                long start = System.nanoTime();
                chart.setCursorTime(7 + Math.sin(i / 10.0)); chart.controls().refresh(); panel.paint(graphics);
                if (i >= 0) times[i] = (System.nanoTime() - start) / 1e6;
            }
            graphics.dispose(); Arrays.sort(times);
            System.out.printf("Chart + controls/cursors: 8 x 15,000 points, 2x, median %.2f ms/frame, p95 %.2f ms/frame%n", times[30], times[57]);
        } finally { dispose(data); }
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                pauseAndCursors(); linkingAndLegend(); hoverRate(); picker(); favorites();
                layout(System.getenv("SPY_WORKSPACE_SCREENSHOT"));
                if (Arrays.asList(args).contains("--benchmark")) benchmark();
            }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("Spy chart workspace tests passed"); System.exit(0);
    }
}
