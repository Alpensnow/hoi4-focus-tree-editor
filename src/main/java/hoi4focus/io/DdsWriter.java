package hoi4focus.io;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

// I fucking hate DDS.
public final class DdsWriter {
    private DdsWriter() {}

    /** Fits the image inside w x h (keeping aspect ratio) on a transparent canvas. */
    public static BufferedImage fit(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        double scale = Math.min((double) w / src.getWidth(), (double) h / src.getHeight());
        int dw = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int dh = Math.max(1, (int) Math.round(src.getHeight() * scale));
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        g.dispose();
        return out;
    }

    public static void write(BufferedImage img, Path out) throws IOException {
        int w = img.getWidth();
        int h = img.getHeight();
        ByteBuffer bb = ByteBuffer.allocate(128 + w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'D').put((byte) 'D').put((byte) 'S').put((byte) ' ');
        bb.putInt(124);                    // dwSize
        bb.putInt(0x1 | 0x2 | 0x4 | 0x8 | 0x1000); // CAPS | HEIGHT | WIDTH | PITCH | PIXELFORMAT
        bb.putInt(h);
        bb.putInt(w);
        bb.putInt(w * 4);                  // pitch
        bb.putInt(0);                      // depth
        bb.putInt(0);                      // mip map count
        for (int i = 0; i < 11; i++) bb.putInt(0); // reserved
        bb.putInt(32);                     // pixel format size
        bb.putInt(0x1 | 0x40);             // ALPHAPIXELS | RGB
        bb.putInt(0);                      // fourCC
        bb.putInt(32);                     // bit count
        bb.putInt(0x00FF0000);             // R mask
        bb.putInt(0x0000FF00);             // G mask
        bb.putInt(0x000000FF);             // B mask
        bb.putInt(0xFF000000);             // A mask
        bb.putInt(0x1000);                 // caps: texture
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) bb.putInt(img.getRGB(x, y)); // ARGB little-endian = B,G,R,A bytes
        }
        Files.createDirectories(out.getParent());
        Files.write(out, bb.array());
    }
}
