package zcm.spy;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** Modeless, bounded search over the latest decoded messages. */
final class SignalPicker extends JDialog
{
    private final ZoomableChartScrollWheel chart;
    private final SignalCatalog.Source source;
    private final SignalCatalog.Favorites favorites;
    private final JTextField search = new JTextField(35);
    private final JCheckBox onlyFavorites = new JCheckBox("Favorites only");
    private final JLabel status = new JLabel("Searching…");
    private final JLabel selectedCount = new JLabel();
    private final JButton add = new JButton("Add selected");
    private final Map<String, SignalCatalog.Signal> selected = new LinkedHashMap<String, SignalCatalog.Signal>();
    private final SignalModel model = new SignalModel();
    private SwingWorker<SignalCatalog.Result, Void> worker;
    private final javax.swing.Timer debounce = new javax.swing.Timer(180, e -> search());

    static void show(ZoomableChartScrollWheel chart)
    {
        if (chart.chartData.signalSource == null) return;
        SignalPicker picker = (SignalPicker)chart.getClientProperty("signalPicker");
        if (picker == null || !picker.isDisplayable()) {
            picker = new SignalPicker(chart);
            chart.putClientProperty("signalPicker", picker);
        }
        picker.setVisible(true); picker.toFront(); picker.search.requestFocusInWindow();
    }

    private SignalPicker(ZoomableChartScrollWheel chart)
    {
        super(SwingUtilities.getWindowAncestor(chart), "Add signals", ModalityType.MODELESS);
        SpyIcons.window(this);
        this.chart = chart; source = chart.chartData.signalSource; favorites = chart.chartData.favorites();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        debounce.setRepeats(false);
        JPanel filters = new JPanel(new BorderLayout(8, 5));
        filters.add(new JLabel("Search channel or field", SpyIcons.icon(SpyIcons.Symbol.SEARCH), JLabel.LEFT), BorderLayout.WEST);
        filters.add(search, BorderLayout.CENTER);
        filters.add(onlyFavorites, BorderLayout.EAST);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { changedUpdate(e); }
            public void removeUpdate(DocumentEvent e) { changedUpdate(e); }
            public void changedUpdate(DocumentEvent e) {
                if (worker != null) worker.cancel(true);
                debounce.restart();
            }
        });
        onlyFavorites.addActionListener(e -> search());
        JTable table = new JTable(model);
        table.setRowHeight(Math.max(23, table.getFontMetrics(table.getFont()).getHeight() + 6));
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setMaxWidth(65);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        table.getColumnModel().getColumn(3).setPreferredWidth(360);
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                             boolean focus, int row, int column) {
                JLabel label = (JLabel)super.getTableCellRendererComponent(table, "", selected, focus, row, column);
                label.setHorizontalAlignment(SwingConstants.CENTER);
                label.setIcon(SpyIcons.icon(Boolean.TRUE.equals(value) ? SpyIcons.Symbol.FAVORITED : SpyIcons.Symbol.FAVORITE));
                label.setToolTipText(Boolean.TRUE.equals(value) ? "Remove from favorites" : "Save as a favorite");
                label.getAccessibleContext().setAccessibleName(Boolean.TRUE.equals(value) ? "Favorite" : "Not a favorite");
                return label;
            }
        });
        JCheckBox favoriteEditor = new JCheckBox();
        favoriteEditor.setHorizontalAlignment(SwingConstants.CENTER);
        favoriteEditor.setIcon(SpyIcons.icon(SpyIcons.Symbol.FAVORITE));
        favoriteEditor.setSelectedIcon(SpyIcons.icon(SpyIcons.Symbol.FAVORITED));
        favoriteEditor.getAccessibleContext().setAccessibleName("Favorite signal");
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(favoriteEditor));
        JPanel selectionActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refresh = new JButton("Refresh"); refresh.addActionListener(e -> search());
        JButton all = new JButton("Select results");
        all.addActionListener(e -> { for (SignalCatalog.Signal signal : model.rows) selected.put(signal.id, signal); selectionChanged(); model.fireTableDataChanged(); });
        JButton clear = new JButton("Clear selection");
        clear.addActionListener(e -> { selected.clear(); selectionChanged(); model.fireTableDataChanged(); });
        add.addActionListener(e -> addSelected());
        JButton close = new JButton("Close"); close.addActionListener(e -> dispose());
        SpyIcons.decorate(refresh, SpyIcons.Symbol.REFRESH);
        SpyIcons.decorate(all, SpyIcons.Symbol.SELECT_ALL);
        SpyIcons.decorate(clear, SpyIcons.Symbol.CLEAR);
        SpyIcons.decorate(add, SpyIcons.Symbol.ADD);
        SpyIcons.decorate(close, SpyIcons.Symbol.CLOSE);
        selectionActions.add(refresh); selectionActions.add(all); selectionActions.add(clear);
        actions.add(selectedCount); actions.add(add); actions.add(close);
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel actionRows = new JPanel(new GridLayout(0, 1)); actionRows.add(selectionActions); actionRows.add(actions);
        bottom.add(status, BorderLayout.NORTH); bottom.add(actionRows, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JScrollPane scroll = new JScrollPane(table); scroll.setColumnHeaderView(table.getTableHeader());
        content.add(filters, BorderLayout.NORTH); content.add(scroll, BorderLayout.CENTER); content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent e) {
                debounce.stop(); if (worker != null) worker.cancel(true);
                chart.putClientProperty("signalPicker", null);
            }
        });
        ZoomableChartScrollWheel.fitToScreen(this, 950, 520);
        setLocationRelativeTo(chart); selectionChanged(); search();
    }

    private void selectionChanged()
    {
        selectedCount.setText(selected.size() + " selected"); add.setEnabled(!selected.isEmpty());
    }

    private void search()
    {
        debounce.stop();
        if (worker != null) worker.cancel(true);
        final String query = search.getText();
        final boolean only = onlyFavorites.isSelected();
        final Set<String> ids = new HashSet<String>(favorites.ids);
        final List<ChannelData> channels = source.channels();
        status.setText("Searching latest messages…");
        worker = new SwingWorker<SignalCatalog.Result, Void>() {
            protected SignalCatalog.Result doInBackground() {
                return SignalCatalog.search(channels, query, ids, only, () -> isCancelled());
            }
            protected void done() {
                if (isCancelled()) return;
                try {
                    SignalCatalog.Result result = get();
                    model.rows = result.signals; model.fireTableDataChanged();
                    status.setText(result.signals.size() + " signals" + (result.limited ? " · Search limit reached; narrow your query or refresh." : " · Values from the latest message. Favorites are saved across sessions."));
                } catch (CancellationException ignored) {
                } catch (Exception error) { status.setText("Search failed: " + error.getMessage()); }
            }
        };
        worker.execute();
    }

    /** A trace belongs to one chart; repeated selections reuse the subscription. */
    static boolean addSignal(ZoomableChartScrollWheel chart, SignalCatalog.Source source, SignalCatalog.Signal signal)
    {
        StreamingTrace trace = source.inspector(signal.channel).traceForSignal(signal);
        for (ZoomableChartScrollWheel owner : chart.chartData.getCharts()) {
            if (owner.getTraces().contains(trace)) {
                owner.setTraceVisible(trace, true); owner.toFront(); return owner == chart;
            }
        }
        trace.setColor(chart.popColor());
        chart.addTrace(trace);
        trace.flush();
        trace.setPaused(chart.isPaused());
        return true;
    }

    private void addSelected()
    {
        int elsewhere = 0;
        for (SignalCatalog.Signal signal : selected.values())
            if (!addSignal(chart, source, signal)) elsewhere++;
        chart.updateRightClickMenu(); chart.chartData.flush(); chart.repaint();
        selected.clear(); selectionChanged(); model.fireTableDataChanged();
        status.setText(elsewhere == 0 ? "Signals added. Collection continues while this window is open." :
            elsewhere + " signals are already plotted in another chart; those windows were brought forward.");
    }

    private class SignalModel extends AbstractTableModel {
        List<SignalCatalog.Signal> rows = Collections.emptyList();
        final String[] columns = {"Add", "Favorite", "Channel", "Field", "Latest value"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return columns[column]; }
        public Class<?> getColumnClass(int column) { return column < 2 ? Boolean.class : String.class; }
        public boolean isCellEditable(int row, int column) { return column < 2; }
        public Object getValueAt(int row, int column) {
            SignalCatalog.Signal signal = rows.get(row);
            switch (column) {
                case 0: return selected.containsKey(signal.id);
                case 1: return favorites.ids.contains(signal.id);
                case 2: return signal.channel.name;
                case 3: return signal.name;
                default: return ChartControls.number(signal.value);
            }
        }
        public void setValueAt(Object value, int row, int column) {
            SignalCatalog.Signal signal = rows.get(row);
            if (column == 0) {
                if ((Boolean)value) selected.put(signal.id, signal); else selected.remove(signal.id);
                selectionChanged();
            } else {
                favorites.set(signal.id, (Boolean)value);
                chart.chartData.saveFavorites(error -> status.setText("Could not save favorites: " + error));
                if (onlyFavorites.isSelected()) search();
            }
            fireTableRowsUpdated(row, row);
        }
    }
}
