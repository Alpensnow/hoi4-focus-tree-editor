package hoi4focus.ui;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.metal.MetalTabbedPaneUI;
import java.awt.Graphics;

// Plain rectangular tabs instead of Metal's clipped top-left corner.
public final class FlatTabbedPaneUI extends MetalTabbedPaneUI {
    public static ComponentUI createUI(JComponent c) {
        return new FlatTabbedPaneUI();
    }

    @Override
    protected void paintTabBackground(Graphics g, int placement, int tabIndex,
                                      int x, int y, int w, int h, boolean isSelected) {
        g.setColor(isSelected ? selectColor : tabPane.getBackgroundAt(tabIndex));
        g.fillRect(x, y, w, h);
    }

    @Override
    protected void paintTabBorder(Graphics g, int placement, int tabIndex,
                                  int x, int y, int w, int h, boolean isSelected) {
        g.setColor(darkShadow);
        int r = x + w - 1, b = y + h - 1;
        g.drawLine(x, y, r, y);
        g.drawLine(x, y, x, b);
        g.drawLine(r, y, r, b);
    }
}
