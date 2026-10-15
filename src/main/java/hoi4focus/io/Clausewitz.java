package hoi4focus.io;

import java.util.ArrayList;
import java.util.List;

// Reader for Clausewitz script.
public final class Clausewitz {
    private Clausewitz() {}

    public static final class Node {
        public String key;
        public String op;
        public String value;
        public List<Node> children;
        public int start, end;
        public int bodyStart, bodyEnd;

        public boolean isBlock() {
            return children != null;
        }

        public String text() {
            return value == null ? null : unquote(value);
        }

        public Node child(String k) {
            if (children == null) return null;
            for (Node c : children) if (k.equalsIgnoreCase(c.key)) return c;
            return null;
        }

        public List<Node> all(String k) {
            List<Node> out = new ArrayList<>();
            if (children != null) for (Node c : children) if (k.equalsIgnoreCase(c.key)) out.add(c);
            return out;
        }

        public String str(String k) {
            Node c = child(k);
            return c == null ? null : c.text();
        }
    }

    public static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"");
        }
        return s;
    }

    public static List<Node> parse(String src) {
        return new Parser(src).list(true);
    }

    private static final class Parser {
        private final String s;
        private int i;
        private String tok;
        private int tokStart;
        private int tokEnd;
        private boolean tokIsWord;

        Parser(String s) {
            this.s = s;
        }

        private boolean next() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '#') {
                    while (i < s.length() && s.charAt(i) != '\n') i++;
                } else {
                    break;
                }
            }
            if (i >= s.length()) {
                tok = null;
                return false;
            }
            tokStart = i;
            tokIsWord = false;
            char c = s.charAt(i);
            if (c == '{' || c == '}') {
                i++;
            } else if (c == '=' || c == '<' || c == '>' || c == '!' || c == '?') {
                i++;
                if (i < s.length() && s.charAt(i) == '=') i++;
            } else if (c == '"') {
                i++;
                while (i < s.length() && s.charAt(i) != '"' && s.charAt(i) != '\n') {
                    if (s.charAt(i) == '\\' && i + 1 < s.length()) i++;
                    i++;
                }
                if (i < s.length() && s.charAt(i) == '"') i++;
                tokIsWord = true;
            } else {
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (Character.isWhitespace(d) || "{}=<>!?#\"".indexOf(d) >= 0) break;
                    i++;
                }
                tokIsWord = true;
            }
            tokEnd = i;
            tok = s.substring(tokStart, tokEnd);
            return true;
        }

        private boolean isOp(String t) {
            return t != null && !tokIsWord && !t.equals("{") && !t.equals("}");
        }

        List<Node> list(boolean top) {
            List<Node> out = new ArrayList<>();
            while (next()) {
                if (tok.equals("}")) {
                    if (top) continue;
                    return out;
                }
                Node n = new Node();
                n.start = tokStart;
                if (tok.equals("{")) {
                    block(n);
                    out.add(n);
                    continue;
                }
                if (!tokIsWord) continue;
                String word = tok;
                int wordEnd = tokEnd;
                int save = i;
                if (next() && isOp(tok)) {
                    n.key = word;
                    n.op = tok;
                    int opEnd = tokEnd;
                    if (!next()) {
                        n.value = "";
                        n.end = opEnd;
                    } else if (tok.equals("{")) {
                        block(n);
                    } else {
                        n.value = tok;
                        n.end = tokEnd;
                    }
                } else {
                    i = save;
                    n.value = word;
                    n.end = wordEnd;
                }
                out.add(n);
            }
            return out;
        }

        private void block(Node n) {
            n.bodyStart = tokEnd;
            n.children = list(false);
            if (tok != null && tok.equals("}")) {
                n.bodyEnd = tokStart;
                n.end = tokEnd;
            } else {
                n.bodyEnd = s.length();
                n.end = s.length();
            }
        }
    }
}
