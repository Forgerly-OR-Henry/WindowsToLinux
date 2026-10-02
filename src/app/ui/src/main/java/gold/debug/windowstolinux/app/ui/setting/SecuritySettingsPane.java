package gold.debug.windowstolinux.app.ui.setting;

import java.awt.*;
import java.util.Arrays;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.security.SecurityApplicationFacade;
import gold.debug.windowstolinux.app.ui.control.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;

/** Edits global security settings after explicit password verification. / 重新验证密码后修改全局安全设置。 */
public final class SecuritySettingsPane extends JPanel {
    /** Narrow security facade used by this view. / 视图使用的窄安全门面。
     */
    private final SecurityApplicationFacade service;

    /** Localized safe message presenter. / 本地化安全消息呈现器。
     */
    private final PageMessagePresenter messages;

    /** Current input or accepted operation context. / 当前输入或已接纳操作上下文。
     */
    /** New password input control. / 新密码输入控件。
     */
    /** Repeated password input control. / 重复密码输入控件。
     */
    private final JPasswordField current = new JPasswordField(24), replacement = new JPasswordField(24),
        confirmation = new JPasswordField(24);

    /** Credential encryption action. / 凭据加密动作。
     */
    /** Password change action. / 修改密码动作。
     */
    private final JButton change, toggle;

    /** Compact status without long diagnostic details. / 不包含冗长诊断详情的简短状态。
     */
    private final JLabel status = new JLabel();

    /** Current-password row hidden until initial setup completes. / 首次设置完成前隐藏的当前密码行。
     */
    private final JPanel currentRow;

    /** Prevents concurrent security submissions. / 防止并发提交安全设置。
     */
    private boolean busy;

    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     * @param service service supplied to this boundary / 传入当前边界的service值
     * @param components components supplied to this boundary / 传入当前边界的components值
     * @param messages messages supplied to this boundary / 传入当前边界的messages值
     */
    public SecuritySettingsPane(SecurityApplicationFacade service, DesktopComponentFactory components,
                                PageMessagePresenter messages) {
        super(new BorderLayout(0, 12));
        this.service = service;
        this.messages = messages;
        setOpaque(false);
        add(components.sectionHeading(messages, "security.settings", "security.settings.hint"),
            BorderLayout.NORTH);
        JPanel form = components.transparent(new GridBagLayout());
        currentRow = components.transparent(new GridBagLayout());
        components.addField(currentRow, 0, 0, messages, "security.current", current);
        components.addField(form, 0, 0, messages, "security.new", replacement);
        components.addField(form, 1, 0, messages, "security.repeat", confirmation);
        JPanel inputs = components.transparent(new BorderLayout());
        inputs.add(currentRow, BorderLayout.NORTH);
        inputs.add(form, BorderLayout.CENTER);
        add(inputs);
        change = messages.bind(components.secondaryButton(""), "security.change");
        toggle = components.secondaryButton("");
        current.setName("security.settings.current");
        replacement.setName("security.settings.password");
        confirmation.setName("security.settings.confirmation");
        change.setName("security.settings.submit");
        toggle.setName("security.settings.encryption");
        status.setName("security.settings.status");
        change.addActionListener(event -> change(false));
        confirmation.addActionListener(event -> change(false));
        toggle.addActionListener(event -> change(true));
        JPanel actions = components.transparent(new FlowLayout(FlowLayout.LEFT));
        actions.add(change);
        actions.add(toggle);
        actions.add(status);
        add(actions, BorderLayout.SOUTH);
        update();
        addHierarchyListener(event -> {
            if (isShowing())
                update();
        });
    }

    /** Refreshes visible status from the current security state. / 按当前安全状态刷新可见状态。
     */
    private void update() {
        boolean configured = service.securityState().configured();
        currentRow.setVisible(configured);
        messages.bind(change, configured ? "security.change" : "security.setup");
        toggle.setVisible(configured);
        messages.bind(toggle, service.securityState().encryptionEnabled()
            ? "security.disableEncryption"
            : "security.enableEncryption");
        revalidate();
        repaint();
    }

    /** Submits a verified security-setting change and clears form secrets. / 提交需验证的安全设置变更并清除表单秘密。
     * @param encryption encryption supplied to this boundary / 传入当前边界的encryption值
     */
    private void change(boolean encryption) {
        if (busy)
            return;
        boolean setup = !service.securityState().configured();
        boolean enabled = !service.securityState().encryptionEnabled();
        if (encryption && !enabled
            && JOptionPane.showConfirmDialog(this, messages.text("security.plaintext.warning"),
            messages.text("security.settings"), JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        char[] old = current.getPassword(), next = replacement.getPassword(), repeat = confirmation.getPassword();
        current.setText("");
        replacement.setText("");
        confirmation.setText("");
        change.setEnabled(false);
        toggle.setEnabled(false);
        status.setText("");
        busy = true;
        new SwingWorker<Void, Void>() {
            /** Executes the security request away from the event thread and clears buffers. / 在事件线程外执行安全请求并清理缓冲区。
             * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
             * @throws Exception if validation or the scoped operation fails / 校验或限定操作失败时
             */
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    if (setup)
                        service.setupAndUnlock(next, repeat);
                    else if (encryption)
                        service.setCredentialEncryption(old, enabled);
                    else
                        service.changeMasterPassword(old, next, repeat);
                    return null;
                } finally {
                    Arrays.fill(old, '\0');
                    Arrays.fill(next, '\0');
                    Arrays.fill(repeat, '\0');
                }
            }

            /** Reports completion on the event thread without changing lock state implicitly. / 在事件线程报告完成，不隐式修改锁定状态。
             */
            @Override
            protected void done() {
                busy = false;
                change.setEnabled(true);
                toggle.setEnabled(true);
                update();
                try {
                    get();
                    status.setText(messages.text("security.saved"));
                } catch (Exception failure) {
                    status.setText(messages.text("security.saveFailed"));
                    JTextArea details = new JTextArea(messages.safe(failure), 8, 60);
                    details.setEditable(false);
                    details.setLineWrap(true);
                    details.setWrapStyleWord(true);
                    details.setCaretPosition(0);
                    JOptionPane.showMessageDialog(SecuritySettingsPane.this, new JScrollPane(details),
                        messages.text("security.settings"), JOptionPane.ERROR_MESSAGE);
                    (setup ? replacement : current).requestFocusInWindow();
                }
            }
        }.execute();
    }
}
