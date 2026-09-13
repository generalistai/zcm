package zcm.spy;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.Iterator;
import info.monitorenter.gui.chart.Chart2D;
import info.monitorenter.gui.chart.ITracePoint2D;
import info.monitorenter.gui.chart.traces.painters.ATracePainter;

/** Batches dense traces into a path, preserving the extrema in each X column.
 * Only rendering is reduced: trace history, zooming and value lookup stay exact.
 */
final class PixelTracePainter extends ATracePainter
{
    private final Path2D.Float path = new Path2D.Float();
    private boolean empty;
    private int column, firstY, lastY, minY, maxY;
    private int nextX, nextY;
    private final StreamingTrace trace;

    PixelTracePainter() { this(null); }

    PixelTracePainter(StreamingTrace trace) { this.trace = trace; }

    @Override
    public void startPaintIteration(Graphics g)
    {
        path.reset();
        empty = true;
        if (trace != null)
            trace.setRendering(true);
    }

    private void append(int x, int y)
    {
        if (empty) {
            path.moveTo(x, y);
            empty = false;
        } else if (x != column) {
            finishColumn();
            path.lineTo(x, y);
        } else {
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            lastY = y;
            return;
        }
        column = x;
        firstY = lastY = minY = maxY = y;
    }

    private void finishColumn()
    {
        // All points in this bucket occupy the same screen column. Keep both
        // extremes so even a one-sample spike remains visible when zoomed out.
        if (minY != maxY) {
            path.lineTo(column, minY);
            path.lineTo(column, maxY);
        }
        if (lastY != firstY || minY != maxY)
            path.lineTo(column, lastY);
    }

    @Override
    public void paintPoint(int x, int y, int nextX, int nextY,
                           Graphics g, ITracePoint2D point)
    {
        append(x, y);
        this.nextX = nextX;
        this.nextY = nextY;
    }

    @Override
    public void endPaintIteration(Graphics g)
    {
        if (trace != null) {
            try {
                paintTrace(g);
            } finally {
                trace.setRendering(false);
            }
            return;
        }
        if (!empty && g != null) {
            append(nextX, nextY);
            finishColumn();
            ((Graphics2D)g).draw(path);
        }
    }

    private void paintTrace(Graphics graphics)
    {
        if (graphics == null) return;
        Chart2D chart = trace.getRenderer();
        int left = chart.getXChartStart(), right = chart.getXChartEnd();
        int top = chart.getYChartEnd(), bottom = chart.getYChartStart();
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            g.clipRect(left, top, right - left + 1, bottom - top + 1);
            path.reset();
            boolean first = true;
            int markerColumn = Integer.MIN_VALUE;
            Iterator<ITracePoint2D> points = trace.renderIterator();
            while (points.hasNext()) {
                ITracePoint2D point = points.next();
                double x = left + point.getScaledX() * (right - left);
                double y = bottom - point.getScaledY() * (bottom - top);
                if (!Double.isFinite(x) || !Double.isFinite(y)) {
                    first = true;
                    continue;
                }
                if (first) path.moveTo(x, y);
                else path.lineTo(x, y);
                first = false;

                // Mark real, visible samples, with at most one square per
                // chart column. Zooming naturally reveals individual samples.
                if (x >= left && x <= right && y >= top && y <= bottom) {
                    int pixelX = (int)Math.round(x);
                    if (pixelX != markerColumn) {
                        g.fillRect(pixelX - 1, (int)Math.round(y) - 1, 2, 2);
                        markerColumn = pixelX;
                    }
                }
            }
            g.draw(path);
        } finally {
            g.dispose();
        }
    }
}
