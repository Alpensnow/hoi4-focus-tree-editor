package hoi4focus.ui;

import hoi4focus.io.Exporter;
import hoi4focus.io.FocusTreeImporter;
import hoi4focus.io.GameIcons;
import hoi4focus.io.IconUtil;
import hoi4focus.io.ProjectIO;
import hoi4focus.io.Validator;
import hoi4focus.model.FocusNode;
import hoi4focus.model.FocusProject;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.BorderFactory;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;

// UI stuff
public class MainFrame extends JFrame {
    private FocusProject project = new FocusProject();
    private Path file;
    private boolean dirty;

    private final Lexicon lex = Lexicon.load();
    private final CanvasPanel canvas = new CanvasPanel();
    private final PropertiesPanel props = new PropertiesPanel(lex);
    private final JLabel status = new JLabel(" ");
    private final Map<CanvasPanel.Tool, JToggleButton> toolButtons = new EnumMap<>(CanvasPanel.Tool.class);

    private final DefaultListModel<Validator.Issue> problemsModel = new DefaultListModel<>();
    private final JList<Validator.Issue> problemsList = new JList<>(problemsModel);
    private final JScrollPane problemsScroll = new JScrollPane(problemsList);

    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private String lastState;

    public MainFrame() {
        super("HOI4 Focus Tree Editor");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (confirmDiscard()) dispose();
            }
        });

        setJMenuBar(buildMenu());

        JPanel top = new JPanel(new BorderLayout());
        top.add(buildToolbar(), BorderLayout.CENTER);

        problemsList.setVisibleRowCount(5);
        problemsScroll.setPreferredSize(new Dimension(100, 110));
        problemsScroll.setVisible(false);
        problemsList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() < 2) return;
                Validator.Issue i = problemsList.getSelectedValue();
                if (i != null && i.focusUid() >= 0) canvas.focusOn(i.focusUid());
            }
        });

        JPanel left = new JPanel(new BorderLayout());
        left.add(canvas, BorderLayout.CENTER);
        left.add(problemsScroll, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, props);
        split.setResizeWeight(1.0);
        split.setDividerLocation(900);

        status.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);

        wireCallbacks();
        setProject(new FocusProject());
        setSize(1340, 820);
        setLocationRelativeTo(null);
    }

    // Construct

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        fileMenu.add(item("New", KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK, this::doNew));
        fileMenu.add(item("Open", KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK, this::doOpen));
        fileMenu.add(item("Save", KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK, this::doSave));
        fileMenu.add(item("Save As", KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, this::doSaveAs));
        fileMenu.addSeparator();
        fileMenu.add(item("Import HOI4 focus tree (.txt)", KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK, this::doImportTree));
        fileMenu.add(item("Export to HOI4 mod", KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK, this::doExport));
        fileMenu.addSeparator();
        fileMenu.add(item("Exit", 0, 0, () -> {
            if (confirmDiscard()) dispose();
        }));
        bar.add(fileMenu);

        JMenu editMenu = new JMenu("Edit");
        editMenu.add(item("Undo", KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK, this::undo));
        editMenu.add(item("Redo", KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK, this::redo));
        editMenu.addSeparator();
        editMenu.add(item("Select all focuses", 0, 0, canvas::selectAll));
        editMenu.add(item("Duplicate selected", 0, 0, canvas::duplicateSelection));
        editMenu.add(item("Delete selected", 0, 0, canvas::deleteSelection));
        bar.add(editMenu);

        JMenu projectMenu = new JMenu("Project");
        projectMenu.add(item("Settings", 0, 0, this::doSettings));
        projectMenu.add(item("Validate tree", KeyEvent.VK_F5, 0, this::doValidate));
        projectMenu.add(item("Preview generated focus file", 0, 0, this::doPreview));
        bar.add(projectMenu);

        JMenu toolsMenu = new JMenu("Tools");
        toolsMenu.add(item("Import autocomplete words (script docs / game folder)", 0, 0, this::doImportLexicon));
        toolsMenu.add(item("Fit view to tree", 0, 0, canvas::fitView));
        bar.add(toolsMenu);

        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(item("Controls", 0, 0, this::showHelp));
        bar.add(helpMenu);
        return bar;
    }

    private JMenuItem item(String text, int key, int mods, Runnable r) {
        JMenuItem mi = new JMenuItem(text);
        if (key != 0) mi.setAccelerator(KeyStroke.getKeyStroke(key, mods));
        mi.addActionListener((ActionEvent e) -> r.run());
        return mi;
    }

    private JToolBar buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        // Same component as the tool buttons so it looks identical, but with a plain button model
        // so it never stays pressed in.
        JToggleButton tile = new JToggleButton("+ New focus (drag onto canvas)");
        tile.setModel(new javax.swing.DefaultButtonModel());
        tile.setFocusable(false);
        tile.setToolTipText("Drag this onto the canvas to create a focus there. You can also double-click the canvas.");
        tile.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                return new StringSelection(CanvasPanel.NEW_FOCUS_TOKEN);
            }
        });
        tile.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                tile.getTransferHandler().exportAsDrag(tile, e, TransferHandler.COPY);
            }
        });
        tb.add(tile);
        tb.addSeparator();

        ButtonGroup group = new ButtonGroup();
        addTool(tb, group, CanvasPanel.Tool.SELECT, "Select / Move (1)",
                "Click and drag focuses. Drag on empty space to box-select.");
        addTool(tb, group, CanvasPanel.Tool.PREREQ_AND, "Prerequisite - all (2)",
                "Solid line. Drag from the prerequisite to the focus that needs it. ALL solid prerequisites are required.");
        addTool(tb, group, CanvasPanel.Tool.PREREQ_OR, "Prerequisite - any (3)",
                "Dotted line. Drag from the prerequisite to the focus. Only ONE of the dotted prerequisites is needed.");
        addTool(tb, group, CanvasPanel.Tool.EXCLUSIVE, "Mutually exclusive (4)",
                "Red link. Drag from one focus to the other; taking one LOCKS the other.");
        toolButtons.get(CanvasPanel.Tool.SELECT).setSelected(true);
        return tb;
    }

    private void addTool(JToolBar tb, ButtonGroup group, CanvasPanel.Tool tool, String text, String tip) {
        JToggleButton b = new JToggleButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> canvas.setTool(tool));
        group.add(b);
        toolButtons.put(tool, b);
        tb.add(b);
    }

    private void wireCallbacks() {
        canvas.onModified = () -> {
            checkpoint();
            props.refreshFromModel();
            canvas.repaint();
        };
        canvas.onSelectionChanged = this::refreshProps;
        canvas.onEditRequested = () -> SwingUtilities.invokeLater(props::focusName);
        canvas.onStatus = status::setText;
        canvas.onToolRequest = t -> {
            toolButtons.get(t).setSelected(true);
            canvas.setTool(t);
        };
        canvas.onIconDropped = this::setIconFromFile;
        canvas.onUndo = this::undo;
        canvas.onRedo = this::redo;

        props.onChanged = canvas::repaint;
        props.onCommit = this::checkpoint;
        props.onUploadIcon = this::chooseIcon;
    }

    private void refreshProps() {
        List<FocusNode> sel = canvas.selectedNodes();
        props.showNode(sel.isEmpty() ? null : sel.get(0), sel.size());
    }

    // Project State

    private void setProject(FocusProject p) {
        this.project = p;
        canvas.setProject(p);
        props.setProject(p);
        refreshProps();
        problemsModel.clear();
        problemsScroll.setVisible(false);
        undo.clear();
        redo.clear();
        lastState = ProjectIO.toJson(p);
        setDirty(false);
        canvas.fitView();
    }

    private void checkpoint() {
        String cur = ProjectIO.toJson(project);
        if (cur.equals(lastState)) return;
        undo.push(lastState);
        while (undo.size() > 100) undo.removeLast();
        redo.clear();
        lastState = cur;
        setDirty(true);
    }

    private void undo() {
        checkpoint();
        if (undo.isEmpty()) {
            status.setText("Nothing to undo.");
            return;
        }
        redo.push(lastState);
        lastState = undo.pop();
        restore(lastState);
    }

    private void redo() {
        checkpoint();
        if (redo.isEmpty()) {
            status.setText("Nothing to redo.");
            return;
        }
        undo.push(lastState);
        lastState = redo.pop();
        restore(lastState);
    }

    private void restore(String json) {
        Set<Integer> sel = canvas.selectionIds();
        project = ProjectIO.fromJson(json);
        canvas.setProject(project);
        props.setProject(project);
        canvas.setSelection(sel);
        refreshProps();
        setDirty(true);
    }

    private void setDirty(boolean d) {
        dirty = d;
        String name = file == null ? "Untitled" : file.getFileName().toString();
        setTitle((dirty ? "*" : "") + name + " - HOI4 Focus Tree Editor");
    }

    private boolean confirmDiscard() {
        checkpoint();
        if (!dirty) return true;
        int r = JOptionPane.showConfirmDialog(this, "Save changes to the current project first?", "Unsaved changes",
                JOptionPane.YES_NO_CANCEL_OPTION);
        if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return false;
        if (r == JOptionPane.YES_OPTION) return doSave();
        return true;
    }

    // File Actions

    private void doNew() {
        if (!confirmDiscard()) return;
        file = null;
        setProject(new FocusProject());
    }

    private void doOpen() {
        if (!confirmDiscard()) return;
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Focus tree projects (*.txt)", "txt"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            FocusProject p = ProjectIO.load(fc.getSelectedFile().toPath());
            file = fc.getSelectedFile().toPath();
            setProject(p);
            status.setText("Opened " + file);
        } catch (Exception ex) {
            error("Could not open that file:\n" + ex.getMessage());
        }
    }

    private boolean doSave() {
        if (file == null) return doSaveAs();
        return saveTo(file);
    }

    private boolean doSaveAs() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Focus tree projects (*.txt)", "txt"));
        fc.setSelectedFile(new File(project.treeId + ".txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".txt")) f = new File(f.getParentFile(), f.getName() + ".txt");
        return saveTo(f.toPath());
    }

    private boolean saveTo(Path p) {
        try {
            checkpoint();
            ProjectIO.save(project, p);
            file = p;
            setDirty(false);
            status.setText("Saved " + p);
            return true;
        } catch (IOException ex) {
            error("Could not save:\n" + ex.getMessage());
            return false;
        }
    }

    // Icons

    private void chooseIcon() {
        List<FocusNode> sel = canvas.selectedNodes();
        if (sel.size() != 1) return;
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Images (png, jpg, gif, bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        setIconFromFile(sel.get(0), fc.getSelectedFile());
    }

    private void setIconFromFile(FocusNode n, File f) {
        try {
            n.setIconPng(IconUtil.loadAsPng(f));
            n.vanillaIcon = "";
            props.showNode(n, 1);
            canvas.repaint();
            checkpoint();
            status.setText("Icon set. It will be exported as gfx/interface/goals/" + project.fullId(n) + ".dds");
        } catch (Exception ex) {
            error("Could not use that image:\n" + ex.getMessage());
        }
    }

    // Project Actions

    private void doSettings() {
        JTextField modName = new JTextField(project.modName, 24);
        JTextField modVersion = new JTextField(project.modVersion);
        JTextField supported = new JTextField(project.supportedVersion);
        JTextField tag = new JTextField(project.countryTag);
        JTextField tree = new JTextField(project.treeId);
        JCheckBox prefix = new JCheckBox("Prefix focus ids with the country tag (GER_my_focus)", project.prefixIds);
        JCheckBox def = new JCheckBox("Default tree (used by countries without their own tree)", project.defaultTree);
        JCheckBox langs = new JCheckBox("Write localisation for every language, not just English", project.allLanguages);
        JSpinner iw = new JSpinner(new SpinnerNumberModel(project.iconWidth, 16, 512, 1));
        JSpinner ih = new JSpinner(new SpinnerNumberModel(project.iconHeight, 16, 512, 1));

        JPanel p = new JPanel(new GridBagLayout());
        int row = 0;
        row = settingsRow(p, row, "Mod name", modName);
        row = settingsRow(p, row, "Mod version", modVersion);
        row = settingsRow(p, row, "Supported game version", supported);
        row = settingsRow(p, row, "Country tag (3 letters)", tag);
        row = settingsRow(p, row, "Focus tree id", tree);
        row = settingsRow(p, row, "Icon width (px)", iw);
        row = settingsRow(p, row, "Icon height (px)", ih);
        row = settingsRow(p, row, "", prefix);
        row = settingsRow(p, row, "", def);
        settingsRow(p, row, "", langs);

        int r = JOptionPane.showConfirmDialog(this, p, "Project settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        project.modName = modName.getText().trim();
        project.modVersion = modVersion.getText().trim();
        project.supportedVersion = supported.getText().trim();
        project.countryTag = tag.getText().trim().toUpperCase();
        project.treeId = tree.getText().trim();
        project.prefixIds = prefix.isSelected();
        project.defaultTree = def.isSelected();
        project.allLanguages = langs.isSelected();
        project.iconWidth = (Integer) iw.getValue();
        project.iconHeight = (Integer) ih.getValue();
        checkpoint();
        refreshProps();
        canvas.repaint();
    }

    private static int settingsRow(JPanel p, int row, String label, JComponent comp) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 3, 3, 8);
        p.add(new JLabel(label), c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 3);
        p.add(comp, c);
        return row + 1;
    }

    private List<Validator.Issue> runValidation() {
        checkpoint();
        List<Validator.Issue> issues = Validator.validate(project);
        problemsModel.clear();
        for (Validator.Issue i : issues) problemsModel.addElement(i);
        problemsScroll.setVisible(!issues.isEmpty());
        canvas.setFlagged(issues.stream().filter(i -> i.focusUid() >= 0)
                .map(Validator.Issue::focusUid).collect(Collectors.toSet()));
        revalidate();
        return issues;
    }

    private void doValidate() {
        List<Validator.Issue> issues = runValidation();
        status.setText(issues.isEmpty() ? "No problems found."
                : issues.size() + " problem(s) found. Double-click one in the list to jump to the focus.");
    }

    private void doPreview() {
        checkpoint();
        JTextArea area = new JTextArea(Exporter.focusTreeText(project), 30, 90);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "common/national_focus/" + project.treeId + ".txt",
                JOptionPane.PLAIN_MESSAGE);
    }

    private void doExport() {
        List<Validator.Issue> issues = runValidation();
        List<Validator.Issue> errors = issues.stream().filter(i -> i.severity() == Validator.Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            error("Fix these errors before exporting:\n\n" + errors.stream().map(Validator.Issue::toString)
                    .collect(Collectors.joining("\n")));
            return;
        }
        if (!issues.isEmpty()) {
            int r = JOptionPane.showConfirmDialog(this, "There are warnings:\n\n"
                            + issues.stream().map(Validator.Issue::toString).collect(Collectors.joining("\n"))
                            + "\n\nExport anyway?", "Warnings", JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Choose the folder to export into");
        File start = null;
        if (!project.lastExportDir.isBlank() && new File(project.lastExportDir).isDirectory()) {
            start = new File(project.lastExportDir);
        } else {
            Path guess = detectHoi4ModDir();
            if (guess != null) start = guess.toFile();
        }
        if (start != null) fc.setCurrentDirectory(start);
        fc.setApproveButtonText("Export here");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path base = fc.getSelectedFile().toPath();
        try {
            Exporter.Result res = Exporter.export(project, base);
            project.lastExportDir = base.toString();
            checkpoint();
            StringBuilder sb = new StringBuilder();
            sb.append("Exported ").append(res.files.size()).append(" files to:\n").append(base).append("\n\n");
            for (Path f : res.files) sb.append("  ").append(base.relativize(f)).append('\n');
            sb.append("\nTo play it: make sure ").append(res.launcherFile.getFileName())
                    .append(" and the \"").append(res.modFolder.getFileName())
                    .append("\" folder are both inside your HOI4 user 'mod' folder, then enable the mod in the launcher.");
            JTextArea area = new JTextArea(sb.toString(), 18, 70);
            area.setEditable(false);
            JOptionPane.showMessageDialog(this, new JScrollPane(area), "Export finished", JOptionPane.INFORMATION_MESSAGE);
            status.setText("Exported to " + base);
        } catch (Exception ex) {
            error("Export failed:\n" + ex);
        }
    }

    private static Path detectHoi4ModDir() {
        Path home = Path.of(System.getProperty("user.home"));
        String[] rel = {
                "Documents/Paradox Interactive/Hearts of Iron IV/mod",
                "OneDrive/Documents/Paradox Interactive/Hearts of Iron IV/mod",
                ".local/share/Paradox Interactive/Hearts of Iron IV/mod",
        };
        for (String r : rel) {
            Path p = home.resolve(r);
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    private void doImportTree() {
        if (!confirmDiscard()) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose a focus tree file (common/national_focus/*.txt in the game or a mod)");
        fc.setFileFilter(new FileNameExtensionFilter("HOI4 focus files (*.txt)", "txt"));
        Path game = GameIcons.detectGameDir();
        if (game != null) fc.setCurrentDirectory(game.resolve("common/national_focus").toFile());
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        List<FocusTreeImporter.Result> results;
        try {
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
            results = FocusTreeImporter.importFile(fc.getSelectedFile().toPath());
        } catch (Exception ex) {
            error("Could not import that file:\n" + ex.getMessage());
            return;
        } finally {
            setCursor(java.awt.Cursor.getDefaultCursor());
        }
        FocusTreeImporter.Result chosen = results.get(0);
        if (results.size() > 1) {
            String[] names = results.stream().map(r -> r.treeId() + " (" + r.project.nodes.size() + " focuses)")
                    .toArray(String[]::new);
            Object pick = JOptionPane.showInputDialog(this, "This file has several focus trees. Which one?",
                    "Import focus tree", JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
            if (pick == null) return;
            chosen = results.get(List.of(names).indexOf(pick));
        }
        file = null;
        setProject(chosen.project);
        String msg = "Imported " + chosen.project.nodes.size() + " focuses and " + chosen.project.links.size()
                + " links from " + fc.getSelectedFile().getName() + ".";
        status.setText(msg);
        if (!chosen.notes.isEmpty()) {
            JTextArea area = new JTextArea(msg + "\n\n- " + String.join("\n- ", chosen.notes), 10, 70);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            JOptionPane.showMessageDialog(this, new JScrollPane(area), "Import finished", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void doImportLexicon() {
        JOptionPane.showMessageDialog(this,
                "Pick either:\n"
                        + " - your HOI4 'documentation' folder (or one of its effects/triggers files), made by typing\n"
                        + "   'script_documentation' in the game's console, or\n"
                        + " - your HOI4 install folder, to import scripted effects/triggers and country tags.\n\n"
                        + "Imported names show up in the suggestion dropdowns.", "Import", JOptionPane.INFORMATION_MESSAGE);
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
            String summary = lex.importPath(fc.getSelectedFile().toPath());
            status.setText(summary + " (" + lex.size() + " total)");
            JOptionPane.showMessageDialog(this, summary, "Import finished", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            error("Import failed:\n" + ex);
        } finally {
            setCursor(java.awt.Cursor.getDefaultCursor());
        }
    }

    private void showHelp() {
        String text = """
                Figure it out yourself lol (placeholder)
                """;
        JTextArea area = new JTextArea(text, 26, 80);
        area.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "Controls", JOptionPane.PLAIN_MESSAGE);
    }

    private void error(String msg) {
        Toolkit.getDefaultToolkit().beep();
        JOptionPane.showMessageDialog(this, msg, "Problem", JOptionPane.ERROR_MESSAGE);
    }

    // Used by tests / tools
    public FocusProject project() {
        return project;
    }

    public CanvasPanel canvas() {
        return canvas;
    }

    public void loadProject(FocusProject p) {
        setProject(p);
    }
}
