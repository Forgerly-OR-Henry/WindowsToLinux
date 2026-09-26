package gold.debug.windowstolinux.app.ui.setting;

import java.awt.*;
import java.util.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.task.PlatformAuthorizationService;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.appapi.task.AuthorizationMode;

/** Edits only persisted platform policy, independently of AI approval. / 独立于 AI 审批，仅编辑持久平台策略。 */
public final class AuthorizationSettingsPane extends JPanel implements AutoCloseable {
    /** Actual preference owner. / 实际偏好所有者。 */
    private final PlatformAuthorizationService service;

    /** Localized feedback. / 本地化反馈。 */
    private final PageMessagePresenter messages;

    /** Same three-choice presentation as the AI approval control. / 与 AI 审批控件一致的三项呈现。 */
    private final JComboBox<AuthorizationMode> mode = new JComboBox<>(AuthorizationMode.values());

    /** Allows retry after a failed read. / 读取失败后允许重试。 */
    private final JButton reload;

    /** Confirmed storage feedback. / 已确认存储反馈。 */
    private final JLabel status = new JLabel();

    /** Last value actually read or saved. / 最后实际读取或保存的值。 */
    private AuthorizationMode confirmed;

    /** Privacy, lifetime, work and programmatic-selection gates. / 隐私、寿命、工作及程序选择门禁。 */
    private boolean locked = true, closed, busy, restoring;

    /** Builds controls without accessing storage on the EDT. / 构造控件，不在 EDT 访问存储。
     * @param service actual policy owner / 实际策略所有者
     * @param components themed controls / 主题控件
     * @param messages localized text / 本地化文字 */
    public AuthorizationSettingsPane(PlatformAuthorizationService service, DesktopComponentFactory components,
            PageMessagePresenter messages) {
        super(new BorderLayout(10, 10));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Settings require the EDT");
        this.service = Objects.requireNonNull(service);
        this.messages = Objects.requireNonNull(messages);
        setName("platform.authorization");
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        mode.setName("authorization.mode");
        var renderer = mode.getRenderer();
        var icons = new EnumMap<AuthorizationMode, Icon>(AuthorizationMode.class);
        icons.put(AuthorizationMode.ONCE, DesktopIcons.icon("hand", 16, mode::getForeground));
        icons.put(AuthorizationMode.GENERAL, DesktopIcons.icon("shield", 16, mode::getForeground));
        icons.put(AuthorizationMode.FULL, DesktopIcons.icon("shield-alert", 16,
                () -> mode.isEnabled() ? new Color(234, 108, 36) : UIManager.getColor("Label.disabledForeground")));
        mode.setRenderer((list, value, index, selected, focus) -> {
            var label = (JLabel) renderer.getListCellRendererComponent(list, value, index, selected, focus);
            label.setText(value == null ? "" : messages.text("authorization.mode." + value.name()));
            label.setIcon(icons.get(value));
            label.setIconTextGap(com.formdev.flatlaf.util.UIScale.scale(8));
            return label;
        });
        mode.setSelectedItem(null);
        mode.addActionListener(event -> save());
        reload = components.secondaryButton(messages.text("authorization.reload"));
        reload.setName("authorization.reload");
        reload.addActionListener(event -> reload());
        var explanation = new JTextArea(messages.text("authorization.explanation"));
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setRows(6);
        explanation.setFont(UIManager.getFont("Label.font"));
        var choices = components.transparent(new FlowLayout(FlowLayout.LEFT));
        choices.add(new JLabel(messages.text("authorization.label")));
        choices.add(mode);
        choices.add(reload);
        status.setName("authorization.status");
        var editor = components.transparent(new BorderLayout(10, 10));
        editor.add(choices, BorderLayout.NORTH);
        editor.add(explanation, BorderLayout.CENTER);
        editor.add(status, BorderLayout.SOUTH);
        add(editor, BorderLayout.NORTH);
        controls();
    }

    /** Reloads saved policy after unlock; active workflows retain their captured mode. / 解锁后重读已存策略；活动流程保留捕获模式。
     * @param value actual privacy state / 实际隐私状态 */
    public void setLocked(boolean value) {
        locked = value;
        controls();
        if (!value)
            reload();
    }

    /** Reads the separate platform preference on a worker. / 在工作线程读取独立平台偏好。 */
    private void reload() {
        if (locked || closed || busy)
            return;
        busy = true;
        controls();
        status.setText(messages.text("authorization.loading"));
        DesktopTaskExecutor.run(service::mode, value -> completed(value, "authorization.loaded"), this::failed);
    }

    /** Saves a user selection and blocks additional edits until confirmation. / 保存用户选择，确认前阻止继续编辑。 */
    private void save() {
        if (locked || closed || busy || restoring || confirmed == null)
            return;
        var requested = (AuthorizationMode) mode.getSelectedItem();
        if (requested == null || requested == confirmed)
            return;
        busy = true;
        controls();
        status.setText(messages.text("authorization.saving"));
        DesktopTaskExecutor.run(() -> service.saveMode(requested), value -> completed(value, "authorization.saved"),
                this::failed);
    }

    /** Publishes confirmed storage values. / 发布已确认存储值。
     * @param value confirmed policy / 已确认策略
     * @param key localized completion / 本地化完成消息 */
    private void completed(AuthorizationMode value, String key) {
        busy = false;
        if (closed)
            return;
        confirmed = value;
        restore();
        controls();
        status.setText(messages.text(key));
    }

    /** Rolls the selector back after an unconfirmed write. / 未确认写入后回滚选择器。
     * @param failure storage or authorization failure / 存储或授权失败 */
    private void failed(Exception failure) {
        busy = false;
        if (closed)
            return;
        restore();
        controls();
        status.setText(messages.text("authorization.failed") + " " + messages.safe(failure));
    }

    /** Restores display without triggering a new write. / 恢复显示，不触发新写入。 */
    private void restore() {
        restoring = true;
        try {
            mode.setSelectedItem(confirmed);
        } finally {
            restoring = false;
        }
    }

    /** Applies privacy and operation gating. / 应用隐私及操作门禁。 */
    private void controls() {
        boolean available = !locked && !closed && !busy;
        mode.setEnabled(available && confirmed != null);
        reload.setEnabled(available);
    }

    /** Detaches this view without cancelling an accepted write. / 分离此视图，不取消已接纳写入。 */
    @Override
    public void close() {
        closed = true;
        controls();
        status.setText("");
    }
}
