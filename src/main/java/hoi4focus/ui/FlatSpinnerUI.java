package hoi4focus.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicSpinnerUI;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;

// Metal's spinner arrows are drawn a pixel or two off-centre; these are drawn dead centre.
public final class FlatSpinnerUI extends BasicSpinnerUI {
    public static ComponentUI createUI(JComponent c) {
        return new FlatSpinnerUI();
    }

    @Override
    protected Component createNextButton() {
        JButton b = new ArrowButton(SwingConstants.NORTH);
        b.setName("Spinner.nextButton");
        installNextButtonListeners(b);
        return b;
    }

    @Override
    protected Component createPreviousButton() {
        JButton b = new ArrowButton(SwingConstants.SOUTH);
        b.setName("Spinner.previousButton");
        installPreviousButtonListeners(b);
        return b;
    }

    private static final class ArrowButton extends JButton {
        private final int direction;

        ArrowButton(int direction) {
            this.direction = direction;
            setFocusable(false);
            setRequestFocusEnabled(false);
            setBorder(null);
            setContentAreaFilled(false);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(16, 9);
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight();
            Color bg = UIManager.getColor("Button.background");
            if (bg == null) bg = new Color(0x3C3F41);
            if (getModel().isPressed()) bg = bg.darker();
            else if (getModel().isRollover()) bg = bg.brighter();
            g.setColor(bg);
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(0x646464));
            g.drawRect(0, 0, w - 1, h - 1);

            // Odd base width so the apex lands on a whole pixel.
            int aw = 7, ah = 4;
            int x0 = (w - aw) / 2;
            int y0 = (h - ah) / 2;
            g.setColor(isEnabled() ? new Color(0xDDDDDD) : new Color(0x777777));
            for (int i = 0; i < ah; i++) {
                int row = direction == SwingConstants.NORTH ? y0 + i : y0 + ah - 1 - i;
                g.drawLine(x0 + ah - 1 - i, row, x0 + aw - ah + i, row);
            }
        }
    }
}
