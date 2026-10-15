package hoi4focus.io;

import hoi4focus.model.FocusNode;
import hoi4focus.model.FocusProject;
import hoi4focus.model.Link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// Checks a project for things that would break (or look wrong) in game before exporting. 
public final class Validator {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern TAG = Pattern.compile("[A-Z0-9]{3}");

    private Validator() {}

    public enum Severity { ERROR, WARNING }

    public record Issue(Severity severity, String message, int focusUid) {
        @Override
        public String toString() {
            return (severity == Severity.ERROR ? "[Error] " : "[Warning] ") + message;
        }
    }

    public static List<Issue> validate(FocusProject p) {
        List<Issue> out = new ArrayList<>();

        if (!TAG.matcher(p.countryTag).matches()) {
            out.add(new Issue(Severity.ERROR, "Country tag \"" + p.countryTag + "\" must be exactly 3 capital letters/digits (e.g. GER).", -1));
        } else if (p.countryTag.equals("TAG")) {
            out.add(new Issue(Severity.WARNING, "Country tag is still the placeholder \"TAG\". Set your real tag in Project > Settings.", -1));
        }
        if (!ID.matcher(p.treeId).matches()) {
            out.add(new Issue(Severity.ERROR, "Focus tree id \"" + p.treeId + "\" may only contain letters, digits and underscores.", -1));
        }
        if (p.nodes.isEmpty()) {
            out.add(new Issue(Severity.ERROR, "The tree has no focuses.", -1));
            return out;
        }

        Map<String, FocusNode> ids = new HashMap<>();
        Map<String, FocusNode> cells = new HashMap<>();
        for (FocusNode n : p.nodes) {
            String label = "\"" + (n.name.isBlank() ? n.id : n.name) + "\"";
            if (!ID.matcher(n.id.trim()).matches()) {
                out.add(new Issue(Severity.ERROR, "Focus " + label + " has an invalid id \"" + n.id
                        + "\" (letters, digits, _ - and . only).", n.uid));
            } else {
                FocusNode other = ids.put(p.fullId(n), n);
                if (other != null) {
                    out.add(new Issue(Severity.ERROR, "Duplicate focus id \"" + p.fullId(n) + "\" (" + label + ").", n.uid));
                }
            }
            if (n.name.isBlank()) {
                out.add(new Issue(Severity.WARNING, "Focus \"" + n.id + "\" has no display name.", n.uid));
            }
            FocusNode clash = cells.put(n.x + "," + n.y, n);
            if (clash != null) {
                out.add(new Issue(Severity.WARNING, "Focuses " + label + " and \"" + clash.name
                        + "\" are on the same grid cell (" + n.x + ", " + n.y + ").", n.uid));
            }
            checkScript(out, n, label, "available", n.available);
            checkScript(out, n, label, "bypass", n.bypass);
            checkScript(out, n, label, "completion reward", n.completionReward);
            checkScript(out, n, label, "extra script", n.extraScript);
        }

        for (Link l : p.links) {
            FocusNode a = p.node(l.from);
            FocusNode b = p.node(l.to);
            if (a == null || b == null) continue;
            if (l.isPrerequisite() && b.y <= a.y) {
                out.add(new Issue(Severity.WARNING, "\"" + b.name + "\" requires \"" + a.name
                        + "\" but sits on the same row or above it, so the line will look odd in game.", b.uid));
            }
        }

        for (FocusNode n : p.nodes) {
            if (cyclic(p, n.uid)) {
                out.add(new Issue(Severity.ERROR, "\"" + n.name + "\" is part of a prerequisite loop.", n.uid));
            }
        }

        boolean anyRoot = false;
        for (FocusNode n : p.nodes) if (!p.hasPrerequisites(n.uid)) anyRoot = true;
        if (!anyRoot) {
            out.add(new Issue(Severity.ERROR, "Every focus has a prerequisite, so nothing can be started.", -1));
        }
        return out;
    }

    private static void checkScript(List<Issue> out, FocusNode n, String label, String what, String text) {
        if (!ScriptFormatter.isBalanced(text)) {
            out.add(new Issue(Severity.ERROR, "Focus " + label + ": unbalanced { } braces in the " + what + " script.", n.uid));
        }
    }

    private static boolean cyclic(FocusProject p, int uid) {
        Set<Integer> seen = new HashSet<>();
        List<Integer> stack = new ArrayList<>();
        for (Link l : p.links) if (l.isPrerequisite() && l.from == uid) stack.add(l.to);
        while (!stack.isEmpty()) {
            int cur = stack.remove(stack.size() - 1);
            if (cur == uid) return true;
            if (!seen.add(cur)) continue;
            for (Link l : p.links) if (l.isPrerequisite() && l.from == cur) stack.add(l.to);
        }
        return false;
    }
}
