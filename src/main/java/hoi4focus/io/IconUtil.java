package hoi4focus.io;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

public final class IconUtil {
    private static final int MAX_DIM = 512;

    private IconUtil() {}

    public static byte[] loadAsPng(File file) throws IOException {
        BufferedImage src = ImageIO.read(file);
        if (src == null) {
            throw new IOException("Unsupported image format. Use PNG, JPG, GIF or BMP.");
        }
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage img = src;
        double scale = Math.min(1.0, (double) MAX_DIM / Math.max(w, h));
        if (scale < 1.0 || src.getType() != BufferedImage.TYPE_INT_ARGB) {
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));
            img = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
