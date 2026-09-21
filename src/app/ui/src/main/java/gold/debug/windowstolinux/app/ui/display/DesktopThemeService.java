package gold.debug.windowstolinux.app.ui.display;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.ui.FlatRoundBorder;

import javax.swing.UIManager;
import java.awt.Font;
import java.awt.Color;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.InsetsUIResource;

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

        ThemePalette palette = effectiveTheme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
        ColorUIResource buttonBackground = new ColorUIResource(palette.secondaryButtonBackground());
        ColorUIResource buttonForeground = new ColorUIResource(palette.secondaryButtonForeground());
        ColorUIResource disabledButtonBackground = new ColorUIResource(effectiveTheme == ThemeMode.DARK
                ? new Color(38, 45, 56) : new Color(239, 241, 245));
        ColorUIResource disabledButtonText = new ColorUIResource(effectiveTheme == ThemeMode.DARK
                ? new Color(125, 134, 149) : new Color(143, 149, 159));
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
        UIManager.put("Label.foreground", palette.sidebarForeground());
        UIManager.put("Component.borderColor", palette.inputBorder());
        UIManager.put("Component.focusColor", palette.accent());
        for (String type : java.util.List.of("TextField", "PasswordField", "FormattedTextField", "TextArea", "ComboBox", "Spinner")) {
            UIManager.put(type + ".background", palette.cardBackground());
            UIManager.put(type + ".foreground", palette.sidebarForeground());
        }
        UIManager.put("ComboBox.padding", new InsetsUIResource(5, 10, 5, 8));
        UIManager.put("ComboBox.buttonStyle", "none");
        UIManager.put("ComboBox.buttonBackground", palette.cardBackground());
        UIManager.put("ComboBox.buttonFocusedBackground", palette.cardBackground());
        UIManager.put("ComboBox.buttonArrowColor", palette.subduedText());
        UIManager.put("ComboBox.buttonHoverArrowColor", palette.accent());
        UIManager.put("ComboBox.buttonPressedArrowColor", palette.accentDark());
        UIManager.put("ComboBox.popupBackground", palette.cardBackground());
        UIManager.put("ComboBox.popupInsets", new InsetsUIResource(5, 4, 5, 4));
        UIManager.put("ComboBox.selectionInsets", new InsetsUIResource(1, 2, 1, 2));
        UIManager.put("ComboBox.selectionArc", 10);
        UIManager.put("ComboBox.selectionBackground", palette.navigationActive());
        UIManager.put("ComboBox.selectionForeground", palette.sidebarForeground());
        UIManager.put("ComboBox.borderCornerRadius", 10);
        UIManager.put("ComboBox.disabledBackground", palette.secondaryButtonBackground());
        UIManager.put("ComboBox.disabledForeground", palette.subduedText());

        Font defaultFont = UIManager.getFont("defaultFont");
        if (defaultFont != null) {
            UIManager.put("defaultFont", defaultFont.deriveFont(13f));
        }
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
}
