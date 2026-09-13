package zcm.spy;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Optional display-backed smoke test of the picker, toolbar, and legend. */
public class ChartWorkspaceGuiTest
{
    private static final ChartData data = new ChartData(0);
    private static ZoomableChartScrollWheel chart;
    private static SignalPicker picker;
    private static ObjectPanel inspector;
    private static JTable results;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static <T extends Component> T component(Container parent, Class<T> type, String text) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child) && (text == null || child instanceof AbstractButton && text.equals(((AbstractButton)child).getText())))
                return type.cast(child);
            if (child instanceof Container) {
                T found = component((Container)child, type, text); if (found != null) return found;
            }
        }
        return null;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10000000000L;
        boolean[] ready = {false};
        do {
            SwingUtilities.invokeAndWait(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("GUI condition timed out");
    }
    private static void search(String query) throws Exception {
        SwingUtilities.invokeAndWait(() -> component(picker, JTextField.class, null).setText(query));
        await(() -> results.getRowCount() == 1 && results.getValueAt(0, 3).equals(query));
    }
    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                ChannelData channel = new ChannelData(); channel.name = "SYNTHETIC_GUI_TEST";
                channel.last = new ChartWorkspaceTest.Message();
                inspector = new ObjectPanel(channel.name, data); inspector.setObject(channel.last, 0);
                data.signalSource = new SignalCatalog.Source() {
                    public List<ChannelData> channels() { return Collections.singletonList(channel); }
                    public ObjectPanel inspector(ChannelData ignored) { return inspector; }
                };
                StreamingTrace trace = new StreamingTrace(15000, "Synthetic chart GUI test");
                trace.setTracePainter(new PixelTracePainter(trace)); trace.setStroke(new BasicStroke(0));
                data.startTrace(trace, () -> {});
                for (int i = 0; i < 15000; i++) trace.record(i / 1000.0, Math.sin(i / 50.0));
                ZoomableChartScrollWheel.newChartFrame(data, trace); chart = data.getCharts().getFirst(); data.flush();
                chart.setPaused(true);
                component(chart.controls(), JButton.class, "Add signals…").doClick();
                picker = (SignalPicker)chart.getClientProperty("signalPicker");
                results = component(picker, JTable.class, null);
            });
            await(() -> results.getRowCount() == 2000);
            search("samples[99999]");
            SwingUtilities.invokeAndWait(() -> { results.setValueAt(true, 0, 0); results.setValueAt(true, 0, 1); });
            search("matrix[0][1]");
            SwingUtilities.invokeAndWait(() -> {
                results.setValueAt(true, 0, 0); component(picker, JButton.class, "Add selected").doClick();
                check(chart.getTraces().size() == 3, "Multi-selection did not survive search");
                component(picker, JCheckBox.class, "Favorites only").doClick();
                component(picker, JTextField.class, null).setText("");
            });
            await(() -> results.getRowCount() == 1 && results.getValueAt(0, 3).equals("samples[99999]"));
            SwingUtilities.invokeAndWait(() -> {
                check(Boolean.TRUE.equals(results.getValueAt(0, 1)), "Favorite checkbox not retained");
                component(picker, JButton.class, "Close").doClick();
                chart.pinCursor(1, 11); chart.pinCursor(2, 13); chart.setCursorTime(12);
                JTable legend = component(chart.controls().legendPanel(), JTable.class, null);
                check(legend.getRowCount() == 3 && legend.getTableHeader().isShowing(), "Legend/header missing");
                legend.setValueAt("New axis", 0, 8); legend.setValueAt(true, 0, 1);
                component(chart.controls(), JToggleButton.class, "Resume").doClick();
                check(!chart.isPaused(), "Resume toolbar failed");
                component(chart.controls(), JButton.class, "Set A").doClick();
                check(chart.getArmedCursor() == 1, "Cursor toolbar failed");
                component(chart.controls(), JButton.class, "Clear cursors").doClick();
                component(chart.controls(), JButton.class, "Live").doClick();
                check(chart.isFollowing(), "Live toolbar failed");
                if (args.length > 0) {
                    JFrame frame = (JFrame)SwingUtilities.getWindowAncestor(chart);
                    BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics(); frame.paint(g); g.dispose();
                    try { ImageIO.write(image, "png", new File(args[0])); } catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            System.out.println("Spy chart GUI smoke test passed");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) window.dispose();
                if (inspector != null) inspector.sparklineRenderer.destroy();
            });
        }
        System.exit(0);
    }
}
