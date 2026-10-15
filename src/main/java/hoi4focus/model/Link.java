package hoi4focus.model;

// Connection between focuses.
public class Link {
    public enum Type {
        AND,
        OR,
        EXCLUSIVE
    }

    public int from;
    public int to;
    public Type type;

    public Link(int from, int to, Type type) {
        this.from = from;
        this.to = to;
        this.type = type;
    }

    public boolean isPrerequisite() {
        return type != Type.EXCLUSIVE;
    }
}
