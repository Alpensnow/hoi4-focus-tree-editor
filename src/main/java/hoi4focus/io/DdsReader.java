package hoi4focus.io;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

// I fucking hate DDS.
public final class DdsReader {
    private DdsReader() {}

    public static BufferedImage read(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        if (data.length < 128 || data[0] != 'D' || data[1] != 'D' || data[2] != 'S' || data[3] != ' ') {
            return ImageIO.read(file.toFile());
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int h = bb.getInt(12);
        int w = bb.getInt(16);
        int pfFlags = bb.getInt(80);
        int fourCC = bb.getInt(84);
        int bits = bb.getInt(88);
        int rMask = bb.getInt(92);
        int gMask = bb.getInt(96);
        int bMask = bb.getInt(100);
        int aMask = bb.getInt(104);
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        if ((pfFlags & 0x4) != 0) { // Compressed
            String cc = "" + (char) (fourCC & 0xFF) + (char) ((fourCC >> 8) & 0xFF)
                    + (char) ((fourCC >> 16) & 0xFF) + (char) ((fourCC >>> 24) & 0xFF);
            int blockSize = cc.equals("DXT1") ? 8 : 16;
            if (!cc.equals("DXT1") && !cc.equals("DXT3") && !cc.equals("DXT5")) return null;
            int pos = 128;
            int[] px = new int[16];
            for (int by = 0; by < (h + 3) / 4; by++) {
                for (int bx = 0; bx < (w + 3) / 4; bx++) {
                    if (pos + blockSize > data.length) return img;
                    if (cc.equals("DXT1")) {
                        colorBlock(data, pos, px, true);
                    } else {
                        colorBlock(data, pos + 8, px, false);
                        if (cc.equals("DXT3")) alphaDxt3(data, pos, px);
                        else alphaDxt5(data, pos, px);
                    }
                    for (int p = 0; p < 16; p++) {
                        int x = bx * 4 + (p & 3);
                        int y = by * 4 + (p >> 2);
                        if (x < w && y < h) img.setRGB(x, y, px[p]);
                    }
                    pos += blockSize;
                }
            }
            return img;
        }

        if ((pfFlags & 0x40) == 0 || (bits != 24 && bits != 32)) return null; // Not plain RGB
        int bpp = bits / 8;
        boolean hasAlpha = (pfFlags & 0x1) != 0 && aMask != 0;
        int pos = 128;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (pos + bpp > data.length) return img;
                int v = (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8 | (data[pos + 2] & 0xFF) << 16
                        | (bpp == 4 ? (data[pos + 3] & 0xFF) << 24 : 0);
                int a = hasAlpha ? channel(v, aMask) : 255;
                img.setRGB(x, y, a << 24 | channel(v, rMask) << 16 | channel(v, gMask) << 8 | channel(v, bMask));
                pos += bpp;
            }
        }
        return img;
    }

    private static int channel(int v, int mask) {
        if (mask == 0) return 0;
        int shift = Integer.numberOfTrailingZeros(mask);
        int max = mask >>> shift;
        int c = (v & mask) >>> shift;
        return max == 255 ? c : (int) Math.round(c * 255.0 / max);
    }

    private static int rgb565(int c) {
        int r = (c >> 11) & 31;
        int g = (c >> 5) & 63;
        int b = c & 31;
        return (r * 255 / 31) << 16 | (g * 255 / 63) << 8 | (b * 255 / 31);
    }

    private static int mix(int a, int b, int wa, int wb) {
        int d = wa + wb;
        int r = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb) / d;
        int g = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb) / d;
        int bl = ((a & 255) * wa + (b & 255) * wb) / d;
        return r << 16 | g << 8 | bl;
    }

    private static void colorBlock(byte[] d, int pos, int[] px, boolean dxt1) {
        int c0 = (d[pos] & 0xFF) | (d[pos + 1] & 0xFF) << 8;
        int c1 = (d[pos + 2] & 0xFF) | (d[pos + 3] & 0xFF) << 8;
        int[] pal = new int[4];
        pal[0] = 0xFF000000 | rgb565(c0);
        pal[1] = 0xFF000000 | rgb565(c1);
        if (!dxt1 || c0 > c1) {
            pal[2] = 0xFF000000 | mix(pal[0], pal[1], 2, 1);
            pal[3] = 0xFF000000 | mix(pal[0], pal[1], 1, 2);
        } else {
            pal[2] = 0xFF000000 | mix(pal[0], pal[1], 1, 1);
            pal[3] = 0; // transparent black
        }
        int idx = (d[pos + 4] & 0xFF) | (d[pos + 5] & 0xFF) << 8 | (d[pos + 6] & 0xFF) << 16 | (d[pos + 7] & 0xFF) << 24;
        for (int p = 0; p < 16; p++) px[p] = pal[(idx >>> (2 * p)) & 3];
    }

    private static void alphaDxt3(byte[] d, int pos, int[] px) {
        for (int p = 0; p < 16; p++) {
            int a = (d[pos + p / 2] >> ((p & 1) * 4)) & 15;
            px[p] = (px[p] & 0xFFFFFF) | (a * 17) << 24;
        }
    }

    private static void alphaDxt5(byte[] d, int pos, int[] px) {
        int a0 = d[pos] & 0xFF;
        int a1 = d[pos + 1] & 0xFF;
        int[] a = new int[8];
        a[0] = a0;
        a[1] = a1;
        if (a0 > a1) {
            for (int k = 1; k < 7; k++) a[k + 1] = ((7 - k) * a0 + k * a1) / 7;
        } else {
            for (int k = 1; k < 5; k++) a[k + 1] = ((5 - k) * a0 + k * a1) / 5;
            a[6] = 0;
            a[7] = 255;
        }
        long bits = 0;
        for (int k = 0; k < 6; k++) bits |= (long) (d[pos + 2 + k] & 0xFF) << (8 * k);
        for (int p = 0; p < 16; p++) {
            int ai = (int) ((bits >>> (3 * p)) & 7);
            px[p] = (px[p] & 0xFFFFFF) | a[ai] << 24;
        }
    }
}
