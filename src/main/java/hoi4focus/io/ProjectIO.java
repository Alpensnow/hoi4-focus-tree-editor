package hoi4focus.io;

import hoi4focus.model.FocusNode;
import hoi4focus.model.FocusProject;
import hoi4focus.model.Link;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Saves and loads projects
public final class ProjectIO {
    private ProjectIO() {}

    public static void save(FocusProject p, Path file) throws IOException {
        Files.writeString(file, toJson(p), StandardCharsets.UTF_8);
    }

    public static FocusProject load(Path file) throws IOException {
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static String toJson(FocusProject p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", 1);
        m.put("modName", p.modName);
        m.put("modVersion", p.modVersion);
        m.put("supportedVersion", p.supportedVersion);
        m.put("countryTag", p.countryTag);
        m.put("treeId", p.treeId);
        m.put("prefixIds", p.prefixIds);
        m.put("defaultTree", p.defaultTree);
        m.put("allLanguages", p.allLanguages);
        m.put("iconWidth", p.iconWidth);
        m.put("iconHeight", p.iconHeight);
        m.put("lastExportDir", p.lastExportDir);
        m.put("nextUid", p.nextUid);

        List<Object> nodes = new ArrayList<>();
        for (FocusNode n : p.nodes) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("uid", n.uid);
            o.put("id", n.id);
            o.put("name", n.name);
            o.put("description", n.description);
            o.put("x", n.x);
            o.put("y", n.y);
            o.put("cost", n.cost);
            o.put("icon", n.iconPng == null ? null : Base64.getEncoder().encodeToString(n.iconPng));
            o.put("vanillaIcon", n.vanillaIcon);
            o.put("available", n.available);
            o.put("bypass", n.bypass);
            o.put("completionReward", n.completionReward);
            o.put("extraScript", n.extraScript);
            o.put("aiFactor", n.aiFactor);
            o.put("cancelIfInvalid", n.cancelIfInvalid);
            o.put("continueIfInvalid", n.continueIfInvalid);
            o.put("availableIfCapitulated", n.availableIfCapitulated);
            o.put("searchFilters", new ArrayList<Object>(n.searchFilters));
            nodes.add(o);
        }
        m.put("nodes", nodes);

        List<Object> links = new ArrayList<>();
        for (Link l : p.links) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("from", l.from);
            o.put("to", l.to);
            o.put("type", l.type.name());
            links.add(o);
        }
        m.put("links", links);
        return Json.write(m);
    }

    @SuppressWarnings("unchecked")
    public static FocusProject fromJson(String text) {
        Object root = Json.parse(text);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Not a focus tree project file.");
        Map<String, Object> m = (Map<String, Object>) root;

        FocusProject p = new FocusProject();
        p.modName = str(m, "modName", p.modName);
        p.modVersion = str(m, "modVersion", p.modVersion);
        p.supportedVersion = str(m, "supportedVersion", p.supportedVersion);
        p.countryTag = str(m, "countryTag", p.countryTag);
        p.treeId = str(m, "treeId", p.treeId);
        p.prefixIds = bool(m, "prefixIds", p.prefixIds);
        p.defaultTree = bool(m, "defaultTree", p.defaultTree);
        p.allLanguages = bool(m, "allLanguages", p.allLanguages);
        p.iconWidth = num(m, "iconWidth", p.iconWidth);
        p.iconHeight = num(m, "iconHeight", p.iconHeight);
        p.lastExportDir = str(m, "lastExportDir", "");
        p.nextUid = num(m, "nextUid", 1);

        Object nodes = m.get("nodes");
        if (nodes instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> nm = (Map<String, Object>) o;
                FocusNode n = new FocusNode();
                n.uid = nm.get("uid") instanceof Number u ? u.intValue() : p.nextUid++;
                n.id = str(nm, "id", "focus");
                n.name = str(nm, "name", "");
                n.description = str(nm, "description", "");
                n.x = num(nm, "x", 0);
                n.y = num(nm, "y", 0);
                n.cost = num(nm, "cost", 10);
                Object icon = nm.get("icon");
                if (icon instanceof String s && !s.isEmpty()) n.setIconPng(Base64.getDecoder().decode(s));
                n.vanillaIcon = str(nm, "vanillaIcon", "");
                n.available = str(nm, "available", "");
                n.bypass = str(nm, "bypass", "");
                n.completionReward = str(nm, "completionReward", "");
                n.extraScript = str(nm, "extraScript", "");
                Object ai = nm.get("aiFactor");
                n.aiFactor = ai instanceof Number num ? num.doubleValue() : 1;
                n.cancelIfInvalid = bool(nm, "cancelIfInvalid", true);
                n.continueIfInvalid = bool(nm, "continueIfInvalid", false);
                n.availableIfCapitulated = bool(nm, "availableIfCapitulated", false);
                Object f = nm.get("searchFilters");
                if (f instanceof List<?> fl) for (Object s : fl) n.searchFilters.add(String.valueOf(s));
                p.nodes.add(n);
                p.nextUid = Math.max(p.nextUid, n.uid + 1);
            }
        }
        Object links = m.get("links");
        if (links instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> lm = (Map<String, Object>) o;
                Link.Type t;
                try {
                    t = Link.Type.valueOf(str(lm, "type", "AND"));
                } catch (IllegalArgumentException e) {
                    t = Link.Type.AND;
                }
                int from = num(lm, "from", -1);
                int to = num(lm, "to", -1);
                if (p.node(from) != null && p.node(to) != null) p.links.add(new Link(from, to, t));
            }
        }
        return p;
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v instanceof String s ? s : def;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean b ? b : def;
    }

    private static int num(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : def;
    }
}
