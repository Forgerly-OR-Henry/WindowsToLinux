package gold.debug.windowstolinux.app.ui.setting;

import java.awt.*;
import java.util.Objects;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.setting.FileSettingsService;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;

/** Edits only the optional explanation preference, with confirmed persistence and privacy gating. / 仅编辑可选说明偏好，保留持久确认及隐私门禁。 */
public final class FileSettingsPane extends JPanel implements AutoCloseable {
    /** Actual preference store. / 实际偏好存储。 */
    private final FileSettingsService service;

    /** Localized presentation. / 本地化呈现。 */
    private final PageMessagePresenter messages;

    /** Optional explanation control. / 可选说明控件。 */
    private final JCheckBox enabled = new JCheckBox();

    /** Explicit retry for unreadable preferences. / 不可读偏好的明确重试。 */
    private final JButton reload;

    /** Confirmed storage feedback. / 已确认存储反馈。 */
    private final JLabel status = new JLabel();

    /** Last actually persisted value; absent after an initial read failure. / 最后实际持久值，初读失败时缺失。 */
    private Boolean confirmed;

    /** Privacy and lifecycle gates. / 隐私及生命周期门禁。 */
    private boolean locked = true, closed, busy, restoring;

    /** Builds UI without reading the database on the EDT. / 构造 UI，不在 EDT 读取数据库。
     * @param service preference owner / 偏好所有者
     * @param components styled controls / 样式控件
     * @param messages text catalog / 文字目录 */
    public FileSettingsPane(FileSettingsService service, DesktopPartFactory components, PageMessagePresenter messages) {
        super(new BorderLayout(10, 10));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("File settings require EDT");
        this.service = Objects.requireNonNull(service);
        this.messages = Objects.requireNonNull(messages);
        setName("platform.fileSettings");
        components.stylePage(this);
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        enabled.setName("fileSettings.enabled");
        messages.bind(enabled, "file.settings.enabled");
        enabled.addActionListener(event -> save());
        reload = messages.bind(components.secondaryButton(""), "file.settings.reload");
        reload.setName("fileSettings.reload");
        reload.addActionListener(event -> reload());
        var explanation = new JTextArea();
        explanation.setEditable(false);
        messages.bind(explanation, "file.settings.explanation");
        explanation.setOpaque(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setRows(4);
        explanation.setFont(UIManager.getFont("Label.font"));
        var choices = components.transparent(new FlowLayout(FlowLayout.LEFT));
        choices.add(enabled);
        choices.add(reload);
        status.setName("fileSettings.status");
        var card = components.card(new BorderLayout(10, 10));
        card.add(choices, BorderLayout.NORTH);
        card.add(explanation, BorderLayout.CENTER);
        card.add(status, BorderLayout.SOUTH);
        add(card, BorderLayout.NORTH);
        components.scrollPage(this);
        controls();
    }

    /** Reloads after unlock. / 解锁后重新读取。
     * @param value privacy state / 隐私状态 */
    public void setLocked(boolean value) {
        locked = value;
        controls();
        if (!value) reload();
    }

    /** Reads only on a background worker. / 仅在后台线程读取。 */
    private void reload() {
        if (locked || closed || busy) return;
        busy = true;
        controls();
        status.setText(messages.text("file.settings.loading"));
        DesktopTaskRunner.run(service::enabled, value -> completed(value, "file.settings.loaded"), this::failed);
    }

    /** Saves one user toggle and waits for durable success. / 保存一次用户切换并等待持久成功。 */
    private void save() {
        if (locked || closed || busy || restoring || confirmed == null) return;
        boolean value = enabled.isSelected();
        if (value == confirmed) return;
        busy = true;
        controls();
        status.setText(messages.text("file.settings.saving"));
        DesktopTaskRunner.run(() -> service.save(value), saved -> completed(saved, "file.settings.saved"), this::failed);
    }

    /** Publishes a confirmed storage result. / 发布已确认存储结果。
     * @param value stored value / 保存值
     * @param key feedback key / 反馈键 */
    private void completed(boolean value, String key) {
        busy = false;
        if (closed) return;
        confirmed = value;
        restore();
        controls();
        status.setText(messages.text(key));
    }

    /** Reports failure and rolls back an unconfirmed toggle. / 报告失败并回滚未确认切换。
     * @param failure actual failure / 实际失败 */
    private void failed(Exception failure) {
        busy = false;
        if (closed) return;
        restore();
        controls();
        status.setText(messages.text("file.settings.failed") + " " + messages.safe(failure));
    }

    /** Restores display without a second write. / 恢复显示而不二次写入。 */
    private void restore() {
        restoring = true;
        try {
            enabled.setSelected(Boolean.TRUE.equals(confirmed));
        } finally {
            restoring = false;
        }
    }

    /** Applies privacy and operation admission. / 应用隐私及操作准入。 */
    private void controls() {
        boolean available = !locked && !closed && !busy;
        enabled.setEnabled(available && confirmed != null);
        reload.setEnabled(available);
    }

    /** Detaches view while an already accepted write finishes normally. / 分离视图并让已接纳写入正常完成。 */
    @Override
    public void close() {
        closed = true;
        controls();
        status.setText("");
    }
}
