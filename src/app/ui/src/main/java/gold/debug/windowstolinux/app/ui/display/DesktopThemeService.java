package gold.debug.windowstolinux.app.ui.display;

import java.awt.Color;
import java.awt.Font;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.InsetsUIResource;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.ui.FlatRoundBorder;

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
        installControlDefaults();

        ThemePalette palette = effectiveTheme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
        ColorUIResource buttonBackground = new ColorUIResource(palette.secondaryButtonBackground());
        ColorUIResource buttonForeground = new ColorUIResource(palette.secondaryButtonForeground());
        ColorUIResource disabledButtonBackground = new ColorUIResource(
            effectiveTheme == ThemeMode.DARK ? new Color(38, 45, 56) : new Color(239, 241, 245));
        ColorUIResource disabledButtonText = new ColorUIResource(
            effectiveTheme == ThemeMode.DARK ? new Color(125, 134, 149) : new Color(143, 149, 159));
        UIManager.put("Button.background", buttonBackground);
        UIManager.put("Button.foreground", buttonForeground);
        UIManager.put("Button.focusedBackground", buttonBackground);
        UIManager.put("Button.default.background", buttonBackground);
        UIManager.put("Button.default.foreground", buttonForeground);
        UIManager.put("Button.default.focusedBackground", buttonBackground);
        UIManager.put("Button.disabledBackground", disabledButtonBackground);
        UIManager.put("Button.disabledSelectedBackground", disabledButtonBackground);
        UIManager.put("Button.disabledText", disabledButtonText);
        UIManager.put("Button.disabledSelectedForeground", disabledButtonText);
        UIManager.put("CheckBox.icon.background", buttonBackground);
        UIManager.put("RadioButton.icon.background", buttonBackground);
        for (String surface : java.util.List.of("Panel", "Viewport", "ScrollPane", "TabbedPane"))
            UIManager.put(surface + ".background", new ColorUIResource(palette.pageBackground()));
        for (String surface : java.util.List.of("List", "Table")) {
            UIManager.put(surface + ".background", new ColorUIResource(palette.cardBackground()));
            UIManager.put(surface + ".foreground", new ColorUIResource(palette.sidebarForeground()));
            UIManager.put(surface + ".selectionBackground", new ColorUIResource(palette.navigationActive()));
            UIManager.put(surface + ".selectionForeground", new ColorUIResource(palette.sidebarForeground()));
        }
        UIManager.put("Label.foreground", new ColorUIResource(palette.sidebarForeground()));
        UIManager.put("Component.borderColor", new ColorUIResource(palette.inputBorder()));
        UIManager.put("Component.focusColor", new ColorUIResource(palette.accent()));
        for (String type : java.util.List.of("TextField", "PasswordField", "FormattedTextField", "TextArea", "ComboBox",
            "Spinner")) {
            UIManager.put(type + ".background", new ColorUIResource(palette.secondaryButtonBackground()));
            UIManager.put(type + ".foreground", new ColorUIResource(palette.sidebarForeground()));
        }
        UIManager.put("ComboBox.padding", new InsetsUIResource(5, 10, 5, 8));
        UIManager.put("ComboBox.buttonStyle", "none");
        UIManager.put("ComboBox.buttonBackground", new ColorUIResource(palette.secondaryButtonBackground()));
        UIManager.put("ComboBox.buttonFocusedBackground", new ColorUIResource(palette.secondaryButtonBackground()));
        UIManager.put("ComboBox.buttonArrowColor", new ColorUIResource(palette.subduedText()));
        UIManager.put("ComboBox.buttonHoverArrowColor", new ColorUIResource(palette.accent()));
        UIManager.put("ComboBox.buttonPressedArrowColor", new ColorUIResource(palette.accentDark()));
        UIManager.put("ComboBox.popupBackground", new ColorUIResource(palette.cardBackground()));
        UIManager.put("ComboBox.popupInsets", new InsetsUIResource(5, 4, 5, 4));
        UIManager.put("ComboBox.selectionInsets", new InsetsUIResource(1, 2, 1, 2));
        UIManager.put("ComboBox.selectionArc", 10);
        UIManager.put("ComboBox.selectionBackground", new ColorUIResource(palette.navigationActive()));
        UIManager.put("ComboBox.selectionForeground", new ColorUIResource(palette.sidebarForeground()));
        UIManager.put("ComboBox.borderCornerRadius", 10);
        UIManager.put("ComboBox.disabledBackground", new ColorUIResource(palette.secondaryButtonBackground()));
        UIManager.put("ComboBox.disabledForeground", new ColorUIResource(palette.subduedText()));

        Font defaultFont = UIManager.getFont("defaultFont");
        if (defaultFont != null) {
            UIManager.put("defaultFont", new javax.swing.plaf.FontUIResource(defaultFont.deriveFont(13f)));
        }
        com.formdev.flatlaf.util.UIScale.getUserScaleFactor();
        com.formdev.flatlaf.util.UIScale.setZoomFactor(scalePercent / 100f);
    }

    /** Installs shape, borders and delegates shared by both themes. / 安装浅深主题共用的形状、边框及委托。 */
    private static void installControlDefaults() {
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.borderWidth", 0);
        UIManager.put("Button.borderWidth", 0);
        UIManager.put("Button.default.borderWidth", 0);
        UIManager.put("ComboBoxUI", DesktopComboBoxUi.class.getName());
        UIManager.put("ComboBox.border", (javax.swing.UIDefaults.ActiveValue) defaults -> {
            FlatRoundBorder border = new FlatRoundBorder();
            border.applyStyleProperty("borderWidth", 0f);
            return border;
        });
        // Empty borders also suppress the Windows-native popup outline while retaining rounded corners. / 空边框同时关闭 Windows 原生弹层描边，保留圆角。
        UIManager.put("PopupMenu.border", new FlatEmptyBorder(4, 1, 4, 1));
        UIManager.put("ToolTip.border", new FlatEmptyBorder(4, 6, 4, 6));
        UIManager.put("ScrollBar.width", 14);
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("ScrollPane.border", new FlatEmptyBorder(0, 0, 0, 0));
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        var noOutline = new ColorUIResource(new Color(0, 0, 0, 0));
        UIManager.put("TableHeader.bottomSeparatorColor", noOutline);
        UIManager.put("TableHeader.separatorColor", noOutline);
        UIManager.put("Table.focusCellHighlightBorder", new FlatEmptyBorder(0, 0, 0, 0));
        UIManager.put("Table.focusSelectedCellHighlightBorder", new FlatEmptyBorder(0, 0, 0, 0));
        UIManager.put("TitledBorder.border", new FlatEmptyBorder(0, 0, 0, 0));
        UIManager.put("SplitPane.border", new FlatEmptyBorder(0, 0, 0, 0));
        UIManager.put("TabbedPane.contentSeparatorHeight", 0);
        UIManager.put("Spinner.buttonSeparatorColor", noOutline);
        UIManager.put("CheckBox.icon.borderWidth", 0);
        UIManager.put("CheckBox.icon", new gold.debug.windowstolinux.app.ui.display.DesktopSwitchIcon());
        UIManager.put("RadioButton.icon.borderWidth", 0);

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
