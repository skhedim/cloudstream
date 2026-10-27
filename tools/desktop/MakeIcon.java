import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the KloudStream mark (white K on a red rounded square, as in the app) and writes
 * kloudstream.png (512 px) and kloudstream.ico (16–256 px, PNG entries).
 * Usage: java tools/desktop/MakeIcon.java kloudstream-desktop/packaging
 */
public class MakeIcon {
    static BufferedImage draw(int s) {
        BufferedImage image = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xE5, 0x09, 0x14));
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, s * 0.44f, s * 0.44f));
        g.setColor(Color.WHITE);
        Path2D k = new Path2D.Float();
        // Stem, then both arms starting inside the stem and overlapping at the junction.
        k.moveTo(s * 0.26, s * 0.2); k.lineTo(s * 0.42, s * 0.2); k.lineTo(s * 0.42, s * 0.8); k.lineTo(s * 0.26, s * 0.8); k.closePath();
        k.moveTo(s * 0.36, s * 0.56); k.lineTo(s * 0.62, s * 0.2); k.lineTo(s * 0.80, s * 0.2); k.lineTo(s * 0.52, s * 0.56); k.closePath();
        k.moveTo(s * 0.42, s * 0.45); k.lineTo(s * 0.80, s * 0.8); k.lineTo(s * 0.61, s * 0.8); k.lineTo(s * 0.36, s * 0.56); k.closePath();
        g.fill(k);
        g.dispose();
        return image;
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : ".");
        out.mkdirs();
        ImageIO.write(draw(512), "png", new File(out, "kloudstream.png"));
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int size : sizes) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(draw(size), "png", bytes);
            pngs.add(bytes.toByteArray());
        }
        try (DataOutputStream ico = new DataOutputStream(new FileOutputStream(new File(out, "kloudstream.ico")))) {
            ico.write(le16(0)); ico.write(le16(1)); ico.write(le16(sizes.length));
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                int size = sizes[i];
                ico.write(size >= 256 ? 0 : size); ico.write(size >= 256 ? 0 : size);
                ico.write(0); ico.write(0);
                ico.write(le16(1)); ico.write(le16(32));
                ico.write(le32(pngs.get(i).length)); ico.write(le32(offset));
                offset += pngs.get(i).length;
            }
            for (byte[] png : pngs) ico.write(png);
        }
    }

    static byte[] le16(int v) { return new byte[]{(byte) v, (byte) (v >> 8)}; }
    static byte[] le32(int v) { return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)}; }
}
