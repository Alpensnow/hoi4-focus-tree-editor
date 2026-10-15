package hoi4focus.io;

import hoi4focus.model.FocusNode;
import hoi4focus.model.FocusProject;
import hoi4focus.model.Link;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// TSPMO
public final class FocusTreeImporter {
    private static final Set<String> HANDLED = Set.of(
            "id", "text", "icon", "x", "y", "relative_position_id", "cost", "prerequisite", "mutually_exclusive",
            "available", "bypass", "completion_reward", "cancel_if_invalid", "continue_if_invalid",
            "available_if_capitulated", "search_filters");
    private static final Pattern LOC_LINE = Pattern.compile("^\\s*([^\\s:#\"]+):\\d*\\s*\"(.*)\"");

    private FocusTreeImporter() {}

    public static final class Result {
        public final FocusProject project;
        public final List<String> notes = new ArrayList<>();

        Result(FocusProject project) {
            this.project = project;
        }

        public String treeId() {
            return project.treeId;
        }
    }

    private static final class Raw {
        String id;
        String locKey;
        String icon;
        int x;
        int y;
        String relativeTo;
        final List<List<String>> prereqs = new ArrayList<>();
        final List<String> exclusive = new ArrayList<>();
        FocusNode out;
    }

    public static List<Result> importFile(Path file) throws IOException {
        String src = read(file);
        List<Clausewitz.Node> top = Clausewitz.parse(src);

        Map<String, String> constants = new HashMap<>();
        Map<String, Clausewitz.Node> shared = new LinkedHashMap<>();
        List<Clausewitz.Node> trees = new ArrayList<>();
        for (Clausewitz.Node n : top) {
            if (n.key == null) continue;
            if (n.key.startsWith("@") && !n.isBlock()) {
                constants.put(n.key, n.text());
            } else if (n.isBlock() && (n.key.equalsIgnoreCase("shared_focus") || n.key.equalsIgnoreCase("joint_focus"))) {
                String id = n.str("id");
                if (id != null) shared.put(id, n);
            } else if (n.isBlock() && n.key.equalsIgnoreCase("focus_tree")) {
                trees.add(n);
            }
        }
        if (trees.isEmpty() && shared.isEmpty()) {
            throw new IOException("No focus_tree or shared_focus found in " + file.getFileName()
                    + ".\nPick a file from common/national_focus.");
        }

        Path root = modRoot(file);
        Context ctx = new Context(src, constants, root);

        boolean needShared = false;
        for (Clausewitz.Node t : trees) {
            for (Clausewitz.Node c : t.children) {
                if (c.isBlock() || c.key == null || c.text() == null) continue;
                if ((c.key.equalsIgnoreCase("shared_focus") || c.key.equalsIgnoreCase("joint_focus"))
                        && !shared.containsKey(c.text())) needShared = true;
            }
        }
        if (needShared) loadSiblingShared(file, shared, constants, ctx);

        List<Result> results = new ArrayList<>();
        if (trees.isEmpty()) {
            String name = file.getFileName().toString().replaceFirst("\\.txt$", "");
            results.add(build(ctx, null, name, new ArrayList<>(shared.values())));
        }
        for (Clausewitz.Node t : trees) {
            String id = t.str("id");
            List<Clausewitz.Node> focuses = new ArrayList<>(t.all("focus"));
            List<String> missingShared = new ArrayList<>();
            Set<String> included = new HashSet<>();
            for (Clausewitz.Node c : t.children) {
                if (c.isBlock() || c.key == null) continue;
                if (!c.key.equalsIgnoreCase("shared_focus") && !c.key.equalsIgnoreCase("joint_focus")) continue;
                Clausewitz.Node sf = shared.get(c.text());
                if (sf == null) missingShared.add(c.text());
                else if (included.add(c.text())) focuses.add(sf);
            }
            boolean grew = !included.isEmpty();
            while (grew) {
                grew = false;
                for (Map.Entry<String, Clausewitz.Node> e : shared.entrySet()) {
                    if (included.contains(e.getKey())) continue;
                    for (Clausewitz.Node p : e.getValue().all("prerequisite")) {
                        if (!p.isBlock()) continue;
                        boolean hit = false;
                        for (Clausewitz.Node f : p.all("focus")) if (included.contains(f.text())) hit = true;
                        if (hit) {
                            included.add(e.getKey());
                            focuses.add(e.getValue());
                            grew = true;
                            break;
                        }
                    }
                }
            }
            Result r = build(ctx, t, id == null ? "imported_tree" : id, focuses);
            if (!missingShared.isEmpty()) {
                r.notes.add("Shared focuses that couldn't be found in this folder were left out: "
                        + String.join(", ", missingShared) + ".");
            }
            results.add(r);
        }
        return results;
    }

    private static void loadSiblingShared(Path file, Map<String, Clausewitz.Node> shared, Map<String, String> constants,
                                          Context ctx) {
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null) return;
        List<Path> siblings;
        try (Stream<Path> s = Files.list(dir)) {
            siblings = s.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .filter(f -> !f.toAbsolutePath().equals(file.toAbsolutePath())).sorted().toList();
        } catch (IOException e) {
            return;
        }
        for (Path f : siblings) {
            try {
                String text = read(f);
                if (!text.contains("shared_focus") && !text.contains("joint_focus")) continue;
                for (Clausewitz.Node n : Clausewitz.parse(text)) {
                    if (n.key == null) continue;
                    if (n.key.startsWith("@") && !n.isBlock()) {
                        constants.putIfAbsent(n.key, n.text());
                    } else if (n.isBlock() && (n.key.equalsIgnoreCase("shared_focus") || n.key.equalsIgnoreCase("joint_focus"))) {
                        String id = n.str("id");
                        if (id != null && !shared.containsKey(id)) {
                            shared.put(id, n);
                            ctx.otherSources.put(n, text);
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static final class Context {
        final String src;
        final Map<String, String> constants;
        final Path root;
        final Map<Clausewitz.Node, String> otherSources = new IdentityHashMap<>();
        GameIcons.SpriteIndex modSprites;
        boolean modSpritesLoaded;

        Context(String src, Map<String, String> constants, Path root) {
            this.src = src;
            this.constants = constants;
            this.root = root;
        }

        GameIcons.SpriteIndex modSprites() {
            if (!modSpritesLoaded) {
                modSpritesLoaded = true;
                if (root != null && !GameIcons.isGameDir(root)) modSprites = GameIcons.index(root);
            }
            return modSprites;
        }

        double num(String v, double def) {
            if (v == null) return def;
            String s = v.trim();
            if (s.startsWith("@")) s = constants.getOrDefault(s, "");
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return def;
            }
        }

        String srcOf(Clausewitz.Node focus) {
            return otherSources.getOrDefault(focus, src);
        }

        String body(Clausewitz.Node focus, String key) {
            Clausewitz.Node block = focus.child(key);
            if (block == null || !block.isBlock()) return "";
            return String.join("\n", ScriptFormatter.format(srcOf(focus).substring(block.bodyStart, block.bodyEnd), 0));
        }

        String raw(Clausewitz.Node focus, Clausewitz.Node n) {
            return srcOf(focus).substring(n.start, n.end);
        }
    }

    private static Result build(Context ctx, Clausewitz.Node tree, String treeId, List<Clausewitz.Node> focusNodes) {
        FocusProject p = new FocusProject();
        Result r = new Result(p);
        p.treeId = treeId;
        p.prefixIds = false;
        if (tree != null) {
            String tag = findTag(tree.child("country"));
            if (tag != null) p.countryTag = tag;
            p.defaultTree = "yes".equalsIgnoreCase(tree.str("default"));
        }
        p.modName = modName(ctx.root, treeId);

        Map<String, Raw> byId = new LinkedHashMap<>();
        int extraKept = 0;
        int mergedOr = 0;
        for (Clausewitz.Node fn : focusNodes) {
            Raw raw = new Raw();
            raw.id = fn.str("id");
            if (raw.id == null || raw.id.isBlank()) continue;
            if (byId.containsKey(raw.id)) {
                r.notes.add("Focus " + raw.id + " is defined twice; only the first one was kept.");
                continue;
            }
            raw.locKey = fn.str("text") != null ? fn.str("text") : raw.id;
            raw.icon = iconName(fn.child("icon"));
            raw.x = (int) Math.round(ctx.num(fn.str("x"), 0));
            raw.y = (int) Math.round(ctx.num(fn.str("y"), 0));
            raw.relativeTo = fn.str("relative_position_id");
            Set<Clausewitz.Node> bakedOffsets = new HashSet<>();
            for (Clausewitz.Node off : fn.all("offset")) {
                if (!off.isBlock() || !onlyTag(off.child("trigger"), p.countryTag)) continue;
                raw.x += (int) Math.round(ctx.num(off.str("x"), 0));
                raw.y += (int) Math.round(ctx.num(off.str("y"), 0));
                bakedOffsets.add(off);
            }
            for (Clausewitz.Node pr : fn.all("prerequisite")) {
                if (!pr.isBlock()) continue;
                List<String> ids = new ArrayList<>();
                for (Clausewitz.Node f : pr.all("focus")) if (f.text() != null) ids.add(f.text());
                if (!ids.isEmpty()) raw.prereqs.add(ids);
            }
            for (Clausewitz.Node me : fn.all("mutually_exclusive")) {
                if (!me.isBlock()) continue;
                for (Clausewitz.Node f : me.all("focus")) if (f.text() != null) raw.exclusive.add(f.text());
            }

            FocusNode n = p.newNode(raw.x, raw.y);
            n.id = raw.id;
            n.cost = (int) Math.max(0, Math.round(ctx.num(fn.str("cost"), 10)));
            n.available = ctx.body(fn, "available");
            n.bypass = ctx.body(fn, "bypass");
            n.completionReward = ctx.body(fn, "completion_reward");
            String cancel = fn.str("cancel_if_invalid");
            n.cancelIfInvalid = cancel == null || cancel.equalsIgnoreCase("yes");
            n.continueIfInvalid = "yes".equalsIgnoreCase(fn.str("continue_if_invalid"));
            n.availableIfCapitulated = "yes".equalsIgnoreCase(fn.str("available_if_capitulated"));
            Clausewitz.Node filters = fn.child("search_filters");
            if (filters != null && filters.isBlock()) {
                for (Clausewitz.Node f : filters.children) {
                    if (f.key == null && f.value != null && !n.searchFilters.contains(f.text())) n.searchFilters.add(f.text());
                }
            }

            List<String> extra = new ArrayList<>();
            for (Clausewitz.Node c : fn.children) {
                if (c.key != null && HANDLED.contains(c.key.toLowerCase(Locale.ROOT))) continue;
                if (bakedOffsets.contains(c)) continue;
                if (c.key != null && c.key.equalsIgnoreCase("ai_will_do") && simpleFactor(ctx, c, n)) continue;
                extra.add(ctx.raw(fn, c));
            }
            if (!extra.isEmpty()) {
                n.extraScript = String.join("\n", ScriptFormatter.format(String.join("\n", extra), 0));
                extraKept++;
            }
            if (raw.prereqs.stream().filter(l -> l.size() > 1).count() > 1) mergedOr++;
            raw.out = n;
            byId.put(raw.id, raw);
        }

        Map<String, int[]> abs = new HashMap<>();
        for (Raw raw : byId.values()) {
            int[] pos = absolute(raw, byId, abs, new HashSet<>());
            raw.out.x = pos[0];
            raw.out.y = pos[1];
        }

        Set<String> missing = new java.util.TreeSet<>();
        int refused = 0;
        for (Raw raw : byId.values()) {
            for (List<String> group : raw.prereqs) {
                Link.Type type = group.size() == 1 ? Link.Type.AND : Link.Type.OR;
                for (String id : group) {
                    Raw from = byId.get(id);
                    if (from == null) {
                        missing.add(id);
                        continue;
                    }
                    if (p.addLink(from.out.uid, raw.out.uid, type) != null) refused++;
                }
            }
        }
        for (Raw raw : byId.values()) {
            for (String id : raw.exclusive) {
                Raw other = byId.get(id);
                if (other == null) {
                    missing.add(id);
                    continue;
                }
                if (alreadyExclusive(p, raw.out.uid, other.out.uid)) continue;
                if (p.addLink(raw.out.uid, other.out.uid, Link.Type.EXCLUSIVE) != null) refused++;
            }
        }

        localise(ctx.root, byId, r);
        icons(ctx, p, byId, r);

        if (extraKept > 0) {
            r.notes.add(extraKept + " focus(es) have settings the editor has no field for (e.g. offset, allow_branch,"
                    + " will_lead_to_war_with); they were kept as-is in the Extra tab.");
        }
        if (mergedOr > 0) {
            r.notes.add(mergedOr + " focus(es) had more than one \"any of\" prerequisite group; the editor supports"
                    + " one, so those groups were merged.");
        }
        if (!missing.isEmpty()) {
            r.notes.add("Links to focuses not in this tree were dropped: " + String.join(", ", missing));
        }
        if (refused > 0) {
            r.notes.add(refused + " link(s) were skipped because they contradicted another link (loop or both"
                    + " prerequisite and mutually exclusive).");
        }
        return r;
    }

    private static boolean simpleFactor(Context ctx, Clausewitz.Node aiWillDo, FocusNode n) {
        if (!aiWillDo.isBlock() || aiWillDo.children.size() != 1) return false;
        Clausewitz.Node c = aiWillDo.children.get(0);
        if (c.key == null || c.isBlock() || !(c.key.equalsIgnoreCase("factor") || c.key.equalsIgnoreCase("base"))) return false;
        double f = ctx.num(c.value, Double.NaN);
        if (Double.isNaN(f)) return false;
        n.aiFactor = Math.max(0, f);
        return true;
    }

    private static boolean onlyTag(Clausewitz.Node trigger, String tag) {
        if (trigger == null || !trigger.isBlock() || trigger.children.size() != 1) return false;
        Clausewitz.Node c = trigger.children.get(0);
        return c.key != null && c.key.equalsIgnoreCase("tag") && !c.isBlock() && tag.equalsIgnoreCase(c.text());
    }

    private static boolean alreadyExclusive(FocusProject p, int a, int b) {
        for (Link l : p.links) {
            if (l.type == Link.Type.EXCLUSIVE && ((l.from == a && l.to == b) || (l.from == b && l.to == a))) return true;
        }
        return false;
    }

    private static int[] absolute(Raw raw, Map<String, Raw> byId, Map<String, int[]> memo, Set<String> visiting) {
        int[] known = memo.get(raw.id);
        if (known != null) return known;
        int[] pos = {raw.x, raw.y};
        Raw parent = raw.relativeTo == null ? null : byId.get(raw.relativeTo);
        if (parent != null && visiting.add(raw.id)) {
            int[] pp = absolute(parent, byId, memo, visiting);
            pos = new int[]{pp[0] + raw.x, pp[1] + raw.y};
        }
        memo.put(raw.id, pos);
        return pos;
    }

    private static String iconName(Clausewitz.Node icon) {
        if (icon == null) return null;
        if (!icon.isBlock()) return icon.text();
        String first = null;
        for (Clausewitz.Node c : icon.children) {
            if (c.key == null) continue;
            if (c.key.equalsIgnoreCase("value") && !c.isBlock()) return c.text();
            if (first == null) first = c.key;
            if (!c.isBlock() && "yes".equalsIgnoreCase(c.text())) return c.key;
        }
        return first;
    }

    private static String findTag(Clausewitz.Node block) {
        if (block == null || !block.isBlock()) return null;
        for (Clausewitz.Node c : block.children) {
            if (c.key != null && c.key.equalsIgnoreCase("tag") && !c.isBlock()) {
                String t = c.text().toUpperCase(Locale.ROOT);
                if (t.matches("[A-Z0-9]{3}")) return t;
            }
            String inner = findTag(c);
            if (inner != null) return inner;
        }
        return null;
    }

    // Localisation

    private static void localise(Path root, Map<String, Raw> byId, Result r) {
        Set<String> wanted = new HashSet<>();
        for (Raw raw : byId.values()) {
            wanted.add(raw.locKey);
            wanted.add(raw.locKey + "_desc");
        }
        Map<String, String> loc = new HashMap<>();
        lookup(root, wanted, loc);
        Pattern ref = Pattern.compile("\\$([A-Za-z0-9_.\\-]+)\\$");
        for (int depth = 0; depth < 3; depth++) {
            Set<String> refs = new HashSet<>();
            for (String v : loc.values()) {
                Matcher m = ref.matcher(v);
                while (m.find()) if (!loc.containsKey(m.group(1))) refs.add(m.group(1));
            }
            if (refs.isEmpty()) break;
            lookup(root, refs, loc);
        }
        int unnamed = 0;
        for (Raw raw : byId.values()) {
            String name = resolveRefs(loc.get(raw.locKey), loc, ref);
            if (name == null || name.isBlank()) unnamed++;
            raw.out.name = name == null || name.isBlank() ? raw.id : name;
            raw.out.description = resolveRefs(loc.getOrDefault(raw.locKey + "_desc", ""), loc, ref);
        }
        if (unnamed > 0) {
            r.notes.add(unnamed + " focus(es) had no English localisation, so their id is used as the name.");
        }
    }

    private static void lookup(Path root, Set<String> wanted, Map<String, String> loc) {
        if (root != null) readLocalisation(root.resolve("localisation"), wanted, loc);
        if (loc.keySet().containsAll(wanted)) return;
        Path game = GameIcons.detectGameDir();
        if (game != null && !game.equals(root)) readLocalisation(game.resolve("localisation"), wanted, loc);
    }

    private static String resolveRefs(String text, Map<String, String> loc, Pattern ref) {
        if (text == null) return null;
        for (int depth = 0; depth < 3 && text.indexOf('$') >= 0; depth++) {
            Matcher m = ref.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = loc.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
            }
            m.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

    private static void readLocalisation(Path dir, Set<String> wanted, Map<String, String> out) {
        if (!Files.isDirectory(dir)) return;
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir, 4)) {
            files = s.filter(f -> {
                String n = f.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                return n.endsWith(".yml") && n.contains("english");
            }).toList();
        } catch (IOException e) {
            return;
        }
        for (Path f : files) {
            String text;
            try {
                text = read(f);
            } catch (IOException e) {
                continue;
            }
            for (String line : text.split("\n")) {
                Matcher m = LOC_LINE.matcher(line);
                if (!m.find()) continue;
                String key = m.group(1);
                if (!wanted.contains(key) || out.containsKey(key)) continue;
                out.put(key, m.group(2).replace("\\\"", "\"").replace("\\n", "\n"));
            }
        }
    }

    // Icons

    private static void icons(Context ctx, FocusProject p, Map<String, Raw> byId, Result r) {
        int embedded = 0;
        int game = 0;
        int unknown = 0;
        GameIcons.SpriteIndex mod = ctx.modSprites();
        GameIcons.SpriteIndex vanilla = GameIcons.gameIndex();
        for (Raw raw : byId.values()) {
            if (raw.icon == null || raw.icon.isBlank()) continue;
            BufferedImage img = GameIcons.load(mod, raw.icon);
            if (img != null) {
                try {
                    raw.out.setIconPng(png(img));
                    embedded++;
                    continue;
                } catch (IOException ignored) {
                }
            }
            raw.out.vanillaIcon = raw.icon;
            if (vanilla != null && vanilla.texture(raw.icon) != null) game++;
            else unknown++;
        }
        if (unknown > 0) {
            r.notes.add(unknown + " icon(s) couldn't be found in the mod or the game and are kept by sprite name only.");
        }
        if (embedded > 0 && game > 0) {
            r.notes.add(game + " icon(s) are vanilla game icons and are referenced by name.");
        }
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    // Files

    private static Path modRoot(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("national_focus")) {
            Path common = dir.getParent();
            if (common != null && common.getFileName() != null && common.getFileName().toString().equalsIgnoreCase("common")) {
                return common.getParent();
            }
        }
        return null;
    }

    private static String modName(Path root, String treeId) {
        if (root != null && !GameIcons.isGameDir(root)) {
            Path desc = root.resolve("descriptor.mod");
            try {
                if (Files.isRegularFile(desc)) {
                    Matcher m = Pattern.compile("(?m)^\\s*name\\s*=\\s*\"([^\"]*)\"").matcher(read(desc));
                    if (m.find() && !m.group(1).isBlank()) return m.group(1);
                }
            } catch (IOException ignored) {
            }
        }
        return treeId;
    }

    private static String read(Path f) throws IOException {
        String s = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }
}
