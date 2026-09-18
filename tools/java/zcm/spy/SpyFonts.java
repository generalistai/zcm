package zcm.spy;

import java.awt.Font;
import java.awt.Component;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

/** Keep desktop fonts for labels, using fixed-width digits only for numeric values. */
final class SpyFonts
{
    private SpyFonts() {}
    static Font monospace(Font font) {
        if (font == null) return new Font(Font.MONOSPACED, Font.PLAIN, 12);
        return new Font(Font.MONOSPACED, font.getStyle(), font.getSize()).deriveFont(font.getSize2D());
    }
    static DefaultTableCellRenderer numbers() {
        return new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setFont(monospace(table.getFont())); return this;
            }
        };
    }
}
