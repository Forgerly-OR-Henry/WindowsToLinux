package gold.debug.windowstolinux.app.ui.display;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.UIManager;
import java.awt.Font;

/**
 * Shared FlatLaf setup for the managed-deployment Swing desktop client.
 *
 * <p>受管部署 Swing 桌面客户端共用的 FlatLaf 设置。
 */
public final class DesktopThemeService {
    private DesktopThemeService() {
    }

    /**
     * Installs the requested effective appearance before Swing components are created.
     *
     * <p>在创建 Swing 组件之前安装请求的生效外观。
     *
     * @param effectiveTheme the {@code effectiveTheme} value / {@code effectiveTheme} 值
     */
    public static void install(ThemeMode effectiveTheme) {
        if (effectiveTheme == ThemeMode.DARK) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 12);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("ScrollBar.width", 14);
        UIManager.put("TitlePane.unifiedBackground", true);

        Font defaultFont = UIManager.getFont("defaultFont");
        if (defaultFont != null) {
            UIManager.put("defaultFont", defaultFont.deriveFont(13f));
        }
    }

    /**
     * Applies a light or dark FlatLaf theme to existing windows.
     *
     * <p>向现有窗口应用浅色或深色 FlatLaf 主题。
     *
     * @param effectiveTheme the {@code effectiveTheme} value / {@code effectiveTheme} 值
     */
    public static void apply(ThemeMode effectiveTheme) {
        install(effectiveTheme);
        com.formdev.flatlaf.FlatLaf.updateUI();
    }
}
