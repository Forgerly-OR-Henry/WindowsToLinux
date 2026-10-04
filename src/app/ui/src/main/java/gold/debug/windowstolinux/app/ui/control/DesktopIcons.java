package gold.debug.windowstolinux.app.ui.control;

import java.awt.Color;
import java.util.Objects;
import java.util.function.Supplier;

import javax.swing.Icon;

import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * Theme-aware bundled SVG icons. / 跟随主题的内置 SVG 图标。
 */
public final class DesktopIcons {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DesktopIcons() {
    }

    /** Loads the bundled, independent project logo. / 加载内置的独立项目标识副本。
     * @param size logical icon size / 逻辑图标尺寸
     * @return scaled logo / 缩放后的标识 */
    public static Icon logo(int size) {
        var url = Objects.requireNonNull(DesktopIcons.class.getResource("brand/windowstolinux-icon-2k.png"));
        var source = new javax.swing.ImageIcon(url);
        int pixels = com.formdev.flatlaf.util.UIScale.scale(size);
        return new javax.swing.ImageIcon(source.getImage().getScaledInstance(pixels, pixels, java.awt.Image.SCALE_SMOOTH));
    }

    /**
     * Creates an icon whose color follows the owning control. / 图标颜色跟随所属控件。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param size size / 大小
     * @param foreground foreground / 前景
     * @return an icon whose color follows the owning control / 图标颜色跟随所属控件
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static Icon icon(String name, int size, Supplier<Color> foreground) {
        var url = Objects.requireNonNull(DesktopIcons.class.getResource("icons/" + name + ".svg"), name);
        FlatSVGIcon icon = new FlatSVGIcon(url).derive(size, size);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(color -> foreground.get()));
        return icon;
    }
}
