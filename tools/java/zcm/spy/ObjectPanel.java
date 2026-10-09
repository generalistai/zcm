package zcm.spy;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.table.*;
import info.monitorenter.gui.chart.axis.AxisLinear;

/** Standard virtual table with the complete decoded message expanded inline. */
public class ObjectPanel extends JPanel
{
    private final String name;
    private final ChartData chartData;
    private static final class Message {
        final Object object;
        final long utime, sequence;
        Message(Object object, long utime, long sequence) {
            this.object = object; this.utime = utime; this.sequence = sequence;
        }
    }

    // Only this snapshot and the selected subscriptions are touched by the receiver.
    private volatile Message latestMessage;
    private Message displayedMessage;
    private static final class Subscription {
        final MessageValue.Path path;
        final StreamingTrace trace;
        Subscription(MessageValue.Path path, StreamingTrace trace) { this.path = path; this.trace = trace; }
        void record(Message message) { trace.record(message.utime / 1000000.0, MessageValue.number(path.read(message.object))); }
    }
    private final ArrayList<Subscription> subscriptions = new ArrayList<Subscription>();

    static final class SignalData {
        final MessageValue.Path path;
        MiniHistory history;
        StreamingTrace detailedTrace;
        long lastSequence = -1;
        SignalData(MessageValue.Path path) { this.path = path; }
    }
    // Active subscriptions survive scrolling; inactive mini histories have a strict bound.
    final HashMap<String, SignalData> selectedSignals = new HashMap<String, SignalData>();
    final LinkedHashMap<String, SignalData> histories = new LinkedHashMap<String, SignalData>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, SignalData> entry) { return size() > 512; }
    };

    final ValuesModel values = new ValuesModel();
    final JTable table = new JTable(values);
    final JScrollPane tableScroll = new JScrollPane(table);
    private final JTextField index = new JTextField(7);
    private final JLabel status = new JLabel("Waiting for a decoded message.");
    private final JButton plot = new JButton("Plot"), newChart = new JButton("New chart");
    private final JButton separate = new JButton("New Y axis"), copy = new JButton("Copy value"), inspect = new JButton("View value");
    private final JButton go = new JButton("Go");
    private final JButton previous = new JButton("Previous rows"), next = new JButton("Next rows");
    private final JPanel jump = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
    private SwingWorker<MessageLayout, Void> layoutWorker;
    private final javax.swing.Timer refreshTimer = new javax.swing.Timer(33, e -> {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (isShowing() && (!(window instanceof Frame) || (((Frame)window).getExtendedState() & Frame.ICONIFIED) == 0)) refreshView();
    });

    public ObjectPanel(String name, ChartData chartData)
    {
        super(new BorderLayout(3, 3));
        this.name = name; this.chartData = chartData;
        table.setRowHeight(Math.max(table.getFontMetrics(table.getFont()).getHeight(),
            table.getFontMetrics(SpyFonts.monospace(table.getFont())).getHeight()) + 3);
        table.setFillsViewportHeight(true); table.setShowVerticalLines(false);
        Color background = table.getBackground(), foreground = table.getForeground();
        table.setGridColor(new Color((background.getRed() * 9 + foreground.getRed()) / 10,
            (background.getGreen() * 9 + foreground.getGreen()) / 10, (background.getBlue() * 9 + foreground.getBlue()) / 10));
        table.setIntercellSpacing(new Dimension(0, 1));
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        DefaultTableCellRenderer text = new DefaultTableCellRenderer();
        text.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, text);
        table.getColumnModel().getColumn(0).setCellRenderer(new FieldRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int index, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, index, column);
                Row row = values.row(index);
                setFont(row != null && row.value instanceof Number ? SpyFonts.monospace(table.getFont()) : table.getFont());
                return this;
            }
        });
        table.getColumnModel().getColumn(3).setCellRenderer(new HistoryRenderer());
        int[] widths = {180, 145, 310, 160};
        int[] minimums = {80, 65, 140, 100};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.getColumnModel().getColumn(i).setMinWidth(minimums[i]);
        }
        tableScroll.setColumnHeaderView(table.getTableHeader());
        tableScroll.getVerticalScrollBar().setUnitIncrement(table.getRowHeight());
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) updateActions(); });
        table.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { popup(e); }
            public void mouseReleased(MouseEvent e) { popup(e); }
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint()); if (row < 0) return;
                if (e.getButton() == MouseEvent.BUTTON2) plotSelected(true, false);
                else if (e.getButton() == MouseEvent.BUTTON1 && (e.getClickCount() == 2 || table.columnAtPoint(e.getPoint()) == 3)) activate(row);
            }
        });

        index.setFont(SpyFonts.monospace(index.getFont()));
        index.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        JToolBar actions = new JToolBar();
        actions.setFloatable(false); actions.setRollover(true); actions.setBorder(BorderFactory.createEmptyBorder());
        SpyIcons.decorate(plot, SpyIcons.Symbol.LIVE); SpyIcons.decorate(newChart, SpyIcons.Symbol.ADD);
        SpyIcons.decorate(copy, SpyIcons.Symbol.COPY);
        plot.addActionListener(e -> plotSelected(false, false)); newChart.addActionListener(e -> plotSelected(true, false));
        separate.addActionListener(e -> plotSelected(false, true)); copy.addActionListener(e -> copy(false)); inspect.addActionListener(e -> viewValue());
        for (JButton button : new JButton[] {plot, newChart, separate, copy, inspect, go, previous, next})
            button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        for (JButton button : new JButton[] {plot, newChart, separate, copy, inspect}) actions.add(button);
        jump.add(new JLabel("Array index")); jump.add(index); jump.add(go);
        JPanel paging = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0)); paging.add(previous); paging.add(next);
        previous.addActionListener(e -> page(-1)); next.addActionListener(e -> page(1));
        index.getAccessibleContext().setAccessibleName("Array index");
        go.addActionListener(e -> jump()); index.addActionListener(e -> jump());
        JPanel tools = new JPanel(new BorderLayout(3, 0)); tools.add(actions, BorderLayout.CENTER); tools.add(jump, BorderLayout.EAST);
        JPanel footer = new JPanel(new BorderLayout()); footer.add(status, BorderLayout.CENTER); footer.add(paging, BorderLayout.EAST);
        setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        add(tools, BorderLayout.NORTH); add(tableScroll, BorderLayout.CENTER); add(footer, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(1050, 650));
        bind("ENTER", "open", () -> { if (table.getSelectedRow() >= 0) activate(table.getSelectedRow()); });
        bind("ctrl C", "copy", () -> copy(false)); bind("ctrl shift C", "copyPath", () -> copy(true));
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) { refreshView(); refreshTimer.start(); } else refreshTimer.stop();
            }
        });
        updateActions();
    }

    /** Constant receive-thread work when no fields are selected, regardless of message size. */
    public void setObject(Object object, long utime)
    {
        synchronized (subscriptions) {
            Message message = new Message(object, utime - chartData.getStartTime(), latestMessage == null ? 0 : latestMessage.sequence + 1);
            latestMessage = message;
            for (Subscription subscription : subscriptions) subscription.record(message);
        }
    }

    void refreshView()
    {
        Message message = latestMessage;
        if (message == null || message == displayedMessage || layoutWorker != null) return;
        load(message);
    }

    private void load(Message message)
    {
        if (message == null) return;
        try {
            present(message, new MessageLayout(message.object, MessageValue.Path.ROOT, 0, Integer.MAX_VALUE, 2048, () -> false));
        } catch (MessageLayout.NeedsBackground large) {
            status.setText("Loading expanded message values…");
            layoutWorker = new SwingWorker<MessageLayout, Void>() {
                protected MessageLayout doInBackground() {
                    return new MessageLayout(message.object, MessageValue.Path.ROOT, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, () -> isCancelled());
                }
                protected void done() {
                    if (layoutWorker != this) return;
                    layoutWorker = null;
                    if (isCancelled()) return;
                    try { present(message, get()); }
                    catch (Exception error) { status.setText("Could not inspect message: " + error.getMessage()); }
                }
            };
            layoutWorker.execute();
        }
    }

    private void present(Message message, MessageLayout layout)
    {
        displayedMessage = message;
        values.update(layout);
        updateActions();
    }

    private int pageRows() { return Math.max(1, (Integer.MAX_VALUE - 1024) / table.getRowHeight()); }

    private void page(int direction)
    {
        values.page += direction * (long)pageRows(); values.repage();
        tableScroll.getViewport().setViewPosition(new Point(0, 0)); updateActions();
    }

    private void jump()
    {
        try { scrollToIndex(Integer.parseInt(index.getText().trim())); }
        catch (NumberFormatException error) { status.setText("Enter a valid array index."); }
    }

    void scrollToIndex(int wanted)
    {
        MessageValue.Path path = selectedArray();
        Object array = path == null ? null : path.read(values.container);
        if (array == null || wanted < 0 || wanted >= Array.getLength(array)) {
            status.setText("That index is outside the selected array."); return;
        }
        scrollToPath(path.append(wanted, array.getClass().getComponentType()));
    }

    void scrollToPath(MessageValue.Path path)
    {
        if (values.layout == null) return;
        long offset = values.layout.indexOf(path);
        if (offset < 0) return;
        if (offset < values.page || offset >= values.page + values.rows) {
            values.page = offset / pageRows() * pageRows(); values.repage();
        }
        int row = (int)(offset - values.page); table.setRowSelectionInterval(row, row); table.scrollRectToVisible(table.getCellRect(row, 0, true));
        updateActions();
    }

    private void activate(int row)
    {
        Row value = values.row(row);
        if (value == null) return;
        if (value.plottable()) plotSelected(false, false);
        else if (!value.expandable()) viewValue();
    }

    private MessageValue.Path selectedArray()
    {
        Row row = values.row(table.getSelectedRow());
        Object[] steps = row == null ? new Object[0] : row.path.steps;
        for (int length = steps.length; length >= 0; length--) {
            MessageValue.Path path = new MessageValue.Path(Arrays.copyOf(steps, length), Object.class);
            Object value = path.read(values.container);
            if (value != null && value.getClass().isArray()) return path;
        }
        return null;
    }

    private void bind(String key, String name, Runnable action)
    {
        table.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        table.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
    }

    private void updateActions()
    {
        int firstSelected = table.getSelectionModel().getMinSelectionIndex();
        boolean selected = firstSelected >= 0;
        Row lead = values.row(firstSelected);
        boolean numeric = selected && (firstSelected != table.getSelectionModel().getMaxSelectionIndex() || lead != null && lead.plottable());
        plot.setEnabled(numeric); newChart.setEnabled(numeric); separate.setEnabled(numeric);
        copy.setEnabled(selected); inspect.setEnabled(selected);
        MessageValue.Path arrayPath = selectedArray();
        boolean array = arrayPath != null;
        go.setEnabled(array && Array.getLength(arrayPath.read(values.container)) > 0); index.setEnabled(go.isEnabled());
        String hint = array ? "Jump within " + (arrayPath.name.isEmpty() ? name : arrayPath.name) : null;
        index.setToolTipText(hint); go.setToolTipText(hint);
        boolean paged = values.totalRows() > pageRows(); previous.setVisible(paged); next.setVisible(paged);
        jump.setVisible(array);
        previous.setEnabled(values.page > 0); next.setEnabled(values.page + values.rows < values.totalRows());
        if (displayedMessage != null) {
            String context = values.container == MessageValue.MISSING ? "Field or array element unavailable in the latest message." :
                values.recursive ? "Recursive reference." :
                values.totalRows() + " expanded fields / values";
            status.setText(context + "   ·   Double-click to plot. Numeric and boolean charts collect every sample.");
        }
    }

    private void popup(MouseEvent event)
    {
        if (!event.isPopupTrigger()) return;
        int row = table.rowAtPoint(event.getPoint()); if (row < 0) return;
        if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
        JPopupMenu menu = new JPopupMenu();
        item(menu, "View value", () -> viewValue(), true);
        item(menu, "Plot", () -> plotSelected(false, false), plot.isEnabled());
        item(menu, "Plot in new chart", () -> plotSelected(true, false), plot.isEnabled());
        item(menu, "Plot on new Y axis", () -> plotSelected(false, true), plot.isEnabled());
        menu.addSeparator(); item(menu, "Copy value", () -> copy(false), true); item(menu, "Copy field path", () -> copy(true), true);
        menu.show(table, event.getX(), event.getY());
    }

    private void item(JPopupMenu menu, String text, Runnable action, boolean enabled)
    {
        JMenuItem item = new JMenuItem(text); item.setEnabled(enabled); item.addActionListener(e -> action.run()); menu.add(item);
    }

    private void copy(boolean path)
    {
        StringBuilder text = new StringBuilder();
        for (int index : table.getSelectedRows()) {
            Row row = values.row(index); if (row == null) continue;
            if (text.length() > 0) text.append('\n');
            text.append(path ? row.path.name : MessageValue.copyValue(row.value));
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.toString()), null);
    }

    private void viewValue()
    {
        Row row = values.row(table.getSelectedRow()); if (row == null) return;
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), row.path.name + " — captured value", Dialog.ModalityType.MODELESS);
        SpyIcons.window(dialog); dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JTextArea text = new JTextArea(MessageValue.copyValue(row.value)); text.setEditable(false);
        text.setFont(row.value instanceof Number ? SpyFonts.monospace(table.getFont()) : table.getFont()); text.setCaretPosition(0);
        dialog.add(new JScrollPane(text)); ZoomableChartScrollWheel.fitToScreen(dialog, 800, 500); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    SignalData signal(MessageValue.Path path)
    {
        SignalData data = selectedSignals.get(path.name);
        if (data == null) data = histories.get(path.name);
        if (data == null || !data.path.equals(path)) { data = new SignalData(path); histories.put(path.name, data); }
        return data;
    }

    StreamingTrace traceForSignal(SignalCatalog.Signal signal)
    {
        return createDetailedTrace(signal(new MessageValue.Path(signal.path, Object.class)));
    }

    StreamingTrace createDetailedTrace(final SignalData data)
    {
        if (data.detailedTrace != null) return data.detailedTrace;
        final StreamingTrace trace = new StreamingTrace(chartData.detailedSparklineChartSize, name + ": " + data.path.name);
        trace.setStroke(new BasicStroke(0)); trace.setTracePainter(new PixelTracePainter(trace));
        if (data.history != null) for (int i = 0; i < data.history.size; i++) trace.record(data.history.x(i), data.history.y(i));
        final Subscription subscription = new Subscription(data.path, trace);
        synchronized (subscriptions) {
            if (latestMessage != null && latestMessage.sequence != data.lastSequence) subscription.record(latestMessage);
            subscriptions.add(subscription);
        }
        data.detailedTrace = trace; selectedSignals.put(data.path.name, data); histories.remove(data.path.name);
        chartData.startTrace(trace, () -> {
            synchronized (subscriptions) { subscriptions.remove(subscription); }
            data.detailedTrace = null; selectedSignals.remove(data.path.name, data); histories.put(data.path.name, data);
        });
        return trace;
    }

    private ZoomableChartScrollWheel recentChart()
    {
        ZoomableChartScrollWheel best = null;
        for (ZoomableChartScrollWheel chart : chartData.getCharts())
            if (best == null || chart.getLastFocusTime() > best.getLastFocusTime()) best = chart;
        return best;
    }

    private ZoomableChartScrollWheel attach(SignalData data, ZoomableChartScrollWheel target, boolean newAxis)
    {
        StreamingTrace trace = createDetailedTrace(data);
        for (ZoomableChartScrollWheel owner : chartData.getCharts())
            if (owner.getTraces().contains(trace)) { owner.toFront(); return target; }
        if (target == null) {
            ZoomableChartScrollWheel.newChartFrame(chartData, trace); target = chartData.getCharts().getLast();
        } else {
            trace.setColor(target.popColor());
            if (newAxis) { AxisLinear axis = new AxisLinear(); target.addAxisYRight(axis); target.addTrace(trace, target.getAxisX(), axis); }
            else target.addTrace(trace);
            target.updateRightClickMenu(); target.toFront();
        }
        return target;
    }

    void displayDetailedChart(SignalData data, boolean newWindow, boolean newAxis)
    {
        attach(data, newWindow ? null : recentChart(), newAxis);
    }

    private void plotSelected(boolean newWindow, boolean newAxis)
    {
        ZoomableChartScrollWheel target = newWindow ? null : recentChart();
        for (int index : table.getSelectedRows()) {
            Row row = values.row(index);
            if (row != null && row.plottable()) target = attach(signal(row.path), target, newAxis);
        }
    }

    static final class Row {
        final MessageValue.Path path;
        final String name;
        final Object value;
        final Class<?> type;
        final boolean recursive;
        Row(MessageValue.Path path, String name, Object value, Class<?> type, boolean recursive) {
            this.path = path; this.name = name; this.value = value;
            this.type = value != null && value != MessageValue.MISSING && !type.isPrimitive() ? value.getClass() : type;
            this.recursive = recursive;
        }
        boolean expandable() { return !recursive && value != null && value != MessageValue.MISSING && !MessageValue.scalar(value.getClass()); }
        boolean plottable() { return !path.constant && MessageValue.plottable(value); }
    }

    /** A renderer is reused for every visible row; indentation does not allocate widgets. */
    private final class FieldRenderer extends DefaultTableCellRenderer {
        FieldRenderer() { putClientProperty("html.disable", Boolean.TRUE); }
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int index, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, index, column);
            Row row = values.row(index);
            int depth = row == null ? 0 : Math.max(0, row.path.steps.length - 1);
            setBorder(BorderFactory.createEmptyBorder(0, 6 + depth * 16, 0, 4));
            if (row != null && row.expandable()) setFont(table.getFont().deriveFont(Font.BOLD));
            setToolTipText(row == null ? null : name + ": " + row.path.name);
            return this;
        }
    }

    final class ValuesModel extends AbstractTableModel {
        MessageLayout layout;
        Object container;
        long page;
        int rows;
        boolean recursive;
        private final LinkedHashMap<Integer, Row> cache = new LinkedHashMap<Integer, Row>(64, .75f, true) {
            protected boolean removeEldestEntry(Map.Entry<Integer, Row> entry) { return size() > 256; }
        };
        void update(MessageLayout next) {
            Row selected = row(table.getSelectedRow());
            int oldRows = rows;
            layout = next; container = next.root.value; recursive = next.root.recursive;
            page = Math.min(page, Math.max(0, next.rows - 1) / pageRows() * pageRows());
            rows = (int)Math.min(pageRows(), next.rows - page); cache.clear();
            long position = selected == null ? -1 : layout.indexOf(selected.path);
            if (oldRows != rows) {
                fireTableDataChanged();
                if (position >= page && position < page + rows) table.setRowSelectionInterval((int)(position - page), (int)(position - page));
            } else {
                if (selected != null && position < 0) table.clearSelection();
                if (selected != null && position >= page && position < page + rows && position - page != table.getSelectedRow())
                    table.setRowSelectionInterval((int)(position - page), (int)(position - page));
                Rectangle visible = table.getVisibleRect(); int top = table.rowAtPoint(visible.getLocation());
                if (top >= 0) fireTableRowsUpdated(top, Math.min(rows - 1, top + visible.height / table.getRowHeight() + 1));
            }
        }
        void repage() {
            rows = (int)Math.min(pageRows(), layout.rows - page); cache.clear(); fireTableDataChanged();
        }
        Row row(int index) {
            if (index < 0 || index >= rows || layout == null) return null;
            Row row = cache.get(index);
            if (row == null) { row = layout.row(page + index); cache.put(index, row); }
            return row;
        }
        long totalRows() { return layout == null ? 0 : layout.rows; }
        int cachedRows() { return cache.size(); }
        public int getRowCount() { return rows; }
        public int getColumnCount() { return 4; }
        public String getColumnName(int column) { return new String[] {"Field", "Type", "Value", "History · 5 s"}[column]; }
        public Object getValueAt(int index, int column) {
            Row row = row(index); if (row == null) return "";
            switch (column) {
                case 0:
                    Object step = row.path.steps.length == 0 ? null : row.path.steps[row.path.steps.length - 1];
                    String name = step instanceof Field ? ((Field)step).getName() : step == null ? "(value)" : "[" + step + "]";
                    return name + (row.path.constant ? " (constant)" : "");
                case 1: return MessageValue.type(row.type);
                case 2: return row.recursive ? "(recursive reference)" : MessageValue.format(row.value);
                default: return row;
            }
        }
    }

    /** Primitive rings avoid a chart object, point objects, or a timer for each table row. */
    static final class MiniHistory {
        final double[] xs, ys;
        int start, size;
        MiniHistory(int capacity) { xs = new double[capacity]; ys = new double[capacity]; }
        void add(double x, double y) {
            int index = (start + size) % xs.length; xs[index] = x; ys[index] = y;
            if (size == xs.length) start = (start + 1) % xs.length; else size++;
        }
        double x(int i) { return xs[(start + i) % xs.length]; }
        double y(int i) { return ys[(start + i) % ys.length]; }
    }

    private final class HistoryRenderer extends JPanel implements TableCellRenderer {
        private SignalData data;
        private Color line;
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            line = selected ? table.getSelectionForeground() : new Color(0x167E8B);
            Row field = (Row)value; data = null;
            if (field.plottable() && displayedMessage != null) {
                data = signal(field.path);
                double number = MessageValue.number(field.value);
                if (data.lastSequence != displayedMessage.sequence && Double.isFinite(number)) {
                    if (data.history == null) data.history = new MiniHistory(chartData.sparklineChartSize);
                    data.history.add(displayedMessage.utime / 1000000.0, number); data.lastSequence = displayedMessage.sequence;
                }
            }
            setToolTipText(data == null ? null : "Click to plot every received sample"); return this;
        }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (data == null || data.history == null || data.history.size == 0) return;
            MiniHistory history = data.history;
            Graphics2D g = (Graphics2D)graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                g.setStroke(new BasicStroke(0)); g.setColor(line); g.clipRect(4, 3, Math.max(0, getWidth() - 8), Math.max(0, getHeight() - 6));
                double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < history.size; i++) { min = Math.min(min, history.y(i)); max = Math.max(max, history.y(i)); }
                double earliest = displayedMessage.utime / 1000000.0 - 5;
                Path2D path = new Path2D.Double(); double x = 0, y = 0;
                for (int i = 0; i < history.size; i++) {
                    x = 4 + (history.x(i) - earliest) / 5 * (getWidth() - 8);
                    double span = max - min;
                    double fraction = min == max ? .5 : Double.isInfinite(span) ? (history.y(i) / 2 - min / 2) / (max / 2 - min / 2) : (history.y(i) - min) / span;
                    y = getHeight() - 4 - fraction * (getHeight() - 8);
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                g.draw(path); g.fillRect((int)Math.round(x) - 1, (int)Math.round(y) - 1, 3, 3);
            } finally { g.dispose(); }
        }
    }

    void dispose() { refreshTimer.stop(); if (layoutWorker != null) { layoutWorker.cancel(true); layoutWorker = null; } }
}
