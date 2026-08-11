package gold.debug.windowstolinux.app.ui.settings;

import gold.debug.windowstolinux.app.ui.appearance.DesktopAppearance;
import gold.debug.windowstolinux.app.ui.appearance.ThemeMode;
import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Provides the {@code SettingsPage} implementation.
 *
 * <p>提供 {@code SettingsPage} 实现。
 */
public final class SettingsPage {
    private SettingsPage() {
    }

    /**
     * Creates a value through {@code create}.
     *
     * <p>通过 {@code create} 创建值。
     *
     * @param c the {@code c} value / {@code c} 值
     * @param m the {@code m} value / {@code m} 值
     * @param appearance the {@code appearance} value / {@code appearance} 值
     * @param applyAppearance the {@code applyAppearance} value / {@code applyAppearance} 值
     * @return the operation result / 操作结果
     */
    public static JPanel create(DesktopComponents c, MessageCatalog m, DesktopAppearance appearance,
                                Consumer<DesktopAppearance> applyAppearance) {
        JPanel panel = c.pagePanel();
        JPanel cards = c.transparent(new GridLayout(1, 2, 12, 0));
        cards.add(c.informationCard(m.text("settings.credentials.title"), m.text("settings.credentials.body")));
        cards.add(c.informationCard(m.text("settings.boundary.title"), m.text("settings.boundary.body")));
        panel.add(cards, BorderLayout.NORTH);
        JPanel center = c.transparent(new GridLayout(1, 2, 12, 0));
        center.add(c.informationCard(m.text("settings.usage.title"), m.text("settings.usage.body")));
        JPanel appearanceCard = c.card(new BorderLayout(0, 12));
        appearanceCard.add(c.sectionHeading(m.text("settings.appearance.title"), m.text("settings.appearance.body")),
                BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        JComboBox<String> locale = new JComboBox<>(new String[]{
                MessageCatalog.ENGLISH_TAG, MessageCatalog.SIMPLIFIED_CHINESE_TAG
        });
        locale.setSelectedItem(appearance.localeTag());
        locale.setRenderer(localeRenderer(m));
        JComboBox<ThemeMode> theme = new JComboBox<>(ThemeMode.values());
        theme.setSelectedItem(appearance.themeMode());
        theme.setRenderer(themeRenderer(m));
        c.addField(form, 0, 0, m.text("field.language"), locale);
        c.addField(form, 1, 0, m.text("field.appearance"), theme);
        Runnable apply = () -> applyAppearance.accept(new DesktopAppearance(
                (String) locale.getSelectedItem(), (ThemeMode) theme.getSelectedItem()));
        locale.addActionListener(event -> apply.run());
        theme.addActionListener(event -> apply.run());
        appearanceCard.add(form, BorderLayout.CENTER);
        center.add(appearanceCard);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

    private static DefaultListCellRenderer localeRenderer(MessageCatalog messages) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                           boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list,
                        MessageCatalog.SIMPLIFIED_CHINESE_TAG.equals(value)
                                ? messages.text("locale.zhCN") : messages.text("locale.en"),
                        index, isSelected, cellHasFocus);
            }
        };
    }

    private static DefaultListCellRenderer themeRenderer(MessageCatalog messages) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                           boolean isSelected, boolean cellHasFocus) {
                String key = value instanceof ThemeMode mode
                        ? "theme." + mode.name().toLowerCase(Locale.ROOT) : "";
                return super.getListCellRendererComponent(list,
                        key.isEmpty() ? value : messages.text(key), index, isSelected, cellHasFocus);
            }
        };
    }
}
