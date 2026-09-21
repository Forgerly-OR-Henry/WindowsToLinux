package gold.debug.windowstolinux.app.ui.component;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import javax.swing.Icon;
import java.awt.Color;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Theme-aware bundled SVG icons. / 跟随主题的内置 SVG 图标。
 */
public final class DesktopIcons {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DesktopIcons() { }

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
