package zcm.spy;

import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.Map.Entry;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;

import info.monitorenter.gui.chart.Chart2D;
import info.monitorenter.gui.chart.ITrace2D;
import info.monitorenter.gui.chart.ITracePoint2D;
import info.monitorenter.gui.chart.axis.AxisLinear;
import info.monitorenter.gui.chart.traces.Trace2DLtd;

/**
 * Panel that displays general data for zcm types.  Viewed by double-clicking
 * or right-clicking and selecting Structure Viewer on the channel list.
 *
 */
public class ObjectPanel extends JPanel
{
    String name;
    Object o;
    long utime; // time of this message's arrival
    int lastwidth = 500;
    int lastheight = 100;
    JViewport scrollViewport;
    private final AtomicBoolean repaintPending = new AtomicBoolean();
    private final javax.swing.Timer repaintTimer = new javax.swing.Timer(33, new ActionListener() {
        public void actionPerformed(ActionEvent e)
        {
            repaintPending.set(false);
            if (isShowing())
                repaint();
        }
    });

    private static class Message
    {
        final Object object;
        final long utime;
        final long sequence;

        Message(Object object, long utime, long sequence)
        {
            this.object = object;
            this.utime = utime;
            this.sequence = sequence;
        }
    }

    private volatile Message latestMessage;
    private Message paintedMessage;

    // Receive-thread work is proportional to the selected fields and their
    // nesting depth, rather than the size of the entire message.
    private static class Subscription
    {
        final Object[] path;
        final StreamingTrace trace;

        Subscription(Object[] path, StreamingTrace trace)
        {
            this.path = path;
            this.trace = trace;
        }

        void record(Message message)
        {
            Object value = message.object;
            try {
                for (Object step : path) {
                    if (value == null)
                        return;
                    if (step instanceof Field) {
                        Field field = (Field) step;
                        if (!field.getDeclaringClass().isInstance(value))
                            return;
                        value = field.get(value);
                    } else {
                        int index = (Integer) step;
                        if (index >= Array.getLength(value))
                            return;
                        value = Array.get(value, index);
                    }
                }
                if (value instanceof Number)
                    trace.record(message.utime / 1000000.0, ((Number) value).doubleValue());
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Cannot read chart field", ex);
            }
        }
    }

    private final ArrayList<Subscription> subscriptions = new ArrayList<Subscription>();
    final LinkedHashMap<String, SparklineData> sparklinesByPath =
            new LinkedHashMap<String, SparklineData>(128, 0.75f, true);
    private final HashMap<Class, Field[]> fieldsByClass = new HashMap<Class, Field[]>();

    final int sparklineWidth = 150; // width in pixels of all sparklines

    // margin around the viewport area in which we will draw graphs
    // (in pixels)
    final int sparklineDrawMargin = 0;

    Section currentlyHoveringSection; // section the mouse is hovering over
    String currentlyHoveringName; // name of the section the mouse is hovering over

    ChartData chartData; // global data about all charts being displayed by zcm-spy

    // jchart2d requires a renderer for point creation and synchronization. The
    // manually painted sparklines share one instead of creating a chart and
    // a repeating Swing timer for every numeric field. They need no axis listeners.
    final Chart2D sparklineRenderer = new Chart2D();
    private final PixelTracePainter sparklinePainter = new PixelTracePainter();

    // array of all sparklines that are visible
    // or near visible to the user right now
    HashSet<SparklineData> visibleSparklines = new HashSet<SparklineData>();

    // we keep track of each drawing iteration to know if the row we clicked
    // on was displayed.  See SparklineData.lastDrawNumber.
    int currentDrawNumber = 0;

    class Section
    {
        int x0, y0, x1, y1; // bounding coordinates for sensitive area
        boolean collapsed;
        HashMap<String, SparklineData> sparklines;


        public Section()
        {
            sparklines = new HashMap<String, SparklineData>();
        }
    }

    /**
     * Data about an individual sparkline.
     *
     */
    class SparklineData
    {
        int xmin, xmax;
        int ymin, ymax;
        boolean isHovering;

        Trace2DLtd trace;

        // The detailed trace has its own full-rate history. The sparkline
        // remains sampled at the inspector's frame rate.
        StreamingTrace detailedTrace;
        Object[] path;
        String fullName;
        long lastSequence = -1;

        String name;
        Section section;

        // we keep track of the drawing iteration number for each line
        // to let us figure out if the line is currently being drawn
        // when the user clicks it.  This is needed to fix a bug where the
        // user clicks in a place a line used to be, but is no longer
        //there since the array it was in got shorter.
        int lastDrawNumber = 0;
    }

    ArrayList<Section> sections = new ArrayList<Section>();

    /**
     * Constructor for an object panel, call when the user clicks to see more
     * data about a message.
     *
     * @param name name of the channel
     * @param chartData global data about all charts displayed by zcm-spy
     */
    public ObjectPanel(String name, ChartData chartData)
    {
        this.name = name;
        this.setLayout(null); // not using a layout manager, drawing everything ourselves
        this.chartData = chartData;
        repaintTimer.setRepeats(false);

        addMouseListener(new MyMouseAdapter());

        addMouseMotionListener(new MyMouseMotionListener());

        repaint();

    }

    public void repaintWithFramelimit() {
        if (isShowing() && repaintPending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run()
                {
                    repaintTimer.start();
                }
            });
        }
    }


    /**
     * If given a viewport, the object panel can make smart decisions to
     * not draw graphs that are currently outside of the user's view
     *
     * @param viewport viewport from the JScrollPane that contains this ObjectPanel.
     */
    public void setViewport(JViewport viewport) {
        scrollViewport = viewport;

        scrollViewport.addChangeListener(new MyViewportChangeListener());
    }

    /**
     * Called on mouse movement to determine if we need to
     * highlight a line or open a chart.
     *
     * @param e MouseEvent to process
     *
     * @return returns true if a mouse click was consumed
     */
    public boolean doSparklineInteraction(MouseEvent e)
    {
        int y = e.getY();

        currentlyHoveringName = "";
        currentlyHoveringSection = null;

        for (SparklineData data : visibleSparklines)
        {
            if (data.ymin <= y && data.ymax >= y && data.lastDrawNumber == currentDrawNumber)
            {
                // the mouse is above this sparkline
                currentlyHoveringName = data.name;
                currentlyHoveringSection = data.section;

                if (e.getButton() == MouseEvent.BUTTON1)
                {
                    displayDetailedChart(data, false, false);

                } else if (e.getButton() == MouseEvent.BUTTON2)
                {
                    // middle click means open a new chart
                    displayDetailedChart(data, true, true);

                } else if (e.getButton() == MouseEvent.BUTTON3)
                {
                    // right click means same chart, new axis
                    displayDetailedChart(data, false, true);
                }

                return true;
            }
        }

        return false;
    }

    /**
     * Opens a detailed, interactive chart for a data stream.  If the data is already
     * displayed in a chart, brings that chart to the front instead.
     *
     *
     * @param data data channel to display
     * @param openNewChart set to true to force opening of a new chart window, false to add
     *      to an already-open chart (if one exists)
     * @param newAxis true if we should add a new Y-axis to display this data
     */
    public void displayDetailedChart(SparklineData data, boolean openNewChart, boolean newAxis)
    {

        if (data.trace == null)
        {
            // this should not happen, but catch it if it does because we can at least safely ignore it
            System.out.println("Warning: detailed chart display requested on uninitialized chart " + data.name);
            return;
        }

        // check to see if we are already displaying this trace
        StreamingTrace trace = data.detailedTrace;

        for (ZoomableChartScrollWheel chart : chartData.getCharts())
        {
            if (trace != null && chart.getTraces().contains(trace))
            {
                chart.toFront();
                return;
            }
        }

        trace = createDetailedTrace(data);

        if (openNewChart || chartData.getCharts().size() < 1)
        {
            ZoomableChartScrollWheel.newChartFrame(chartData, trace);
        } else
        {
            // find the most recently interacted with chart

            long bestFocusTime = -1;
            ZoomableChartScrollWheel bestChart = null;

            for (ZoomableChartScrollWheel chart : chartData.getCharts())
            {
                if (bestChart == null || chart.getLastFocusTime() > bestFocusTime)
                {
                    bestFocusTime = chart.getLastFocusTime();
                    bestChart = chart;
                }

            }

            if (bestChart != null)
            {
               // add this trace to the winning chart

                if (!bestChart.getTraces().contains(trace))
                {
                    trace.setColor(bestChart.popColor());

                    if (newAxis)
                    {
                        // add an axis
                        AxisLinear axis = new AxisLinear();
                        bestChart.addAxisYRight(axis);
                        bestChart.addTrace(trace, bestChart.getAxisX(), axis);
                    } else
                    {
                        bestChart.addTrace(trace);
                    }


                }
                bestChart.updateRightClickMenu();
                bestChart.toFront();

            }

        }

        trace.setTracePainter(new PixelTracePainter(trace));
    }

    StreamingTrace createDetailedTrace(final SparklineData data)
    {
        if (data.detailedTrace != null)
            return data.detailedTrace;

        final StreamingTrace trace = new StreamingTrace(chartData.detailedSparklineChartSize,
                name + ": " + data.fullName);
        // A device-pixel hairline avoids expanding thousands of tiny segments
        // into stroked polygons on high-DPI displays.
        trace.setStroke(new BasicStroke(0));
        final Subscription subscription = new Subscription(data.path, trace);
        // Seed the detailed view with the sampled history already on screen.
        ITrace2D sparkline = data.trace;
        for (Iterator<ITracePoint2D> it = sparkline.iterator(); it.hasNext();) {
            ITracePoint2D point = it.next();
            trace.record(point.getX(), point.getY());
        }
        synchronized (subscriptions) {
            if (latestMessage != null && latestMessage.sequence != data.lastSequence)
                subscription.record(latestMessage);
            subscriptions.add(subscription);
        }
        data.detailedTrace = trace;
        chartData.startTrace(trace, new Runnable() {
            public void run()
            {
                synchronized (subscriptions) {
                    subscriptions.remove(subscription);
                }
                data.detailedTrace = null;
            }
        });
        return trace;
    }

    class PaintState
    {
        Color indentColors[] = new Color[] {new Color(255,255,255), new Color(230,230,255), new Color(200,200,255)};
        Graphics g;
        FontMetrics fm;
        JPanel panel;

        int indent_level;
        int color_level;
        int y;
        int textheight;

        int x[] = new int[4]; // tab stops
        int indentpx = 20; // pixels per indent level

        int maxwidth;

        int nextsection = 0;

        int collapse_depth = 0;
        int clipTop, clipBottom;

        boolean rowVisible()
        {
            return y + 2 >= clipTop && y - textheight <= clipBottom;
        }

        public int beginSection(String type, String name, String value)
        {
            // allocate a new section number and make sure there's
            // an entry for us to use in the sections array.
            int section = nextsection++;
            Section cs;
            if (section == sections.size()) {
                cs = new Section();
                sections.add(cs);
            }

            cs = sections.get(section);
            cs.sparklines.clear();

            // Some enclosing section is collapsed, exit before drawing anything.

            if (collapse_depth == 0)
            {
                // we're not currently collapsed. Draw the header (at least.)
                beginColorBlock();
                spacer();

                Font of = g.getFont();
                g.setFont(of.deriveFont(Font.BOLD));
                FontMetrics fm = g.getFontMetrics();

                String tok = cs.collapsed ? "+" : "-";
                g.setColor(Color.white);
                g.fillRect(x[0] + indent_level*indentpx, y, 1, 1);
                g.setColor(Color.black);

                String type_split[] = type.split("\\.");
                String drawtype = type_split[type_split.length - 1];

                int type_len = fm.stringWidth(drawtype);
                int name_len = fm.stringWidth(name);

                int tok_pixidx = x[0] + indent_level*indentpx;
                int type_pixidx = x[0] + indent_level*indentpx + 10;

                g.drawString(tok, tok_pixidx, y);
                g.drawString(drawtype, type_pixidx, y);

                // set top of clicking area before
                // we might do any text wrapping
                cs.y0 = y - textheight;

                // check if type field is too long. put name on new line if yes
                if (type_pixidx + type_len > x[1])
                    y+= textheight;
                g.drawString(name,  x[1], y);

                // check if name field is too long.  put value on new line if yes
                // No need to put it on a new line if value is NULL
                if (x[1] + name_len > x[2] && value.length() > 0)
                    y+= textheight;
                g.drawString(value, x[2], y);

                g.setFont(of);

                final int extra_click_margin = 10; // in pixels

                // set up the coordinates where clicking will toggle whether
                // we are collapsed.
                cs.x0 = x[0];

                // only have section minimization out to the edge of the text
                if (name_len > 0)
                    cs.x1 = x[1] + name_len + extra_click_margin;
                else {
                    cs.x1 = type_pixidx + type_len + extra_click_margin;
                }

                cs.y1 = y;

                y += textheight;

            }
            else
            {
                // no clicking area.
                cs.x0 = 0; cs.x1 = 0; cs.y0 = 0; cs.y1 = 0;
            }


            // if this section is collapsed, stop drawing.
            if (sections.get(section).collapsed) {
                collapse_depth ++;
            } else if (collapse_depth == 0) {
                // Only indent if this section isn't collapsed.
                indent();
            }

            return section;
        }

        public void endSection(int section)
        {
            Section cs = sections.get(section);

            if (collapse_depth == 0) {
                unindent();
            }

            // if this section is collapsed, resume drawing.
            if (sections.get(section).collapsed) {
                collapse_depth --;
            }

            spacer();
            endColorBlock();
            spacer();
        }

        public void drawStrings(String type, String name, String value, boolean isstatic)
        {
            if (collapse_depth > 0)
                return;

            if (!rowVisible()) {
                y += textheight;
                return;
            }
            Font of = g.getFont();
            if (isstatic)
                g.setFont(of.deriveFont(Font.ITALIC));

            g.drawString(type,  x[0] + indent_level*indentpx, y);
            g.drawString(name,  x[1], y);
            g.drawString(value, x[2], y);

            y+= textheight;

            g.setFont(of);
        }

        /**
         * Draws a row for a piece of data in the message and also a sparkline
         * for that data.
         *
         * @param cls type of the data
         * @param name name of the entry in the message
         * @param o the data itself
         * @param isstatic true if the data is static
         * @param sec index of section this row is in, used to determine if this
         *      row should be highlighted because it is under the mouse cursor.
         */
        public void drawStringsAndGraph(Class cls, String name, Object o, boolean isstatic,
                int sec)
        {
            Section cs = sections.get(sec);

            double value = Double.NaN;

            if (o instanceof Double)
                value = (Double) o;
            else if (o instanceof Float)
                value = (Float) o;
            else if (o instanceof Integer)
                value = (Integer) o;
            else if (o instanceof Long)
                value = (Long) o;
            else if (o instanceof Short)
                value = (Short) o;
            else if (o instanceof Byte)
                value = (Byte) o;

            if (collapse_depth > 0)
                return;

            if (isstatic)
            {
                drawStrings(cls.getName(), name, o.toString(), isstatic);
                return;
            }
            Color oldColor = g.getColor();

            boolean isHovering = false;

            if (currentlyHoveringSection != null && cs == currentlyHoveringSection
                    && currentlyHoveringName.equals(name))
            {
                isHovering = true;
                g.setColor(Color.RED);
            }


            Font of = g.getFont();

            g.drawString(cls.getName(),  x[0] + indent_level*indentpx, y);
            g.drawString(name,  x[1], y);


            if (cls.equals(Byte.TYPE)) {
                g.drawString(String.format("0x%02X   %03d   %+04d   %c",
                        (o),((Byte)o).intValue()&0x00FF,(o), ((Byte)o)&0xff), x[2], y);
            } else {
                g.drawString(o.toString(), x[2], y);
            }

            g.setColor(oldColor);

            // draw the graph

            if (Double.isFinite(value))
            {
                SparklineData data = cs.sparklines.get(name);

                if (data.trace == null)
                {
                    data.trace = initSparklineTrace(name);

                }

                ITrace2D trace = data.trace;

                // update the positions every loop in case another section
                // was collapsed

                data.xmin = x[3];
                data.xmax = x[3]+sparklineWidth;

                // add the data to our trace
                if (data.lastSequence != paintedMessage.sequence) {
                    trace.addPoint(utime/1000000.0d, value);
                    data.lastSequence = paintedMessage.sequence;
                }

                data.lastDrawNumber = currentDrawNumber;

                // draw the graph
                DrawSparkline(x[3], y, trace, isHovering);


            }

            y+= textheight;

            g.setFont(of);
            g.setColor(oldColor);
        }

        /**
         * Draws a sparkline.
         *
         * @param x x-coordinate of the left side of the line
         * @param y y-coordinate of the top of the line
         * @param trace data for the sparkline
         * @param isHovering true if the mouse cursor is hovering over this row
         */
        public void DrawSparkline(int x, int y, ITrace2D trace, boolean isHovering)
        {

            if (trace.getSize() < 2)
            {
                return;
            }

            Graphics2D g2 = (Graphics2D) g;


            Iterator<ITracePoint2D> iter = trace.iterator();

            final int circleSize = 3;
            final int height = textheight;
            double numSecondsDisplayed = 5.0;
            final double width = sparklineWidth;

            //width = width * ((double)trace.getSize() / (double) trace.getMaxSize());

            if (trace.getMaxX() == trace.getMinX())
            {
                // no time series, don't draw anything
                return;
            }

            Color pointColor = Color.RED;
            Color lineColor = Color.BLACK;

            if (isHovering) {
                Color temp = pointColor;
                pointColor = lineColor;
                lineColor = temp;
            }

            double earliestTimeDisplayed = (utime/1000000.0 - numSecondsDisplayed);

            // decide on the main axis scale
            double xscale = width / (numSecondsDisplayed);

            if (trace.getMaxY() == trace.getMinY())
            {
                // divide by zero error coming up!
                // bail and draw a straight line down the center of the graph
                g2.setColor(lineColor);
                ITracePoint2D firstPoint = iter.next();

                int leftLineX = (int)((firstPoint.getX() - earliestTimeDisplayed) * xscale) + x;

                if (leftLineX < x)
                {
                    leftLineX = x;
                }

                g2.drawLine(leftLineX, y-(int)((double)height/(double)2), x+(int)width, y-(int)((double)height/(double)2));
                g2.setColor(pointColor);
                g2.fillOval(x + (int) width - 1, y-(int)((double)height/(double)2) - 1, circleSize, circleSize);
                return;
            }


            double yscale = height / (trace.getMaxY() - trace.getMinY());


            g2.setColor(lineColor);

            PixelTracePainter painter = sparklinePainter;
            painter.startPaintIteration(g);
            boolean first = true;

            double lastX = 0, lastY = 0, thisX, thisY;

            while (iter.hasNext())
            {
                ITracePoint2D point = iter.next();

                if (first)
                {
                    first = false;
                    lastX = (point.getX() - earliestTimeDisplayed) * xscale + x;
                    lastY = y - (point.getY() - trace.getMinY()) * yscale;
                } else {
                    thisX = (point.getX() - earliestTimeDisplayed) * xscale + x;
                    thisY = y - (point.getY() - trace.getMinY()) * yscale;

                    if (thisX >= x && lastX >= x)
                    {
                        painter.paintPoint((int)lastX, (int)lastY,
                                (int)thisX, (int)thisY, g, point);
                    }
                    lastX = thisX;
                    lastY = thisY;
                }

                if (!iter.hasNext())
                {
                    painter.endPaintIteration(g);
                    // this is the last point, bold it
                    g2.setColor(pointColor);
                    g2.fillOval((int)lastX - 1, (int)lastY - 1, 3, 3);
                    g2.setColor(lineColor);
                }
            }
        }


        public void spacer()
        {
            if (collapse_depth > 0)
                return;

            y+= textheight/2;
        }

        public void beginColorBlock()
        {
            if (collapse_depth > 0)
                return;

            color_level++;
            g.setColor(indentColors[color_level%indentColors.length]);
            g.fillRect(x[0] + indent_level*indentpx - indentpx/2, y - fm.getMaxAscent(), getWidth(), getHeight());
            g.setColor(Color.black);
        }

        public void endColorBlock()
        {
            if (collapse_depth > 0)
                return;

            color_level--;
            g.setColor(indentColors[color_level%indentColors.length]);
            g.fillRect(x[0] + indent_level*indentpx -indentpx/2, y - fm.getMaxAscent(), getWidth(), getHeight());
            g.setColor(Color.black);
        }

        public void indent()
        {
            indent_level++;
        }

        public void unindent()
        {
            indent_level--;
        }

        public void finish()
        {
            g.setColor(Color.white);
            g.fillRect(0, y, getWidth(), getHeight());
        }
    }

    public void setObject(Object o, long utime)
    {
        synchronized (subscriptions) {
            Message message = new Message(o, utime - chartData.getStartTime(),
                    latestMessage == null ? 0 : latestMessage.sequence + 1);
            latestMessage = message;
            for (Subscription subscription : subscriptions)
                subscription.record(message);
        }
        repaintWithFramelimit();
    }

    public Dimension getPreferredSize()
    {
        return new Dimension(lastwidth, lastheight);
    }

    public Dimension getMinimumSize()
    {
        return getPreferredSize();
    }

    public Dimension getMaximumSize()
    {
        return getPreferredSize();
    }

    /**
     * Updates visibleSparklines to reflect the data that is near the user's view
     * at the current time.
     *
     * @param viewport viewport the user is looking at.  Usually from an event: e.getSource()
     */
    void updateVisibleSparklines(JViewport viewport)
    {
        Rectangle view_rect = viewport == null ? new Rectangle(0, 0, getWidth(), getHeight())
                : viewport.getViewRect();

        visibleSparklines.clear();

        for (int i = sections.size() -1; i > -1; i--)
        {
            Section section = sections.get(i);

            if (section.collapsed == false)
            {
                Iterator<Entry<String, SparklineData>> it = section.sparklines.entrySet().iterator();
                while (it.hasNext())
                {
                    Entry<String, SparklineData> pair = it.next();

                    SparklineData data = pair.getValue();

                    if (data.ymin > view_rect.y - sparklineDrawMargin
                            && data.ymax < view_rect.y + view_rect.height + sparklineDrawMargin)
                    {
                        visibleSparklines.add(data);
                    }
                }
            }
        }
    }

    public void paint(Graphics g)
    {
        // Take a consistent message/time snapshot for the entire paint pass.
        paintedMessage = latestMessage;
        if (paintedMessage != null) {
            o = paintedMessage.object;
            utime = paintedMessage.utime;
        }
        Graphics2D g2 = (Graphics2D) g;

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int width = getWidth(), height = getHeight();
        g.setColor(Color.white);
        g.fillRect(0, 0, width, height);

        g.setColor(Color.black);
        FontMetrics fm = g.getFontMetrics();

        PaintState ps = new PaintState();

        ps.panel = this;
        ps.g = g;
        ps.fm = fm;
        ps.textheight = 15;
        Rectangle clip = g.getClipBounds();
        if (clip == null)
            clip = new Rectangle(0, 0, width, height);
        if (scrollViewport != null)
            clip = scrollViewport.getViewRect();
        ps.clipTop = clip.y;
        ps.clipBottom = clip.y + clip.height;
        visibleSparklines.clear();
        ps.y = ps.textheight;
        ps.indent_level=1;
        ps.x[0] = 0;
        ps.x[1] = Math.min(200, width/4);
        ps.x[2] = Math.min(ps.x[1]+200, 2*width/4);
        ps.x[3] = ps.x[2]+150;

        currentDrawNumber ++;

        if (o != null)
            paintRecurse(g, ps, "", o.getClass(), o, false, -1, "",
                    new ArrayList<Object>());

        sections.subList(ps.nextsection, sections.size()).clear();

        // Keep a small scroll-back cache, plus every selected detailed signal.
        // Otherwise visiting a large array eventually retains a history for
        // every row and exhausts the launcher's bounded Java heap.
        Iterator<SparklineData> cached = sparklinesByPath.values().iterator();
        while (sparklinesByPath.size() > 512 && cached.hasNext()) {
            SparklineData data = cached.next();
            if (data.detailedTrace == null && !visibleSparklines.contains(data)) {
                if (data.section.sparklines.get(data.name) == data)
                    data.section.sparklines.remove(data.name);
                cached.remove();
            }
        }

        ps.finish();
        if (ps.y != lastheight) {
            lastheight = ps.y;
            invalidate();
            if (getParent() != null)
                getParent().validate();
        }
    }

    void paintRecurse(Graphics g, PaintState ps, String name, Class cls, Object o, boolean isstatic,
            int section, String fullName, ArrayList<Object> path)
    {
        if (o == null) {
            ps.drawStrings(cls==null ? "(null)" : cls.getName(), name, "(null)", isstatic);
            return;
        }

        if (cls.isPrimitive() || cls.equals(Byte.TYPE)) {

            // This is our common case...
            Section cs = sections.get(section);
            SparklineData data = sparklinesByPath.get(fullName);

            if (data == null && (ps.collapse_depth > 0 || !ps.rowVisible())) {
                if (ps.collapse_depth == 0) ps.y += ps.textheight;
                return;
            }

            if (data == null)
            {
                // we may or may not draw this depending on if it is near the view but we need to keep track of it
                // so the user can click on it

                data = new SparklineData();
                data.name = name;
                data.isHovering = false;
                data.path = path.toArray();
                data.fullName = fullName;
                sparklinesByPath.put(fullName, data);

            }

            data.section = cs;
            cs.sparklines.put(name, data);

            // text can drop below the expected height for letters like
            // "g", which makes it possible to click on a letter and get
            // the wrong graph.  Add a small correction factor to deal with that
            final int text_below_line_height = 2; // in px

            data.ymin = ps.y - ps.textheight + text_below_line_height;
            data.ymax = ps.y + text_below_line_height;

            if (ps.collapse_depth == 0 && ps.rowVisible())
            {
                visibleSparklines.add(data);
                ps.drawStringsAndGraph(cls, name, o, isstatic, section);

            } else {
                // don't bother drawing the strings or graph for it.
                // just update the text height to pretend we drew it
                // (on huge messages, this is a large CPU savings)

                if (ps.collapse_depth > 0)
                    return;

                ps.y+= ps.textheight;

            }

        } else if (o instanceof Enum) {

            ps.drawStrings(cls.getName(), name, ((Enum) o).name(), isstatic);

        } else if (cls.equals(String.class)) {

            ps.drawStrings("String", name, o.toString(), isstatic);

        } else if (cls.isArray())  {

            int sz = Array.getLength(o);
            int sec = ps.beginSection(cls.getComponentType()+"[]", name+"["+sz+"]", "");

            // Primitive arrays have fixed-height rows. Jump directly to the
            // visible slice without boxing, formatting, or caching hidden data.
            int first = 0, end = sz;
            if (cls.getComponentType().isPrimitive()) {
                if (ps.collapse_depth > 0) {
                    end = 0;
                } else {
                    first = Math.min(sz, Math.max(0, (ps.clipTop - ps.y - 2) / ps.textheight));
                    end = Math.min(sz, Math.max(first,
                        (ps.clipBottom - ps.y) / ps.textheight + 2));
                    ps.y += first * ps.textheight;
                }
            }
            for (int i = first; i < end; i++) {
                path.add(i);
                paintRecurse(g, ps, name+"["+i+"]", cls.getComponentType(), Array.get(o, i),
                        isstatic, sec, fullName+"["+i+"]", path);
                path.remove(path.size() - 1);
            }

            if (cls.getComponentType().isPrimitive() && ps.collapse_depth == 0)
                ps.y += (sz - end) * ps.textheight;
            ps.endSection(sec);

        } else {

            // it's a compound type. recurse.
            int sec = ps.beginSection(cls.getName(), name, "");

            // it's a class
            Field fs[] = fieldsByClass.get(cls);
            if (fs == null) {
                fs = cls.getFields();
                fieldsByClass.put(cls, fs);
            }
            for (Field f : fs) {
                path.add(f);
                try {
                    String fieldName = fullName.isEmpty() ? f.getName() : fullName + "." + f.getName();
                    paintRecurse(g, ps, f.getName(), f.getType(), f.get(o),
                            isstatic || Modifier.isStatic(f.getModifiers()), sec, fieldName, path);
                } catch (Exception ex) {
                    System.out.println(ex.getMessage());
                    ex.printStackTrace(System.out);
                } finally {
                    path.remove(path.size() - 1);
                }
            }

            ps.endSection(sec);
        }
    }

    public boolean isOptimizedDrawingEnabled()
    {
        return false;
    }

    private Trace2DLtd initSparklineTrace(String name)
    {
        Trace2DLtd trace = new StreamingTrace(chartData.sparklineChartSize, name);
        trace.setRenderer(sparklineRenderer);
        return trace;
    }

    class MyMouseAdapter extends MouseAdapter
    {
        /**
         * Handle mouse clicks.  Either opens graphs if the user
         * clicked on a row or toggles sections.
         *
         * @param e MouseEvent that fired this click
         */
        public void mouseClicked(MouseEvent e)
        {
            int x = e.getX(), y = e.getY();

            // check to see if we have clicked on a row in the inspector
            // and should open a graph of the data
            if (doSparklineInteraction(e) == true)
            {
                return;
            }

            int bestsection = -1;

            // find the bottom-most section that contains the mouse click.
            for (int i = 0; i < sections.size(); i++)
            {
                Section cs = sections.get(i);

                if (x>=cs.x0 && x<=cs.x1 && y>=cs.y0 && y<=cs.y1) {
                    bestsection = i;
                }
            }

            if (bestsection >= 0)
                sections.get(bestsection).collapsed ^= true;

            // when changing sections, need to recompute visibility of sparklines
            // or you can end up not displaying a section until the viewport changes
            updateVisibleSparklines(scrollViewport);

            // call repaint here so the UI will update immediately instead of
            // waiting for the next piece of data
            repaint();
        }
    }

    class MyMouseMotionListener extends MouseMotionAdapter
    {

        /**
         * Check to see if we need to update the highlight
         * on a row.
         *
         * @param e MouseEvent from the mouse move
         */
        public void mouseMoved(MouseEvent e)
        {
            Section oldSection = currentlyHoveringSection;
            String oldName = currentlyHoveringName;
            doSparklineInteraction(e);
            if (oldSection != currentlyHoveringSection ||
                    !Objects.equals(oldName, currentlyHoveringName))
                repaint();
        }
    }

    class MyViewportChangeListener implements ChangeListener
    {
        /**
         * Here we build a list of the items that are visible
         * or are close to visible to the user.  That way, we can
         * only update sparkline charts that are close to what the
         * user is looking at, reducing CPU load with huge messages
         *
         * @param e change event that fired this update
         */
        public void stateChanged(ChangeEvent e)
        {

            JViewport viewport = (JViewport) e.getSource();

            updateVisibleSparklines(viewport);
        }
    }
}
