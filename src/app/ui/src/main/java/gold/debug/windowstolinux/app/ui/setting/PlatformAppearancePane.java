package gold.debug.windowstolinux.app.ui.setting;

import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.util.Objects;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.ui.control.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;

/** Persists explicit appearance choices without recreating runtime or business pages. / 持久化明确的外观选择，不重建运行器或业务页面。 */
public final class PlatformAppearancePane extends JPanel implements AutoCloseable {
    /** Supported language choices, independent of display labels. / 独立于显示标签的受支持语言选项。 */
    private final JComboBox<String> language = new JComboBox<>(new String[]{"zh-CN", "en"});

    /** Stored theme preference, including system selection. / 包含系统选项的已存主题偏好。 */
    private final JComboBox<ThemeMode> theme = new JComboBox<>(ThemeMode.values());

    /** Explicit preference confirmation. / 明确的偏好确认。 */
    private final JButton save;

    /** Current preference feedback. / 当前偏好反馈。 */
    private final JLabel status = new JLabel();

    /** Current message resolver. / 当前消息解析器。 */
    private final PageMessagePresenter messages;

    /** Original persistence operation outside the EDT. / 事件线程外的原持久化操作。 */
    private final Preferences preferences;

    /** Applies only a successfully persisted selection. / 仅应用成功持久化的选择。 */
    private final Consumer<DesktopDisplayConfiguration> applied;

    /** Privacy, lifetime and pending write gates. / 隐私、寿命和待写门禁。 */
    private boolean locked, closed, busy;

    /** Builds language and theme drafts from confirmed preferences. / 根据已确认偏好构造语言和主题草稿。
     * @param initial confirmed display preference / 已确认显示偏好
     * @param preferences original persistence operation / 原持久化操作
     * @param applied callback retaining the original window / 保留原窗口的回调
     * @param components current component factory / 当前组件工厂
     * @param messages current message presenter / 当前消息展示器 */
    public PlatformAppearancePane(DesktopDisplayConfiguration initial, Preferences preferences,
                                  Consumer<DesktopDisplayConfiguration> applied, DesktopComponentFactory components,
                                  PageMessagePresenter messages) {
        super(new BorderLayout(12, 12));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Appearance controls require the EDT");
        this.messages = Objects.requireNonNull(messages);
        this.preferences = Objects.requireNonNull(preferences);
        this.applied = Objects.requireNonNull(applied);
        setName("platform.appearance");
        components.stylePage(this);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 24, 20, 24));
        language.setName("appearance.language");
        theme.setName("appearance.theme");
        language.setSelectedItem(MessageCatalog.normalizeLanguageTag(initial.localeTag()));
        theme.setSelectedItem(initial.themeMode());
        var renderer = language.getRenderer();
        language.setRenderer((list, value, index, selected, focus) -> renderer.getListCellRendererComponent(list,
            messages.text("zh-CN".equals(value) ? "appearance.language.zh" : "appearance.language.en"), index,
            selected, focus));
        messages.localize(theme, "theme.");
        language.setPreferredSize(new java.awt.Dimension(220, 34));
        theme.setPreferredSize(new java.awt.Dimension(220, 34));
        save = messages.bind(components.primaryButton(""), "appearance.save");
        save.setName("appearance.save");
        save.addActionListener(event -> save());
        var fields = components.transparent(new GridBagLayout());
        components.addField(fields, 0, 0, messages, "field.language", language);
        components.addField(fields, 1, 0, messages, "field.appearance", theme);
        var editor = components.card(new BorderLayout(12, 12));
        editor.add(components.sectionHeading(messages, "settings.appearance.title", "settings.appearance.body"),
            BorderLayout.NORTH);
        editor.add(fields, BorderLayout.CENTER);
        var footer = components.transparent(new BorderLayout(12, 12));
        footer.add(save, BorderLayout.WEST);
        footer.add(status, BorderLayout.CENTER);
        editor.add(footer, BorderLayout.SOUTH);
        add(editor, BorderLayout.NORTH);
    }

    /** Persists a captured draft, then applies that exact value on the EDT. / 持久化捕获的草稿，再在事件线程应用同一值。 */
    private void save() {
        if (locked || closed || busy)
            return;
        var requested = new DesktopDisplayConfiguration((String) language.getSelectedItem(),
            (ThemeMode) theme.getSelectedItem());
        busy = true;
        controls();
        messages.bind(status, "appearance.saving");
        DesktopTaskExecutor.run(() -> {
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
        /** Saves both display preferences atomically. / 原子保存两个显示偏好。
         * @param requested captured preference selection / 捕获的偏好选择
         * @throws Exception if persistence or security rejects the write / 持久化或安全拒绝写入时 */
        void save(DesktopDisplayConfiguration requested) throws Exception;
    }
}
