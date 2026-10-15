package hoi4focus.io;

import hoi4focus.model.FocusNode;
import hoi4focus.model.FocusProject;
import hoi4focus.model.Link;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/*
Writes the complete HOI4 mod folder:
    &lt;base&gt;/&lt;ModFolder&gt;.mod
    &lt;base&gt;/&lt;ModFolder&gt;/descriptor.mod
    &lt;base&gt;/&lt;ModFolder&gt;/common/national_focus/&lt;tree&gt;.txt
    &lt;base&gt;/&lt;ModFolder&gt;/interface/&lt;tree&gt;_goals.gfx
    &lt;base&gt;/&lt;ModFolder&gt;/gfx/interface/goals/&lt;focus&gt;.dds
    &lt;base&gt;/&lt;ModFolder&gt;/localisation/english/&lt;tree&gt;_focus_l_english.yml
*/
public final class Exporter {
    public static final List<String> LANGUAGES = List.of(
            "english", "braz_por", "french", "german", "japanese", "polish", "russian", "simp_chinese", "spanish");

    private Exporter() {}

    public static final class Result {
        public Path modFolder;
        public Path launcherFile;
        public final List<Path> files = new ArrayList<>();
    }

    public static Result export(FocusProject p, Path baseDir) throws IOException {
        Result r = new Result();
        String folder = p.modFolder();
        Path mod = baseDir.resolve(folder);
        r.modFolder = mod;

        r.launcherFile = baseDir.resolve(folder + ".mod");
        writeText(r, r.launcherFile, descriptor(p, "mod/" + folder), false);
        writeText(r, mod.resolve("descriptor.mod"), descriptor(p, null), false);
        writeText(r, mod.resolve("common/national_focus/" + p.treeId + ".txt"), focusTreeText(p), false);
        writeText(r, mod.resolve("interface/" + p.treeId + "_goals.gfx"), gfxText(p), false);

        for (FocusNode n : p.nodes) {
            BufferedImage img = n.getIconImage();
            if (img == null) continue;
            Path dds = mod.resolve("gfx/interface/goals/" + p.fullId(n) + ".dds");
            DdsWriter.write(DdsWriter.fit(img, p.iconWidth, p.iconHeight), dds);
            r.files.add(dds);
        }

        List<String> langs = p.allLanguages ? LANGUAGES : List.of("english");
        for (String lang : langs) {
            writeText(r, mod.resolve("localisation/" + lang + "/" + p.treeId + "_focus_l_" + lang + ".yml"),
                    localisationText(p, lang), true);
        }
        return r;
    }

    private static void writeText(Result r, Path file, String text, boolean bom) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (bom) {
            byte[] withBom = new byte[body.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(body, 0, withBom, 3, body.length);
            body = withBom;
        }
        Files.write(file, body);
        r.files.add(file);
    }

    static String descriptor(FocusProject p, String launcherPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("version=\"").append(esc(p.modVersion)).append("\"\n");
        sb.append("tags={\n\t\"Gameplay\"\n}\n");
        sb.append("name=\"").append(esc(p.modName)).append("\"\n");
        sb.append("supported_version=\"").append(esc(p.supportedVersion)).append("\"\n");
        if (launcherPath != null) sb.append("path=\"").append(esc(launcherPath)).append("\"\n");
        return sb.toString();
    }

    public static String iconName(FocusProject p, FocusNode n) {
        if (n.iconPng != null) return "GFX_goal_" + p.fullId(n);
        if (n.vanillaIcon != null && !n.vanillaIcon.isBlank()) return n.vanillaIcon.trim();
        return "GFX_goal_unknown";
    }

    static String gfxText(FocusProject p) {
        StringBuilder sb = new StringBuilder("spriteTypes = {\n");
        for (FocusNode n : p.nodes) {
            if (n.iconPng == null) continue;
            String id = p.fullId(n);
            sb.append("\tspriteType = {\n");
            sb.append("\t\tname = \"GFX_goal_").append(id).append("\"\n");
            sb.append("\t\ttexturefile = \"gfx/interface/goals/").append(id).append(".dds\"\n");
            sb.append("\t}\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    static String localisationText(FocusProject p, String lang) {
        StringBuilder sb = new StringBuilder("l_").append(lang).append(":\n");
        for (FocusNode n : p.nodes) {
            String id = p.fullId(n);
            sb.append(' ').append(id).append(":0 \"").append(locEsc(n.name)).append("\"\n");
            sb.append(' ').append(id).append("_desc:0 \"").append(locEsc(n.description)).append("\"\n");
        }
        return sb.toString();
    }

    // Public so the UI can preview it.
    public static String focusTreeText(FocusProject p) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (FocusNode n : p.nodes) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
        }
        if (p.nodes.isEmpty()) {
            minX = 0;
            minY = 0;
        }

        List<FocusNode> sorted = new ArrayList<>(p.nodes);
        sorted.sort(Comparator.<FocusNode>comparingInt(n -> n.y).thenComparingInt(n -> n.x));

        StringBuilder sb = new StringBuilder();
        sb.append("focus_tree = {\n");
        sb.append("\tid = ").append(p.treeId).append("\n\n");
        sb.append("\tcountry = {\n");
        sb.append("\t\tfactor = 0\n\n");
        sb.append("\t\tmodifier = {\n");
        sb.append("\t\t\tadd = 10\n");
        sb.append("\t\t\ttag = ").append(p.countryTag).append("\n");
        sb.append("\t\t}\n");
        sb.append("\t}\n\n");
        sb.append("\tdefault = ").append(p.defaultTree ? "yes" : "no").append("\n");

        for (FocusNode n : sorted) {
            if (!p.hasPrerequisites(n.uid)) {
                sb.append("\n\tinitial_show_position = {\n");
                sb.append("\t\tfocus = ").append(p.fullId(n)).append("\n");
                sb.append("\t}\n");
                break;
            }
        }

        for (FocusNode n : sorted) {
            sb.append("\n");
            appendFocus(sb, p, n, n.x - minX, n.y - minY);
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendFocus(StringBuilder sb, FocusProject p, FocusNode n, int x, int y) {
        sb.append("\tfocus = {\n");
        line(sb, 2, "id = " + p.fullId(n));
        line(sb, 2, "icon = " + iconName(p, n));
        line(sb, 2, "x = " + x);
        line(sb, 2, "y = " + y);
        line(sb, 2, "cost = " + n.cost);

        List<FocusNode> andParents = new ArrayList<>();
        List<FocusNode> orParents = new ArrayList<>();
        List<FocusNode> exclusive = new ArrayList<>();
        for (Link l : p.links) {
            if (l.type == Link.Type.EXCLUSIVE) {
                if (l.from == n.uid) exclusive.add(p.node(l.to));
                else if (l.to == n.uid) exclusive.add(p.node(l.from));
            } else if (l.to == n.uid) {
                (l.type == Link.Type.AND ? andParents : orParents).add(p.node(l.from));
            }
        }
        Comparator<FocusNode> byPos = Comparator.<FocusNode>comparingInt(f -> f.y).thenComparingInt(f -> f.x);
        andParents.sort(byPos);
        orParents.sort(byPos);
        exclusive.sort(byPos);

        if (!andParents.isEmpty() || !orParents.isEmpty() || !exclusive.isEmpty()) sb.append("\n");
        for (FocusNode a : andParents) {
            line(sb, 2, "prerequisite = { focus = " + p.fullId(a) + " }");
        }
        if (!orParents.isEmpty()) {
            StringBuilder b = new StringBuilder("prerequisite = {");
            for (FocusNode o : orParents) b.append(" focus = ").append(p.fullId(o));
            b.append(" }");
            line(sb, 2, b.toString());
        }
        if (!exclusive.isEmpty()) {
            StringBuilder b = new StringBuilder("mutually_exclusive = {");
            for (FocusNode e : exclusive) b.append(" focus = ").append(p.fullId(e));
            b.append(" }");
            line(sb, 2, b.toString());
        }

        boolean flags = !n.cancelIfInvalid || n.continueIfInvalid || n.availableIfCapitulated || !n.searchFilters.isEmpty();
        if (flags) sb.append("\n");
        if (!n.cancelIfInvalid) line(sb, 2, "cancel_if_invalid = no");
        if (n.continueIfInvalid) line(sb, 2, "continue_if_invalid = yes");
        if (n.availableIfCapitulated) line(sb, 2, "available_if_capitulated = yes");
        if (!n.searchFilters.isEmpty()) {
            line(sb, 2, "search_filters = { " + String.join(" ", n.searchFilters) + " }");
        }

        block(sb, "available", n.available);
        block(sb, "bypass", n.bypass);
        block(sb, "completion_reward", n.completionReward);

        List<String> extra = ScriptFormatter.format(n.extraScript, 2);
        boolean ownAi = extra.stream().anyMatch(s -> s.matches("\t\tai_will_do\\s*=.*"));
        if (!ownAi) {
            sb.append("\n");
            line(sb, 2, "ai_will_do = {");
            line(sb, 3, "factor = " + num(n.aiFactor));
            line(sb, 2, "}");
        }

        if (!extra.isEmpty()) {
            sb.append("\n");
            for (String s : extra) sb.append(s).append("\n");
        }
        sb.append("\t}\n");
    }

    private static void block(StringBuilder sb, String key, String body) {
        List<String> lines = ScriptFormatter.format(body, 3);
        if (lines.isEmpty()) return;
        sb.append("\n");
        line(sb, 2, key + " = {");
        for (String s : lines) sb.append(s).append("\n");
        line(sb, 2, "}");
    }

    private static void line(StringBuilder sb, int tabs, String text) {
        sb.append("\t".repeat(tabs)).append(text).append("\n");
    }

    private static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String locEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n");
    }
}
