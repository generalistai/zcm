package zcm.spy;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Locale;
import javax.swing.*;
import javax.swing.table.*;
import info.monitorenter.gui.chart.ITrace2D;

/** Controls and readouts stay outside the plot so they do not obscure signals. */
final class ChartControls extends JPanel
{
    private final ZoomableChartScrollWheel chart;
    private final JToggleButton pause = new JToggleButton("Pause");
    private final JToggleButton link = new JToggleButton("Link time");
    private final JComboBox<String> window = new JComboBox<String>(
        new String[] {"Last 5 s", "Last 30 s", "Last 2 min", "All retained"});
    private final JLabel state = new JLabel();
    private final JLabel cursors = new JLabel("Move over the plot to inspect samples.");
    private final LegendModel model = new LegendModel();
    private final JTable table = new JTable(model);
    private final JPanel legend = new JPanel(new BorderLayout());
    private boolean updating;
    private final double[] windows = {5, 30, 120, 0};

    ChartControls(ZoomableChartScrollWheel chart)
    {
        super(new BorderLayout(0, 4));
        this.chart = chart;
        setBorder(BorderFactory.createEmptyBorder(5, 5, 3, 5));
        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 4));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        toolbar.add(pause);
        SpyIcons.decorate(pause, SpyIcons.Symbol.PAUSE);
        pause.setToolTipText("Freeze the display while collection continues. Applies to linked charts together.");
        pause.addActionListener(e -> { if (!updating) chart.setPaused(pause.isSelected()); });
        button(toolbar, "Live", SpyIcons.Symbol.LIVE, () -> chart.goLive());
        toolbar.add(window);
        window.addActionListener(e -> { if (!updating) chart.setTimeWindow(windows[window.getSelectedIndex()]); });
        toolbar.add(link);
        SpyIcons.decorate(link, SpyIcons.Symbol.LINK);
        link.setToolTipText("Join other linked charts for time navigation, pause/live, and shared cursors. Y ranges stay independent.");
        link.addActionListener(e -> { if (!updating) chart.setTimeLinked(link.isSelected()); });
        toolbar.add(state);
        JPanel measurements = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        button(measurements, "Set A", SpyIcons.Symbol.CURSOR_A, () -> chart.armCursor(1)).setToolTipText("Click the plot to place cursor A; or press A at the pointer.");
        button(measurements, "Set B", SpyIcons.Symbol.CURSOR_B, () -> chart.armCursor(2)).setToolTipText("Click the plot to place cursor B; or press B at the pointer.");
        button(measurements, "Clear cursors", SpyIcons.Symbol.CLEAR_CURSORS, () -> chart.clearCursors());
        JButton add = button(measurements, "Add signals…", SpyIcons.Symbol.ADD, () -> SignalPicker.show(chart));
        add.setEnabled(chart.chartData.signalSource != null);
        rows.add(toolbar); rows.add(measurements);
        add(rows, BorderLayout.NORTH);
        add(cursors, BorderLayout.SOUTH);

        table.setRowHeight(Math.max(22, table.getFontMetrics(table.getFont()).getHeight() + 6));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        table.getColumnModel().getColumn(1).setMaxWidth(45);
        table.getColumnModel().getColumn(2).setMaxWidth(46);
        table.getColumnModel().getColumn(3).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                             boolean focus, int row, int column) {
                JLabel cell = (JLabel)super.getTableCellRendererComponent(table, "", selected, focus, row, column);
                cell.setBackground((Color)value); cell.setToolTipText("Click to change trace color");
                return cell;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint()), column = table.columnAtPoint(e.getPoint());
                if (row < 0 || column != 2) return;
                ITrace2D trace = model.traces.get(row);
                Color color = JColorChooser.showDialog(ChartControls.this, "Trace color", trace.getColor());
                if (color != null) { trace.setColor(color); model.fireTableRowsUpdated(row, row); chart.repaint(); }
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setColumnHeaderView(table.getTableHeader());
        scroll.setPreferredSize(new Dimension(900, 155));
        legend.add(scroll, BorderLayout.CENTER);
        JLabel help = new JLabel(" Wheel: both axes   Shift+wheel: X   Ctrl+wheel: Y   Drag: pan   Click: A then B   Double-click: reset   Values: nearest retained sample");
        help.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        legend.add(help, BorderLayout.SOUTH);
        bind("SPACE", "pause", () -> chart.setPaused(!chart.isPaused()));
        bind("A", "cursorA", () -> chart.pinCursor(1, chart.getCursorTime()));
        bind("B", "cursorB", () -> chart.pinCursor(2, chart.getCursorTime()));
        bind("ESCAPE", "clear", () -> chart.clearCursors());
        rebuildLegend();
    }

    private void bind(String key, String name, Runnable action)
    {
        chart.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        chart.getActionMap().put(name, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private JButton button(JPanel panel, String text, SpyIcons.Symbol icon, Runnable action)
    {
        JButton button = new JButton(text); SpyIcons.decorate(button, icon);
        button.addActionListener(e -> action.run()); panel.add(button); return button;
    }

    JPanel legendPanel() { return legend; }

    void rebuildLegend()
    {
        model.traces.clear(); model.traces.addAll(chart.getTraces());
        model.fireTableDataChanged();
        table.getColumnModel().getColumn(8).setCellEditor(new DefaultCellEditor(new JComboBox<String>(chart.axisNames())));
        refresh();
    }

    void refresh()
    {
        updating = true;
        try {
            pause.setSelected(chart.isPaused()); pause.setText(chart.isPaused() ? "Resume" : "Pause");
            SpyIcons.decorate(pause, chart.isPaused() ? SpyIcons.Symbol.PLAY : SpyIcons.Symbol.PAUSE);
            link.setSelected(chart.isTimeLinked());
            for (int i = 0; i < windows.length; i++) if (windows[i] == chart.getWindowSeconds()) window.setSelectedIndex(i);
            state.setText(String.format(Locale.ROOT, "%s · %.1f s retained",
                chart.isPaused() ? "Paused" : chart.isFollowing() ? "Live" : "Browsing", chart.retainedSeconds()));
        } finally { updating = false; }
        refreshReadouts();
    }

    static String number(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6g", value) : "—"; }

    void refreshReadouts()
    {
        cursors.setText("t: " + number(chart.getCursorTime()) + " s     A: " + number(chart.getCursorA()) +
                       " s     B: " + number(chart.getCursorB()) + " s     Δt: " +
                       number(chart.getCursorB() - chart.getCursorA()) + " s");
        if (chart.getArmedCursor() != 0)
            cursors.setText("Click the plot to place cursor " + (chart.getArmedCursor() == 1 ? "A" : "B") + ".   " + cursors.getText());
        if (!table.isEditing() && !model.traces.isEmpty()) model.fireTableRowsUpdated(0, model.traces.size() - 1);
    }

    private double value(ITrace2D trace, double time)
    {
        if (!(trace instanceof StreamingTrace)) return Double.NaN;
        StreamingTrace.Sample sample = ((StreamingTrace)trace).sampleAt(time);
        return sample == null ? Double.NaN : sample.value;
    }

    private class LegendModel extends AbstractTableModel
    {
        final ArrayList<ITrace2D> traces = new ArrayList<ITrace2D>();
        final String[] columns = {"Show", "Solo", "Color", "Signal", "At cursor / latest", "At A", "At B", "Δvalue", "Y axis"};
        public int getRowCount() { return traces.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return columns[column]; }
        public Class<?> getColumnClass(int column) { return column < 2 ? Boolean.class : column == 2 ? Color.class : String.class; }
        public boolean isCellEditable(int row, int column) { return column < 2 || column == 8; }
        public Object getValueAt(int row, int column) {
            ITrace2D trace = traces.get(row);
            switch (column) {
                case 0: return trace.isVisible();
                case 1: return chart.isSolo(trace);
                case 2: return trace.getColor();
                case 3: return trace.getName();
                case 4: return number(Double.isNaN(chart.getCursorTime()) && trace instanceof StreamingTrace ?
                    ((StreamingTrace)trace).latestValue() : value(trace, chart.getCursorTime()));
                case 5: return number(value(trace, chart.getCursorA()));
                case 6: return number(value(trace, chart.getCursorB()));
                case 7: return number(value(trace, chart.getCursorB()) - value(trace, chart.getCursorA()));
                case 8: return chart.axisName(trace);
                default: return "";
            }
        }
        public void setValueAt(Object value, int row, int column) {
            ITrace2D trace = traces.get(row);
            if (column == 0) chart.setTraceVisible(trace, (Boolean)value);
            else if (column == 1) chart.solo(trace);
            else if (column == 8) chart.assignAxis(trace, value.toString());
        }
    }
}
