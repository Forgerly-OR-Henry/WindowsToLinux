package gold.debug.windowstolinux.app.ui.component;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import javax.swing.Icon;
import java.awt.Color;
import java.util.Objects;
import java.util.function.Supplier;

/** Theme-aware bundled Lucide icons. / 跟随主题的内置 Lucide 图标。 */
public final class DesktopIcons {
    private DesktopIcons() { }

    /** Creates an icon whose color follows the owning control. / 图标颜色跟随所属控件。 */
    public static Icon icon(String name, int size, Supplier<Color> foreground) {
        var url = Objects.requireNonNull(DesktopIcons.class.getResource("icons/" + name + ".svg"), name);
        FlatSVGIcon icon = new FlatSVGIcon(url).derive(size, size);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(color -> foreground.get()));
        return icon;
    }
}
