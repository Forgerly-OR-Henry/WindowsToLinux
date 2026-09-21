package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Independent server editor with the existing secret store and SSH trust flow. / 独立服务器编辑窗口，复用秘密存储及 SSH 信任流程。
 */
public final class ServerProfileDialog extends JDialog {
    /**
     * Swing control for name.
     * <p>名称对应的 Swing 控件。
     * <p>host:
     * Swing control for host.
     * <p>主机对应的 Swing 控件。
     * <p>username:
     * Swing control for username.
     * <p>用户名对应的 Swing 控件。
     */
    private final JTextField name = new JTextField(24), host = new JTextField(24), username = new JTextField(24);
    /**
     * Swing control for port.
     * <p>端口对应的 Swing 控件。
     */
    private final JTextField port = new JTextField("22", 8);
    /**
     * Swing control for password.
     * <p>密码对应的 Swing 控件。
     * <p>master:
     * Swing control for master.
     * <p>主对应的 Swing 控件。
     */
    private final JPasswordField password = new JPasswordField(24), master = new JPasswordField(24);
    /**
     * Storage.
     * <p>存储。
     */
    private final JComboBox<CredentialStorageMode> storage = new JComboBox<>(CredentialStorageMode.values());
    /**
     * Swing control for status.
     * <p>状态对应的 Swing 控件。
     */
    private final JTextArea status = DesktopComponentFactory.outputArea();
    /**
     * Validates and saves server connection metadata and the selected credential-storage mode, then notifies the owning page.
     * <p>校验并保存服务器连接元数据及所选凭据存储模式，随后通知所属页面。
     */
    private final JButton save;
    /**
     * Swing control for form.
     * <p>表单对应的 Swing 控件。
     */
    private final JPanel form;
    /**
     * Bound server application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的服务器应用门面协作对象。
     */
    private final ServerApplicationFacade service;
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;
    /**
     * Saved.
     * <p>已保存。
     */
    private final Consumer<ServerProfile> saved;
    /**
     * Stable identifier within the owning registry.
     * <p>所属登记表内的稳定标识。
     * <p>credentialKey:
     * Opaque lookup key in the platform secret store.
     * <p>平台秘密存储中的不透明查找键。
     */
    private final String id, credentialKey;
    /**
     * Whether a page action is in progress and conflicting controls must remain disabled.
     * <p>页面动作是否正在进行且冲突控件须保持禁用。
     * <p>existing:
     * Existing.
     * <p>既有。
     */
    private boolean busy, existing;

    /**
     * Creates an add or edit dialog; null profile starts an independent new identity. / 创建添加或编辑窗口，空配置生成独立新身份。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param messages localized message resolver / 本地化消息解析器
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param saved saved / 已保存
     */
    public ServerProfileDialog(Window owner, ServerApplicationFacade service, DesktopComponentFactory c,
                               PageMessagePresenter messages, ServerProfile profile, Consumer<ServerProfile> saved) {
        super(owner, messages.text(profile == null ? "auto.addServer" : "server.edit"), ModalityType.APPLICATION_MODAL);
        this.service = service; this.messages = messages; this.saved = saved;
        existing = profile != null;
        id = existing ? profile.id() : "server-" + UUID.randomUUID().toString().substring(0, 12);
        credentialKey = existing ? profile.credentialKey() : "ssh/" + id + "/password";
        storage.setSelectedItem(existing ? profile.credentialMode() : CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER);
        messages.localize(storage, "credential.mode.");
        storage.addActionListener(event -> master.setEnabled(storage.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        master.setEnabled(storage.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD);
        if (existing) {
            name.setText(profile.displayName()); host.setText(profile.host()); username.setText(profile.username());
            port.setText(Integer.toString(profile.sshPort())); password.setToolTipText(messages.text("server.password.keep"));
        }
        form = c.card(new GridBagLayout());
        c.addField(form, 0, 0, messages.text("server.name"), name);
        c.addField(form, 1, 0, messages.text("field.host"), host);
        c.addField(form, 2, 0, messages.text("field.sshPort"), port);
        c.addField(form, 3, 0, messages.text("field.sshUser"), username);
        c.addField(form, 4, 0, messages.text("field.sshPassword"), password);
        c.addField(form, 5, 0, messages.text("field.credentialStorage"), storage);
        c.addField(form, 6, 0, messages.text("field.masterPassword"), master);
        save = c.primaryButton(messages.text("button.saveServer")); save.addActionListener(event -> save());
        JButton cancel = c.secondaryButton(messages.text("button.cancel")); cancel.addActionListener(event -> { if (!busy) dispose(); });
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT)); actions.add(cancel); actions.add(save);
        JPanel body = c.pagePanel(); body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        body.add(form, BorderLayout.NORTH); status.setRows(4); status.setLineWrap(true); status.setWrapStyleWord(true);
        body.add(new JScrollPane(status), BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH);
        setContentPane(body); setMinimumSize(new Dimension(560, 490)); pack(); setLocationRelativeTo(owner);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            /**
             * Handles the user's close request through the owning window's cleanup path.
             * <p>通过所属窗口的清理路径处理用户关闭请求。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override public void windowClosing(WindowEvent event) { if (!busy) dispose(); }
            /**
             * Completes resource cleanup after the Swing window has closed.
             * <p>在 Swing 窗口关闭后完成资源清理。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override public void windowClosed(WindowEvent event) { password.setText(""); master.setText(""); }
        });
        getRootPane().setDefaultButton(save);
    }

    /**
     * Validates and saves server connection metadata and the selected credential-storage mode, then notifies the owning page.
     * <p>校验并保存服务器连接元数据及所选凭据存储模式，随后通知所属页面。
     *
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private void save() {
        if (busy) return;
        try {
            var mode = (CredentialStorageMode) storage.getSelectedItem();
            var profile = new ServerProfile(id, host.getText().trim(), Integer.parseInt(port.getText().trim()),
                    username.getText().trim(), credentialKey, mode, name.getText());
            char[] secret = password.getPassword(), unlock = master.getPassword();
            if (!existing && secret.length == 0) { Arrays.fill(unlock, '\0'); throw new IllegalArgumentException(messages.text("server.password.required")); }
            busy = true; save.setEnabled(false); setInputs(false); status.setText(messages.text("server.connecting"));
            var written = new java.util.concurrent.atomic.AtomicBoolean();
            DesktopTaskExecutor.run(() -> {
                try {
                    service.saveServerProfile(profile, mode, unlock.clone(), secret);
                    written.set(true);
                    service.verifyServer(profile, mode, unlock.clone(), fingerprint -> ServerTrustPrompt.confirm(this, messages, fingerprint));
                    return profile;
                } finally { Arrays.fill(secret, '\0'); Arrays.fill(unlock, '\0'); }
            }, value -> { busy = false; saved.accept(value); dispose(); }, failure -> {
                busy = false; existing = existing || written.get(); save.setEnabled(true); setInputs(true);
                status.setText(messages.text("server.saveCheckFailed", Map.of("detail", messages.safe(failure))));
            });
        } catch (Exception failure) { status.setText(messages.safe(failure)); }
    }

    /**
     * Updates reviewed non-secret deployment input fields.
     * <p>更新已审阅的非秘密部署输入字段。
     *
     * @param enabled whether this configured capability participates in execution / 当前配置能力是否参与执行
     */
    private void setInputs(boolean enabled) {
        for (Component child : form.getComponents()) child.setEnabled(enabled);
        master.setEnabled(enabled && storage.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD);
    }
}
