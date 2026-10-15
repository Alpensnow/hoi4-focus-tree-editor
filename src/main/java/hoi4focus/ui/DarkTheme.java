package hoi4focus.ui;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.Color;

// I love dark mode.
public final class DarkTheme extends DefaultMetalTheme {
    private static final ColorUIResource BG = new ColorUIResource(0x2B2B2B);
    private static final ColorUIResource BG_LIGHT = new ColorUIResource(0x3C3F41);
    private static final ColorUIResource BG_DARK = new ColorUIResource(0x1E1E1E);
    private static final ColorUIResource FG = new ColorUIResource(0xDDDDDD);
    private static final ColorUIResource BORDER = new ColorUIResource(0x646464);
    private static final ColorUIResource SELECTION = new ColorUIResource(0x3D6A99);

    public static void install() {
        try {
            MetalLookAndFeel.setCurrentTheme(new DarkTheme());
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception ignored) {
        }
        Color text = FG;
        for (String key : new String[]{"TextField", "TextArea", "TextPane", "EditorPane", "FormattedTextField",
                "PasswordField", "List", "Table", "Tree", "ComboBox", "Spinner"}) {
            UIManager.put(key + ".background", BG_DARK);
            UIManager.put(key + ".foreground", text);
            UIManager.put(key + ".caretForeground", text);
            UIManager.put(key + ".selectionBackground", SELECTION);
            UIManager.put(key + ".selectionForeground", Color.WHITE);
            UIManager.put(key + ".inactiveForeground", new Color(0x888888));
        }
        UIManager.put("Spinner.background", BG_DARK);
        UIManager.put("ToolTip.background", BG_LIGHT);
        UIManager.put("ToolTip.foreground", FG);
        UIManager.put("OptionPane.background", BG);
        UIManager.put("OptionPane.messageForeground", FG);
        UIManager.put("Panel.background", BG);
        UIManager.put("Label.foreground", FG);
        UIManager.put("Label.disabledForeground", new Color(0x888888));
        UIManager.put("CheckBox.foreground", FG);
        UIManager.put("CheckBox.background", BG);
        UIManager.put("ToggleButton.select", SELECTION);
        UIManager.put("TabbedPane.selected", BG_LIGHT);
        UIManager.put("TabbedPane.contentAreaColor", BG);
        UIManager.put("SplitPane.background", BG);
        UIManager.put("ScrollPane.background", BG);
        UIManager.put("Separator.foreground", BORDER);
        UIManager.put("Separator.background", BG);
        UIManager.put("SpinnerUI", FlatSpinnerUI.class.getName());
        UIManager.put("TabbedPaneUI", FlatTabbedPaneUI.class.getName());
    }

    @Override public String getName() { return "Dark"; }

    @Override protected ColorUIResource getPrimary1() { return BORDER; }
    @Override protected ColorUIResource getPrimary2() { return SELECTION; }
    @Override protected ColorUIResource getPrimary3() { return SELECTION; }
    @Override protected ColorUIResource getSecondary1() { return BORDER; }
    @Override protected ColorUIResource getSecondary2() { return new ColorUIResource(0x555555); }
    @Override protected ColorUIResource getSecondary3() { return BG; }

    @Override public ColorUIResource getControlTextColor() { return FG; }
    @Override public ColorUIResource getSystemTextColor() { return FG; }
    @Override public ColorUIResource getUserTextColor() { return FG; }
    @Override public ColorUIResource getMenuForeground() { return FG; }
    @Override public ColorUIResource getInactiveControlTextColor() { return new ColorUIResource(0x888888); }
    @Override public ColorUIResource getMenuDisabledForeground() { return new ColorUIResource(0x888888); }
    @Override public ColorUIResource getWindowBackground() { return BG_DARK; }
    @Override public ColorUIResource getMenuBackground() { return BG_LIGHT; }
    @Override public ColorUIResource getControl() { return BG_LIGHT; }
    @Override public ColorUIResource getPrimaryControl() { return BG_LIGHT; }
    @Override public ColorUIResource getControlShadow() { return BORDER; }
    @Override public ColorUIResource getControlDarkShadow() { return new ColorUIResource(0x1A1A1A); }
    @Override public ColorUIResource getControlHighlight() { return new ColorUIResource(0x555555); }
    @Override public ColorUIResource getSeparatorBackground() { return BG; }
    @Override public ColorUIResource getSeparatorForeground() { return BORDER; }
    @Override public ColorUIResource getFocusColor() { return new ColorUIResource(0x6A9AC9); }
}
