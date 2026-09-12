package zcm.spy;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import info.monitorenter.gui.chart.Chart2D;
import info.monitorenter.gui.chart.ITrace2D;
import info.monitorenter.gui.chart.ITracePoint2D;
import info.monitorenter.gui.chart.traces.Trace2DLtd;

/** Headless regression tests; run with test/java/run-spy-tests.sh. */
public class ChartStreamingTest
{
    public static class Child
    {
        public double value;
        Child(double value) { this.value = value; }
    }

    public static class Message
    {
        public Child left;
        public Child right;
        public Child[] children;
        public double[][] matrix;
        public double tail;

        Message(int value)
        {
            left = new Child(value);
            right = new Child(-value);
            children = new Child[] {new Child(value + 100), new Child(value + 200)};
            matrix = new double[][] {{value + 300, value + 400}};
            tail = value + 500;
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
            throw new AssertionError(message);
    }

    private static void paint(ObjectPanel panel)
    {
        BufferedImage image = new BufferedImage(1300, 1300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            panel.paint(g);
        } finally {
            g.dispose();
        }
    }

    private static ObjectPanel.SparklineData field(ObjectPanel panel, String path)
    {
        for (ObjectPanel.Section section : panel.sections)
            for (ObjectPanel.SparklineData data : section.sparklines.values())
                if (path.equals(data.fullName))
                    return data;
        throw new AssertionError("Missing field " + path);
    }

    private static void checkSequence(ITrace2D trace, int first, int last, int offset, int sign)
    {
        check(trace.getSize() == last - first + 1, "Wrong sample count for " + trace.getName()
                + ": " + trace.getSize());
        Iterator<ITracePoint2D> points = trace.iterator();
        for (int i = first; i <= last; i++) {
            ITracePoint2D point = points.next();
            check(point.getY() == sign * i + offset, "Missing, duplicate, or wrong-field sample at " + i);
        }
    }

    private static void testInspector() throws Exception
    {
        ChartData charts = new ChartData(1000);
        ObjectPanel panel = new ObjectPanel("TEST", charts);
        ZoomableChartScrollWheel detail = new ZoomableChartScrollWheel(charts);
        Chart2D movedChart = new Chart2D();
        charts.getCharts().add(detail);
        ArrayList<StreamingTrace> traces = new ArrayList<StreamingTrace>();
        try {
            panel.setSize(1300, 1300);
            panel.setObject(new Message(0), 1000);
            paint(panel);
            paint(panel);
            String[] paths = {"left.value", "right.value", "children[1].value", "matrix[0][1]", "tail"};
            for (String path : paths) {
                ObjectPanel.SparklineData data = field(panel, path);
                // No window has received focus yet. This also exercises adding
                // traces to the existing chart and to separate Y axes.
                panel.displayDetailedChart(data, false, !traces.isEmpty());
                StreamingTrace trace = data.detailedTrace;
                traces.add(trace);
                panel.displayDetailedChart(data, false, false);
                check(panel.createDetailedTrace(data) == trace, "Repeated selection registered twice");
            }
            charts.flush();
            check(traces.get(0).getMinX() == 0, "Lost sample at time zero");

            // No inspector paint or EDT work happens during the entire burst.
            // Repeated microsecond timestamps must still produce separate points.
            Thread producer = new Thread(new Runnable() {
                public void run()
                {
                    for (int i = 1; i <= 10000; i++)
                        panel.setObject(new Message(i), 1000 + i / 4);
                }
            });
            synchronized (detail) {
                producer.start();
                producer.join(5000);
                check(!producer.isAlive(), "Message collection waited for the chart's paint lock");
            }
            charts.flush();
            checkSequence(traces.get(0), 0, 10000, 0, 1);
            checkSequence(traces.get(1), 0, 10000, 0, -1);
            checkSequence(traces.get(2), 0, 10000, 200, 1);
            checkSequence(traces.get(3), 0, 10000, 400, 1);
            checkSequence(traces.get(4), 0, 10000, 500, 1);
            int index = 0;
            for (Iterator<ITracePoint2D> it = traces.get(0).iterator(); it.hasNext(); index++)
                check(it.next().getX() == (index / 4) / 1000000.0, "Wrong sample timestamp");

            paint(panel);
            paint(panel);
            charts.flush();
            check(traces.get(0).getSize() == 10001, "Repainting duplicated detailed samples");
            check(field(panel, "left.value").trace.getSize() == 2,
                    "Sparkline collected full-rate data");

            // Shrinking arrays changes the inspector's section numbers. Cached
            // access paths must neither throw nor switch to a similarly named field.
            Message shortMessage = new Message(10001);
            shortMessage.children = new Child[0];
            shortMessage.matrix = null;
            shortMessage.left = null;
            panel.setObject(shortMessage, 4000);
            paint(panel);
            paint(panel);
            panel.sections.get(0).collapsed = true;
            panel.setObject(new Message(10002), 4001);
            paint(panel);
            charts.flush();
            check(traces.get(0).getSize() == 10002, "Null child did not recover");
            check(traces.get(2).getSize() == 10002, "Array element did not recover");
            check(traces.get(3).getSize() == 10002, "Null matrix did not recover");
            check(traces.get(4).getMaxY() == 10502, "Array resize changed tail field binding");

            StreamingTrace stopped = traces.get(0);
            charts.stopTrace(stopped);
            check(field(panel, "left.value").detailedTrace == null, "Closed trace stayed registered");
            panel.setObject(new Message(10003), 4002);
            charts.flush();
            stopped.flush();
            check(stopped.getSize() == 10002, "Closed chart kept receiving data");
            panel.sections.get(0).collapsed = false;
            paint(panel);
            paint(panel);
            StreamingTrace reopened = panel.createDetailedTrace(field(panel, "left.value"));
            detail.addTrace(reopened);
            traces.add(reopened);
            charts.flush();
            check(reopened != stopped, "Reopening reused a closed trace");
            int before = reopened.getSize();
            panel.setObject(new Message(10004), 4003);
            charts.flush();
            check(reopened.getSize() == before + 1, "Reopened chart lost a sample");

            StreamingTrace moved = traces.get(1);
            before = moved.getSize();
            detail.removeTrace(moved);
            movedChart.addTrace(moved);
            panel.setObject(new Message(10005), 4004);
            charts.flush();
            check(moved.getSize() == before + 1, "Moving a trace stopped collection");
        } finally {
            for (StreamingTrace trace : traces)
                charts.stopTrace(trace);
            detail.destroy();
            movedChart.destroy();
            panel.sparklineRenderer.destroy();
        }
    }

    private static void checkBounds(StreamingTrace trace)
    {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Iterator<ITracePoint2D> it = trace.iterator(); it.hasNext();) {
            ITracePoint2D point = it.next();
            minX = Math.min(minX, point.getX());
            maxX = Math.max(maxX, point.getX());
            minY = Math.min(minY, point.getY());
            maxY = Math.max(maxY, point.getY());
        }
        check(trace.getMinX() == minX && trace.getMaxX() == maxX
                && trace.getMinY() == minY && trace.getMaxY() == maxY,
                "Incorrect chart bounds after history rollover");
    }

    private static void testRollover()
    {
        Chart2D chart = new Chart2D();
        try {
            for (int capacity : new int[] {1, 37, 1500}) {
                StreamingTrace trace = new StreamingTrace(capacity, "rollover");
                chart.addTrace(trace);
                Random random = new Random(0);
                for (int i = 0; i < 12000; i++) {
                    double y = i < 3000 ? 7 : i < 6000 ? i : i < 9000 ? -i : random.nextInt(100);
                    trace.record(i / 3, y);
                    if (i % 19 == 0) {
                        trace.flush();
                        checkBounds(trace);
                    }
                }
                trace.flush();
                checkBounds(trace);
                check(trace.getSize() == capacity, "History is not bounded");
                trace.record(4000, Double.NaN);
                trace.record(4000, Double.POSITIVE_INFINITY);
                trace.flush();
                checkBounds(trace);
                chart.removeTrace(trace);
            }

            StreamingTrace trace = new StreamingTrace(100, "backlog");
            chart.addTrace(trace);
            for (int i = 0; i < 10000; i++)
                trace.record(i, i);
            trace.flush();
            checkSequence(trace, 9900, 9999, 0, 1);
            checkBounds(trace);
            trace.record(10000, 10000);
            trace.flush();
            checkSequence(trace, 9901, 10000, 0, 1);
            trace.removeAllPoints();
            trace.record(10001, -5);
            trace.flush();
            checkBounds(trace);
        } finally {
            chart.destroy();
        }
    }

    private static void testConcurrentFlush() throws Exception
    {
        final Chart2D chart = new Chart2D();
        final StreamingTrace trace = new StreamingTrace(100000, "concurrent");
        chart.addTrace(trace);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread producer = new Thread(new Runnable() {
            public void run()
            {
                try {
                    for (int i = 0; i < 100000; i++)
                        trace.record(i, i);
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            }
        });
        try {
            producer.start();
            while (producer.isAlive())
                trace.flush();
            producer.join();
            trace.flush();
            check(failure.get() == null, "Producer failed: " + failure.get());
            checkSequence(trace, 0, 99999, 0, 1);
            checkBounds(trace);
            chart.setSize(600, 500);
            BufferedImage image = new BufferedImage(600, 500, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                chart.paint(g);
            } finally {
                g.dispose();
            }
        } finally {
            chart.destroy();
        }
    }

    private static void benchmark()
    {
        for (boolean buffered : new boolean[] {false, true}) {
            Chart2D chart = new Chart2D();
            Trace2DLtd trace = buffered ? new StreamingTrace(15000, "buffered")
                    : new Trace2DLtd(15000, "original");
            chart.addTrace(trace);
            long start = System.nanoTime();
            for (int i = 0; i < 100000; i++) {
                if (buffered) {
                    ((StreamingTrace) trace).record(i, 1);
                    if (i % 100 == 0)
                        ((StreamingTrace) trace).flush();
                } else {
                    trace.addPoint(i, 1);
                }
            }
            if (buffered)
                ((StreamingTrace) trace).flush();
            System.out.printf("%s: 100,000 samples, 15,000 retained, %.1f ms%n",
                    buffered ? "Buffered trace" : "Original Trace2DLtd", (System.nanoTime() - start) / 1e6);
            chart.destroy();
        }
    }

    public static void main(String[] args) throws Exception
    {
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run()
                {
                    try {
                        testInspector();
                        testRollover();
                        testConcurrentFlush();
                        if (args.length > 0 && args[0].equals("--benchmark"))
                            benchmark();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }
            });
            System.out.println("Spy chart streaming tests passed");
            System.exit(0);
        } catch (Throwable ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }
}
