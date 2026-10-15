package hoi4focus.ui;

import hoi4focus.io.Exporter;
import hoi4focus.io.GameIcons;
import hoi4focus.model.FocusNode;
import hoi4focus.model.FocusProject;
import hoi4focus.model.Link;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// Right hand editor thing
public class PropertiesPanel extends JPanel {
    private static final String[][] FILTERS = {
            {"Political", "FOCUS_FILTER_POLITICAL"},
            {"Research", "FOCUS_FILTER_RESEARCH"},
            {"Industry", "FOCUS_FILTER_INDUSTRY"},
            {"Stability", "FOCUS_FILTER_STABILITY"},
            {"War support", "FOCUS_FILTER_WAR_SUPPORT"},
            {"Manpower", "FOCUS_FILTER_MANPOWER"},
            {"Annexation", "FOCUS_FILTER_ANNEXATION"},
    };

    private FocusProject project = new FocusProject();
    private FocusNode node;
    private boolean loading;

    private final JLabel header = new JLabel("No focus selected", SwingConstants.LEFT);
    private final JTextField idField = new JTextField();
    private final JTextField nameField = new JTextField();
    private final JTextArea descArea = new JTextArea(3, 20);
    private final JSpinner xSpin = new JSpinner(new SpinnerNumberModel(0, -999, 999, 1));
    private final JSpinner ySpin = new JSpinner(new SpinnerNumberModel(0, -999, 999, 1));
    private final JTextField costField = new JTextField();
    private final JSpinner aiSpin = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 9999.0, 0.5));
    private final JLabel iconPreview = new JLabel("no icon", SwingConstants.CENTER);
    private final JButton uploadBtn = new JButton("Upload image");
    private final JButton clearIconBtn = new JButton("Clear");
    private final JCheckBox cancelBox = new JCheckBox("cancel_if_invalid (default yes)");
    private final JCheckBox continueBox = new JCheckBox("continue_if_invalid");
    private final JCheckBox capitulatedBox = new JCheckBox("available_if_capitulated");
    private final Map<String, JCheckBox> filterBoxes = new LinkedHashMap<>();
    private final JTextArea relations = new JTextArea(4, 20);
    private JScrollPane descScroll;
    private final ScriptEditor availableEd;
    private final ScriptEditor bypassEd;
    private final ScriptEditor rewardEd;
    private final ScriptEditor extraEd;

    private final JPanel content = new JPanel(new BorderLayout());
    private final JLabel placeholder = new JLabel("", SwingConstants.CENTER);

    public Runnable onChanged = () -> { };
    public Runnable onCommit = () -> { };
    public Runnable onUploadIcon = () -> { };

    public PropertiesPanel(Lexicon lex) {
        super(new BorderLayout());
        availableEd = new ScriptEditor(lex, ScriptEditor.Context.TRIGGER,
                "<b>available</b> - conditions that must be true before the focus can be started (triggers).");
        bypassEd = new ScriptEditor(lex, ScriptEditor.Context.TRIGGER,
                "<b>bypass</b> - if these conditions become true the focus is skipped for free (triggers).");
        rewardEd = new ScriptEditor(lex, ScriptEditor.Context.EFFECT,
                "<b>completion_reward</b> - what happens when the focus finishes (effects). Type to get suggestions.");
        extraEd = new ScriptEditor(lex, ScriptEditor.Context.ANY,
                "<b>extra</b> - anything else placed inside the focus block (select_effect, will_lead_to_war_with, ...).");

        setPreferredSize(new Dimension(430, 600));
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Color.GRAY));

        header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));
        header.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", new JScrollPane(buildGeneral()));
        tabs.addTab("Available", pad(availableEd));
        tabs.addTab("Bypass", pad(bypassEd));
        tabs.addTab("Reward", pad(rewardEd));
        tabs.addTab("Extra", pad(extraEd));
        content.add(tabs, BorderLayout.CENTER);

                add(content, BorderLayout.CENTER);

        wire();
        setProject(project);
        showNode(null, 0);
    }

    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel(LayoutManager lm) {
            super(lm);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return 64; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        // Stretch to fill the viewport when shorter than it, so the viewport's own (lighter) background never shows.
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof javax.swing.JViewport vp && vp.getHeight() > getPreferredSize().height;
        }
    }

    private static JComponent pad(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildGeneral() {
        JPanel p = new WidthTrackingPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
        int row = 0;
        row = addRow(p, row, "Focus name", nameField);
        row = addRow(p, row, "Focus id", idField);
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);
        descScroll = new JScrollPane(descArea);
        // Same border style as the single-line text fields (the default scroll pane border looks different).
        descScroll.setBorder(new javax.swing.plaf.metal.MetalBorders.Flush3DBorder());
        descScroll.setPreferredSize(new Dimension(100, 72));
        descScroll.setMinimumSize(new Dimension(100, 72));
        row = addRow(p, row, "Description", descScroll);

        JPanel pos = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        pos.add(new JLabel("x"));
        pos.add(xSpin);
        pos.add(new JLabel("y"));
        pos.add(ySpin);
        row = addRow(p, row, "Position", pos);
        row = addRow(p, row, "Cost (weeks)", costField);

        JPanel iconRow = new JPanel(new BorderLayout(8, 0));
        iconPreview.setPreferredSize(new Dimension(95, 85));
        iconPreview.setBorder(BorderFactory.createLineBorder(new Color(0x9AA3AD)));
        iconPreview.setOpaque(true);
        iconPreview.setBackground(new Color(0x2B3138));
        iconPreview.setForeground(new Color(0xA0AAB5));
        iconRow.add(iconPreview, BorderLayout.WEST);
        JPanel btns = new JPanel();
        btns.setLayout(new BoxLayout(btns, BoxLayout.Y_AXIS));
        uploadBtn.setAlignmentX(0f);
        clearIconBtn.setAlignmentX(0f);
        btns.add(uploadBtn);
        btns.add(Box.createVerticalStrut(4));
        btns.add(clearIconBtn);
        iconRow.add(btns, BorderLayout.CENTER);
        row = addRow(p, row, "Icon", iconRow);

        JPanel flags = new JPanel();
        flags.setLayout(new BoxLayout(flags, BoxLayout.Y_AXIS));
        flags.add(cancelBox);
        flags.add(continueBox);
        flags.add(capitulatedBox);
        row = addRow(p, row, "Behaviour", flags);

        JPanel filters = new JPanel(new java.awt.GridLayout(0, 2));
        for (String[] f : FILTERS) {
            JCheckBox cb = new JCheckBox(f[0]);
            cb.setToolTipText(f[1]);
            filterBoxes.put(f[1], cb);
            filters.add(cb);
        }
        row = addRow(p, row, "Search filters", filters);

        JPanel ai = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ai.add(aiSpin);
        ai.add(new JLabel("ai_will_do factor (higher = AI prefers it)"));
        row = addRow(p, row, "AI weight", ai);

        relations.setEditable(false);
        relations.setLineWrap(true);
        relations.setWrapStyleWord(true);
        relations.setBorder(BorderFactory.createCompoundBorder(
                new javax.swing.plaf.metal.MetalBorders.Flush3DBorder(),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        relations.setFont(relations.getFont().deriveFont(12f));
        row = addRow(p, row, "Links", relations);

        GridBagConstraints fill = new GridBagConstraints();
        fill.gridx = 0;
        fill.gridy = row;
        fill.weighty = 1;
        p.add(Box.createVerticalGlue(), fill);
        return p;
    }

    private static int addRow(JPanel p, int row, String label, JComponent comp) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(5, 0, 5, 8);
        JLabel l = new JLabel(label);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        p.add(l, c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(5, 0, 5, 0);
        p.add(comp, c);
        return row + 1;
    }

    // Wires

    private void wire() {
        text(nameField, s -> node.name = s);
        text(idField, s -> node.id = s.trim());
        text(descArea, s -> node.description = s);
        spin(xSpin, v -> node.x = v.intValue());
        spin(ySpin, v -> node.y = v.intValue());
        // Whole weeks only, up to 4 digits.
        ((javax.swing.text.AbstractDocument) costField.getDocument()).setDocumentFilter(new javax.swing.text.DocumentFilter() {
            @Override
            public void replace(FilterBypass fb, int off, int len, String str, javax.swing.text.AttributeSet a)
                    throws javax.swing.text.BadLocationException {
                String cur = fb.getDocument().getText(0, fb.getDocument().getLength());
                String next = cur.substring(0, off) + (str == null ? "" : str) + cur.substring(off + len);
                if (next.matches("[0-9]{0,4}")) super.replace(fb, off, len, str, a);
            }

            @Override
            public void insertString(FilterBypass fb, int off, String str, javax.swing.text.AttributeSet a)
                    throws javax.swing.text.BadLocationException {
                replace(fb, off, 0, str, a);
            }
        });
        text(costField, v -> {
            if (!v.isEmpty()) node.cost = Integer.parseInt(v);
        });
        costField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                if (node != null && costField.getText().isEmpty()) costField.setText(String.valueOf(node.cost));
            }
        });
        spin(aiSpin, v -> node.aiFactor = v.doubleValue());
        check(cancelBox, b -> node.cancelIfInvalid = b);
        check(continueBox, b -> node.continueIfInvalid = b);
        check(capitulatedBox, b -> node.availableIfCapitulated = b);
        for (Map.Entry<String, JCheckBox> e : filterBoxes.entrySet()) {
            check(e.getValue(), b -> {
                node.searchFilters.remove(e.getKey());
                if (b) node.searchFilters.add(e.getKey());
            });
        }
        script(availableEd, s -> node.available = s);
        script(bypassEd, s -> node.bypass = s);
        script(rewardEd, s -> node.completionReward = s);
        script(extraEd, s -> node.extraScript = s);

        uploadBtn.addActionListener(e -> {
            if (node != null) onUploadIcon.run();
        });
        clearIconBtn.addActionListener(e -> {
            if (node == null) return;
            node.setIconPng(null);
            node.vanillaIcon = "";
            refreshIcon();
            onChanged.run();
            onCommit.run();
        });
    }

    private void text(JTextComponent c, Consumer<String> setter) {
        c.getDocument().addDocumentListener(new DocumentListener() {
            private void fire() {
                if (loading || node == null) return;
                setter.accept(c.getText());
                onChanged.run();
            }

            @Override public void insertUpdate(DocumentEvent e) { fire(); }
            @Override public void removeUpdate(DocumentEvent e) { fire(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        c.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { onCommit.run(); }
        });
    }

    private void spin(JSpinner s, Consumer<Number> setter) {
        s.addChangeListener(e -> {
            if (loading || node == null) return;
            setter.accept((Number) s.getValue());
            onChanged.run();
            onCommit.run();
        });
    }

    private void check(JCheckBox c, Consumer<Boolean> setter) {
        c.addActionListener(e -> {
            if (loading || node == null) return;
            setter.accept(c.isSelected());
            onChanged.run();
            onCommit.run();
        });
    }

    private void script(ScriptEditor ed, Consumer<String> setter) {
        ed.onChange(() -> {
            if (loading || node == null) return;
            setter.accept(ed.getText());
            onChanged.run();
        });
        ed.onCommit(() -> onCommit.run());
    }

    // Public API

    public void setProject(FocusProject p) {
        this.project = p;
    }

    public void showNode(FocusNode n, int selectionSize) {
        loading = true;
        try {
            this.node = selectionSize == 1 ? n : null;
            boolean on = node != null;
            for (JComponent c : List.of(idField, nameField, descArea, descScroll, xSpin, ySpin, costField, aiSpin, uploadBtn,
                    clearIconBtn, cancelBox, continueBox, capitulatedBox)) {
                c.setEnabled(on);
            }
            for (JCheckBox cb : filterBoxes.values()) cb.setEnabled(on);
            availableEd.setEditable(on);
            bypassEd.setEditable(on);
            rewardEd.setEditable(on);
            extraEd.setEditable(on);

            if (!on) {
                header.setText(selectionSize > 1 ? selectionSize + " focuses selected" : "No focus selected");
                idField.setText("");
                nameField.setText("");
                descArea.setText("");
                costField.setText("");
                relations.setText(selectionSize > 1
                        ? "Select a single focus to edit it. Drag to move all selected focuses together; Delete removes them."
                        : "Click a focus to see its links.");
                availableEd.setText("");
                bypassEd.setText("");
                rewardEd.setText("");
                extraEd.setText("");
                iconPreview.setIcon(null);
                iconPreview.setText("no icon");
                for (JCheckBox cb : filterBoxes.values()) cb.setSelected(false);
                return;
            }
            header.setText("Focus: " + (node.name.isBlank() ? node.id : node.name));
            idField.setText(node.id);
            nameField.setText(node.name);
            descArea.setText(node.description);
            xSpin.setValue(node.x);
            ySpin.setValue(node.y);
            costField.setText(String.valueOf(node.cost));
            aiSpin.setValue(node.aiFactor);
            cancelBox.setSelected(node.cancelIfInvalid);
            continueBox.setSelected(node.continueIfInvalid);
            capitulatedBox.setSelected(node.availableIfCapitulated);
            for (Map.Entry<String, JCheckBox> e : filterBoxes.entrySet()) {
                e.getValue().setSelected(node.searchFilters.contains(e.getKey()));
            }
            availableEd.setText(node.available);
            bypassEd.setText(node.bypass);
            rewardEd.setText(node.completionReward);
            extraEd.setText(node.extraScript);
            refreshIcon();
            refreshRelations();
        } finally {
            loading = false;
        }
    }

    public void focusName() {
        if (node == null) return;
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    public void refreshFromModel() {
        if (node == null) return;
        loading = true;
        try {
            xSpin.setValue(node.x);
            ySpin.setValue(node.y);
            header.setText("Focus: " + (node.name.isBlank() ? node.id : node.name));
            refreshRelations();
        } finally {
            loading = false;
        }
    }

    private void refreshIcon() {
        BufferedImage img = node == null ? null : node.getIconImage();
        boolean gameIcon = img == null && node != null && (img = GameIcons.preview(node.vanillaIcon)) != null;
        if (img != null) {
            int w = project.iconWidth;
            int h = project.iconHeight;
            double s = Math.min(93.0 / img.getWidth(), 83.0 / img.getHeight());
            Image scaled = img.getScaledInstance(Math.max(1, (int) (img.getWidth() * s)),
                    Math.max(1, (int) (img.getHeight() * s)), Image.SCALE_SMOOTH);
            iconPreview.setIcon(new ImageIcon(scaled));
            iconPreview.setText("");
            iconPreview.setToolTipText(gameIcon ? "Game icon " + node.vanillaIcon
                    : "Exported as a " + w + " x " + h + " px .dds");
        } else {
            iconPreview.setIcon(null);
            iconPreview.setText(node != null && node.vanillaIcon != null && !node.vanillaIcon.isBlank()
                    ? "<html><center>game icon</center></html>" : "no icon");
            iconPreview.setToolTipText(node != null && node.vanillaIcon != null && !node.vanillaIcon.isBlank()
                    ? node.vanillaIcon : null);
        }
    }

    private void refreshRelations() {
        if (node == null) return;
        List<String> and = new ArrayList<>();
        List<String> or = new ArrayList<>();
        List<String> excl = new ArrayList<>();
        List<String> leadsTo = new ArrayList<>();
        for (Link l : project.links) {
            FocusNode a = project.node(l.from);
            FocusNode b = project.node(l.to);
            if (a == null || b == null) continue;
            if (l.type == Link.Type.EXCLUSIVE) {
                if (a == node) excl.add(label(b));
                else if (b == node) excl.add(label(a));
            } else if (b == node) {
                (l.type == Link.Type.AND ? and : or).add(label(a));
            } else if (a == node) {
                leadsTo.add(label(b));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Requires (all): ").append(and.isEmpty() ? "-" : String.join(", ", and)).append('\n');
        sb.append("Requires (any one of): ").append(or.isEmpty() ? "-" : String.join(", ", or)).append('\n');
        sb.append("Mutually exclusive with: ").append(excl.isEmpty() ? "-" : String.join(", ", excl)).append('\n');
        sb.append("Leads to: ").append(leadsTo.isEmpty() ? "-" : String.join(", ", leadsTo)).append('\n');
        sb.append("Exported id: ").append(project.fullId(node));
        relations.setText(sb.toString());
    }

    private static String label(FocusNode n) {
        return n.name.isBlank() ? n.id : n.name;
    }

    public void iconChanged() {
        loading = true;
        try {
            refreshIcon();
        } finally {
            loading = false;
        }
    }

    static String describeIconTarget(FocusProject p, FocusNode n) {
        return Exporter.iconName(p, n);
    }
}
