package hoi4focus.ui;

import hoi4focus.io.GameIcons;
import hoi4focus.model.FocusNode;
import hoi4focus.model.FocusProject;
import hoi4focus.model.Link;

import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// Focus tree canvas.
public class CanvasPanel extends JPanel {
    public enum Tool { SELECT, PREREQ_AND, PREREQ_OR, EXCLUSIVE }

    public static final String NEW_FOCUS_TOKEN = "hoi4focus:new-focus";

    // Like the game, one x step is about half a focus wide: x and x+1 overlap, x and x+2 sit side by side.
    static final int CELL_W = 80;
    static final int CELL_H = 116;
    static final int NODE_W = 142;
    static final int NODE_H = 78;

    private static final Color BG = new Color(0x1B1F24);
    private static final Color GRID = new Color(0x272D34);
    private static final Color GRID_LABEL = new Color(0x3C454F);
    private static final Color NODE_FILL = new Color(0x2F3740);
    private static final Color NODE_BORDER = new Color(0x74808D);
    private static final Color SELECT = new Color(0xFFC94A);
    private static final Color LINE = new Color(0xD7DCE1);
    private static final Color EXCL = new Color(0xD64541);

    private FocusProject project = new FocusProject();
    private final Set<Integer> selection = new LinkedHashSet<>();
    private Link selectedLink;
    private Tool tool = Tool.SELECT;
    private double zoom = 1.0;
    private double panX = 60;
    private double panY = 40;
    private Set<Integer> flagged = new HashSet<>();

    private enum Drag { NONE, PAN, MOVE, LINK, BAND }

    private Drag drag = Drag.NONE;
    private Point lastScreen;
    private Point2D startWorld;
    private final Map<Integer, Point> origPos = new HashMap<>();
    private FocusNode linkFrom;
    private Point2D linkEnd;
    private Rectangle2D band;
    private boolean moved;
    private boolean spaceDown;

    public Runnable onModified = () -> { };
    public Runnable onSelectionChanged = () -> { };
    public Runnable onEditRequested = () -> { };
    public Consumer<String> onStatus = s -> { };
    public Consumer<Tool> onToolRequest = t -> { };
    public BiConsumer<FocusNode, File> onIconDropped = (n, f) -> { };
    public Runnable onUndo = () -> { };
    public Runnable onRedo = () -> { };

    public CanvasPanel() {
        setBackground(BG);
        setFocusable(true);
        setPreferredSize(new Dimension(900, 640));
        setTransferHandler(new DropHandler());
        installMouse();
        installKeys();
    }

    // Public API

    public void setProject(FocusProject p) {
        this.project = p;
        selection.clear();
        selectedLink = null;
        flagged = new HashSet<>();
        repaint();
    }

    public Tool getTool() {
        return tool;
    }

    public void setTool(Tool t) {
        this.tool = t;
        setCursor(Cursor.getPredefinedCursor(t == Tool.SELECT ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
        switch (t) {
            case SELECT -> onStatus.accept("Select tool: click or drag focuses, drag on empty space to box-select.");
            case PREREQ_AND -> onStatus.accept("Drag from the prerequisite focus to the focus that requires it (solid line = required).");
            case PREREQ_OR -> onStatus.accept("Drag from a prerequisite to the focus (dotted line = any ONE of the dotted prerequisites is enough).");
            case EXCLUSIVE -> onStatus.accept("Drag from one focus to the other to make them mutually exclusive.");
        }
        repaint();
    }

    public List<FocusNode> selectedNodes() {
        List<FocusNode> out = new ArrayList<>();
        for (int uid : selection) {
            FocusNode n = project.node(uid);
            if (n != null) out.add(n);
        }
        return out;
    }

    public Set<Integer> selectionIds() {
        return new LinkedHashSet<>(selection);
    }

    public void setSelection(Collection<Integer> uids) {
        selection.clear();
        for (int uid : uids) if (project.node(uid) != null) selection.add(uid);
        selectedLink = null;
        onSelectionChanged.run();
        repaint();
    }

    public void setFlagged(Set<Integer> uids) {
        flagged = new HashSet<>(uids);
        repaint();
    }

    public void focusOn(int uid) {
        FocusNode n = project.node(uid);
        if (n == null) return;
        Rectangle2D r = nodeRect(n);
        panX = getWidth() / 2.0 - r.getCenterX() * zoom;
        panY = getHeight() / 2.0 - r.getCenterY() * zoom;
        setSelection(List.of(uid));
    }

    public void fitView() {
        if (project.nodes.isEmpty()) {
            zoom = 1;
            panX = 60;
            panY = 40;
            repaint();
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (FocusNode n : project.nodes) {
            Rectangle2D r = nodeRect(n);
            minX = Math.min(minX, r.getX());
            minY = Math.min(minY, r.getY());
            maxX = Math.max(maxX, r.getMaxX());
            maxY = Math.max(maxY, r.getMaxY());
        }
        double w = Math.max(1, getWidth());
        double h = Math.max(1, getHeight());
        zoom = Math.max(0.25, Math.min(1.25, Math.min((w - 80) / (maxX - minX), (h - 80) / (maxY - minY))));
        panX = (w - (maxX - minX) * zoom) / 2 - minX * zoom;
        panY = (h - (maxY - minY) * zoom) / 2 - minY * zoom;
        repaint();
    }

    public FocusNode addFocusAtCell(int cx, int cy) {
        FocusNode n = project.newNode(cx, cy);
        selection.clear();
        selection.add(n.uid);
        selectedLink = null;
        onSelectionChanged.run();
        onModified.run();
        repaint();
        return n;
    }

    public void deleteSelection() {
        if (selectedLink != null) {
            project.links.remove(selectedLink);
            selectedLink = null;
        } else if (!selection.isEmpty()) {
            for (int uid : new ArrayList<>(selection)) project.removeNode(uid);
            selection.clear();
            onSelectionChanged.run();
        } else {
            return;
        }
        onModified.run();
        repaint();
    }

    public void selectAll() {
        selection.clear();
        for (FocusNode n : project.nodes) selection.add(n.uid);
        selectedLink = null;
        onSelectionChanged.run();
        repaint();
    }

    // Geometry

    private Point2D toWorld(Point p) {
        return new Point2D.Double((p.x - panX) / zoom, (p.y - panY) / zoom);
    }

    static Rectangle2D nodeRect(FocusNode n) {
        return new Rectangle2D.Double(n.x * CELL_W + (CELL_W - NODE_W) / 2.0, n.y * CELL_H + (CELL_H - NODE_H) / 2.0, NODE_W, NODE_H);
    }

    // Topmost first, matching paint order (selected focuses are drawn above the rest).
    private FocusNode nodeAt(Point2D w) {
        FocusNode hit = null;
        for (int i = project.nodes.size() - 1; i >= 0; i--) {
            FocusNode n = project.nodes.get(i);
            if (!nodeRect(n).contains(w)) continue;
            if (selection.contains(n.uid)) return n;
            if (hit == null) hit = n;
        }
        return hit;
    }

    private static Point2D edgePoint(Rectangle2D r, Point2D toward) {
        double cx = r.getCenterX();
        double cy = r.getCenterY();
        double dx = toward.getX() - cx;
        double dy = toward.getY() - cy;
        if (dx == 0 && dy == 0) return new Point2D.Double(cx, cy);
        double sx = dx == 0 ? Double.MAX_VALUE : (r.getWidth() / 2) / Math.abs(dx);
        double sy = dy == 0 ? Double.MAX_VALUE : (r.getHeight() / 2) / Math.abs(dy);
        double s = Math.min(sx, sy);
        return new Point2D.Double(cx + dx * s, cy + dy * s);
    }

    private List<Point2D> route(Link l) {
        FocusNode a = project.node(l.from);
        FocusNode b = project.node(l.to);
        List<Point2D> pts = new ArrayList<>();
        if (a == null || b == null) return pts;
        Rectangle2D ra = nodeRect(a);
        Rectangle2D rb = nodeRect(b);
        if (l.type == Link.Type.EXCLUSIVE) {
            Point2D ca = new Point2D.Double(ra.getCenterX(), ra.getCenterY());
            Point2D cb = new Point2D.Double(rb.getCenterX(), rb.getCenterY());
            pts.add(edgePoint(ra, cb));
            pts.add(edgePoint(rb, ca));
            return pts;
        }
        if (a.y == b.y) {
            boolean right = rb.getCenterX() > ra.getCenterX();
            pts.add(new Point2D.Double(right ? ra.getMaxX() : ra.getX(), ra.getCenterY()));
            pts.add(new Point2D.Double(right ? rb.getX() : rb.getMaxX(), rb.getCenterY()));
            return pts;
        }
        double sx = ra.getCenterX();
        double ex = rb.getCenterX();
        double sy;
        double ey;
        double midY = Math.min(ra.getMaxY(), rb.getMaxY()) + (CELL_H - NODE_H) / 2.0;
        if (rb.getCenterY() > ra.getCenterY()) {
            sy = ra.getMaxY();
            ey = rb.getY();
        } else {
            sy = ra.getY();
            ey = rb.getMaxY();
        }
        pts.add(new Point2D.Double(sx, sy));
        pts.add(new Point2D.Double(sx, midY));
        pts.add(new Point2D.Double(ex, midY));
        pts.add(new Point2D.Double(ex, ey));
        return pts;
    }

    private Link linkAt(Point2D w) {
        double tol = 7 / zoom;
        Link best = null;
        double bestD = tol;
        for (Link l : project.links) {
            List<Point2D> pts = route(l);
            double d = Double.MAX_VALUE;
            for (int i = 0; i + 1 < pts.size(); i++) {
                d = Math.min(d, Line2D.ptSegDist(pts.get(i).getX(), pts.get(i).getY(),
                        pts.get(i + 1).getX(), pts.get(i + 1).getY(), w.getX(), w.getY()));
            }
            if (l.type == Link.Type.EXCLUSIVE && pts.size() == 2) {
                double mx = (pts.get(0).getX() + pts.get(1).getX()) / 2;
                double my = (pts.get(0).getY() + pts.get(1).getY()) / 2;
                d = Math.min(d, Math.max(0, Point2D.distance(mx, my, w.getX(), w.getY()) - 10 / zoom));
            }
            if (d < bestD) {
                bestD = d;
                best = l;
            }
        }
        return best;
    }

    // Painting

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(BG);
        g.fillRect(0, 0, getWidth(), getHeight());

        g.translate(panX, panY);
        g.scale(zoom, zoom);

        drawGrid(g);
        for (Link l : project.links) drawLink(g, l);
        List<FocusNode> order = new ArrayList<>(project.nodes);
        order.sort((a, b) -> Boolean.compare(selection.contains(a.uid), selection.contains(b.uid)));
        for (FocusNode n : order) drawNode(g, n);

        if (drag == Drag.LINK && linkFrom != null && linkEnd != null) {
            Rectangle2D r = nodeRect(linkFrom);
            g.setColor(tool == Tool.EXCLUSIVE ? EXCL : SELECT);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                    tool == Tool.PREREQ_OR ? new float[]{6f, 5f} : null, 0f));
            g.draw(new Line2D.Double(r.getCenterX(), r.getCenterY(), linkEnd.getX(), linkEnd.getY()));
        }
        if (band != null) {
            g.setColor(new Color(74, 144, 217, 50));
            g.fill(band);
            g.setColor(new Color(74, 144, 217, 200));
            g.setStroke(new BasicStroke((float) (1 / zoom)));
            g.draw(band);
        }
        g.dispose();

        if (project.nodes.isEmpty()) {
            Graphics2D h = (Graphics2D) g0.create();
            h.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            h.setColor(new Color(0x8995A1));
            h.setFont(getFont().deriveFont(Font.PLAIN, 15f));
            String[] lines = {
                // Had something here but took it out. Lowkey don't need this.
            };
            int y = getHeight() / 2 - 30;
            for (String s : lines) {
                int w = h.getFontMetrics().stringWidth(s);
                h.drawString(s, (getWidth() - w) / 2, y);
                y += 26;
            }
            h.dispose();
        }
    }

    private void drawGrid(Graphics2D g) {
        double x0 = -panX / zoom;
        double y0 = -panY / zoom;
        double x1 = (getWidth() - panX) / zoom;
        double y1 = (getHeight() - panY) / zoom;
        g.setStroke(new BasicStroke((float) (1 / zoom)));
        g.setColor(GRID);
        for (int cx = (int) Math.floor(x0 / CELL_W); cx <= Math.ceil(x1 / CELL_W); cx++) {
            g.draw(new Line2D.Double(cx * CELL_W, y0, cx * CELL_W, y1));
        }
        for (int cy = (int) Math.floor(y0 / CELL_H); cy <= Math.ceil(y1 / CELL_H); cy++) {
            g.draw(new Line2D.Double(x0, cy * CELL_H, x1, cy * CELL_H));
        }
        if (zoom >= 0.55) {
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
            g.setColor(GRID_LABEL);
            for (int cx = (int) Math.floor(x0 / CELL_W); cx <= Math.ceil(x1 / CELL_W); cx++) {
                for (int cy = (int) Math.floor(y0 / CELL_H); cy <= Math.ceil(y1 / CELL_H); cy++) {
                    g.drawString(cx + "," + cy, cx * CELL_W + 4, cy * CELL_H + 12);
                }
            }
        }
    }

    private void drawLink(Graphics2D g, Link l) {
        List<Point2D> pts = route(l);
        if (pts.size() < 2) return;
        boolean sel = l == selectedLink;
        if (l.type == Link.Type.EXCLUSIVE) {
            Point2D a = pts.get(0);
            Point2D b = pts.get(1);
            g.setColor(sel ? SELECT : LINE);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(a, b));
            arrowHead(g, b, a);
            arrowHead(g, a, b);
            double mx = (a.getX() + b.getX()) / 2;
            double my = (a.getY() + b.getY()) / 2;
            Shape badge = new Ellipse2D.Double(mx - 10, my - 10, 20, 20);
            g.setColor(EXCL);
            g.fill(badge);
            g.setColor(sel ? SELECT : Color.WHITE);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(mx - 4, my - 4, mx + 4, my + 4));
            g.draw(new Line2D.Double(mx - 4, my + 4, mx + 4, my - 4));
            return;
        }
        Path2D path = new Path2D.Double();
        path.moveTo(pts.get(0).getX(), pts.get(0).getY());
        for (int i = 1; i < pts.size(); i++) path.lineTo(pts.get(i).getX(), pts.get(i).getY());
        g.setColor(sel ? SELECT : LINE);
        float w = sel ? 3.2f : 2.4f;
        if (l.type == Link.Type.OR) {
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{7f, 6f}, 0f));
        } else {
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        }
        g.draw(path);
    }

    private static void arrowHead(Graphics2D g, Point2D tip, Point2D from) {
        double ang = Math.atan2(tip.getY() - from.getY(), tip.getX() - from.getX());
        double len = 11;
        double spread = Math.toRadians(26);
        Path2D p = new Path2D.Double();
        p.moveTo(tip.getX(), tip.getY());
        p.lineTo(tip.getX() - len * Math.cos(ang - spread), tip.getY() - len * Math.sin(ang - spread));
        p.lineTo(tip.getX() - len * Math.cos(ang + spread), tip.getY() - len * Math.sin(ang + spread));
        p.closePath();
        g.fill(p);
    }

    private void drawNode(Graphics2D g, FocusNode n) {
        Rectangle2D r = nodeRect(n);
        boolean sel = selection.contains(n.uid);
        boolean bad = flagged.contains(n.uid);
        Rectangle2D shape = r;
        int x = (int) Math.round(r.getX()), y = (int) Math.round(r.getY());
        int w = (int) Math.round(r.getWidth()), h = (int) Math.round(r.getHeight());

        // Old-school raised panel: flat fill, light top/left edge, dark bottom/right edge.
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setStroke(new BasicStroke(1f));
        g.setColor(NODE_FILL);
        g.fillRect(x, y, w, h);
        g.setColor(new Color(0x4A5561));
        g.drawLine(x + 1, y + 1, x + w - 2, y + 1);
        g.drawLine(x + 1, y + 1, x + 1, y + h - 2);
        g.setColor(new Color(0x161A1F));
        g.drawLine(x + 1, y + h - 2, x + w - 2, y + h - 2);
        g.drawLine(x + w - 2, y + 1, x + w - 2, y + h - 2);
        g.setColor(bad ? EXCL : sel ? SELECT : NODE_BORDER);
        g.drawRect(x, y, w - 1, h - 1);
        if (sel || bad) g.drawRect(x - 1, y - 1, w + 1, h + 1);

        int ix = x + 8;
        int iy = y + 10;
        int iw = 46;
        int ih = 42;
        // Sunken icon well.
        g.setColor(new Color(0x1B2026));
        g.fillRect(ix, iy, iw, ih);
        g.setColor(new Color(0x161A1F));
        g.drawLine(ix - 1, iy - 1, ix + iw, iy - 1);
        g.drawLine(ix - 1, iy - 1, ix - 1, iy + ih);
        g.setColor(new Color(0x4A5561));
        g.drawLine(ix - 1, iy + ih, ix + iw, iy + ih);
        g.drawLine(ix + iw, iy - 1, ix + iw, iy + ih);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        BufferedImage img = n.getIconImage();
        if (img == null) img = GameIcons.preview(n.vanillaIcon);
        if (img != null) {
            double s = Math.min((double) iw / img.getWidth(), (double) ih / img.getHeight());
            double dw = img.getWidth() * s;
            double dh = img.getHeight() * s;
            g.drawImage(img, (int) Math.round(ix + (iw - dw) / 2), (int) Math.round(iy + (ih - dh) / 2),
                    (int) Math.round(dw), (int) Math.round(dh), null);
        } else {
            g.setColor(new Color(0x56616D));
            g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
            String t = n.vanillaIcon == null || n.vanillaIcon.isBlank() ? "no icon" : "game icon";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(t, (float) (ix + (iw - fm.stringWidth(t)) / 2.0), (float) (iy + ih / 2.0 + fm.getAscent() / 2.0 - 2));
        }

        Shape oldClip = g.getClip();
        g.clip(shape);

        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(n.name.isBlank() ? "(unnamed)" : n.name, fm, (int) (r.getWidth() - 8 - iw - 6 - 6), 3);
        double ty = r.getY() + 20;
        for (String s : lines) {
            g.drawString(s, (float) (ix + iw + 6), (float) ty);
            ty += 14;
        }

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        fm = g.getFontMetrics();
        String cost = n.cost + "w";
        g.setColor(new Color(0x7FB2E5));
        g.drawString(cost, (float) (r.getMaxX() - 8 - fm.stringWidth(cost)), (float) (r.getMaxY() - 7));
        g.setColor(new Color(0x9AA5B1));
        String id = n.id;
        int maxW = (int) (r.getWidth() - 16 - fm.stringWidth(cost) - 6);
        while (fm.stringWidth(id) > maxW && id.length() > 3) id = id.substring(0, id.length() - 1);
        if (!id.equals(n.id)) id = id.substring(0, Math.max(0, id.length() - 1)) + "…";
        g.drawString(id, (float) (r.getX() + 8), (float) (r.getMaxY() - 7));
        g.setClip(oldClip);
    }

    private static List<String> wrap(String text, FontMetrics fm, int maxW, int maxLines) {
        List<String> lines = new ArrayList<>();
        String[] words = text.trim().split("\\s+");
        StringBuilder cur = new StringBuilder();
        boolean truncated = false;
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            String t = cur.length() == 0 ? w : cur + " " + w;
            if (fm.stringWidth(t) <= maxW) {
                cur = new StringBuilder(t);
                continue;
            }
            if (cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder();
                if (lines.size() == maxLines) {
                    truncated = true;
                    break;
                }
            }
            String ww = w;
            while (fm.stringWidth(ww) > maxW && ww.length() > 1) ww = ww.substring(0, ww.length() - 1);
            cur = new StringBuilder(ww);
        }
        if (!truncated && cur.length() > 0) lines.add(cur.toString());
        if (lines.size() > maxLines) lines = new ArrayList<>(lines.subList(0, maxLines));
        if (truncated && !lines.isEmpty()) {
            String last = lines.get(lines.size() - 1);
            lines.set(lines.size() - 1, last + "…");
        }
        return lines;
    }

    // Input

    private void installMouse() {
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                lastScreen = e.getPoint();
                if (e.isPopupTrigger()) {
                    showMenu(e);
                    return;
                }
                if (SwingUtilities.isMiddleMouseButton(e) || (SwingUtilities.isLeftMouseButton(e) && spaceDown)) {
                    drag = Drag.PAN;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(e)) return;

                Point2D w = toWorld(e.getPoint());
                FocusNode hit = nodeAt(w);
                moved = false;
                if (tool != Tool.SELECT) {
                    if (hit != null) {
                        drag = Drag.LINK;
                        linkFrom = hit;
                        linkEnd = w;
                        selectedLink = null;
                    } else {
                        Link l = linkAt(w);
                        selectedLink = l;
                        selection.clear();
                        onSelectionChanged.run();
                        drag = Drag.PAN; 
                    }
                    repaint();
                    return;
                }
                boolean additive = e.isControlDown() || e.isShiftDown();
                if (hit != null) {
                    selectedLink = null;
                    if (additive) {
                        if (!selection.remove(hit.uid)) selection.add(hit.uid);
                    } else if (!selection.contains(hit.uid)) {
                        selection.clear();
                        selection.add(hit.uid);
                    }
                    onSelectionChanged.run();
                    if (selection.contains(hit.uid)) {
                        drag = Drag.MOVE;
                        startWorld = w;
                        origPos.clear();
                        for (int uid : selection) {
                            FocusNode n = project.node(uid);
                            if (n != null) origPos.put(uid, new Point(n.x, n.y));
                        }
                    }
                } else {
                    Link l = linkAt(w);
                    if (l != null) {
                        selectedLink = l;
                        selection.clear();
                        onSelectionChanged.run();
                    } else {
                        selectedLink = null;
                        if (!additive) selection.clear();
                        drag = Drag.BAND;
                        startWorld = w;
                        band = new Rectangle2D.Double(w.getX(), w.getY(), 0, 0);
                        onSelectionChanged.run();
                    }
                }
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (drag == Drag.NONE) return;
                Point2D w = toWorld(e.getPoint());
                switch (drag) {
                    case PAN -> {
                        panX += e.getX() - lastScreen.x;
                        panY += e.getY() - lastScreen.y;
                    }
                    case MOVE -> {
                        int dx = (int) Math.round((w.getX() - startWorld.getX()) / CELL_W);
                        int dy = (int) Math.round((w.getY() - startWorld.getY()) / CELL_H);
                        for (Map.Entry<Integer, Point> en : origPos.entrySet()) {
                            FocusNode n = project.node(en.getKey());
                            if (n == null) continue;
                            int nx = en.getValue().x + dx;
                            int ny = en.getValue().y + dy;
                            if (nx != n.x || ny != n.y) moved = true;
                            n.x = nx;
                            n.y = ny;
                        }
                    }
                    case LINK -> linkEnd = w;
                    case BAND -> {
                        double x = Math.min(startWorld.getX(), w.getX());
                        double y = Math.min(startWorld.getY(), w.getY());
                        band = new Rectangle2D.Double(x, y, Math.abs(w.getX() - startWorld.getX()), Math.abs(w.getY() - startWorld.getY()));
                        if (!e.isControlDown() && !e.isShiftDown()) selection.clear();
                        for (FocusNode n : project.nodes) if (band.intersects(nodeRect(n))) selection.add(n.uid);
                    }
                    default -> { }
                }
                lastScreen = e.getPoint();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showMenu(e);
                    resetDrag();
                    return;
                }
                Drag d = drag;
                if (d == Drag.MOVE && moved) {
                    onModified.run();
                } else if (d == Drag.LINK && linkFrom != null) {
                    FocusNode target = nodeAt(toWorld(e.getPoint()));
                    if (target != null && target != linkFrom) {
                        Link.Type type = switch (tool) {
                            case PREREQ_AND -> Link.Type.AND;
                            case PREREQ_OR -> Link.Type.OR;
                            default -> Link.Type.EXCLUSIVE;
                        };
                        String err = project.addLink(linkFrom.uid, target.uid, type);
                        if (err != null) {
                            onStatus.accept(err);
                            java.awt.Toolkit.getDefaultToolkit().beep();
                        } else {
                            onModified.run();
                        }
                    }
                } else if (d == Drag.BAND) {
                    onSelectionChanged.run();
                }
                resetDrag();
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                Point2D w = toWorld(e.getPoint());
                FocusNode hit = nodeAt(w);
                if (hit != null) {
                    onEditRequested.run();
                } else if (linkAt(w) == null && tool == Tool.SELECT) {
                    addFocusAtCell((int) Math.floor(w.getX() / CELL_W), (int) Math.floor(w.getY() / CELL_H));
                    onEditRequested.run();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                double factor = e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1;
                double nz = Math.max(0.2, Math.min(2.5, zoom * factor));
                double wx = (e.getX() - panX) / zoom;
                double wy = (e.getY() - panY) / zoom;
                zoom = nz;
                panX = e.getX() - wx * zoom;
                panY = e.getY() - wy * zoom;
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) spaceDown = true;
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) spaceDown = false;
            }
        });
    }

    private void resetDrag() {
        drag = Drag.NONE;
        linkFrom = null;
        linkEnd = null;
        band = null;
        moved = false;
        origPos.clear();
        setCursor(Cursor.getPredefinedCursor(tool == Tool.SELECT ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
    }

    private void installKeys() {
        bind("DELETE", this::deleteSelection);
        bind("BACK_SPACE", this::deleteSelection);
        bind("ESCAPE", () -> {
            selection.clear();
            selectedLink = null;
            onToolRequest.accept(Tool.SELECT);
            onSelectionChanged.run();
            repaint();
        });
        bind("ctrl A", this::selectAll);
        bind("ctrl Z", () -> onUndo.run());
        bind("ctrl Y", () -> onRedo.run());
        bind("ctrl shift Z", () -> onRedo.run());
        bind("ctrl D", this::duplicateSelection);
        bind("1", () -> onToolRequest.accept(Tool.SELECT));
        bind("2", () -> onToolRequest.accept(Tool.PREREQ_AND));
        bind("3", () -> onToolRequest.accept(Tool.PREREQ_OR));
        bind("4", () -> onToolRequest.accept(Tool.EXCLUSIVE));
        bind("F", this::fitView);
        bind("LEFT", () -> nudge(-1, 0));
        bind("RIGHT", () -> nudge(1, 0));
        bind("UP", () -> nudge(0, -1));
        bind("DOWN", () -> nudge(0, 1));
    }

    private void bind(String keystroke, Runnable r) {
        String name = "act-" + keystroke;
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keystroke), name);
        getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private void nudge(int dx, int dy) {
        if (selection.isEmpty()) return;
        for (FocusNode n : selectedNodes()) {
            n.x += dx;
            n.y += dy;
        }
        onModified.run();
        repaint();
    }

    public void duplicateSelection() {
        List<Integer> created = new ArrayList<>();
        for (FocusNode n : selectedNodes()) created.add(project.duplicate(n, 1, 1).uid);
        if (created.isEmpty()) return;
        selection.clear();
        selection.addAll(created);
        onSelectionChanged.run();
        onModified.run();
        repaint();
    }

    private void showMenu(MouseEvent e) {
        Point2D w = toWorld(e.getPoint());
        FocusNode hit = nodeAt(w);
        JPopupMenu menu = new JPopupMenu();
        if (hit != null) {
            if (!selection.contains(hit.uid)) {
                selection.clear();
                selection.add(hit.uid);
                selectedLink = null;
                onSelectionChanged.run();
            }
            menu.add(item("Add child focus below", () -> {
                FocusNode child = project.newNode(hit.x, hit.y + 1);
                project.addLink(hit.uid, child.uid, Link.Type.AND);
                selection.clear();
                selection.add(child.uid);
                onSelectionChanged.run();
                onModified.run();
                onEditRequested.run();
                repaint();
            }));
            menu.add(item("Duplicate", this::duplicateSelection));
            menu.addSeparator();
            menu.add(item("Delete", this::deleteSelection));
        } else {
            Link l = linkAt(w);
            if (l != null) {
                selectedLink = l;
                selection.clear();
                onSelectionChanged.run();
                if (l.type == Link.Type.AND) {
                    menu.add(item("Make dotted (any one of)", () -> retype(l, Link.Type.OR)));
                } else if (l.type == Link.Type.OR) {
                    menu.add(item("Make solid (required)", () -> retype(l, Link.Type.AND)));
                }
                menu.add(item("Delete link", this::deleteSelection));
            } else {
                final int cx = (int) Math.floor(w.getX() / CELL_W);
                final int cy = (int) Math.floor(w.getY() / CELL_H);
                menu.add(item("Add focus here", () -> {
                    addFocusAtCell(cx, cy);
                    onEditRequested.run();
                }));
            }
        }
        repaint();
        menu.show(this, e.getX(), e.getY());
    }

    private void retype(Link l, Link.Type t) {
        l.type = t;
        onModified.run();
        repaint();
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem mi = new JMenuItem(text);
        mi.addActionListener(ev -> r.run());
        return mi;
    }

    // Drag and drop

    private final class DropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDrop() && (s.isDataFlavorSupported(DataFlavor.stringFlavor)
                    || s.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            if (!canImport(s)) return false;
            try {
                Point2D w = toWorld(s.getDropLocation().getDropPoint());
                if (s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    FocusNode n = nodeAt(w);
                    if (n == null || files.isEmpty()) {
                        onStatus.accept("Drop an image onto a focus to use it as that focus's icon.");
                        return false;
                    }
                    onIconDropped.accept(n, files.get(0));
                    return true;
                }
                String t = (String) s.getTransferable().getTransferData(DataFlavor.stringFlavor);
                if (!NEW_FOCUS_TOKEN.equals(t)) return false;
                addFocusAtCell((int) Math.floor(w.getX() / CELL_W), (int) Math.floor(w.getY() / CELL_H));
                onEditRequested.run();
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
