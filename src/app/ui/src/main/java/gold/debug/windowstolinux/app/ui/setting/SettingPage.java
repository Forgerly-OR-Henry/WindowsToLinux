package gold.debug.windowstolinux.app.ui.setting;

import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.util.Locale;
import java.util.function.Consumer;

/** Owns appearance controls and their immediate application workflow. / 持有外观控件与即时应用流程。 */
public final class SettingPage {
    private final JPanel panel;

    /** Creates the stateful settings controller. / 创建有状态设置控制器。 */
    public SettingPage(DesktopComponentFactory c, PageMessagePresenter messages, DesktopDisplayConfiguration appearance,
                        Consumer<DesktopDisplayConfiguration> applyAppearance) {
        panel = c.pagePanel();
        JPanel cards = c.transparent(new GridLayout(1, 2, 12, 0));
        cards.add(c.informationCard(messages.text("settings.credentials.title"), messages.text("settings.credentials.body")));
        cards.add(c.informationCard(messages.text("settings.boundary.title"), messages.text("settings.boundary.body")));
        panel.add(cards, BorderLayout.NORTH);
        JPanel center = c.transparent(new GridLayout(1, 2, 12, 0));
        center.add(c.informationCard(messages.text("settings.usage.title"), messages.text("settings.usage.body")));
        JPanel appearanceCard = c.card(new BorderLayout(0, 12));
        appearanceCard.add(c.sectionHeading(messages.text("settings.appearance.title"),
                messages.text("settings.appearance.body")), BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        JComboBox<String> locale = new JComboBox<>(new String[]{MessageCatalog.ENGLISH_TAG,
                MessageCatalog.SIMPLIFIED_CHINESE_TAG});
        locale.setSelectedItem(appearance.localeTag());
        locale.setRenderer(localeRenderer(messages));
        JComboBox<ThemeMode> theme = new JComboBox<>(ThemeMode.values());
        theme.setSelectedItem(appearance.themeMode());
        theme.setRenderer(themeRenderer(messages));
        c.addField(form, 0, 0, messages.text("field.language"), locale);
        c.addField(form, 1, 0, messages.text("field.appearance"), theme);
        Runnable apply = () -> applyAppearance.accept(new DesktopDisplayConfiguration(
                (String) locale.getSelectedItem(), (ThemeMode) theme.getSelectedItem()));
        locale.addActionListener(event -> apply.run());
        theme.addActionListener(event -> apply.run());
        appearanceCard.add(form, BorderLayout.CENTER);
        center.add(appearanceCard);
        panel.add(center, BorderLayout.CENTER);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() { return panel; }
    /** Captures settings state; controls apply immediately. / 捕获设置状态；控件即时应用。 */
    public SettingPageState captureState() { return new SettingPageState(); }
    /** Restores settings state; no unsaved value exists. / 恢复设置状态；不存在未保存值。 */
    public void restoreState(SettingPageState state) { java.util.Objects.requireNonNull(state, "state"); }

    private static DefaultListCellRenderer localeRenderer(PageMessagePresenter messages) {
        return new DefaultListCellRenderer() {
            /** Performs the {@code getListCellRendererComponent} operation. / 执行 {@code getListCellRendererComponent} 操作。 */
            @Override public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list,
                        MessageCatalog.SIMPLIFIED_CHINESE_TAG.equals(value) ? messages.text("locale.zhCN")
                                : messages.text("locale.en"), index, selected, focus);
            }
        };
    }

    private static DefaultListCellRenderer themeRenderer(PageMessagePresenter messages) {
        return new DefaultListCellRenderer() {
            /** Performs the {@code getListCellRendererComponent} operation. / 执行 {@code getListCellRendererComponent} 操作。 */
            @Override public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                String key = value instanceof ThemeMode mode ? "theme." + mode.name().toLowerCase(Locale.ROOT) : "";
                return super.getListCellRendererComponent(list, key.isEmpty() ? value : messages.text(key),
                        index, selected, focus);
            }
        };
    }
}
