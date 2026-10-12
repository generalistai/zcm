package zcm.spy;

import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import info.monitorenter.gui.chart.IAxis;
import info.monitorenter.gui.chart.ITrace2D;
import info.monitorenter.gui.chart.ZoomableChart;
import info.monitorenter.gui.chart.axis.AAxis;
import info.monitorenter.gui.chart.axis.AxisLinear;
import info.monitorenter.gui.chart.labelformatters.LabelFormatterNumber;
import info.monitorenter.gui.chart.rangepolicies.RangePolicyFixedViewport;
import info.monitorenter.util.Range;
import javax.swing.*;

/**
 * Chart that supports panning and zooming in the Google-maps style.
 *
 */
public class ZoomableChartScrollWheel extends ZoomableChart
{
    private double mouseDownStartX, mouseDownStartY, mouseDownValPerPxX, mouseDownMinX, mouseDownMaxX;
    private ArrayList<Double> mouseDownValPerPxY = new ArrayList<Double>();
    private ArrayList<Double> mouseDownMinY = new ArrayList<Double>();
    private ArrayList<Double> mouseDownMaxY = new ArrayList<Double>();
    
    private long lastFocusTime = -1;
    private JFrame frame = null;
    
    // internal color list
    private ArrayList<Color> colors = new ArrayList<Color>();

    // color index
    private int colorNum = 0;
    
    // we need a list of the axes on the right, which we update ourselves
    private ArrayList<AAxis> rightYAxis = new ArrayList<AAxis>();
    
    private JPopupMenu popup = new JPopupMenu();
    
    ChartData chartData;
    private ChartControls controls;
    private boolean paused, following, timeLinked;
    private double windowSeconds = 5;
    private double cursorTime = Double.NaN, cursorA = Double.NaN, cursorB = Double.NaN;
    private int armedCursor;
    private final Map<ITrace2D, Boolean> beforeSolo = new LinkedHashMap<ITrace2D, Boolean>();
    private ITrace2D soloTrace;

    ChartControls controls()
    {
        if (controls == null) controls = new ChartControls(this);
        return controls;
    }

    boolean isPaused() { return paused; }
    boolean isFollowing() { return following; }
    boolean isTimeLinked() { return timeLinked; }
    double getWindowSeconds() { return windowSeconds; }
    double getCursorTime() { return cursorTime; }
    double getCursorA() { return cursorA; }
    double getCursorB() { return cursorB; }
    int getArmedCursor() { return armedCursor; }

    double latestTime()
    {
        double time = Double.NEGATIVE_INFINITY;
        for (ITrace2D trace : getTraces())
            if (trace.getSize() > 0) time = Math.max(time, trace.getMaxX());
        return time;
    }

    double retainedSeconds()
    {
        double start = Double.POSITIVE_INFINITY, end = Double.NEGATIVE_INFINITY;
        for (ITrace2D trace : getTraces()) {
            if (trace.getSize() == 0) continue;
            start = Math.min(start, trace.getMinX()); end = Math.max(end, trace.getMaxX());
        }
        return Double.isFinite(start) ? end - start : 0;
    }

    double earliestTime()
    {
        double time = Double.POSITIVE_INFINITY;
        for (ITrace2D trace : getTraces())
            if (trace.getSize() > 0) time = Math.min(time, trace.getMinX());
        return time;
    }

    void applyPaused(boolean value)
    {
        if (paused == value) return;
        for (ITrace2D trace : getTraces()) {
            if (trace instanceof StreamingTrace) {
                StreamingTrace stream = (StreamingTrace)trace;
                if (value) stream.flush();
                stream.setPaused(value);
                if (!value) stream.flush();
            }
        }
        paused = value;
        if (controls != null) controls.refresh();
        repaint();
    }

    void setPaused(boolean value)
    {
        applyPaused(value);
        chartData.linkPause(this, value);
        refreshView();
    }

    void goLive()
    {
        following = true;
        setFixedWidthXAxisFormat();
        setPaused(false);
        refreshView();
        chartData.linkView(this);
    }

    void setTimeWindow(double seconds)
    {
        windowSeconds = Math.max(0, seconds);
        following = true;
        setFixedWidthXAxisFormat();
        applyTimeWindow(chartData.latestTime(this));
        chartData.linkView(this);
        if (controls != null) controls.refresh();
    }

    private void applyTimeWindow(double end)
    {
        if (!Double.isFinite(end)) return;
        if (windowSeconds == 0) {
            double start = chartData.earliestTime(this);
            if (!Double.isFinite(start)) start = end;
            setTimeRange(start, Math.max(start + .001, end));
        } else setTimeRange(end - windowSeconds, end);
    }

    void refreshView()
    {
        if (following && !paused) applyTimeWindow(chartData.latestTime(this));
        if (controls != null && controls.isShowing()) controls.refresh();
    }

    private void setTimeRange(double min, double max)
    {
        if (!Double.isFinite(min) || !Double.isFinite(max) || min >= max) return;
        if (getAxisX().getRangePolicy() instanceof RangePolicyFixedViewport) {
            Range range = getAxisX().getRangePolicy().getRange();
            if (range.getMin() != min || range.getMax() != max)
                getAxisX().getRangePolicy().setRange(new Range(min, max));
        } else getAxisX().setRangePolicy(new RangePolicyFixedViewport(new Range(min, max)));
    }

    void manualTimeViewChanged()
    {
        following = false;
        chartData.linkView(this);
        if (controls != null) controls.refresh();
    }

    void setTimeLinked(boolean linked)
    {
        timeLinked = linked;
        if (linked) {
            for (ZoomableChartScrollWheel chart : chartData.getCharts()) {
                if (chart != this && chart.isTimeLinked()) {
                    applyPaused(chart.paused);
                    copyTimeView(chart); copyCursors(chart);
                    break;
                }
            }
        }
        if (controls != null) controls.refresh();
    }

    void copyTimeView(ZoomableChartScrollWheel source)
    {
        following = source.following;
        windowSeconds = source.windowSeconds;
        setTimeRange(source.getAxisX().getMin(), source.getAxisX().getMax());
        if (following) setFixedWidthXAxisFormat(); else setVariableWidthXAxisFormat();
        if (controls != null) controls.refresh();
        repaint();
    }

    void copyCursors(ZoomableChartScrollWheel source)
    {
        cursorTime = source.cursorTime; cursorA = source.cursorA; cursorB = source.cursorB;
        if (controls != null) controls.refreshReadouts();
        repaint();
    }

    void setCursorTime(double time)
    {
        cursorTime = time;
        chartData.linkHover(this);
        // Mouse motion may arrive hundreds of times per second. Use the existing
        // 30 Hz chart timer; ChartData.refreshView updates the legend at that rate.
        setRequestedRepaint(true);
    }

    void copyHover(ZoomableChartScrollWheel source)
    {
        cursorTime = source.cursorTime;
        setRequestedRepaint(true);
    }

    void armCursor(int cursor)
    {
        armedCursor = cursor; requestFocusInWindow();
        if (controls != null) controls.refreshReadouts();
    }

    void pinCursor(int cursor, double time)
    {
        if (!Double.isFinite(time)) return;
        if (cursor == 1) cursorA = time;
        else cursorB = time;
        armedCursor = 0;
        chartData.linkCursors(this);
        if (controls != null) controls.refreshReadouts();
        repaint();
    }

    void clearCursors()
    {
        cursorA = cursorB = Double.NaN; armedCursor = 0;
        chartData.linkCursors(this);
        if (controls != null) controls.refreshReadouts();
        repaint();
    }

    void setTraceVisible(ITrace2D trace, boolean visible)
    {
        restoreSolo(); trace.setVisible(visible);
        if (controls != null) controls.rebuildLegend();
        repaint();
    }

    boolean isSolo(ITrace2D trace) { return soloTrace == trace; }

    private void restoreSolo()
    {
        for (Map.Entry<ITrace2D, Boolean> entry : beforeSolo.entrySet())
            if (getTraces().contains(entry.getKey())) entry.getKey().setVisible(entry.getValue());
        beforeSolo.clear(); soloTrace = null;
    }

    void solo(ITrace2D trace)
    {
        if (soloTrace == trace) restoreSolo();
        else {
            if (soloTrace == null)
                for (ITrace2D item : getTraces()) beforeSolo.put(item, item.isVisible());
            soloTrace = trace;
            for (ITrace2D item : getTraces()) item.setVisible(item == trace);
        }
        if (controls != null) controls.rebuildLegend();
        repaint();
    }

    String axisName(ITrace2D trace)
    {
        IAxis<?> axis = getAxisY(trace);
        int index = rightYAxis.indexOf(axis);
        return index < 0 ? "Main" : "Y" + (index + 2);
    }

    String[] axisNames()
    {
        ArrayList<String> names = new ArrayList<String>(); names.add("Main");
        for (int i = 0; i < rightYAxis.size(); i++) names.add("Y" + (i + 2));
        names.add("New axis"); return names.toArray(new String[0]);
    }

    void assignAxis(ITrace2D trace, String name)
    {
        IAxis<?> old = getAxisY(trace), axis = getAxisY();
        if ("New axis".equals(name)) {
            AxisLinear fresh = new AxisLinear(); addAxisYRight(fresh); axis = fresh;
        } else if (name.startsWith("Y")) {
            int index = Integer.parseInt(name.substring(1)) - 2;
            if (index < 0 || index >= rightYAxis.size()) return;
            axis = rightYAxis.get(index);
        }
        if (axis == old) return;
        removeTrace(trace); addTrace(trace, getAxisX(), axis);
        if (old != getAxisY() && old.getTraces().isEmpty()) removeAxisYRight(old);
        updateRightClickMenu(); repaint();
    }

    void detachTrace(ITrace2D trace)
    {
        IAxis<?> axis = getAxisY(trace);
        // A moved trace should retain its visibility from before Solo.
        Boolean visible = beforeSolo.remove(trace);
        removeTrace(trace);
        if (visible != null) trace.setVisible(visible);
        if (axis != null && axis != getAxisY() && axis.getTraces().isEmpty()) removeAxisYRight(axis);
        updateRightClickMenu();
    }

    private void paintCursors(Graphics2D g)
    {
        g.setStroke(new BasicStroke(0));
        paintCursor(g, cursorTime, new Color(100, 100, 100), "t");
        paintCursor(g, cursorA, new Color(30, 90, 210), "A");
        paintCursor(g, cursorB, new Color(190, 90, 0), "B");
    }

    private void paintCursor(Graphics2D g, double time, Color color, String label)
    {
        if (!Double.isFinite(time) || time < getAxisX().getMin() || time > getAxisX().getMax()) return;
        int x = getAxisX().translateValueToPx(time);
        g.setColor(color);
        g.drawLine(x, getYChartEnd(), x, getYChartStart());
        g.drawString(label, x + 3, getYChartEnd() + 12);
    }

    @Override
    public void mouseMoved(MouseEvent e)
    {
        if (e.getX() >= getXChartStart() && e.getX() <= getXChartEnd() &&
            e.getY() >= getYChartEnd() && e.getY() <= getYChartStart())
            setCursorTime(getAxisX().translatePxToValue(e.getX()));
        else if (Double.isFinite(cursorTime)) setCursorTime(Double.NaN);
    }

    @Override
    public void mouseExited(MouseEvent e)
    {
        super.mouseExited(e);
        if (Double.isFinite(cursorTime)) setCursorTime(Double.NaN);
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        Graphics2D graphics = (Graphics2D)g.create();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                 RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        try {
            super.paintComponent(graphics);
            paintCursors(graphics);
        } finally {
            graphics.dispose();
            // A painter normally resets this itself. Also restore full-history
            // iteration if the chart library aborts a paint with an exception.
            for (ITrace2D trace : getTraces())
                if (trace instanceof StreamingTrace)
                    ((StreamingTrace)trace).setRendering(false);
        }
    }
    
    /**
     * Constructor, taking in a chartData so that we can set up the chart 
     * 
     * @param chartData global data about all charts displayed in zcm-spy
     */
    public ZoomableChartScrollWheel(ChartData chartData)
    {
        this.addMouseWheelListener(new MyMouseWheelListener(this));
        
        this.getAxisX().setPaintGrid(true);
        this.getAxisY().setPaintGrid(true);
        this.setUseAntialiasing(false);
        this.setPaintLabels(false);
        this.setGridColor(Color.LIGHT_GRAY);
        this.getAxisX().getAxisTitle().setTitle("Time (sec)");
        this.getAxisY().getAxisTitle().setTitle("");
        this.chartData = chartData;
        
        colors.add(Color.RED);
        colors.add(Color.BLACK);
        colors.add(Color.BLUE);
        colors.add(Color.MAGENTA);
        colors.add(Color.CYAN);
        colors.add(Color.ORANGE);
        colors.add(Color.GREEN);
        
        this.setFixedWidthXAxisFormat();
        
        
        this.setMinPaintLatency(33); // match the 30 Hz chart data refresh
    }
    
    /**
     * Creates a new frame for this trace.  Called either by ObjectPanel to create
     * a new graph or from the right-click menu to move a trace to a new graph.
     * 
     * @param chartData global chart data for all of zcm-spy
     * @param trace data that this chart should display
     */
    public static void newChartFrame(final ChartData chartData, final ITrace2D trace)
    {
        JFrame frame = new JFrame(trace.getName());
        SpyIcons.window(frame);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        
        final ZoomableChartScrollWheel newChart = new ZoomableChartScrollWheel(chartData);
        
        trace.setColor(newChart.popColor());
        
        newChart.addTrace(trace);
        newChart.updateRightClickMenu();
        
        chartData.getCharts().add(newChart);
        
        Container content = frame.getContentPane();
        content.setLayout(new BorderLayout());
        content.add(newChart.controls(), BorderLayout.NORTH);
        content.add(newChart, BorderLayout.CENTER);
        content.add(newChart.controls().legendPanel(), BorderLayout.SOUTH);
        newChart.goLive();
        
        newChart.addFrameFocusTimer(frame);
        
        frame.addWindowListener(new WindowAdapter()
        {
            public void windowClosed(WindowEvent e)
            {
                for (ITrace2D trace : new ArrayList<ITrace2D>(newChart.getTraces()))
                {
                    chartData.stopTrace(trace);
                    newChart.removeTrace(trace);
                }
                chartData.getCharts().remove(newChart);
                newChart.destroy();
            }
        });
        
        fitToScreen(frame, 1100, 750);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    static void fitToScreen(java.awt.Window window, int width, int height)
    {
        java.awt.GraphicsConfiguration config = window.getGraphicsConfiguration();
        java.awt.Rectangle bounds = config.getBounds();
        java.awt.Insets insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(config);
        int availableWidth = Math.max(1, bounds.width - insets.left - insets.right);
        int availableHeight = Math.max(1, bounds.height - insets.top - insets.bottom);
        window.setSize(Math.min(width, availableWidth), Math.min(height, availableHeight));
        window.setMinimumSize(new java.awt.Dimension(Math.min(720, availableWidth), Math.min(450, availableHeight)));
    }
    
    /**
     * Shows the right-click menu if appropriate.
     * 
     * @param e MouseEvent to process
     * @return true if the right-click menu was shown
     */
    private boolean maybeShowPopup(MouseEvent e)
    {
        if (e.isPopupTrigger())
        {
            popup.show(e.getComponent(), e.getX(), e.getY());
            return true;
        }
        return false;
    }
    
    /**
     * Gets the next color for a new trace.  Use this to keep colors as different
     * as possible.  Increments the color counter.
     * 
     * @return next color to use for a trace
     */
    public Color popColor()
    {
        Color thisColor = colors.get(colorNum % colors.size());
        colorNum++;
        return thisColor;
    }
    
    /**
     * Adds the newest trace color back onto the stack.
     */
    public void pushColor()
    {
        colorNum--;
    }
    
    /**
     * Updates the right click menu to allow for moving
     * traces around.  Should be called immediately after adding
     * a new trace.
     */
    public void updateRightClickMenu()
    {
        for (ITrace2D trace : getTraces()) {
            if (trace instanceof StreamingTrace) {
                StreamingTrace stream = (StreamingTrace)trace;
                if (paused && !stream.isPaused()) stream.flush();
                stream.setPaused(paused);
            }
            if (soloTrace != null && !beforeSolo.containsKey(trace)) {
                beforeSolo.put(trace, trace.isVisible()); trace.setVisible(false);
            }
        }
        if (soloTrace != null && !getTraces().contains(soloTrace)) restoreSolo();
        if (controls != null) controls.rebuildLegend();
        // zap the old right click menu
        popup = new JPopupMenu();
        
        Iterator<ITrace2D> iter = this.getTraces().iterator();
        
        boolean firstFlag = true;
        
        StringBuilder frameTitle = new StringBuilder();
        
        while (iter.hasNext())
        {
            final ITrace2D trace = iter.next();
            
            JMenuItem topItem = new JMenuItem(trace.getName());
            topItem.setEnabled(false);
            
            if (!firstFlag)
            {
                popup.addSeparator();
            }
            
            popup.add(topItem);
            popup.addSeparator();
            
            boolean rightTraceFlag = false;
            
            for (final AAxis axis : rightYAxis)
            {
                if (axis.getTraces().contains(trace))
                {
                    // this trace is in the extra Y axis area
                    
                    JMenuItem newItem = new JMenuItem("    to main axis");
                    
                    newItem.addActionListener(new ActionListener() {
                        public void actionPerformed(ActionEvent e)
                        {
                            assignAxis(trace, "Main");
                        }
                    });
                    
                    popup.add(newItem);
                    rightTraceFlag = true;
                    break;
                }
            }
            
            if (rightTraceFlag == false)
            {
                // this trace is on the normal Y axis
                JMenuItem newItem = new JMenuItem("    to separate axis");
                
                if (this.getAxisY().getTraces().size() < 2)
                {
                    newItem.setEnabled(false);
                }
                
                newItem.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e)
                    {
                        assignAxis(trace, "New axis");
                    }
                });
                
                popup.add(newItem);
            }
            
            JMenuItem moveWindowItem = new JMenuItem("    move to new window");
            moveWindowItem.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e)
                {
                    detachTrace(trace);
                    
                    ZoomableChartScrollWheel.newChartFrame(chartData, trace);
                    
                    
                }
            });
            
            
            JMenuItem delItem = new JMenuItem("    remove");
            delItem.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e)
                {
                    detachTrace(trace);
                    chartData.stopTrace(trace);
                }
            });
            
            if (this.getAxisX().getTraces().size() < 2)
            {
                delItem.setEnabled(false);
                moveWindowItem.setEnabled(false);
            }
            
            popup.add(moveWindowItem);
            popup.add(delItem);
            
            if (!firstFlag)
            {
                frameTitle.append(", ");
            }
            frameTitle.append(trace.getName());
            
            firstFlag = false;
        }
        
        if (this.frame != null)
        {
            this.frame.setTitle(frameTitle.toString());
        }
    }
    

    public void addAxisYRight(AAxis<?> axisY)
    {
        super.addAxisYRight(axisY);
        
        rightYAxis.add(axisY);
    }
    
    public boolean removeAxisYRight(IAxis<?> axisY)
    {
        rightYAxis.remove(axisY);
        
        return super.removeAxisYRight(axisY);
    }
    
    /**
     * Move this frame to the front to get the user's attention
     */
    public void toFront()
    {
        if (frame != null)
        {
            java.awt.EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    frame.toFront();
                    frame.repaint();
                }
            });
        }
    }
    
    /**
     * Saves the time this window was last in focus.  Allows us to put new traces
     * on the last chart that was in focus
     * 
     * @param frame frame to add the focus timer to
     */
    public void addFrameFocusTimer(JFrame frame)
    {
        this.frame = frame;
        
        this.frame.addWindowFocusListener(new WindowFocusListener()
        {
            public void windowGainedFocus(WindowEvent we)
            {
                lastFocusTime = System.nanoTime()/1000;
            }

            public void windowLostFocus(WindowEvent we)
            {
                lastFocusTime = System.nanoTime()/1000;
            }
        });
    }
    
    /**
     * Returns the last time this frame was focused.
     * 
     * @return the last time the frame was focused
     */
    public long getLastFocusTime() { return lastFocusTime; }
    
    /**
     * Handle mouse press events
     * 
     */
    public void mousePressed(MouseEvent e)
    {
        if (maybeShowPopup(e))
        {
            return;
        }
        
        IAxis xAxis = this.getAxisX();
        IAxis yAxis = this.getAxisY();
        
        double xAxisRange = xAxis.getRange().getExtent();
        
        mouseDownValPerPxY.clear();
        mouseDownMinY.clear();
        mouseDownMaxY.clear();
        
        mouseDownStartX = e.getX();
        mouseDownStartY = e.getY();
        
        double xAxisWidth = this.getXChartEnd() - this.getXChartStart();
        double yAxisHeight = this.getYChartStart() - this.getYChartEnd();
        
        mouseDownValPerPxX = xAxisRange / xAxisWidth;
        
        mouseDownMinX = xAxis.getMin();
        mouseDownMaxX = xAxis.getMax();
        
        double yAxisRange = yAxis.getRange().getExtent();
        
        mouseDownValPerPxY.add(yAxisRange / yAxisHeight);
        mouseDownMinY.add(yAxis.getMin());
        mouseDownMaxY.add(yAxis.getMax());
        
        for (AAxis yAxisRight : rightYAxis)
        {
            double yAxisRangeRight = yAxisRight.getMax() - yAxisRight.getMin();
            mouseDownValPerPxY.add(yAxisRangeRight / yAxisHeight);
            mouseDownMinY.add(yAxisRight.getMin());
            mouseDownMaxY.add(yAxisRight.getMax());
        }
        
    }
    
    /**
     * Pan the chart when the mouse is dragged.
     */
    public void mouseDragged(MouseEvent e)
    {
        // move the view
        if ((e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0 &&
            (Math.abs(e.getX() - mouseDownStartX) >= 3 || Math.abs(e.getY() - mouseDownStartY) >= 3))
        {
            dragChart(e);
        }
    }
    
    /**
     * Handle mouse release events, including double-click and right click.
     */
    public void mouseReleased(MouseEvent e)
    {
        if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2)
        {
            this.zoomAll();
            setFixedWidthXAxisFormat();
            goLive();
            
            e.consume();
        } else if (!maybeShowPopup(e) && e.getButton() == MouseEvent.BUTTON1 &&
                Math.abs(e.getX() - mouseDownStartX) < 3 && Math.abs(e.getY() - mouseDownStartY) < 3 &&
                e.getX() >= getXChartStart() && e.getX() <= getXChartEnd() &&
                e.getY() >= getYChartEnd() && e.getY() <= getYChartStart()) {
            pinCursor(armedCursor != 0 ? armedCursor : Double.isNaN(cursorA) ? 1 : 2,
                      getAxisX().translatePxToValue(e.getX()));
        }
    }
    
    /**
     * Implements panning the chart on mouse drag.
     * 
     * @param e MouseEvent to process
     */
    private void dragChart(MouseEvent e)
    {
        
        double deltaPxX = e.getX() - mouseDownStartX;
        double deltaPxY = e.getY() - mouseDownStartY;
        
        
        
        double deltaX = deltaPxX * mouseDownValPerPxX;
        double deltaY = deltaPxY * mouseDownValPerPxY.get(0);
        
        if (Double.isNaN(mouseDownMinX) || Double.isNaN(mouseDownMaxX) || Double.isNaN(mouseDownMinY.get(0))
                || Double.isNaN(mouseDownMaxY.get(0)) ||Double.isNaN(deltaX) || Double.isNaN(deltaY)
                || Double.isInfinite(mouseDownMinX) || Double.isInfinite(mouseDownMaxX) || Double.isInfinite(mouseDownMinY.get(0))
                || Double.isInfinite(mouseDownMaxY.get(0)) ||Double.isInfinite(deltaX) || Double.isInfinite(deltaY))
        {
            return;
        }
        
        zoom(mouseDownMinX - deltaX, mouseDownMaxX - deltaX,
                mouseDownMinY.get(0) + deltaY, mouseDownMaxY.get(0) + deltaY);
        
        setVariableWidthXAxisFormat();
        manualTimeViewChanged();
        
        // do moving for right Y axes
        for (int i = 0; i < rightYAxis.size(); i++)
        {
            AAxis axis = rightYAxis.get(i);
            
            double deltaYRight = deltaPxY * mouseDownValPerPxY.get(i+1);
            
            zoom(axis, axis.translateValueToPx(mouseDownMinY.get(i+1) + deltaYRight),
                    axis.translateValueToPx(mouseDownMaxY.get(i+1) + deltaYRight));
            
        }
        
        
    }
    
    /**
     * Variable-width x-axis formatting causes jumps when data is "rolling in"
     * because the labels change width, which causes the X-axis to change width
     * which causes visual disruption.  When using zoomAll, change to a fixed
     * width format on the x-axis.
     */
    private void setFixedWidthXAxisFormat()
    {
        DecimalFormat fixedWidthFormat = new DecimalFormat("#");
        LabelFormatterNumber fixedWidthFormatter = new LabelFormatterNumber(fixedWidthFormat);
        this.getAxisX().setFormatter(fixedWidthFormatter);
    }
    
    /**
     * When data is not "rolling in" use a varible format for
     * maximum flexibility in display.
     */
    private void setVariableWidthXAxisFormat()
    {
        DecimalFormat variableWidthFormat = new DecimalFormat();
        LabelFormatterNumber variableWidthFormatter = new LabelFormatterNumber(variableWidthFormat);
        this.getAxisX().setFormatter(variableWidthFormatter);
    }
    
    
    
    /** Zoom one axis around the cursor without freezing the other axes. */
    private boolean zoomAxis(IAxis<?> axis, int pixel, double fraction, double factor)
    {
        double extent = axis.getRange().getExtent() * factor;
        double anchor = axis.translatePxToValue(pixel);
        double min = anchor - extent * fraction;
        double max = anchor + extent * (1 - fraction);
        if (!Double.isFinite(min) || !Double.isFinite(max) || min >= max)
            return false;
        axis.setRangePolicy(new RangePolicyFixedViewport(new Range(min, max)));
        if (axis == getAxisX()) manualTimeViewChanged();
        return true;
    }

    public class MyMouseWheelListener implements MouseWheelListener
    {
        private ZoomableChartScrollWheel chart;
        
        public MyMouseWheelListener(ZoomableChartScrollWheel chart)
        {
            this.chart = chart;
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e)
        {
            double rotation = e.getPreciseWheelRotation();
            int width = chart.getXChartEnd() - chart.getXChartStart();
            int height = chart.getYChartStart() - chart.getYChartEnd();
            if (rotation == 0 || width <= 0 || height <= 0)
                return;

            // Shift selects X, Ctrl selects Y; neither or both selects both.
            boolean zoomX = !e.isControlDown() || e.isShiftDown();
            boolean zoomY = !e.isShiftDown() || e.isControlDown();
            double factor = Math.pow(1.2, rotation);
            double xFraction = (e.getX() - chart.getXChartStart()) / (double)width;
            double yFraction = (chart.getYChartStart() - e.getY()) / (double)height;

            boolean changed = false;
            if (zoomX && zoomAxis(chart.getAxisX(), e.getX(), xFraction, factor)) {
                chart.setVariableWidthXAxisFormat();
                changed = true;
            }
            if (zoomY) {
                changed |= zoomAxis(chart.getAxisY(), e.getY(), yFraction, factor);
                for (AAxis<?> axis : chart.rightYAxis)
                    changed |= zoomAxis(axis, e.getY(), yFraction, factor);
            }
            if (changed) {
                chart.repaint();
                e.consume();
            }
        }
    }
    
}
