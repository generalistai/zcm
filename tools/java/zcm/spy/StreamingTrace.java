package zcm.spy;

import java.util.ArrayDeque;

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
    }
}
