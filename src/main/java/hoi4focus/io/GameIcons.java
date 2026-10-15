package hoi4focus.io;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class GameIcons {
    private static final Map<String, BufferedImage> CACHE = new HashMap<>();
    private static SpriteIndex gameIndex;
    private static boolean gameIndexed;

    private GameIcons() {}

    public static final class SpriteIndex {
        public final Path root;
        private final Map<String, String> sprites = new HashMap<>();

        private SpriteIndex(Path root) {
            this.root = root;
        }

        public Path texture(String sprite) {
            String rel = sprites.get(sprite.toLowerCase(Locale.ROOT));
            return rel == null ? null : root.resolve(rel);
        }

        public boolean isEmpty() {
            return sprites.isEmpty();
        }
    }

    public static SpriteIndex index(Path root) {
        SpriteIndex idx = new SpriteIndex(root);
        Path dir = root.resolve("interface");
        if (!Files.isDirectory(dir)) return idx;
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir, 3)) {
            files = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gfx")).toList();
        } catch (IOException e) {
            return idx;
        }
        for (Path f : files) {
            try {
                String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                collect(Clausewitz.parse(text), idx.sprites);
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return idx;
    }

    private static void collect(List<Clausewitz.Node> nodes, Map<String, String> out) {
        for (Clausewitz.Node n : nodes) {
            if (!n.isBlock()) continue;
            String name = n.str("name");
            String tex = n.str("texturefile");
            if (name != null && tex != null) {
                out.putIfAbsent(name.toLowerCase(Locale.ROOT), tex.replace('\\', '/'));
            } else {
                collect(n.children, out);
            }
        }
    }

    public static BufferedImage load(SpriteIndex idx, String sprite) {
        if (idx == null || sprite == null || sprite.isBlank()) return null;
        Path tex = idx.texture(sprite.trim());
        if (tex == null || !Files.isRegularFile(tex)) return null;
        try {
            return DdsReader.read(tex);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static synchronized BufferedImage preview(String sprite) {
        if (sprite == null || sprite.isBlank()) return null;
        String key = sprite.trim();
        if (CACHE.containsKey(key)) return CACHE.get(key);
        if (!gameIndexed) {
            gameIndexed = true;
            Path game = detectGameDir();
            if (game != null) gameIndex = index(game);
        }
        BufferedImage img = load(gameIndex, key);
        CACHE.put(key, img);
        return img;
    }

    public static synchronized SpriteIndex gameIndex() {
        preview("GFX_goal_unknown");
        return gameIndex;
    }

    public static boolean isGameDir(Path p) {
        return p != null && (Files.isRegularFile(p.resolve("hoi4.exe")) || Files.isRegularFile(p.resolve("hoi4"))
                || Files.isRegularFile(p.resolve("launcher-settings.json")) && Files.isDirectory(p.resolve("common/national_focus")));
    }

    public static Path detectGameDir() {
        String home = System.getProperty("user.home");
        List<Path> steamRoots = new ArrayList<>(List.of(
                Path.of("C:/Program Files (x86)/Steam"),
                Path.of("C:/Program Files/Steam"),
                Path.of(home, ".steam/steam"),
                Path.of(home, ".local/share/Steam"),
                Path.of(home, "Library/Application Support/Steam")));
        List<Path> libraries = new ArrayList<>(steamRoots);
        Pattern pathLine = Pattern.compile("\"path\"\\s*\"([^\"]+)\"");
        for (Path root : steamRoots) {
            Path vdf = root.resolve("steamapps/libraryfolders.vdf");
            if (!Files.isRegularFile(vdf)) continue;
            try {
                Matcher m = pathLine.matcher(Files.readString(vdf, StandardCharsets.UTF_8));
                while (m.find()) libraries.add(Path.of(m.group(1).replace("\\\\", "\\")));
            } catch (IOException | RuntimeException ignored) {
            }
        }
        for (Path lib : libraries) {
            Path game = lib.resolve("steamapps/common/Hearts of Iron IV");
            if (Files.isDirectory(game.resolve("common/national_focus"))) return game;
        }
        return null;
    }
}
