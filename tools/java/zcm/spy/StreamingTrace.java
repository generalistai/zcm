package zcm.spy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;

import info.monitorenter.gui.chart.ITracePoint2D;
import info.monitorenter.gui.chart.traces.Trace2DLtd;

/**
 * A fixed-size time series with a separate buffer for the receive thread.
 * Only flush() touches jchart2d, and must be called on the Swing event thread.
 * Samples are ordered by arrival time, including equal timestamps.
 */
final class StreamingTrace extends Trace2DLtd
{
    private final Object pendingLock = new Object();
    private double[] pendingX, pendingY, drawingX, drawingY;
    private int pendingStart, pendingSize;
    private boolean paused;
    // Cursor lookups use a primitive ring and binary search, never a scan per
    // mouse event. This ring follows the displayed history, including pauses.
    private double[] historyX, historyY;
    private int historyStart, historySize;

    void setPaused(boolean paused) { this.paused = paused; }
    boolean isPaused() { return paused; }

    double latestValue()
    {
        return historySize == 0 ? Double.NaN : historyY[(historyStart + historySize - 1) % historyY.length];
    }

    static final class Sample {
        final double time, value;
        Sample(double time, double value) { this.time = time; this.value = value; }
    }

    Sample sampleAt(double time)
    {
        if (!Double.isFinite(time) || historySize == 0 ||
            time < historyX[historyStart] || time > historyX[(historyStart + historySize - 1) % historyX.length])
            return null;
        int low = 0, high = historySize;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (historyX[(historyStart + mid) % historyX.length] < time) low = mid + 1;
            else high = mid;
        }
        int index = Math.min(low, historySize - 1);
        if (index > 0 && time - historyX[(historyStart + index - 1) % historyX.length] <=
                historyX[(historyStart + index) % historyX.length] - time)
            index--;
        index = (historyStart + index) % historyX.length;
        return new Sample(historyX[index], historyY[index]);
    }
    private boolean rendering;
    private final ArrayList<ITracePoint2D> renderPoints = new ArrayList<ITracePoint2D>();

    void setRendering(boolean rendering)
    {
        this.rendering = rendering;
    }

    @Override
    public Iterator<ITracePoint2D> iterator()
    {
        // The streaming painter draws the trace in one batch at the end of
        // the paint iteration. Axis scaling and callers outside that iteration
        // still receive the complete history.
        return rendering ? Collections.<ITracePoint2D>emptyList().iterator() : super.iterator();
    }

    Iterator<ITracePoint2D> renderIterator()
    {
        Iterator<ITracePoint2D> points = super.iterator();
        int width = Math.max(1, getRenderer().getXChartEnd() - getRenderer().getXChartStart());
        if (getSize() <= width * 4)
            return points;
        return PixelTraceIterator.reduce(points, width, renderPoints);
    }

    // Monotonic queues avoid scanning the entire trace when an extremum expires.
    private final ArrayDeque<ITracePoint2D> minima = new ArrayDeque<ITracePoint2D>();
    private final ArrayDeque<ITracePoint2D> maxima = new ArrayDeque<ITracePoint2D>();

    StreamingTrace(int capacity, String name)
    {
        super(capacity, name);
        pendingX = new double[capacity];
        pendingY = new double[capacity];
        drawingX = new double[capacity];
        drawingY = new double[capacity];
        historyX = new double[capacity];
        historyY = new double[capacity];
    }

    @Override
    public int hashCode()
    {
        // Trace2DLtd has identity equality but hashes its mutable point buffer.
        // A stable hash is also needed by jchart2d's trace collections.
        return System.identityHashCode(this);
    }

    void record(double x, double y)
    {
        if (Double.isNaN(y) || Double.isInfinite(y))
            return;

        synchronized (pendingLock) {
            int index = (pendingStart + pendingSize) % pendingX.length;
            pendingX[index] = x;
            pendingY[index] = y;
            if (pendingSize == pendingX.length)
                pendingStart = (pendingStart + 1) % pendingX.length;
            else
                pendingSize++;
        }
    }

    void flush()
    {
        if (paused) return;
        int start, size;
        synchronized (pendingLock) {
            start = pendingStart;
            size = pendingSize;
            if (size == 0)
                return;

            double[] swap = drawingX;
            drawingX = pendingX;
            pendingX = swap;
            swap = drawingY;
            drawingY = pendingY;
            pendingY = swap;
            pendingStart = 0;
            pendingSize = 0;
        }

        // If the UI fell behind by a whole history window, all older points
        // have expired. Keep exactly the same bounded history as a live UI.
        if (size == getMaxSize())
            removeAllPoints();
        for (int i = 0; i < size; i++) {
            int index = (start + i) % drawingX.length;
            addPoint(drawingX[index], drawingY[index]);
        }
    }

    @Override
    protected boolean addPointInternal(ITracePoint2D point)
    {
        int index = (historyStart + historySize) % historyX.length;
        historyX[index] = point.getX();
        historyY[index] = point.getY();
        if (historySize == historyX.length) historyStart = (historyStart + 1) % historyX.length;
        else historySize++;
        if (m_buffer.isFull()) {
            ITracePoint2D oldest = m_buffer.getOldest();
            if (minima.peekFirst() == oldest)
                minima.removeFirst();
            if (maxima.peekFirst() == oldest)
                maxima.removeFirst();
        }
        while (!minima.isEmpty() && minima.peekLast().getY() >= point.getY())
            minima.removeLast();
        while (!maxima.isEmpty() && maxima.peekLast().getY() <= point.getY())
            maxima.removeLast();
        minima.addLast(point);
        maxima.addLast(point);
        return super.addPointInternal(point);
    }

    @Override
    protected void minXSearch()
    {
        m_minX = m_buffer.isEmpty() ? 0 : m_buffer.getOldest().getX();
    }

    @Override
    protected void maxXSearch()
    {
        m_maxX = m_buffer.isEmpty() ? 0 : m_buffer.getYoungest().getX();
    }

    @Override
    protected void minYSearch()
    {
        m_minY = minima.isEmpty() ? 0 : minima.peekFirst().getY();
    }

    @Override
    protected void maxYSearch()
    {
        m_maxY = maxima.isEmpty() ? 0 : maxima.peekFirst().getY();
    }

    @Override
    public void removeAllPointsInternal()
    {
        super.removeAllPointsInternal();
        minima.clear();
        maxima.clear();
        historyStart = historySize = 0;
    }
}
