package zcm.spy;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import zcm.util.TableSorter;
import zcm.zcm.ZCMDataInputStream;

/** Regression for receiver updates exposing row counts newer than the sorter's cached mapping. */
public class ChannelTableTest
{
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static ChannelData channel(String name) {
        ChannelData channel = new ChannelData(); channel.name = name; channel.nreceived = 123456789;
        channel.hz = 42; channel.bandwidth = 4096; return channel;
    }
    private static void paint(JTable table) {
        table.setSize(800, Math.max(600, table.getRowCount() * table.getRowHeight())); table.doLayout();
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try { g.setClip(0, 0, 800, 600); table.paint(g); } finally { g.dispose(); }
    }

    private static void snapshots() throws Exception {
        ChannelTableModel model = new ChannelTableModel();
        TableSorter sorter = new TableSorter(model); JTable table = new JTable(sorter);
        ArrayList<ChannelData> incoming = new ArrayList<ChannelData>(Arrays.asList(channel("B"), channel("C")));
        sorter.setSortingStatus(0, TableSorter.ASCENDING);
        model.setChannels(incoming); sorter.getValueAt(0, 0); // Cache two rows before arrival; numeric cells are still unread.
        Thread receiver = new Thread(() -> {
            incoming.get(0).nreceived = 987654321; incoming.get(0).hz = 99;
            incoming.add(channel("A"));
        });
        synchronized (sorter) {
            receiver.start(); receiver.join(5000);
            check(!receiver.isAlive(), "Receiver waited for the sorter's paint lock");
            check(table.getRowCount() == 2, "Unpublished arrival changed the visible row count");
            paint(table); // Formerly throws ArrayIndexOutOfBoundsException, index 2, length 2.
        }
        check(model.getValueAt(0, 2).equals("123456789"), "Receiver changed a published cell");
        check(model.getValueAt(0, 3).equals(String.format("%6.2f", 42.0)), "Sort values changed midway through a snapshot");
        model.setChannels(incoming); paint(table);
        check(table.getRowCount() == 3 && table.getValueAt(0, 0).equals("A") && table.getValueAt(2, 0).equals("C"), "New channel did not sort into the refreshed table");
        check(model.channelAt(sorter.modelIndex(0)) == incoming.get(2), "Sorted click lookup points to the wrong channel");

        incoming.clear(); paint(table);
        check(table.getRowCount() == 3, "Receiver clear changed rows while Swing was painting");
        model.setChannels(incoming); check(table.getRowCount() == 0, "Clear did not publish an empty snapshot");
        incoming.add(channel("RESTARTED")); model.setChannels(incoming); paint(table);
        check(table.getValueAt(0, 0).equals("RESTARTED"), "Clear left the old sorter mapping cached");
        check(model.channelAt(-1) == null && model.channelAt(10) == null, "Blank-area lookup should return no channel");

        for (int pass = 0; pass < 100; pass++) {
            incoming.clear();
            for (int i = pass % 37; i >= 0; i--) incoming.add(channel("CHANNEL_" + i));
            model.setChannels(incoming);
            sorter.setSortingStatus(pass % 8, pass % 2 == 0 ? TableSorter.ASCENDING : TableSorter.DESCENDING);
            paint(table);
            for (int row = 0; row < table.getRowCount(); row++)
                check(table.getValueAt(row, 0).equals(model.channelAt(sorter.modelIndex(row)).name), "Refresh changed row identity");
        }
        AtomicReference<Throwable> result = new AtomicReference<Throwable>();
        Thread wrongThread = new Thread(() -> {
            try { model.setChannels(Collections.emptyList()); }
            catch (Throwable expected) { result.set(expected); }
        });
        wrongThread.start(); wrongThread.join(5000);
        check(result.get() instanceof IllegalStateException, "Off-thread table updates were accepted");
    }

    private static void bandwidthUnits() {
        ChannelTableModel model = new ChannelTableModel();
        TableSorter sorter = new TableSorter(model);
        double[] rates = {0, 512, 1023, 1024, 2048, 1048575, 1048576, 1310720};
        String[] labels = {"0 B/s", "512 B/s", "1023 B/s",
            String.format("%.2f KB/s", 1.0), String.format("%.2f KB/s", 2.0),
            String.format("%.2f KB/s", 1048575.0 / 1024),
            String.format("%.2f MB/s", 1.0), String.format("%.2f MB/s", 1.25)};
        ArrayList<ChannelData> channels = new ArrayList<ChannelData>();
        for (int i = rates.length - 1; i >= 0; i--) {
            ChannelData channel = channel("RATE_" + i); channel.bandwidth = rates[i]; channels.add(channel);
        }
        model.setChannels(channels);
        channels.get(0).bandwidth = 0; // Published bandwidth values must remain stable until refresh.
        sorter.setSortingStatus(6, TableSorter.ASCENDING);
        for (int i = 0; i < rates.length; i++) {
            check(sorter.getValueAt(i, 0).equals("RATE_" + i), "Bandwidth sorted by its label instead of its rate");
            check(sorter.getValueAt(i, 6).toString().equals(labels[i]), "Incorrect adaptive bandwidth units at " + rates[i]);
        }
        sorter.setSortingStatus(6, TableSorter.DESCENDING);
        check(sorter.getValueAt(0, 0).equals("RATE_7"), "Descending bandwidth sort failed across units");
    }

    /** Optional integration run through the actual Spy receiver, Clear button, and GTK painter. */
    private static void liveGui() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> failure.compareAndSet(null, error));
        Spy[] spy = new Spy[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                SpyAppearance.configure();
                try { spy[0] = new Spy("inproc", new Spy.WindowTitleOptions()); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            Spy.MySubscriber receiver = spy[0].new MySubscriber();
            receiver.messageReceived(null, "B", new ZCMDataInputStream(new byte[8]));
            receiver.messageReceived(null, "C", new ZCMDataInputStream(new byte[8]));
            // Queue refreshes while keeping the EDT occupied, so painting gets the same
            // arrival-before-refresh ordering as the reported crash.
            SwingUtilities.invokeAndWait(() -> {
                check(spy[0].channelTable.getRowCount() == 2, "Initial channels did not publish");
                paint(spy[0].channelTable);
                Thread arrivals = new Thread(() -> receiver.messageReceived(null, "A", new ZCMDataInputStream(new byte[8])));
                arrivals.start();
                try { arrivals.join(5000); } catch (InterruptedException error) { throw new RuntimeException(error); }
                check(!arrivals.isAlive(), "Receiver blocked behind painting");
                check(spy[0].channelTable.getRowCount() == 2, "Receiver exposed a row before Swing processed its update");
                paint(spy[0].channelTable);
            });
            SwingUtilities.invokeAndWait(() -> {
                check(spy[0].channelTable.getRowCount() == 3, "Queued receiver refresh lost channels");
                check(spy[0].channelTable.getValueAt(0, 0).equals("A"), "Channel name sorting failed");
                spy[0].channelTable.setRowSelectionInterval(1, 1); // B
                spy[0].showPopupMenu(new MouseEvent(spy[0].channelTable, MouseEvent.MOUSE_RELEASED,
                    0, 0, 20, 500, 1, true, MouseEvent.BUTTON3)); // Blank area, no popup.
            });
            receiver.messageReceived(null, "0_FIRST", new ZCMDataInputStream(new byte[8]));
            SwingUtilities.invokeAndWait(() -> check(spy[0].channelTable.getValueAt(spy[0].channelTable.getSelectedRow(), 0).equals("B"), "New channel shifted the selected channel"));

            Thread burst = new Thread(() -> {
                for (int i = 0; i < 10000; i++)
                    receiver.messageReceived(null, "BURST_" + i % 200, new ZCMDataInputStream(new byte[8]));
            });
            burst.start();
            for (int pass = 0; pass < 40; pass++) {
                final int current = pass;
                SwingUtilities.invokeAndWait(() -> {
                    if (current % 5 == 0) spy[0].clearButton.doClick(0);
                    spy[0].channelTableModel.setSortingStatus(current % 8, TableSorter.ASCENDING);
                    paint(spy[0].channelTable);
                });
            }
            burst.join(5000); check(!burst.isAlive(), "Channel burst stalled");
            SwingUtilities.invokeAndWait(() -> spy[0].clearButton.doClick(0));
            receiver.messageReceived(null, "AFTER_CLEAR", new ZCMDataInputStream(new byte[8]));
            SwingUtilities.invokeAndWait(() -> {
                check(spy[0].channelTable.getRowCount() == 1 && spy[0].channelTable.getValueAt(0, 0).equals("AFTER_CLEAR"), "Queued refresh resurrected cleared channels");
                paint(spy[0].channelTable);
            });
            if (failure.get() != null) throw new AssertionError("Background or GUI exception", failure.get());
            System.out.println("Spy channel arrival/clear GUI stress test passed");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) window.dispose();
                if (spy[0] != null) { spy[0].zcm.stop(); spy[0].zcm.close(); }
            });
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("--gui")) liveGui();
            else SwingUtilities.invokeAndWait(() -> {
                SpyAppearance.configure();
                try { snapshots(); bandwidthUnits(); } catch (Exception error) { throw new RuntimeException(error); }
            });
            System.out.println("Spy channel table tests passed"); System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
