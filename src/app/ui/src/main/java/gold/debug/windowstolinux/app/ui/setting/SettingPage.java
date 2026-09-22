package gold.debug.windowstolinux.app.ui.setting;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.util.Locale;
import java.util.function.Consumer;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;

/**
 * Owns appearance controls and their immediate application workflow. / 持有外观控件与即时应用流程。
 */
public final class SettingPage {
    /**
     * Swing control for panel.
     * <p>面板对应的 Swing 控件。
     */
    private final JPanel panel;

    /**
     * Creates the stateful settings controller. / 创建有状态设置控制器。
     *
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param messages localized message resolver / 本地化消息解析器
     * @param appearance appearance / 外观
     * @param applyAppearance apply appearance / 应用外观
     */
    public SettingPage(DesktopComponentFactory c, PageMessagePresenter messages, DesktopDisplayConfiguration appearance,
            Consumer<DesktopDisplayConfiguration> applyAppearance) {
        this(c, messages, appearance, applyAppearance, null);
    }

    /**
     * Adds the optional Class-only preview action supplied by the startup layer. / 添加启动层提供的可选 Class 模式预览入口。
     *
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param messages localized message resolver / 本地化消息解析器
     * @param appearance appearance / 外观
     * @param applyAppearance apply appearance / 应用外观
     * @param openUiDebug open ui debug / 打开界面Debug
     */
    public SettingPage(DesktopComponentFactory c, PageMessagePresenter messages, DesktopDisplayConfiguration appearance,
            Consumer<DesktopDisplayConfiguration> applyAppearance, Runnable openUiDebug) {
        JPanel page = c.pagePanel();
        var advanced = new gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane(page, c, messages);
        panel = advanced;
        JPanel cards = c.transparent(new GridLayout(0, 1, 0, 12));
        cards.add(c.informationCard(messages.text("settings.credentials.title"),
                messages.text("settings.credentials.body")));
        cards.add(c.informationCard(messages.text("settings.boundary.title"), messages.text("settings.boundary.body")));
        advanced.addOption(cards);
        JPanel center = c.transparent(new GridLayout(2, 1, 0, 12));
        center.add(c.informationCard(messages.text("settings.usage.title"), messages.text("settings.usage.body")));
        JPanel appearanceCard = c.card(new BorderLayout(0, 12));
        appearanceCard.add(
                c.sectionHeading(messages.text("settings.appearance.title"), messages.text("settings.appearance.body")),
                BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        JComboBox<String> locale = new JComboBox<>(
                new String[]{MessageCatalog.ENGLISH_TAG, MessageCatalog.SIMPLIFIED_CHINESE_TAG});
        locale.setSelectedItem(appearance.localeTag());
        locale.setRenderer(localeRenderer(messages));
        JComboBox<ThemeMode> theme = new JComboBox<>(ThemeMode.values());
        theme.setSelectedItem(appearance.themeMode());
        theme.setRenderer(themeRenderer(messages));
        locale.setName("settings.language");
        theme.setName("settings.theme");
        int choiceWidth = Math.max(UIScale.scale(220),
                Math.max(locale.getPreferredSize().width, theme.getPreferredSize().width));
        for (JComboBox<?> choice : java.util.List.of(locale, theme)) {
            choice.putClientProperty(FlatClientProperties.STYLE,
                    "background: $Button.background; buttonBackground: $Button.background; buttonFocusedBackground: $Button.background; borderWidth: 0");
            choice.setPreferredSize(
                    new Dimension(choiceWidth, Math.max(UIScale.scale(34), choice.getPreferredSize().height)));
        }
        c.addField(form, 0, 0, messages.text("field.language"), locale);
        c.addField(form, 1, 0, messages.text("field.appearance"), theme);
        java.util.concurrent.atomic.AtomicBoolean applying = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable apply = () -> {
            if (applying.getAndSet(true))
                return;
            try {
                if (gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor.hasActiveTasks()) {
                    locale.setSelectedItem(appearance.localeTag());
                    theme.setSelectedItem(appearance.themeMode());
                    javax.swing.JOptionPane.showMessageDialog(panel, messages.text("auto.appearance.busy"));
                    return;
                }
                applyAppearance.accept(new DesktopDisplayConfiguration((String) locale.getSelectedItem(),
                        (ThemeMode) theme.getSelectedItem()));
            } finally {
                applying.set(false);
            }
        };
        locale.addActionListener(event -> apply.run());
        theme.addActionListener(event -> apply.run());
        JPanel compactForm = c.transparent(new BorderLayout());
        compactForm.add(form, BorderLayout.NORTH);
        appearanceCard.add(compactForm, BorderLayout.WEST);
        JPanel diagnostics = c.transparent(new BorderLayout(0, 6));
        diagnostics.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 40));
        JLabel diagnosticPath = new JLabel(messages.diagnosticsPath());
        diagnosticPath.setToolTipText(messages.diagnosticsPath());
        JButton openDiagnostics = c.secondaryButton(messages.text("settings.diagnostics.open"));
        openDiagnostics.addActionListener(event -> {
            if (!messages.openDiagnosticsDirectory()) {
                diagnosticPath.setText(messages.diagnosticsPath());
            }
        });
        diagnostics.add(diagnosticPath, BorderLayout.CENTER);
        diagnostics.add(openDiagnostics, BorderLayout.EAST);
        advanced.addOption(diagnostics);
        if (openUiDebug != null) {
            JButton debug = c.secondaryButton(messages.text("settings.debug.open"));
            debug.setName("settings.uiDebug");
            debug.setToolTipText(messages.text("settings.debug.hint"));
            debug.addActionListener(event -> openUiDebug.run());
            advanced.addOption(debug);
        }
        center.add(appearanceCard);
        page.add(center, BorderLayout.CENTER);
    }

    /**
     * Returns the page panel. / 返回页面面板。
     *
     * @return the page panel / 页面面板
     */
    public JPanel panel() {
        return panel;
    }

    /**
     * Captures settings state; controls apply immediately. / 捕获设置状态；控件即时应用。
     *
     * @return constructed or resolved setting page state / 构造或解析得到的Setting页面状态
     */
    public SettingPageState captureState() {
        return new SettingPageState();
    }

    /**
     * Restores settings state; no unsaved value exists. / 恢复设置状态；不存在未保存值。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void restoreState(SettingPageState state) {
        java.util.Objects.requireNonNull(state, "state");
    }

    /**
     * Builds default list cell renderer from the supplied locale renderer inputs.
     * <p>根据所提供区域渲染器输入构建默认列表Cell渲染器。
     *
     * @param messages localized message resolver / 本地化消息解析器
     * @return default list cell renderer from the supplied locale renderer inputs / 根据所提供区域渲染器输入构建默认列表Cell渲染器
     */
    private static DefaultListCellRenderer localeRenderer(PageMessagePresenter messages) {
        return new DefaultListCellRenderer() {
            /**
             * Returns list cell renderer component.
             * <p>返回列表Cell渲染器组件。
             *
             * @param list list / 列表
             * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
             * @param index index / 索引
             * @param selected explicitly selected item or state / 显式选择的项目或状态
             * @param focus focus / 焦点
             * @return list cell renderer component / 列表Cell渲染器组件
             */
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                String languageTag = MessageCatalog.normalizeLanguageTag((String) value);
                String key = "locale." + languageTag;
                String label = messages.text("locale.option", java.util.Map.of("native",
                        MessageCatalog.forLanguageTag(languageTag).text(key), "translated", messages.text(key)));
                return super.getListCellRendererComponent(list, label, index, selected, focus);
            }
        };
    }

    /**
     * Builds default list cell renderer from the supplied theme renderer inputs.
     * <p>根据所提供主题渲染器输入构建默认列表Cell渲染器。
     *
     * @param messages localized message resolver / 本地化消息解析器
     * @return default list cell renderer from the supplied theme renderer inputs / 根据所提供主题渲染器输入构建默认列表Cell渲染器
     */
    private static DefaultListCellRenderer themeRenderer(PageMessagePresenter messages) {
        return new DefaultListCellRenderer() {
            /**
             * Returns list cell renderer component.
             * <p>返回列表Cell渲染器组件。
             *
             * @param list list / 列表
             * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
             * @param index index / 索引
             * @param selected explicitly selected item or state / 显式选择的项目或状态
             * @param focus focus / 焦点
             * @return list cell renderer component / 列表Cell渲染器组件
             */
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                String key = value instanceof ThemeMode mode ? "theme." + mode.name().toLowerCase(Locale.ROOT) : "";
                return super.getListCellRendererComponent(list, key.isEmpty() ? value : messages.text(key), index,
                        selected, focus);
            }
        };
    }
}
