package zcm.spy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/** The channel table and sorter see one snapshot, changed only on Swing's event thread. */
final class ChannelTableModel extends AbstractTableModel
{
    private static final String[] COLUMNS = {"Channel", "Type", "Num Msgs", "Hz", "1/Hz", "Jitter", "Bandwidth", "Undecodable"};
    private List<Row> rows = Collections.emptyList();

    void setChannels(List<ChannelData> channels)
    {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Channel table updates must run on the event thread");
        ArrayList<Row> next = new ArrayList<Row>(channels.size());
        for (ChannelData channel : channels) next.add(new Row(channel));
        rows = next;
        // Invalidate the sorter's cached mapping before Swing can paint another frame.
        fireTableDataChanged();
    }

    ChannelData channelAt(int row) { return row < 0 || row >= rows.size() ? null : rows.get(row).channel; }
    int indexOf(ChannelData channel) {
        for (int row = 0; row < rows.size(); row++) if (rows.get(row).channel == channel) return row;
        return -1;
    }
    public int getRowCount() { return rows.size(); }
    public int getColumnCount() { return COLUMNS.length; }
    public String getColumnName(int column) { return COLUMNS[column]; }
    public Class<?> getColumnClass(int column) { return column == 6 ? Bandwidth.class : super.getColumnClass(column); }
    public Object getValueAt(int row, int column) { return rows.get(row).value(column); }

    /** Keep sorting numeric even when rows display different bandwidth units. */
    private static final class Bandwidth implements Comparable<Bandwidth> {
        private final double bytesPerSecond;
        private String text;
        Bandwidth(double bytesPerSecond) { this.bytesPerSecond = bytesPerSecond; }
        public int compareTo(Bandwidth other) { return Double.compare(bytesPerSecond, other.bytesPerSecond); }
        public String toString() {
            if (text == null) {
                if (bytesPerSecond < 1024) text = String.format("%.0f B/s", bytesPerSecond);
                else if (bytesPerSecond < 1024 * 1024) text = String.format("%.2f KB/s", bytesPerSecond / 1024);
                else text = String.format("%.2f MB/s", bytesPerSecond / (1024 * 1024));
            }
            return text;
        }
    }

    private static final class Row {
        final ChannelData channel;
        final long received;
        final int errors;
        final double hz, period, jitter, bandwidth;
        final boolean hasJitter;
        final Object[] cells = new Object[COLUMNS.length];
        Row(ChannelData channel) {
            this.channel = channel;
            cells[0] = channel.name;
            String type = channel.cls == null ? null : channel.cls.getName();
            cells[1] = type == null ? String.format("?? %016x", channel.fingerprint) : type.substring(type.lastIndexOf('.') + 1);
            received = channel.nreceived; errors = channel.nerrors; hz = channel.hz;
            period = 1000.0 / hz; jitter = (channel.max_interval - channel.min_interval) / 1000.0;
            bandwidth = channel.bandwidth;
            hasJitter = channel.hz_num_hz_updates > 1 && hz > 0;
        }
        Object value(int column) {
            if (cells[column] == null) {
                switch (column) {
                    case 2: cells[column] = Long.toString(received); break;
                    case 3: cells[column] = String.format("%6.2f", hz); break;
                    case 4: cells[column] = String.format("%6.2f ms", period); break;
                    case 5: cells[column] = hasJitter ? String.format("%6.2f ms", jitter) : " -"; break;
                    case 6: cells[column] = new Bandwidth(bandwidth); break;
                    case 7: cells[column] = Integer.toString(errors); break;
                }
            }
            return cells[column];
        }
    }
}
