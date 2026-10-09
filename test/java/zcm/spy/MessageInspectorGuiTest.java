package zcm.spy;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real desktop theme, expanded array navigation, value dialog, and plot actions without JNI. */
public class MessageInspectorGuiTest
{
    public static class Robot {
        public long timestamp = 1726224345123456L;
        public String status = "Tracking · camera connected";
        public double[] velocity = {1.25, -0.375, 2.5};
        public MessageInspectorTest.Embedded pose = new MessageInspectorTest.Embedded();
        public MessageInspectorTest.Embedded[] objects = {new MessageInspectorTest.Embedded(), new MessageInspectorTest.Embedded()};
        public boolean enabled = true;
    }
    private static <T extends Component> T find(Container parent, Class<T> type, String text) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child) && (text == null || child instanceof AbstractButton && text.equals(((AbstractButton)child).getText()))) return type.cast(child);
            if (child instanceof Container) { T found = find((Container)child, type, text); if (found != null) return found; }
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        ObjectPanel[] panel = new ObjectPanel[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                SpyAppearance.configure();
                panel[0] = new ObjectPanel("ROBOT_STATE", new ChartData(0));
                Robot message = new Robot(); panel[0].setObject(message, 0);
                JFrame frame = new JFrame("ROBOT_STATE — Message inspector"); SpyIcons.window(frame);
                frame.add(panel[0]); frame.setSize(1100, 750); frame.setVisible(true);
                MessageInspectorTest.paint(panel[0]);
                MessageInspectorTest.check(panel[0].table.getTableHeader().isShowing(), "Table header missing");
                MessageInspectorTest.check(find(panel[0], JTree.class, null) == null && find(panel[0], JButton.class, "Up") == null,
                    "Inspector still shows the removed tree or Up button");
                long last = panel[0].values.layout.indexOf(MessageInspectorTest.path(message, "objects[1].position.z"));
                MessageInspectorTest.check(last > 0, "Embedded array is not expanded in main view");
                if (args.length > 0) {
                    BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics(); frame.paint(g); g.dispose();
                    try { ImageIO.write(image, "png", new File(args[0])); } catch (Exception e) { throw new RuntimeException(e); }
                }
                int status = (int)panel[0].values.layout.indexOf(MessageInspectorTest.path(message, "status"));
                panel[0].table.setRowSelectionInterval(status, status); find(panel[0], JButton.class, "View value").doClick();
                boolean dialog = false;
                for (Window window : Window.getWindows()) if (window instanceof JDialog) {
                    JTextArea text = find(window, JTextArea.class, null);
                    if (text != null) { dialog = true; MessageInspectorTest.check(text.getText().equals(message.status), "Full value dialog missing text"); window.dispose(); }
                }
                MessageInspectorTest.check(dialog, "View value did not open a dialog");
                panel[0].scrollToPath(MessageInspectorTest.path(message, "objects"));
                panel[0].scrollToIndex(1);
                MessageInspectorTest.check(panel[0].values.row(panel[0].table.getSelectedRow()).path.name.equals("objects[1]"), "Object array jump failed");
                MessageInspectorTest.check(panel[0].values.rows == 29, "Array navigation hid message fields");
                int enabled = (int)panel[0].values.layout.indexOf(MessageInspectorTest.path(message, "enabled"));
                panel[0].table.setRowSelectionInterval(enabled, enabled); find(panel[0], JButton.class, "New chart").doClick();
                MessageInspectorTest.check(panel[0].selectedSignals.containsKey("enabled"), "Boolean plot action failed");
                frame.setSize(720, 600); MessageInspectorTest.layout(frame);
                for (boolean array : new boolean[] {false, true}) {
                    if (array) panel[0].scrollToPath(MessageInspectorTest.path(message, "objects"));
                    MessageInspectorTest.layout(frame);
                    for (String name : new String[] {"Plot", "New chart", "New Y axis", "Copy value", "View value", "Go"}) {
                        JButton button = find(panel[0], JButton.class, name);
                        if (!button.isShowing()) continue;
                        MessageInspectorTest.check(button.getY() + button.getHeight() <= button.getParent().getHeight() &&
                            button.getX() + button.getWidth() <= button.getParent().getWidth(), "Toolbar clips " + name);
                    }
                }
                System.out.println("Spy inspector GUI smoke test passed (" + UIManager.getLookAndFeel().getName() + ")");
                if (args.length > 1 && args[1].equals("--benchmark")) RenderPerformanceTest.inspector(true);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) window.dispose(); if (panel[0] != null) panel[0].dispose(); });
        }
        System.exit(0);
    }
}
