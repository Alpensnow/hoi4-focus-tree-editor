package hoi4focus.model;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Position in the focus tree.
public class FocusNode {
    public int uid;

    public String id = "focus";
    public String name = "New Focus";
    public String description = "";

    public int x;
    public int y;
    public int cost = 10;

    public byte[] iconPng;
    public String vanillaIcon = "";

    public String available = "";
    public String bypass = "";
    public String completionReward = "";
    public String extraScript = "";

    public double aiFactor = 1;
    public boolean cancelIfInvalid = true;
    public boolean continueIfInvalid = false;
    public boolean availableIfCapitulated = false;
    public final List<String> searchFilters = new ArrayList<>();

    private transient BufferedImage iconImage;
    private transient boolean iconDecoded;

    public void setIconPng(byte[] png) {
        this.iconPng = png;
        this.iconImage = null;
        this.iconDecoded = false;
    }

    public BufferedImage getIconImage() {
        if (iconPng == null) return null;
        if (!iconDecoded) {
            iconDecoded = true;
            try {
                iconImage = ImageIO.read(new ByteArrayInputStream(iconPng));
            } catch (IOException e) {
                iconImage = null;
            }
        }
        return iconImage;
    }

    public FocusNode copy() {
        FocusNode n = new FocusNode();
        n.id = id;
        n.name = name;
        n.description = description;
        n.x = x;
        n.y = y;
        n.cost = cost;
        n.setIconPng(iconPng);
        n.vanillaIcon = vanillaIcon;
        n.available = available;
        n.bypass = bypass;
        n.completionReward = completionReward;
        n.extraScript = extraScript;
        n.aiFactor = aiFactor;
        n.cancelIfInvalid = cancelIfInvalid;
        n.continueIfInvalid = continueIfInvalid;
        n.availableIfCapitulated = availableIfCapitulated;
        n.searchFilters.addAll(searchFilters);
        return n;
    }
}
