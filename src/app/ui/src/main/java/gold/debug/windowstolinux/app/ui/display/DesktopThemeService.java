package gold.debug.windowstolinux.app.ui.display;

import java.awt.Font;

import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

/**
 * Shared FlatLaf setup for the managed-deployment Swing desktop client.
 *
 *  <p>受管部署 Swing 桌面客户端共用的 FlatLaf 设置。
 */
public final class DesktopThemeService {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DesktopThemeService() {
    }

    /**
     * Installs the requested effective appearance before Swing components are created.
     *
     *  <p>在创建 Swing 组件之前安装请求的生效外观。
     *
     * @param effectiveTheme effective theme / 生效主题
     */
    public static void install(ThemeMode effectiveTheme) {
        install(effectiveTheme, Math.round(com.formdev.flatlaf.util.UIScale.getZoomFactor() * 100));
    }

    /** Installs system-aware application zoom before creating controls. / 创建控件前安装保留系统缩放的应用缩放。
     * @param effectiveTheme effective theme / 生效主题
     * @param scalePercent application zoom percentage / 应用缩放百分比 */
    public static void install(ThemeMode effectiveTheme, int scalePercent) {
        com.formdev.flatlaf.util.UIScale.setZoomFactor(1f);
        if (effectiveTheme == ThemeMode.DARK) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        DesktopControlDefaults.install(effectiveTheme);

        Font defaultFont = UIManager.getFont("defaultFont");
        if (defaultFont != null) {
            UIManager.put("defaultFont", new javax.swing.plaf.FontUIResource(defaultFont.deriveFont(13f)));
        }
        com.formdev.flatlaf.util.UIScale.getUserScaleFactor();
        com.formdev.flatlaf.util.UIScale.setZoomFactor(scalePercent / 100f);
    }

    /**
     * Applies a light or dark FlatLaf theme to existing windows.
     *
     *  <p>向现有窗口应用浅色或深色 FlatLaf 主题。
     *
     * @param effectiveTheme effective theme / 生效主题
     */
    public static void apply(ThemeMode effectiveTheme) {
        install(effectiveTheme);
        com.formdev.flatlaf.FlatLaf.updateUI();
    }

    /** Updates existing controls with confirmed theme and zoom, preserving their state. / 按已确认主题和缩放更新现有控件并保留状态。
     * @param effectiveTheme effective theme / 生效主题
     * @param scalePercent confirmed application zoom / 已确认应用缩放百分比 */
    public static void apply(ThemeMode effectiveTheme, int scalePercent) {
        install(effectiveTheme, scalePercent);
        com.formdev.flatlaf.FlatLaf.updateUI();
    }
}
