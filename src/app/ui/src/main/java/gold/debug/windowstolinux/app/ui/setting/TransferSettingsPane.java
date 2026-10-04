package gold.debug.windowstolinux.app.ui.setting;

import java.awt.*;
import java.util.Objects;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.setting.TransferConfig;
import gold.debug.windowstolinux.app.service.setting.TransferSettingsService;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;

/** Permanent platform threshold editor with persistence-confirmed values. / 使用持久化已确认值的常驻平台阈值编辑器。 */
public final class TransferSettingsPane extends JPanel implements AutoCloseable {
    /** Actual shared preference owner. / 实际共用偏好所有者。 */
    private final TransferSettingsService service;

    /** Localized feedback and safe failure formatting. / 本地化反馈及安全失败格式化。 */
    private final PageMessagePresenter messages;

    /** Whole-second connection input. / 整数秒连接输入。 */
    private final JTextField connect = new JTextField(12);

    /** Whole-second no-progress input. / 整数秒无进展输入。 */
    private final JTextField idle = new JTextField(12);

    /** Explicit persistence and reload actions. / 明确持久化及重新加载操作。 */
    private final JButton save, reload;

    /** Non-secret success or failure explanation. / 非秘密成功或失败说明。 */
    private final JLabel status = new JLabel();

    /** Last successfully loaded or saved pair, never an unconfirmed edit. / 最后成功读取或保存的设置对，不是未确认编辑。 */
    private TransferConfig confirmed;

    /** Current privacy, operation and view lifetime state. / 当前隐私、操作及视图寿命状态。 */
    private boolean locked = true, busy, closed;

    /** Builds controls on the EDT without reading persistence. / 在 EDT 构造控件，不读取持久存储。
     * @param service real preference owner / 真实偏好所有者
     * @param components themed platform controls / 主题平台控件
     * @param messages localized feedback / 本地化反馈 */
    public TransferSettingsPane(TransferSettingsService service, DesktopPartFactory components,
                                PageMessagePresenter messages) {
        super(new BorderLayout(10, 10));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Settings controls require the EDT");
        this.service = Objects.requireNonNull(service);
        this.messages = Objects.requireNonNull(messages);
        setName("platform.transfers");
        components.stylePage(this);
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        connect.setName("transfer.settings.connect");
        idle.setName("transfer.settings.idle");
        var description = new JTextArea();
        description.setEditable(false);
        messages.bind(description, "transfer.settings.explanation");
        description.setOpaque(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setRows(3);
        description.setFont(UIManager.getFont("Label.font"));
        var fields = components.transparent(new GridBagLayout());
        components.addField(fields, 0, 0, messages, "transfer.settings.connect", connect);
        components.addField(fields, 1, 0, messages, "transfer.settings.idle", idle);
        save = messages.bind(components.primaryButton(""), "transfer.settings.save");
        save.setName("transfer.settings.save");
        reload = messages.bind(components.secondaryButton(""), "transfer.settings.reload");
        reload.setName("transfer.settings.reload");
        save.addActionListener(event -> save());
        reload.addActionListener(event -> reload());
        var actions = components.transparent(new FlowLayout(FlowLayout.LEFT, 0, 8));
        actions.add(save);
        actions.add(Box.createHorizontalStrut(10));
        actions.add(reload);
        var editor = components.transparent(new BorderLayout(10, 10));
        editor.add(fields, BorderLayout.CENTER);
        editor.add(actions, BorderLayout.SOUTH);
        var top = components.card(new BorderLayout(10, 10));
        top.add(description, BorderLayout.NORTH);
        top.add(editor, BorderLayout.CENTER);
        status.setName("transfer.settings.status");
        top.add(status, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        components.scrollPage(this);
        controls();
    }

    /** Applies privacy state and reloads confirmed values after unlock. / 应用隐私状态，解锁后重读已确认值。
     * @param value actual lock state / 实际锁定状态 */
    public void setLocked(boolean value) {
        locked = value;
        controls();
        if (!value)
            reload();
    }

    /** Reads actual stored values in a background operation. / 在后台操作中读取实际已存值。 */
    private void reload() {
        if (locked || busy || closed)
            return;
        busy = true;
        controls();
        status.setText(messages.text("transfer.settings.loading"));
        DesktopTaskRunner.run(service::read, value -> completed(value, "transfer.settings.loaded"), this::failed);
    }

    /** Validates editable seconds before scheduling one atomic save. / 调度一次原子保存前校验可编辑秒数。 */
    private void save() {
        if (locked || busy || closed || confirmed == null)
            return;
        final TransferConfig requested;
        try {
            requested = new TransferConfig(Integer.parseInt(connect.getText().trim()),
                Integer.parseInt(idle.getText().trim()));
        } catch (IllegalArgumentException invalid) {
            status.setText(messages.text("transfer.settings.invalid"));
            return;
        }
        busy = true;
        controls();
        status.setText(messages.text("transfer.settings.saving"));
        DesktopTaskRunner.run(() -> service.save(requested), value -> completed(value, "transfer.settings.saved"),
            this::failed);
    }

    /** Publishes only a confirmed read or completed atomic write. / 仅发布已确认读取或已完成原子写入。
     * @param value confirmed stored pair / 已确认存储设置对
     * @param key localized completion message / 本地化完成消息 */
    private void completed(TransferConfig value, String key) {
        busy = false;
        if (closed)
            return;
        confirmed = value;
        restore();
        status.setText(messages.text(key));
        controls();
    }

    /** Restores the prior confirmed display when persistence fails. / 持久化失败时恢复先前已确认的显示。
     * @param failure actual storage or authorization failure / 实际存储或授权失败 */
    private void failed(Exception failure) {
        busy = false;
        if (closed)
            return;
        restore();
        status.setText(messages.text("transfer.settings.failed") + " " + messages.safe(failure));
        controls();
    }

    /** Restores both inputs together from the last confirmation. / 从最后确认值一并恢复两个输入。 */
    private void restore() {
        connect.setText(confirmed == null ? "" : Integer.toString(confirmed.connectSeconds()));
        idle.setText(confirmed == null ? "" : Integer.toString(confirmed.idleSeconds()));
    }

    /** Disables edits during privacy lock, storage work or teardown. / 隐私锁定、存储操作或拆除期间禁止编辑。 */
    private void controls() {
        boolean available = !locked && !busy && !closed;
        connect.setEnabled(available && confirmed != null);
        idle.setEnabled(available && confirmed != null);
        save.setEnabled(available && confirmed != null);
        reload.setEnabled(available);
    }

    /** Detaches controls without cancelling an already accepted atomic write. / 分离控件，不取消已接纳的原子写入。 */
    @Override
    public void close() {
        closed = true;
        controls();
        status.setText("");
    }
}
