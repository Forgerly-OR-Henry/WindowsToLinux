package gold.debug.windowstolinux.app.ui.display;

import java.awt.Color;
import java.util.List;

import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.InsetsUIResource;

import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.ui.FlatRoundBorder;

/** Shared control surfaces and interaction states, separate from theme installation and zoom. / 共用控件表面及交互状态，与主题安装及缩放分离。 */
final class DesktopControlDefaults {
    /** Prevents instantiation of the defaults installer. / 防止实例化默认值安装器。 */
    private DesktopControlDefaults() {
    }

    /** Installs controls after FlatLaf has supplied its native delegates. / FlatLaf 提供原生绘制器后安装控件默认值。
     * @param theme effective light or dark appearance / 生效浅色或深色外观 */
    static void install(ThemeMode theme) {
        geometry();
        colors(theme);
    }

    /** Installs shared geometry without changing control or window dimensions. / 安装共用形状，不改变控件或窗口尺寸。 */
    private static void geometry() {
        UIManager.put("Component.arc", 14);
        UIManager.put("Button.arc", 14);
        UIManager.put("TextComponent.arc", 14);
        UIManager.put("Component.focusWidth", 0);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.innerOutlineWidth", 0);
        UIManager.put("Component.borderWidth", 0);
        UIManager.put("Button.borderWidth", 0);
        UIManager.put("Button.innerFocusWidth", 0);
        UIManager.put("Button.default.borderWidth", 0);
        UIManager.put("Button.toolbar.focusWidth", 0);
        UIManager.put("ComboBoxUI", DesktopComboBoxUi.class.getName());
        UIManager.put("TextAreaUI", DesktopTextAreaUi.class.getName());
        UIManager.put("ListUI", DesktopListUi.class.getName());
        UIManager.put("TableUI", DesktopTableUi.class.getName());
        UIManager.put("ScrollPaneUI", DesktopScrollPaneUi.class.getName());
        UIManager.put("ScrollBarUI", DesktopScrollBarUi.class.getName());
        UIManager.put("Table.rowHeight", 32);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 4));
        UIManager.put("Table.cellMargins", new InsetsUIResource(4, 12, 4, 12));
        UIManager.put("ComboBox.border", (UIDefaults.ActiveValue) defaults -> {
            FlatRoundBorder border = new FlatRoundBorder();
            border.applyStyleProperty("borderWidth", 0f);
            return border;
        });
        UIManager.put("ComboBox.padding", new InsetsUIResource(5, 10, 5, 8));
        UIManager.put("ComboBox.buttonStyle", "none");
        UIManager.put("ComboBox.popupInsets", new InsetsUIResource(5, 4, 5, 4));
        for (String type : List.of("ComboBox", "List", "MenuItem")) {
            UIManager.put(type + ".selectionInsets", new InsetsUIResource(2, 4, 2, 4));
            UIManager.put(type + ".selectionArc", 12);
        }
        for (String type : List.of("Popup", "PopupMenu", "ComboBox", "ToolTip"))
            UIManager.put(type + ".borderCornerRadius", 10);
        // FlatLaf uses empty borders to suppress the Windows-native popup outline. / FlatLaf 使用空边框关闭 Windows 原生弹层描边。
        UIManager.put("PopupMenu.border", new FlatEmptyBorder(4, 1, 4, 1));
        UIManager.put("ToolTip.border", new FlatEmptyBorder(4, 6, 4, 6));
        UIManager.put("ScrollBar.width", 14);
        UIManager.put("ScrollBar.trackInsets", new InsetsUIResource(2, 2, 2, 2));
        UIManager.put("ScrollBar.thumbInsets", new InsetsUIResource(3, 3, 3, 3));
        UIManager.put("ScrollBar.trackArc", 999);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("ScrollPane.border", new FlatEmptyBorder(0, 0, 0, 0));
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        var noOutline = color(new Color(0, 0, 0, 0));
        UIManager.put("TableHeader.bottomSeparatorColor", noOutline);
        UIManager.put("TableHeader.separatorColor", noOutline);
        UIManager.put("Table.focusCellHighlightBorder", new FlatEmptyBorder(0, 0, 0, 0));
        UIManager.put("Table.focusSelectedCellHighlightBorder", new FlatEmptyBorder(0, 0, 0, 0));
        var listMargins = UIManager.getInsets("List.cellMargins");
        UIManager.put("List.focusCellHighlightBorder", new FlatEmptyBorder(listMargins));
        UIManager.put("List.focusSelectedCellHighlightBorder", new FlatEmptyBorder(listMargins));
        UIManager.put("List.showCellFocusIndicator", false);
        UIManager.put("TitledBorder.border", new FlatEmptyBorder(0, 0, 0, 0));
        UIManager.put("SplitPane.border", new FlatEmptyBorder(0, 0, 0, 0));
        UIManager.put("TabbedPane.contentSeparatorHeight", 0);
        UIManager.put("Spinner.buttonSeparatorColor", noOutline);
        UIManager.put("CheckBox.icon.borderWidth", 0);
        UIManager.put("CheckBox.icon.focusWidth", 0);
        UIManager.put("CheckBox.icon", new DesktopSwitchIcon());
        UIManager.put("RadioButton.icon.borderWidth", 0);
        UIManager.put("RadioButton.icon.focusWidth", 0);
    }

    /** Installs surfaces and visible hover, pressed and disabled states from one palette. / 根据同一配色安装表面及可辨识的悬停、按下和禁用状态。
     * @param theme effective light or dark appearance / 生效浅色或深色外观 */
    private static void colors(ThemeMode theme) {
        boolean dark = theme == ThemeMode.DARK;
        ThemePalette palette = dark ? ThemePalette.dark() : ThemePalette.light();
        var buttonBackground = color(palette.secondaryButtonBackground());
        var buttonForeground = color(palette.secondaryButtonForeground());
        var buttonHover = color(dark ? new Color(66, 77, 95) : new Color(214, 224, 242));
        var buttonPressed = color(palette.navigationActive());
        var inputBackground = buttonBackground;
        var disabledBackground = buttonBackground;
        var disabledForeground = color(dark ? new Color(125, 134, 149) : new Color(143, 149, 159));
        for (String type : List.of("Button", "Button.default", "ToggleButton")) {
            UIManager.put(type + ".background", buttonBackground);
            UIManager.put(type + ".foreground", buttonForeground);
            UIManager.put(type + ".focusedBackground", buttonBackground);
            UIManager.put(type + ".hoverBackground", buttonHover);
            UIManager.put(type + ".pressedBackground", buttonPressed);
        }
        for (String type : List.of("Button", "ToggleButton")) {
            UIManager.put(type + ".disabledBackground", disabledBackground);
            UIManager.put(type + ".disabledSelectedBackground", disabledBackground);
            UIManager.put(type + ".disabledText", disabledForeground);
            UIManager.put(type + ".disabledSelectedForeground", disabledForeground);
        }
        UIManager.put("CheckBox.icon.background", buttonBackground);
        UIManager.put("RadioButton.icon.background", buttonBackground);
        for (String surface : List.of("Panel", "Viewport", "ScrollPane", "TabbedPane", "ToolBar", "MenuBar",
            "SplitPane", "TableHeader", "TitlePane"))
            UIManager.put(surface + ".background", color(palette.pageBackground()));
        UIManager.put("TitlePane.inactiveBackground", color(palette.pageBackground()));
        for (String surface : List.of("Tree", "PopupMenu", "Menu", "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem", "ToolTip")) {
            UIManager.put(surface + ".background", color(palette.cardBackground()));
            UIManager.put(surface + ".foreground", color(palette.sidebarForeground()));
        }
        UIManager.put("Tree.textBackground", color(palette.cardBackground()));
        UIManager.put("Tree.textForeground", color(palette.sidebarForeground()));
        for (String surface : List.of("List", "Table")) {
            UIManager.put(surface + ".background", color(palette.cardBackground()));
            UIManager.put(surface + ".foreground", color(palette.sidebarForeground()));
            var selected = color(dark ? palette.navigationActive() : new Color(231, 242, 255));
            UIManager.put(surface + ".selectionBackground", selected);
            UIManager.put(surface + ".selectionInactiveBackground", selected);
            UIManager.put(surface + ".selectionForeground", color(palette.sidebarForeground()));
            UIManager.put(surface + ".selectionInactiveForeground", color(palette.sidebarForeground()));
        }
        UIManager.put("List.cardBackground", color(palette.cardBackground()));
        UIManager.put("List.cardOutline", color(palette.emphasisBackground()));
        UIManager.put("List.background", color(palette.pageBackground()));
        UIManager.put("Label.foreground", color(palette.sidebarForeground()));
        UIManager.put("Component.borderColor", color(palette.inputBorder()));
        UIManager.put("Component.focusColor", color(palette.accent()));
        for (String type : List.of("TextField", "PasswordField", "FormattedTextField", "TextArea", "TextPane", "EditorPane", "ComboBox", "Spinner")) {
            UIManager.put(type + ".background", inputBackground);
            UIManager.put(type + ".foreground", color(palette.sidebarForeground()));
            UIManager.put(type + ".disabledBackground", disabledBackground);
            UIManager.put(type + ".inactiveBackground", disabledBackground);
        }
        UIManager.put("TextField.searchBackground", inputBackground);
        UIManager.put("ComboBox.buttonBackground", inputBackground);
        UIManager.put("ComboBox.buttonFocusedBackground", inputBackground);
        UIManager.put("ComboBox.buttonArrowColor", color(palette.subduedText()));
        UIManager.put("ComboBox.buttonHoverArrowColor", color(palette.accent()));
        UIManager.put("ComboBox.buttonPressedArrowColor", color(palette.accentDark()));
        UIManager.put("ComboBox.popupBackground", color(palette.cardBackground()));
        UIManager.put("ComboBox.selectionBackground", color(palette.navigationActive()));
        UIManager.put("ComboBox.selectionForeground", color(palette.sidebarForeground()));
        UIManager.put("ComboBox.disabledBackground", disabledBackground);
        UIManager.put("ComboBox.disabledForeground", disabledForeground);
        UIManager.put("ScrollBar.background", color(palette.pageBackground()));
        UIManager.put("ScrollBar.track", color(palette.pageBackground()));
        UIManager.put("ScrollBar.thumb", color(palette.emphasisBackground()));
        UIManager.put("ScrollBar.hoverThumbColor", buttonHover);
        UIManager.put("ScrollBar.pressedThumbColor", buttonPressed);
    }

    /** Marks palette colors as replaceable by live Swing theme updates. / 将配色标记为可由 Swing 实时主题更新替换。
     * @param value palette color / 配色值
     * @return Swing theme resource / Swing 主题资源 */
    private static ColorUIResource color(Color value) {
        return new ColorUIResource(value);
    }
}
