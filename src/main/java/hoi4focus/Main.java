package hoi4focus;

import hoi4focus.io.ProjectIO;
import hoi4focus.ui.DarkTheme;
import hoi4focus.ui.MainFrame;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            DarkTheme.install();
            MainFrame frame = new MainFrame();
            if (args.length > 0 && Files.isRegularFile(Path.of(args[0]))) {
                try {
                    frame.loadProject(ProjectIO.load(Path.of(args[0])));
                } catch (Exception ex) {
                    System.err.println("Could not open " + args[0] + ": " + ex.getMessage());
                }
            }
            frame.setVisible(true);
        });
    }
}
