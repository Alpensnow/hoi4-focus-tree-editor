package hoi4focus.io;

import java.util.ArrayList;
import java.util.List;

// Basically just cleans up the script.
public final class ScriptFormatter {
    private ScriptFormatter() {}

    public static List<String> format(String text, int baseDepth) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int depth = 0;
        boolean lastBlank = false;
        for (String raw : text.replace("\r", "").split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (!out.isEmpty() && !lastBlank) {
                    out.add("");
                    lastBlank = true;
                }
                continue;
            }
            lastBlank = false;
            String code = stripComment(line);
            int leadingClose = 0;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '}') leadingClose++;
                else if (!Character.isWhitespace(c)) break;
            }
            int d = Math.max(0, depth - leadingClose);
            out.add("\t".repeat(baseDepth + d) + line);
            depth = Math.max(0, depth + netBraces(code));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    public static boolean isBalanced(String text) {
        if (text == null) return true;
        int depth = 0;
        for (String raw : text.replace("\r", "").split("\n")) {
            String code = stripComment(raw);
            boolean inQuote = false;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '"') inQuote = !inQuote;
                else if (!inQuote && c == '{') depth++;
                else if (!inQuote && c == '}') {
                    depth--;
                    if (depth < 0) return false;
                }
            }
        }
        return depth == 0;
    }

    static String stripComment(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (c == '#' && !inQuote) return line.substring(0, i);
        }
        return line;
    }

    private static int netBraces(String code) {
        int n = 0;
        boolean inQuote = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (!inQuote && c == '{') n++;
            else if (!inQuote && c == '}') n--;
        }
        return n;
    }
}
