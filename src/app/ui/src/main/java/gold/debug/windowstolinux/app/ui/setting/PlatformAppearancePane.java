package gold.debug.windowstolinux.app.ui.setting;

import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.util.Objects;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfig;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;

/** Persists explicit appearance choices without recreating runtime or business pages. / 持久化明确的外观选择，不重建运行器或业务页面。 */
public final class PlatformAppearancePane extends JPanel implements AutoCloseable {
    /** Supported language choices, independent of display labels. / 独立于显示标签的受支持语言选项。 */
    private final JComboBox<String> language = new JComboBox<>(new String[]{"zh-CN", "en"});

    /** Stored theme preference, including system selection. / 包含系统选项的已存主题偏好。 */
    private final JComboBox<ThemeMode> theme = new JComboBox<>(ThemeMode.values());

    /** Application zoom choices relative to system scaling. / 相对于系统缩放的应用缩放选项。 */
    private final JComboBox<Integer> scale = new JComboBox<>(new Integer[]{100, 150, 200});

    /** Explicit next-start page restoration choice. / 明确的下次启动页面恢复选择。 */
    private final JCheckBox restoreLastPage = new JCheckBox();

    /** Form labels whose explicit sizes follow live zoom. / 明确尺寸跟随实时缩放的表单标签。 */
    private JPanel fields;

    /** Complete preference page inside one vertical viewport. / 一个纵向视口中的完整偏好页面。 */
    private final JPanel body;

    /** Explicit preference confirmation. / 明确的偏好确认。 */
    private final JButton save;

    /** Current preference feedback. / 当前偏好反馈。 */
    private final JLabel status = new JLabel();

    /** Current message resolver. / 当前消息解析器。 */
    private final PageMessagePresenter messages;

    /** Original persistence operation outside the EDT. / 事件线程外的原持久化操作。 */
    private final Preferences preferences;

    /** Applies only a successfully persisted selection. / 仅应用成功持久化的选择。 */
    private final Consumer<DesktopDisplayConfig> applied;

    /** Privacy, lifetime and pending write gates. / 隐私、寿命和待写门禁。 */
    private boolean locked, closed, busy;

    /** Builds language and theme drafts from confirmed preferences. / 根据已确认偏好构造语言和主题草稿。
     * @param initial confirmed display preference / 已确认显示偏好
     * @param preferences original persistence operation / 原持久化操作
     * @param applied callback retaining the original window / 保留原窗口的回调
     * @param components current component factory / 当前组件工厂
     * @param messages current message presenter / 当前消息展示器 */
    public PlatformAppearancePane(DesktopDisplayConfig initial, Preferences preferences,
                                  Consumer<DesktopDisplayConfig> applied, DesktopPartFactory components,
                                  PageMessagePresenter messages) {
        super(new BorderLayout(12, 12));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Appearance controls require the EDT");
        this.messages = Objects.requireNonNull(messages);
        this.preferences = Objects.requireNonNull(preferences);
        this.applied = Objects.requireNonNull(applied);
        setName("platform.appearance");
        components.stylePage(this);
        body = components.pagePanel();
        body.setLayout(new BorderLayout(12, 12));
        body.setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 24, 20, 24));
        language.setName("appearance.language");
        theme.setName("appearance.theme");
        scale.setName("appearance.scale");
        language.setSelectedItem(MessageCatalog.normalizeLanguageTag(initial.localeTag()));
        theme.setSelectedItem(initial.themeMode());
        scale.setSelectedItem(initial.scalePercent());
        restoreLastPage.setName("appearance.restoreLastPage");
        restoreLastPage.setSelected(initial.restoreLastPage());
        messages.bind(restoreLastPage, "appearance.restoreLastPage");
        gold.debug.windowstolinux.app.ui.language.LocaleBinding.tooltip(restoreLastPage,
            gold.debug.windowstolinux.app.ui.language.LocalizedMessage.of("appearance.restoreLastPage"), messages.catalog());
        var renderer = language.getRenderer();
        language.setRenderer((list, value, index, selected, focus) -> {
            String key = "zh-CN".equals(value) ? "appearance.language.zh" : "appearance.language.en";
            String label = messages.text("appearance.language.option", java.util.Map.of("native",
                MessageCatalog.forLanguageTag("zh-CN".equals(value) ? "zh-CN" : "en").text(key), "translated", messages.text(key)));
            return renderer.getListCellRendererComponent(list, label, index, selected, focus);
        });
        messages.localize(theme, "theme.");
        var scaleRenderer = scale.getRenderer();
        scale.setRenderer((list, value, index, selected, focus) -> {
            var label = (JLabel) scaleRenderer.getListCellRendererComponent(list, value, index, selected, focus);
            label.setText(value == null ? "" : value + "%");
            return label;
        });
        for (var field : new JComboBox<?>[]{language, theme, scale}) {
            field.putClientProperty("FlatLaf.style", "arc: 8; borderWidth: 0; focusWidth: 1; "
                + "background: $Panel.background; buttonBackground: $Panel.background; "
                + "buttonFocusedBackground: $Panel.background; buttonArrowColor: $Label.foreground");
        }
        save = messages.bind(components.primaryButton(""), "appearance.save");
        save.setName("appearance.save");
        save.addActionListener(event -> save());
        fields = components.transparent(new GridBagLayout());
        components.addField(fields, 0, 0, messages, "field.language", language);
        components.addField(fields, 1, 0, messages, "field.appearance", theme);
        components.addField(fields, 2, 0, messages, "field.scale", scale);
        components.addField(fields, 3, 0, messages, "field.startup", restoreLastPage);
        sizing();
        var editor = components.card(new BorderLayout(12, 12));
        editor.add(components.sectionHeading(messages, "settings.appearance.title", "settings.appearance.body"),
            BorderLayout.NORTH);
        editor.add(fields, BorderLayout.CENTER);
        var footer = components.transparent(new BorderLayout(12, 12));
        footer.add(save, BorderLayout.WEST);
        footer.add(status, BorderLayout.CENTER);
        editor.add(footer, BorderLayout.SOUTH);
        body.add(editor, BorderLayout.NORTH);
        var scroll = new javax.swing.JScrollPane(body, javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setName("appearance.scroll");
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(com.formdev.flatlaf.util.UIScale.scale(20));
        add(scroll, BorderLayout.CENTER);
    }

    /** Keeps entrance ordering in the same complete scrollable page. / 入口排序保持在同一完整可滚动页面。
     * @param ordering existing order controls / 既有排序控件 */
    public void ordering(javax.swing.JComponent ordering) {
        body.add(ordering, BorderLayout.CENTER);
    }

    /** Recalculates explicit form sizes after a live zoom or theme change. / 实时缩放或主题变化后重新计算明确的表单尺寸。 */
    @Override
    public void updateUI() {
        super.updateUI();
        if (fields != null) sizing();
    }

    /** Keeps display fields and their captions aligned at each zoom. / 各缩放下保持显示控件和标签对齐。 */
    private void sizing() {
        restoreLastPage.setMinimumSize(com.formdev.flatlaf.util.UIScale.scale(new java.awt.Dimension(36, 24)));
        for (var field : new JComboBox<?>[]{language, theme, scale}) {
            field.setPreferredSize(com.formdev.flatlaf.util.UIScale.scale(new java.awt.Dimension(260, 34)));
            field.setMinimumSize(com.formdev.flatlaf.util.UIScale.scale(new java.awt.Dimension(100, 34)));
        }
        for (var field : fields.getComponents())
            if (field instanceof JLabel label) {
                label.setPreferredSize(com.formdev.flatlaf.util.UIScale.scale(new java.awt.Dimension(96, 22)));
                label.setMinimumSize(label.getPreferredSize());
            }
    }

    /** Persists a captured draft, then applies that exact value on the EDT. / 持久化捕获的草稿，再在事件线程应用同一值。 */
    private void save() {
        if (locked || closed || busy)
            return;
        var requested = new DesktopDisplayConfig((String) language.getSelectedItem(),
            (ThemeMode) theme.getSelectedItem(), (Integer) scale.getSelectedItem(), restoreLastPage.isSelected());
        busy = true;
        controls();
        messages.bind(status, "appearance.saving");
        DesktopTaskRunner.run(() -> {
            preferences.save(requested);
            return requested;
        }, value -> {
            busy = false;
            if (!closed) {
                applied.accept(value);
                messages.bind(status, "appearance.saved");
                controls();
            }
        }, failure -> {
            busy = false;
            if (!closed) {
                gold.debug.windowstolinux.app.ui.language.LocaleBinding.unbind(status);
                status.setText(messages.safe(failure));
                controls();
            }
        });
    }

    /** Updates privacy without discarding drafts or cancelling accepted writes. / 更新隐私状态，不丢弃草稿或取消已接纳写入。
     * @param locked actual privacy state / 实际隐私状态 */
    public void setLocked(boolean locked) {
        this.locked = locked;
        controls();
    }

    /** Applies the original write and privacy gates. / 应用原写入及隐私门禁。 */
    private void controls() {
        boolean enabled = !locked && !closed && !busy;
        language.setEnabled(enabled);
        theme.setEnabled(enabled);
        scale.setEnabled(enabled);
        restoreLastPage.setEnabled(enabled);
        save.setEnabled(enabled);
    }

    /** Detaches the editor while keeping an accepted preference write owned by its worker. / 分离编辑器，已接纳偏好写入仍由工作线程持有。 */
    @Override
    public void close() {
        closed = true;
        controls();
    }

    /** Narrow original preference persistence, independent of business task control. / 独立于业务任务控制的窄原偏好持久化。 */
    @FunctionalInterface
    public interface Preferences {
        /** Saves all display preferences atomically. / 原子保存全部显示偏好。
         * @param requested captured preference selection / 捕获的偏好选择
         * @throws Exception if persistence or security rejects the write / 持久化或安全拒绝写入时 */
        void save(DesktopDisplayConfig requested) throws Exception;
    }
}
