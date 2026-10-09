package zcm.spy;

import java.util.ArrayList;
import java.util.Iterator;
import info.monitorenter.gui.chart.ITracePoint2D;

/** Select original samples for rendering, keeping first/min/max/last per column. */
final class PixelTraceIterator
{
    static Iterator<ITracePoint2D> reduce(Iterator<ITracePoint2D> input, int width,
                                         ArrayList<ITracePoint2D> output)
    {
        output.clear();
        ITracePoint2D first = null, min = null, max = null, last = null;
        int column = 0, index = 0, minIndex = 0, maxIndex = 0;
        while (input.hasNext()) {
            ITracePoint2D point = input.next();
            // Collapse offscreen history too, retaining the boundary samples
            // used by jchart2d to clip lines that cross the visible X range.
            int nextColumn = (int)Math.max(-1, Math.min(width + 1,
                Math.floor(point.getScaledX() * width)));
            if (first == null || column != nextColumn) {
                if (first != null)
                    append(output, first, min, max, last, minIndex, maxIndex);
                column = nextColumn;
                first = min = max = point;
                minIndex = maxIndex = index;
            } else {
                if (point.getY() < min.getY()) { min = point; minIndex = index; }
                if (point.getY() > max.getY()) { max = point; maxIndex = index; }
            }
            last = point;
            index++;
        }
        if (first != null)
            append(output, first, min, max, last, minIndex, maxIndex);
        return output.iterator();
    }

    private static void append(ArrayList<ITracePoint2D> output, ITracePoint2D first,
                               ITracePoint2D min, ITracePoint2D max, ITracePoint2D last,
                               int minIndex, int maxIndex)
    {
        add(output, first);
        // Preserve arrival order, including samples with equal timestamps.
        if (minIndex < maxIndex) {
            if (min != first) add(output, min);
            if (max != first) add(output, max);
        } else {
            if (max != first) add(output, max);
            if (min != first) add(output, min);
        }
        add(output, last);
    }

    private static void add(ArrayList<ITracePoint2D> output, ITracePoint2D point)
    {
        if (output.isEmpty() || output.get(output.size() - 1) != point)
            output.add(point);
    }
}
