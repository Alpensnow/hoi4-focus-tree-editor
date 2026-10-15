package hoi4focus.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Word list
public final class Lexicon {
    public enum Kind { EFFECT, TRIGGER, SCOPE, ANY, ICON }

    public record Entry(String name, Kind kind, String template, String doc) {
        public String insertText() {
            if (template != null && !template.isBlank()) return template;
            return kind == Kind.SCOPE ? name + " = {\n\t$0\n}" : name + " = $0";
        }
    }

    private static final Map<String, List<String>> VALUES = Map.of(
            "ideology", List.of("democratic", "fascism", "communism", "neutrality"),
            "ruling_party", List.of("democratic", "fascism", "communism", "neutrality"),
            "has_government", List.of("democratic", "fascism", "communism", "neutrality"));

    private final Map<String, Entry> map = new LinkedHashMap<>();
    private final Set<String> userKeys = new HashSet<>();
    private final Path userFile;

    private Lexicon(Path userFile) {
        this.userFile = userFile;
    }

    public static Lexicon load() {
        Path dir = Path.of(System.getProperty("user.home"), ".hoi4-focus-tree-editor");
        Lexicon lx = new Lexicon(dir.resolve("imported.tsv"));
        try (InputStream in = Lexicon.class.getResourceAsStream("/builtin_dictionary.tsv")) {
            if (in != null) lx.readTsv(new String(in.readAllBytes(), StandardCharsets.UTF_8), false);
        } catch (IOException ignored) {
        }
        try {
            if (Files.isRegularFile(lx.userFile)) {
                lx.readTsv(Files.readString(lx.userFile, StandardCharsets.UTF_8), true);
            }
        } catch (IOException ignored) {
        }
        return lx;
    }

    private void readTsv(String text, boolean user) {
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\\|", -1);
            if (p.length < 2) continue;
            Kind k;
            try {
                k = Kind.valueOf(p[1].trim());
            } catch (IllegalArgumentException e) {
                continue;
            }
            String template = p.length > 2 ? p[2].replace("\\n", "\n").replace("\\t", "\t") : "";
            String doc = p.length > 3 ? p[3].trim() : "";
            put(new Entry(p[0].trim(), k, template, doc), user);
        }
    }

    private boolean put(Entry e, boolean user) {
        String key = e.kind() + ":" + e.name();
        Entry old = map.get(key);
        if (old != null) {
            if (!old.doc().isEmpty() || e.doc().isEmpty()) return false;
            map.put(key, new Entry(old.name(), old.kind(), old.template(), e.doc()));
            return false;
        }
        map.put(key, e);
        if (user) userKeys.add(key);
        return true;
    }

    public int size() {
        return map.size();
    }

    public List<String> valuesFor(String key) {
        return VALUES.get(key);
    }

    public List<Entry> suggest(String prefix, Set<Kind> kinds, int max) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<Entry> starts = new ArrayList<>();
        List<Entry> contains = new ArrayList<>();
        for (Entry e : map.values()) {
            if (!kinds.contains(e.kind())) continue;
            String n = e.name().toLowerCase(Locale.ROOT);
            if (n.equals(p) && p.length() > 0) {
                starts.add(0, e);
            } else if (n.startsWith(p)) {
                starts.add(e);
            } else if (p.length() >= 3 && n.contains(p)) {
                contains.add(e);
            }
        }
        Comparator<Entry> shortFirst = Comparator.<Entry>comparingInt(e -> e.name().length()).thenComparing(Entry::name);
        starts.sort(shortFirst);
        contains.sort(Comparator.comparing(Entry::name));
        List<Entry> out = new ArrayList<>(starts);
        out.addAll(contains);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    public static EnumSet<Kind> kindsFor(ScriptEditor.Context ctx) {
        return switch (ctx) {
            case EFFECT -> EnumSet.of(Kind.EFFECT, Kind.SCOPE, Kind.ANY);
            case TRIGGER -> EnumSet.of(Kind.TRIGGER, Kind.SCOPE, Kind.ANY);
            case ANY -> EnumSet.of(Kind.EFFECT, Kind.TRIGGER, Kind.SCOPE, Kind.ANY);
        };
    }

    // Importing

    public String importPath(Path path) throws IOException {
        int[] added = new int[Kind.values().length];
        if (Files.isDirectory(path)) {
            List<Path> files;
            try (Stream<Path> s = Files.walk(path, 8)) {
                files = s.filter(Files::isRegularFile).toList();
            }
            for (Path f : files) {
                String rel = path.relativize(f).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.size(f) > 30_000_000L) continue;
                if (rel.contains("common/scripted_effects/") && name.endsWith(".txt")) {
                    scriptedKeys(read(f), Kind.EFFECT, added);
                } else if (rel.contains("common/scripted_triggers/") && name.endsWith(".txt")) {
                    scriptedKeys(read(f), Kind.TRIGGER, added);
                } else if (rel.contains("common/country_tags/") && name.endsWith(".txt")) {
                    countryTags(read(f), added);
                } else if ((name.endsWith(".md") || name.endsWith(".txt"))
                        && (name.contains("documentation") || rel.contains("documentation/"))) {
                    Kind k = docKind(name);
                    if (k != null) docEntries(read(f), k, added);
                }
            }
        } else {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            String text = read(path);
            Kind k = docKind(name);
            docEntries(text, k == null ? Kind.ANY : k, added);
        }
        save();
        int total = 0;
        for (int a : added) total += a;
        return "Added " + total + " new entries ("
                + added[Kind.EFFECT.ordinal()] + " effects, "
                + added[Kind.TRIGGER.ordinal()] + " triggers, "
                + added[Kind.SCOPE.ordinal()] + " scopes/tags, "
                + added[Kind.ANY.ordinal()] + " general).";
    }

    private static String read(Path f) throws IOException {
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    private static Kind docKind(String lowerName) {
        if (lowerName.contains("effect")) return Kind.EFFECT;
        if (lowerName.contains("trigger")) return Kind.TRIGGER;
        return null;
    }

    private void add(Kind k, String name, String doc, int[] added) {
        if (put(new Entry(name, k, "", doc), true)) added[k.ordinal()]++;
    }

    private void docEntries(String text, Kind k, int[] added) {
        for (String line : text.replace("\r", "").split("\n")) {
            String[] r = parseDocLine(line);
            if (r != null) add(k, r[0], r[1], added);
        }
    }

    // Parser

    static String[] parseDocLine(String raw) {
        String s = raw.trim();
        boolean emphasised = false;
        while (s.startsWith("#")) {
            s = s.substring(1);
            emphasised = true;
        }
        s = s.trim();
        while (!s.isEmpty() && !s.startsWith("**") && "-*+>".indexOf(s.charAt(0)) >= 0) s = s.substring(1).trim();
        if (s.startsWith("**") || s.startsWith("__") || s.startsWith("`")) {
            emphasised = true;
            s = s.replaceFirst("^(\\*\\*|__|`)", "");
        }
        Matcher m = Pattern.compile("^([a-z][a-z0-9_]*)(.*)$").matcher(s);
        if (!m.matches()) return null;
        String name = m.group(1);
        String rest = m.group(2).replaceFirst("^(\\*\\*|__|`)+", "").trim();
        if (!emphasised && name.indexOf('_') < 0) return null;
        if (!rest.isEmpty()) {
            if (":=-\u2013\u2014".indexOf(rest.charAt(0)) < 0) return null;
            rest = rest.substring(1).trim();
        }
        if (rest.matches("^[0-9{\"-].*") || rest.matches("^(yes|no)\\b.*")) rest = "";
        return new String[]{name, rest};
    }

    private void scriptedKeys(String text, Kind k, int[] added) {
        int depth = 0;
        Pattern top = Pattern.compile("^([A-Za-z0-9_.]+)\\s*=\\s*\\{.*$");
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            if (depth == 0) {
                Matcher m = top.matcher(line.trim());
                if (m.matches()) add(k, m.group(1), "Scripted " + (k == Kind.EFFECT ? "effect" : "trigger"), added);
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth = Math.max(0, depth - 1);
            }
        }
    }

    private void countryTags(String text, int[] added) {
        Pattern p = Pattern.compile("^\\s*([A-Z0-9]{3})\\s*=\\s*\"");
        for (String line : text.replace("\r", "").split("\n")) {
            Matcher m = p.matcher(line);
            if (m.find()) add(Kind.SCOPE, m.group(1), "Country tag", added);
        }
    }

    private void save() throws IOException {
        Files.createDirectories(userFile.getParent());
        StringBuilder sb = new StringBuilder("# Imported by HOI4 Focus Tree Editor\n");
        for (Map.Entry<String, Entry> e : map.entrySet()) {
            if (!userKeys.contains(e.getKey())) continue;
            Entry en = e.getValue();
            sb.append(en.name()).append('|').append(en.kind()).append('|')
                    .append(en.template() == null ? "" : en.template().replace("\n", "\\n").replace("\t", "\\t"))
                    .append('|').append(en.doc().replace("|", "/").replace("\n", " ")).append('\n');
        }
        Files.writeString(userFile, sb.toString(), StandardCharsets.UTF_8);
    }
}
