package zcm.spy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import info.monitorenter.gui.chart.ITracePoint2D;
import info.monitorenter.gui.chart.TracePoint2D;
import info.monitorenter.gui.chart.IAxis;
import info.monitorenter.gui.chart.IRangePolicy;
import info.monitorenter.gui.chart.axis.AxisLinear;
import info.monitorenter.gui.chart.traces.painters.TracePainterDisc;

/** Rendering correctness and repeatable high-DPI load benchmarks. */
public class RenderPerformanceTest
{
    public static class Signals {
        public double[] signals;
        public double tail = 123;
        Signals(int count) { signals = new double[count]; }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static void paint(ObjectPanel panel, Graphics2D g, int top)
    {
        Graphics2D copy = (Graphics2D)g.create();
        copy.translate(0, -top);
        copy.setClip(0, top, 1000, 600);
        panel.paint(copy);
        copy.dispose();
    }

    private static void inspector(boolean benchmark)
    {
        for (int count : new int[] {1000, 100000}) {
            ChartData charts = new ChartData(0);
            ObjectPanel panel = new ObjectPanel("LOAD", charts);
            JViewport viewport = new JViewport();
            viewport.setSize(1000, 600);
            viewport.setView(panel);
            panel.setViewport(viewport);
            panel.setSize(1000, count * 15 + 200);
            BufferedImage image = new BufferedImage(2000, 1200, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.scale(2, 2);
            StreamingTrace selected = null;
            try {
                Signals message = new Signals(count);
                panel.setObject(message, 0);
                paint(panel, g, 0);
                check(panel.visibleSparklines.size() < 45, "Rendered hidden array rows");
                check(panel.sections.get(1).sparklines.size() < 45,
                      "Allocated metadata for hidden array rows");
                int fullHeight = panel.getPreferredSize().height;
                check(fullHeight > count * 15, "Virtualization lost the scrollbar height");
                ObjectPanel.SparklineData first = panel.sections.get(1).sparklines.get("signals[0]");
                check(first != null && first.trace.getSize() == 1,
                      "First paint requires another frame before drawing signals");
                selected = panel.createDetailedTrace(first);
                selected.setRenderer(panel.sparklineRenderer);
                charts.flush();

                int top = count / 2 * 15;
                viewport.setViewPosition(new Point(0, top));
                paint(panel, g, top);
                check(panel.sections.get(1).sparklines.containsKey("signals[" + count / 2 + "]"),
                      "Scrolled-to array element is missing");
                check(!panel.visibleSparklines.contains(first), "Scrolled-away row is still interactive");
                check(fullHeight == panel.getPreferredSize().height, "Scrolling changed layout height");
                for (int i = 1; i <= 100; i++) {
                    message = new Signals(count);
                    message.signals[0] = i;
                    panel.setObject(message, i);
                }
                charts.flush();
                check(selected.getSize() == 101 && selected.getMaxY() == 100,
                      "Offscreen detailed chart lost full-rate samples");

                if (count == 100000) {
                    for (int i = 1; i <= 40; i++) {
                        int scroll = i * 50 * 15;
                        viewport.setViewPosition(new Point(0, scroll));
                        paint(panel, g, scroll);
                    }
                    check(panel.sparklinesByPath.size() <= 512,
                          "Scrolling retains unbounded sparkline history");
                    check(panel.sparklinesByPath.get("signals[0]") == first,
                          "Cache eviction discarded an active detailed signal");
                }

                panel.setObject(new Signals(2), 101);
                viewport.setViewPosition(new Point(0, 0));
                paint(panel, g, 0);
                check(panel.getPreferredSize().height < 200, "Array shrink left stale layout");

                if (benchmark) {
                    message = new Signals(count);
                    for (int i = 0; i < count; i++) message.signals[i] = Math.sin(i);
                    for (int i = 0; i < 520; i++) {
                        panel.setObject(message, 1000 + i * 33000L);
                        paint(panel, g, 0);
                    }
                    long start = System.nanoTime();
                    for (int i = 0; i < 100; i++) {
                        panel.setObject(message, 1000 + (i + 520) * 33000L);
                        paint(panel, g, 0);
                    }
                    System.out.printf("Inspector: %,d signals, 2x, %.2f ms/frame%n",
                                      count, (System.nanoTime() - start) / 1e8);
                }
            } finally {
                if (selected != null) charts.stopTrace(selected);
                g.dispose();
                panel.sparklineRenderer.destroy();
            }
        }
    }

    private static void reduction()
    {
        ArrayList<ITracePoint2D> input = new ArrayList<ITracePoint2D>();
        ArrayList<ITracePoint2D> output = new ArrayList<ITracePoint2D>();
        Random random = new Random(4);
        for (int i = 0; i < 10000; i++) {
            ITracePoint2D point = new TracePoint2D(i, random.nextDouble());
            point.setScaledX(i / 10000.0);
            input.add(point);
        }
        for (int width : new int[] {1, 17, 100}) {
            PixelTraceIterator.reduce(input.iterator(), width, output);
            check(output.size() <= 4 * (width + 1), "Rendering grows with sample rate");
            check(output.get(0) == input.get(0) && output.get(output.size() - 1) == input.get(9999),
                  "Reduction lost first or last sample");
            double previous = -1;
            for (ITracePoint2D point : output) {
                check(point.getX() > previous, "Reduced samples are duplicated or out of order");
                previous = point.getX();
            }
            for (int column = 0; column < width; column++) {
                ITracePoint2D min = null, max = null;
                for (ITracePoint2D point : input) {
                    if ((int)Math.floor(point.getScaledX() * width) != column) continue;
                    if (min == null || point.getY() < min.getY()) min = point;
                    if (max == null || point.getY() > max.getY()) max = point;
                }
                check(min == null || output.contains(min) && output.contains(max),
                      "Reduction erased a signal spike");
            }
        }
        PixelTraceIterator.reduce(input.iterator(), 20000, output);
        check(output.equals(input), "Sparse rendering changed individual samples");
        // A tight zoom retains the neighbors needed to clip crossing lines.
        for (ITracePoint2D point : input) point.setScaledX((point.getX() - 5000) / 10);
        PixelTraceIterator.reduce(input.iterator(), 100, output);
        check(output.contains(input.get(4999)) && output.contains(input.get(5011)),
              "Zoom lost boundary-crossing samples");
        for (int i = 5000; i <= 5010; i++)
            check(output.contains(input.get(i)), "Zoom does not recover individual samples");
    }

    private static void pixelPainter()
    {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 64, 64); g.setColor(Color.BLACK);
        PixelTracePainter painter = new PixelTracePainter();
        painter.startPaintIteration(g);
        painter.paintPoint(10, 30, 10, 5, g, null);
        painter.paintPoint(10, 5, 10, 55, g, null);
        painter.paintPoint(10, 55, 30, 30, g, null);
        painter.endPaintIteration(g);
        check(image.getRGB(10, 5) == Color.BLACK.getRGB(), "Positive spike not painted");
        check(image.getRGB(10, 55) == Color.BLACK.getRGB(), "Negative spike not painted");
        check(image.getRGB(30, 30) == Color.BLACK.getRGB(), "Final endpoint not painted");
        painter.startPaintIteration(g);
        painter.paintPoint(40, 5, 40, 10, g, null);
        painter.discontinue(g);
        painter.paintPoint(40, 40, 40, 50, g, null);
        painter.endPaintIteration(g);
        check(image.getRGB(40, 25) == Color.WHITE.getRGB(), "Discontinuity was connected");
        g.dispose();
    }

    private static void detailed(boolean benchmark)
    {
        for (boolean original : benchmark ? new boolean[] {true, false} : new boolean[] {false}) {
            ZoomableChartScrollWheel chart = new ZoomableChartScrollWheel(new ChartData(0));
            chart.setSize(900, 600);
            chart.setUseAntialiasing(original);
            ArrayList<StreamingTrace> traces = new ArrayList<StreamingTrace>();
            for (int t = 0; t < 8; t++) {
                StreamingTrace trace = new StreamingTrace(15000, "signal" + t);
                chart.addTrace(trace);
                traces.add(trace);
                for (int i = 0; i < 15000; i++)
                    trace.record(i / 1000.0, Math.sin(i * .01 + t) + Math.sin(i * .731) * .1);
                trace.flush();
                if (original) {
                    TracePainterDisc disc = new TracePainterDisc(); disc.setDiscSize(2);
                    trace.addTracePainter(disc);
                } else {
                    trace.setStroke(new java.awt.BasicStroke(0));
                    trace.setTracePainter(new PixelTracePainter(trace));
                }
            }
            BufferedImage image = new BufferedImage(1800, 1200, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); g.scale(2, 2);
            try {
                // Warm the optimized path after the legacy renderer benchmark
                // so tiered compilation is not counted as steady-state cost.
                int warmup = benchmark && !original ? 30 : 3;
                for (int i = 0; i < warmup; i++) chart.paint(g);
                for (StreamingTrace trace : traces) {
                    int count = 0;
                    for (Iterator<ITracePoint2D> it = trace.iterator(); it.hasNext();) {
                        it.next(); count++;
                    }
                    check(count == 15000, "Painting altered full-history iteration");
                }
                if (benchmark) {
                    long start = System.nanoTime();
                    for (int i = 0; i < 10; i++) chart.paint(g);
                    System.out.printf("Detailed %s: 8 x 15,000 points, 2x, %.2f ms/frame%n",
                                      original ? "original" : "optimized", (System.nanoTime() - start) / 1e7);
                }
            } finally { g.dispose(); chart.destroy(); }
        }
    }

    private static void renderedSpikes()
    {
        ZoomableChartScrollWheel chart = new ZoomableChartScrollWheel(new ChartData(0));
        chart.setSize(900, 600);
        StreamingTrace trace = new StreamingTrace(10000, "spikes");
        trace.setColor(Color.RED);
        trace.setStroke(new java.awt.BasicStroke(0));
        trace.setTracePainter(new PixelTracePainter(trace));
        chart.addTrace(trace);
        for (int i = 0; i < 10000; i++)
            trace.record(i, i == 5000 ? 5 : i == 5001 ? -5 : 0);
        trace.flush();
        BufferedImage image = new BufferedImage(1800, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); g.scale(2, 2);
        try {
            for (boolean zoom : new boolean[] {false, true}) {
                if (zoom) chart.zoom(4990, 5010, -6, 6);
                chart.paint(g);
                for (Iterator<ITracePoint2D> it = trace.iterator(); it.hasNext();) {
                    ITracePoint2D point = it.next();
                    if (point.getX() != 5000 && point.getX() != 5001) continue;
                    int x = (int)Math.round(2 * (chart.getXChartStart() + point.getScaledX() *
                            (chart.getXChartEnd() - chart.getXChartStart())));
                    int y = (int)Math.round(2 * (chart.getYChartStart() - point.getScaledY() *
                            (chart.getYChartStart() - chart.getYChartEnd())));
                    boolean red = false;
                    for (int dx = -3; dx <= 3; dx++)
                        for (int dy = -3; dy <= 3; dy++)
                            red |= image.getRGB(x + dx, y + dy) == Color.RED.getRGB();
                    check(red, "Detailed rendering lost spike at " + point.getX() + ", zoom=" + zoom);
                }
            }
            check(trace.getSize() == 10000, "Zoom changed sample history");
        } finally { g.dispose(); chart.destroy(); }
    }

    private static void wheelZoom()
    {
        ZoomableChartScrollWheel chart = new ZoomableChartScrollWheel(new ChartData(0));
        chart.setSize(900, 600);
        StreamingTrace left = new StreamingTrace(100, "left");
        StreamingTrace right = new StreamingTrace(100, "right");
        AxisLinear rightAxis = new AxisLinear();
        chart.addAxisYRight(rightAxis);
        chart.addTrace(left);
        chart.addTrace(right, chart.getAxisX(), rightAxis);
        left.record(0, 0); left.record(100, 100); left.flush();
        right.record(0, 1000); right.record(100, 2000); right.flush();
        BufferedImage image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            int shift = InputEvent.SHIFT_DOWN_MASK, ctrl = InputEvent.CTRL_DOWN_MASK;
            for (int modifiers : new int[] {0, shift, ctrl, shift | ctrl}) {
                for (double rotation : new double[] {-3, -0.5, 2, 0}) {
                    chart.zoomAll();
                    chart.paint(g);
                    int x = chart.getXChartStart() + (chart.getXChartEnd() - chart.getXChartStart()) / 3;
                    int y = chart.getYChartEnd() + (chart.getYChartStart() - chart.getYChartEnd()) / 3;
                    IAxis<?>[] axes = {chart.getAxisX(), chart.getAxisY(), rightAxis};
                    IRangePolicy[] policies = new IRangePolicy[3];
                    double[] min = new double[3], max = new double[3], anchor = new double[3];
                    for (int i = 0; i < 3; i++) {
                        policies[i] = axes[i].getRangePolicy();
                        min[i] = axes[i].getMin(); max[i] = axes[i].getMax();
                        anchor[i] = axes[i].translatePxToValue(i == 0 ? x : y);
                    }
                    MouseWheelEvent event = new MouseWheelEvent(chart, MouseEvent.MOUSE_WHEEL,
                        System.currentTimeMillis(), modifiers, x, y, x, y, 0, false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, (int)rotation, rotation);
                    chart.dispatchEvent(event);
                    for (int i = 0; i < 3; i++) {
                        boolean selected = rotation != 0 && (i == 0 ? modifiers != ctrl : modifiers != shift);
                        if (!selected) {
                            check(axes[i].getRangePolicy() == policies[i],
                                  "Axis-only zoom froze an unselected axis");
                            check(axes[i].getMin() == min[i] && axes[i].getMax() == max[i],
                                  "Axis-only zoom changed an unselected range");
                        } else {
                            double newMin = axes[i].getRangePolicy().getRange().getMin();
                            double newMax = axes[i].getRangePolicy().getRange().getMax();
                            double expectedExtent = (max[i] - min[i]) * Math.pow(1.2, rotation);
                            check(Math.abs(newMax - newMin - expectedExtent) < 1e-8,
                                  "Wrong wheel zoom extent for axis " + i);
                            double fraction = (anchor[i] - min[i]) / (max[i] - min[i]);
                            check(Math.abs(newMin + fraction * expectedExtent - anchor[i]) < 1e-8,
                                  "Wheel zoom moved the value under the cursor");
                        }
                    }
                    check(event.isConsumed() == (rotation != 0), "Wheel event consumption is incorrect");
                }
            }
        } finally { g.dispose(); chart.destroy(); }
    }

    private static boolean hasSquareMarker(ZoomableChartScrollWheel chart, BufferedImage image,
                                            ITracePoint2D point, int scale)
    {
        int x = (int)Math.round(chart.getXChartStart() + point.getScaledX() *
                (chart.getXChartEnd() - chart.getXChartStart()));
        int y = (int)Math.round(chart.getYChartStart() - point.getScaledY() *
                (chart.getYChartStart() - chart.getYChartEnd()));
        for (int dx = 0; dx < 2 * scale; dx++)
            for (int dy = 0; dy < 2 * scale; dy++)
                if (image.getRGB((x - 1) * scale + dx, (y - 1) * scale + dy) != Color.RED.getRGB())
                    return false;
        return true;
    }

    private static void sampleMarkers()
    {
        for (int scale : new int[] {1, 2}) {
            ZoomableChartScrollWheel chart = new ZoomableChartScrollWheel(new ChartData(0));
            chart.setSize(900, 600);
            StreamingTrace trace = new StreamingTrace(100, "markers");
            trace.setColor(Color.RED);
            trace.setStroke(new java.awt.BasicStroke(0));
            trace.setTracePainter(new PixelTracePainter(trace));
            chart.addTrace(trace);
            for (int i = 0; i < 5; i++) trace.record(5 + i * .0001, 2 + i);
            trace.flush();
            BufferedImage image = new BufferedImage(900 * scale, 600 * scale, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); g.scale(scale, scale);
            try {
                // The five samples occupy one chart column when zoomed out.
                // Check the painted squares, not just the underlying line.
                for (boolean zoom : new boolean[] {false, true}) {
                    if (zoom) chart.zoom(4.9999, 5.0005, 0, 10);
                    else chart.zoom(0, 10, 0, 10);
                    chart.paint(g);
                    int index = 0;
                    for (Iterator<ITracePoint2D> it = trace.iterator(); it.hasNext();) {
                        ITracePoint2D point = it.next();
                        check(hasSquareMarker(chart, image, point, scale) == (zoom || index == 0),
                              "Wrong sample marker at " + index + ", zoom=" + zoom + ", scale=" + scale);
                        index++;
                    }
                    check(index == 5, "Marker capping discarded trace samples");
                }
                trace.removeAllPoints();
                trace.record(5, 5); trace.flush();
                chart.zoom(0, 10, 0, 10); chart.paint(g);
                check(hasSquareMarker(chart, image, trace.iterator().next(), scale),
                      "Isolated sample has no marker");
            } finally { g.dispose(); chart.destroy(); }
        }
    }

    public static void main(String[] args) throws Exception
    {
        try {
            SwingUtilities.invokeAndWait(() -> {
                boolean benchmark = args.length > 0 && args[0].equals("--benchmark");
                inspector(benchmark);
                reduction();
                pixelPainter();
                renderedSpikes();
                wheelZoom();
                sampleMarkers();
                detailed(benchmark);
            });
            System.out.println("Spy rendering tests passed");
            System.exit(0);
        } catch (Throwable ex) { ex.printStackTrace(); System.exit(1); }
    }
}
