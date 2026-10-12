package zcm.spy;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import javax.swing.*;

/** Small vector icons follow Java's display transform, including fractional scales. */
final class SpyIcons
{
    enum Symbol {
        PAUSE, PLAY, LIVE, LINK, CURSOR_A, CURSOR_B, CLEAR_CURSORS,
        ADD, SEARCH, REFRESH, SELECT_ALL, CLEAR, CLOSE, FAVORITE, FAVORITED
    }

    private static final Color TEAL = new Color(0x168C9C);
    private static final Color GOLD = new Color(0xC48813);
    private static final EnumMap<Symbol, Icon> ICONS = new EnumMap<Symbol, Icon>(Symbol.class);

    static {
        for (Symbol symbol : Symbol.values()) ICONS.put(symbol, new SymbolIcon(symbol));
    }

    private SpyIcons() {}

    static Icon icon(Symbol symbol) { return ICONS.get(symbol); }

    static void decorate(AbstractButton button, Symbol symbol)
    {
        Icon icon = icon(symbol);
        if (button.getIcon() == icon) return;
        button.setIcon(icon);
        // Paint disabled vectors directly instead of letting Swing rasterize them
        // at 1x and enlarge the resulting bitmap on high-DPI screens.
        button.setDisabledIcon(icon);
        button.setDisabledSelectedIcon(icon);
        button.setIconTextGap(6);
    }

    static void window(Window window)
    {
        window.setIconImages(WindowImages.IMAGES);
    }

    private static final class WindowImages {
        static final List<Image> IMAGES = create();
        private static List<Image> create() {
            List<Image> images = new ArrayList<Image>();
            for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) images.add(applicationImage(size));
            return Collections.unmodifiableList(images);
        }
    }

    /** An eye enclosing a live waveform, drawn separately at every window-icon size. */
    static BufferedImage applicationImage(int size)
    {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(size / 64.0, size / 64.0);
            g.setPaint(new GradientPaint(8, 4, new Color(0x142B42), 52, 60, new Color(0x155268)));
            g.fill(new RoundRectangle2D.Double(2, 2, 60, 60, 15, 15));
            g.setColor(new Color(255, 255, 255, 35));
            g.setStroke(new BasicStroke(1));
            g.draw(new RoundRectangle2D.Double(2.5, 2.5, 59, 59, 14, 14));
            Path2D eye = new Path2D.Double();
            eye.moveTo(10, 32); eye.curveTo(20, 15, 44, 15, 54, 32);
            eye.curveTo(44, 49, 20, 49, 10, 32); eye.closePath();
            g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0xF1FBFF)); g.draw(eye);
            g.setColor(new Color(0x58E1DD));
            g.draw(path(19, 32, 25, 32, 29, 25, 35, 39, 39, 32, 45, 32));
        } finally { g.dispose(); }
        return image;
    }

    private static Path2D path(double... points)
    {
        Path2D path = new Path2D.Double(); path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        return path;
    }

    private static Path2D star()
    {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5, radius = i % 2 == 0 ? 9 : 4.2;
            double x = 12 + Math.cos(angle) * radius, y = 12 + Math.sin(angle) * radius;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.closePath(); return path;
    }

    private static final class SymbolIcon implements Icon {
        private final Symbol symbol;
        private final List<Shape> outlines = new ArrayList<Shape>();
        private final List<Shape> fills = new ArrayList<Shape>();

        SymbolIcon(Symbol symbol) {
            this.symbol = symbol;
            switch (symbol) {
                case PAUSE:
                    fills.add(new RoundRectangle2D.Double(5, 4, 5, 16, 1.8, 1.8));
                    fills.add(new RoundRectangle2D.Double(14, 4, 5, 16, 1.8, 1.8)); break;
                case PLAY:
                    Path2D play = path(7, 4, 20, 12, 7, 20); play.closePath(); fills.add(play); break;
                case LIVE:
                    outlines.add(path(2, 12, 6, 12, 9, 5, 13, 19, 16, 10, 21, 10));
                    fills.add(new Ellipse2D.Double(19, 8, 4, 4)); break;
                case LINK:
                    outlines.add(path(8, 16, 16, 8));
                    Path2D link = new Path2D.Double();
                    link.moveTo(10, 7); link.lineTo(12, 5); link.curveTo(17, 0, 24, 7, 19, 12); link.lineTo(17, 14);
                    link.moveTo(14, 17); link.lineTo(12, 19); link.curveTo(7, 24, 0, 17, 5, 12); link.lineTo(7, 10);
                    outlines.add(link); break;
                case CURSOR_A:
                case CURSOR_B:
                    outlines.add(path(12, 8, 12, 21));
                    outlines.add(path(4, 17, 20, 17));
                    Path2D marker = path(8, 3, 16, 3, 12, 8); marker.closePath(); fills.add(marker); break;
                case CLEAR_CURSORS:
                    outlines.add(path(6, 3, 6, 16, 12, 16));
                    outlines.add(path(12, 3, 12, 10));
                    outlines.add(path(3, 12, 12, 12));
                    outlines.add(path(15, 15, 21, 21)); outlines.add(path(21, 15, 15, 21)); break;
                case ADD:
                    outlines.add(new RoundRectangle2D.Double(3, 3, 18, 18, 5, 5));
                    outlines.add(path(7, 12, 17, 12)); outlines.add(path(12, 7, 12, 17)); break;
                case SEARCH:
                    outlines.add(new Ellipse2D.Double(3, 3, 12, 12)); outlines.add(path(14, 14, 21, 21)); break;
                case REFRESH:
                    outlines.add(new Arc2D.Double(4, 4, 16, 16, 35, 285, Arc2D.OPEN));
                    outlines.add(path(15, 8, 20, 8, 20, 3)); break;
                case SELECT_ALL:
                    outlines.add(new RoundRectangle2D.Double(3, 3, 18, 18, 4, 4));
                    outlines.add(path(7, 12, 10, 15, 17, 8)); break;
                case CLEAR:
                    outlines.add(path(3, 15, 13, 5, 21, 13, 13, 21, 9, 21, 3, 15));
                    outlines.add(path(7, 11, 15, 19)); outlines.add(path(13, 21, 22, 21)); break;
                case CLOSE:
                    outlines.add(path(6, 6, 18, 18)); outlines.add(path(18, 6, 6, 18)); break;
                case FAVORITE: outlines.add(star()); break;
                case FAVORITED: fills.add(star()); break;
            }
        }

        public int getIconWidth() { return 18; }
        public int getIconHeight() { return 18; }

        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D)graphics.create();
            try {
                g.translate(x, y); g.scale(getIconWidth() / 24.0, getIconHeight() / 24.0);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Color color = component == null ? new Color(0x334155) : component.getForeground();
                if (symbol == Symbol.LIVE ||
                    symbol == Symbol.LINK && component instanceof AbstractButton && ((AbstractButton)component).isSelected()) color = TEAL;
                if (symbol == Symbol.CURSOR_A) color = new Color(30, 90, 210);
                if (symbol == Symbol.CURSOR_B) color = new Color(190, 90, 0);
                if (symbol == Symbol.FAVORITED) color = GOLD;
                if (component != null && !component.isEnabled()) {
                    Color disabled = UIManager.getColor("Label.disabledForeground");
                    if (disabled != null) color = disabled;
                    g.setComposite(AlphaComposite.SrcOver.derive(.55f));
                }
                g.setColor(color);
                for (Shape shape : outlines) g.draw(shape);
                for (Shape shape : fills) g.fill(shape);
            } finally { g.dispose(); }
        }
    }
}
