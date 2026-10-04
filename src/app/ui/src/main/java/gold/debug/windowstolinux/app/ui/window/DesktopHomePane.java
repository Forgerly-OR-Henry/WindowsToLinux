package gold.debug.windowstolinux.app.ui.window;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;

/** Home surface displaying the independent bundled 16:9 project cover. / 展示独立内置 16:9 项目封面的首页。 */
final class DesktopHomePane extends JPanel {
    /** Original full-resolution packaged image. / 打包的原分辨率完整图片。 */
    private final BufferedImage cover;

    /** Loads only the UI resource copy. / 仅加载 UI 资源副本。 */
    DesktopHomePane() {
        setName("platform.home");
        try {
            cover = Objects.requireNonNull(ImageIO.read(Objects.requireNonNull(
                gold.debug.windowstolinux.app.ui.control.DesktopIcons.class.getResource("brand/windowstolinux-cover-4k.png"))));
        } catch (IOException failure) {
            throw new IllegalStateException("Bundled home cover is unavailable", failure);
        }
    }

    /** Paints the entire cover centered and scaled proportionally. / 居中等比绘制完整封面。
     * @param graphics current paint context / 当前绘制上下文 */
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (cover == null) return;
        int padding = UIScale.scale(24), width = Math.max(0, getWidth() - padding * 2), height = Math.max(0, getHeight() - padding * 2);
        double factor = Math.min(width / (double) cover.getWidth(), height / (double) cover.getHeight());
        int imageWidth = (int) Math.round(cover.getWidth() * factor), imageHeight = (int) Math.round(cover.getHeight() * factor);
        int x = (getWidth() - imageWidth) / 2, y = (getHeight() - imageHeight) / 2;
        var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.clip(new RoundRectangle2D.Double(x, y, imageWidth, imageHeight, UIScale.scale(16), UIScale.scale(16)));
            g.drawImage(cover, x, y, imageWidth, imageHeight, null);
        } finally {
            g.dispose();
        }
    }
}
