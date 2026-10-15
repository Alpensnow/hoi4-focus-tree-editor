package hoi4focus.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FocusProject {
    public String modName = "My Focus Mod";
    public String modVersion = "1.0";
    public String supportedVersion = "1.*";
    public String countryTag = "TAG";
    public String treeId = "my_focus_tree";
    public boolean prefixIds = true;
    public boolean defaultTree = false;
    public boolean allLanguages = false;
    public int iconWidth = 95;
    public int iconHeight = 85;
    public String lastExportDir = "";

    public final List<FocusNode> nodes = new ArrayList<>();
    public final List<Link> links = new ArrayList<>();
    public int nextUid = 1;

    public FocusNode node(int uid) {
        for (FocusNode n : nodes) if (n.uid == uid) return n;
        return null;
    }

    public FocusNode newNode(int x, int y) {
        FocusNode n = new FocusNode();
        n.uid = nextUid++;
        n.x = x;
        n.y = y;
        n.id = uniqueId("focus");
        n.name = "New Focus";
        nodes.add(n);
        return n;
    }

    public FocusNode duplicate(FocusNode src, int dx, int dy) {
        FocusNode n = src.copy();
        n.uid = nextUid++;
        n.x += dx;
        n.y += dy;
        n.id = uniqueId(src.id.isBlank() ? "focus" : src.id + "_copy");
        nodes.add(n);
        return n;
    }

    public void removeNode(int uid) {
        nodes.removeIf(n -> n.uid == uid);
        links.removeIf(l -> l.from == uid || l.to == uid);
    }

    public String uniqueId(String base) {
        Set<String> used = new HashSet<>();
        for (FocusNode n : nodes) used.add(n.id);
        int i = 1;
        String id;
        do {
            id = base + "_" + i++;
        } while (used.contains(id));
        return id;
    }

    public String fullId(FocusNode n) {
        String id = n.id.trim();
        if (!prefixIds) return id;
        String prefix = countryTag + "_";
        return id.startsWith(prefix) ? id : prefix + id;
    }

    public String modFolder() {
        String f = modName.trim().replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        return f.isEmpty() ? "focus_mod" : f;
    }

    private static boolean samePair(Link l, int a, int b) {
        return (l.from == a && l.to == b) || (l.from == b && l.to == a);
    }

    public String addLink(int from, int to, Link.Type type) {
        if (from == to) return "A focus can't be linked to itself.";
        if (node(from) == null || node(to) == null) return "Unknown focus.";

        if (type == Link.Type.EXCLUSIVE) {
            for (Link l : links) {
                if (!samePair(l, from, to)) continue;
                if (l.type == Link.Type.EXCLUSIVE) return "Those two focuses are already mutually exclusive.";
                return "A focus can't be both a prerequisite and mutually exclusive with the same focus.";
            }
            links.add(new Link(from, to, type));
            return null;
        }

        for (Link l : links) {
            if (!samePair(l, from, to)) continue;
            if (l.type == Link.Type.EXCLUSIVE) {
                return "These focuses are mutually exclusive, so one can't be a prerequisite of the other.";
            }
            if (l.from == from) {
                l.type = type;
                return null;
            }
            return "There is already a prerequisite link in the opposite direction.";
        }
        if (reaches(to, from)) return "That would create a prerequisite loop.";
        links.add(new Link(from, to, type));
        return null;
    }

    public boolean reaches(int a, int b) {
        Deque<Integer> stack = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        stack.push(a);
        while (!stack.isEmpty()) {
            int cur = stack.pop();
            if (cur == b) return true;
            if (!seen.add(cur)) continue;
            for (Link l : links) if (l.isPrerequisite() && l.from == cur) stack.push(l.to);
        }
        return false;
    }

    public boolean hasPrerequisites(int uid) {
        for (Link l : links) if (l.isPrerequisite() && l.to == uid) return true;
        return false;
    }
}
