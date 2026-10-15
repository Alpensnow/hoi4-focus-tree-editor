package hoi4focus.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.text.BadLocationException;

// Clausewitz autocompletion
public class ScriptEditor extends JPanel {
    public enum Context { EFFECT, TRIGGER, ANY }

    private final JTextArea area = new JTextArea(10, 30);
    private final Lexicon lex;
    private final Context context;

    private JWindow popup;
    private DefaultListModel<Lexicon.Entry> model;
    private JList<Lexicon.Entry> list;
    private JLabel hint;
    private int wordStart;
    private boolean suppress;
    private Runnable changeListener = () -> {};
    private Runnable commitListener = () -> {};

    public ScriptEditor(Lexicon lex, Context context, String help) {
        super(new BorderLayout(0, 4));
        this.lex = lex;
        this.context = context;

        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setTabSize(4);
        area.setLineWrap(false);
        area.setFocusTraversalKeysEnabled(false);

        JLabel label = new JLabel("<html>" + help + "</html>");
        add(label, BorderLayout.NORTH);
        add(new JScrollPane(area), BorderLayout.CENTER);

        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { edited(); }
            @Override public void removeUpdate(DocumentEvent e) { edited(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        area.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE && e.isControlDown()) {
                    updatePopup(true);
                    e.consume();
                    return;
                }
                if (popup == null || !popup.isVisible()) return;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> { move(1); e.consume(); }
                    case KeyEvent.VK_UP -> { move(-1); e.consume(); }
                    case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> { accept(); e.consume(); }
                    case KeyEvent.VK_ESCAPE -> { hidePopup(); e.consume(); }
                    default -> { }
                }
            }
        });
        area.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { hidePopup(); }
        });
        area.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                hidePopup();
                commitListener.run();
            }
        });
    }

    public JTextArea textArea() {
        return area;
    }

    public String getText() {
        return area.getText();
    }

    public void setText(String text) {
        suppress = true;
        try {
            area.setText(text == null ? "" : text);
            area.setCaretPosition(0);
        } finally {
            suppress = false;
        }
        hidePopup();
    }

    public void setEditable(boolean editable) {
        area.setEditable(editable);
        area.setBackground(editable ? UIManager.getColor("TextArea.background") : UIManager.getColor("Panel.background"));
    }

    public void onChange(Runnable r) {
        changeListener = r;
    }

    public void onCommit(Runnable r) {
        commitListener = r;
    }

    private void edited() {
        if (suppress) return;
        changeListener.run();
        SwingUtilities.invokeLater(() -> {
            if (area.hasFocus()) updatePopup(false);
        });
    }

    // Suggestions

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void updatePopup(boolean force) {
        String text = area.getText();
        int caret = Math.min(area.getCaretPosition(), text.length());
        int start = caret;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        String prefix = text.substring(start, caret);

        int lineStart = text.lastIndexOf('\n', Math.max(0, caret - 1));
        if (caret == 0) lineStart = -1;
        if (text.substring(lineStart + 1, caret).contains("#")) {
            hidePopup();
            return;
        }

        List<Lexicon.Entry> items = new ArrayList<>();

        int p = start - 1;
        while (p >= 0 && (text.charAt(p) == ' ' || text.charAt(p) == '\t')) p--;
        if (p >= 0 && text.charAt(p) == '=') {
            int q = p - 1;
            while (q >= 0 && (text.charAt(q) == ' ' || text.charAt(q) == '\t')) q--;
            int end = q;
            while (q >= 0 && isWordChar(text.charAt(q))) q--;
            List<String> values = lex.valuesFor(text.substring(q + 1, end + 1));
            if (values != null) {
                if (prefix.isEmpty() && !force) {
                    hidePopup();
                    return;
                }
                for (String v : values) {
                    if (v.startsWith(prefix)) items.add(new Lexicon.Entry(v, Lexicon.Kind.ANY, v, "value"));
                }
            }
        }

        if (items.isEmpty()) {
            if (prefix.isEmpty() && !force) {
                hidePopup();
                return;
            }
            items = lex.suggest(prefix, Lexicon.kindsFor(context), 14);
        }
        if (items.isEmpty()) {
            hidePopup();
            return;
        }
        showPopup(items, start);
    }

    private void ensurePopup() {
        if (popup != null) return;
        Window owner = SwingUtilities.getWindowAncestor(area);
        popup = new JWindow(owner);
        popup.setFocusableWindowState(false);
        model = new DefaultListModel<>();
        list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.setCellRenderer(new Renderer());
        list.addListSelectionListener(e -> {
            Lexicon.Entry sel = list.getSelectedValue();
            hint.setText(sel == null || sel.doc().isEmpty() ? " " : " " + sel.doc());
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) {
                    list.setSelectedIndex(i);
                    accept();
                }
            }
        });
        hint = new JLabel(" ");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        hint.setForeground(Color.LIGHT_GRAY);
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        content.add(hint, BorderLayout.SOUTH);
        popup.setContentPane(content);
    }

    private void showPopup(List<Lexicon.Entry> items, int start) {
        ensurePopup();
        wordStart = start;
        model.clear();
        for (Lexicon.Entry e : items) model.addElement(e);
        list.setSelectedIndex(0);
        try {
            Rectangle2D r = area.modelToView2D(start);
            Point pt = new Point((int) r.getX(), (int) (r.getY() + r.getHeight()));
            SwingUtilities.convertPointToScreen(pt, area);
            popup.setLocation(pt);
        } catch (BadLocationException ex) {
            return;
        }
        int rows = Math.min(items.size(), 8);
        popup.setSize(400, rows * 20 + 26);
        popup.setVisible(true);
    }

    private void hidePopup() {
        if (popup != null && popup.isVisible()) popup.setVisible(false);
    }

    private void move(int delta) {
        int n = model.size();
        if (n == 0) return;
        int i = Math.max(0, Math.min(n - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void accept() {
        Lexicon.Entry e = list.getSelectedValue();
        hidePopup();
        if (e == null) return;
        String text = area.getText();
        int caret = area.getCaretPosition();
        int ls = text.lastIndexOf('\n', Math.max(0, wordStart - 1));
        if (wordStart == 0) ls = -1;
        int i = ls + 1;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        String indent = text.substring(ls + 1, Math.min(i, wordStart));

        String ins = e.insertText().replace("\n", "\n" + indent);
        int off = ins.indexOf("$0");
        ins = ins.replace("$0", "");
        suppress = true;
        try {
            area.replaceRange(ins, wordStart, caret);
            area.setCaretPosition(wordStart + (off >= 0 ? off : ins.length()));
        } finally {
            suppress = false;
        }
        changeListener.run();
    }

    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean sel, boolean focus) {
            super.getListCellRendererComponent(l, value, index, sel, focus);
            Lexicon.Entry e = (Lexicon.Entry) value;
            String kind = switch (e.kind()) {
                case EFFECT -> "effect";
                case TRIGGER -> "trigger";
                case SCOPE -> "scope";
                case ICON -> "icon";
                case ANY -> e.doc().equals("value") ? "value" : "keyword";
            };
            String color = sel ? "#DDEEFF" : "#888888";
            setText("<html><b>" + esc(e.name()) + "</b> <span style='color:" + color + "'>&nbsp;" + kind + "</span></html>");
            return this;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
