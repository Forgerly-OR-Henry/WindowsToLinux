package gold.debug.windowstolinux.app.ui.window;

import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.service.security.SecurityAccess;
import gold.debug.windowstolinux.app.ui.control.DesktopIcons;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.language.LocalizedMessage;

/** A single owned password prompt using the existing authorization service. / 使用原授权服务的唯一所属密码提示。 */
final class DesktopUnlockDialog implements AutoCloseable {
    /** Single owned modal password prompt. / 唯一所属模态密码提示。 */
    private final JDialog dialog;

    /** Original authorization and privacy facade. / 原授权及隐私门面。 */
    private final SecurityAccess security;

    /** Localized safe failure presenter. / 本地化安全失败展示器。 */
    private final PageMessagePresenter messages;

    /** Successful-unlock and explicit-dismissal callbacks. / 解锁成功及明确关闭的回调。 */
    private final Runnable completed, dismissed;

    /** Temporary password control cleared before worker dispatch. / 工作线程派发前清空的临时密码控件。 */
    private final JPasswordField password = new JPasswordField(24);

    /** Unlock, privacy retry and cancellation controls. / 解锁、隐私重试及取消控件。 */
    private final JButton submit = new JButton(), retry = new JButton(), cancel = new JButton();

    /** Unlock result, privacy confirmation and task-count captions. / 解锁结果、隐私确认及任务数量文案。 */
    private final JLabel status = new JLabel(" "), privacy = new JLabel(), summary = new JLabel();

    /** Prevents duplicate unlock dispatch while the worker owns the password. / 工作线程持有密码时防止重复派发解锁。 */
    private boolean busy;

    /** Creates the single password prompt and binds original security actions. / 创建唯一密码提示并绑定原安全操作。
     * @param frame owned desktop / 所属桌面
     * @param security original authorization facade / 原授权门面
     * @param messages localized safe messages / 本地化安全消息
     * @param completed successful-unlock callback / 解锁成功回调
     * @param dismissed explicit-dismissal callback / 明确关闭回调
     */
    DesktopUnlockDialog(JFrame frame, SecurityAccess security, PageMessagePresenter messages,
                        Runnable completed, Runnable dismissed) {
        this.security = security;
        this.messages = messages;
        this.completed = completed;
        this.dismissed = dismissed;
        dialog = new JDialog(frame, messages.text("security.unlock"), true);
        dialog.setName("security.unlockDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        var form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        var title = messages.bind(new JLabel(), "security.unlock");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setIcon(DesktopIcons.icon("lock/unlocked", 24, title::getForeground));
        form.add(title);
        form.add(Box.createVerticalStrut(12));
        form.add(messages.bind(new JLabel(), "security.password"));
        password.setName("security.password");
        password.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(34)));
        form.add(Box.createVerticalStrut(6));
        form.add(password);
        form.add(Box.createVerticalStrut(10));
        form.add(status);
        form.add(summary);
        privacy.setName("security.privacy");
        form.add(privacy);
        retry.setName("security.privacy.retry");
        messages.bind(retry, "security.privacy.retry");
        retry.addActionListener(event -> {
            security.retryPrivacy();
            refresh();
        });
        form.add(retry);
        var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        messages.bind(cancel, "button.cancel");
        cancel.setName("security.cancel");
        cancel.addActionListener(event -> {
            if (!busy) {
                close();
                dismissed.run();
            }
        });
        messages.bind(submit, "security.unlock");
        submit.setName("security.submit");
        submit.setBackground(UIManager.getColor("Component.focusColor"));
        submit.setForeground(Color.WHITE);
        submit.addActionListener(event -> unlock());
        password.addActionListener(event -> unlock());
        buttons.add(cancel);
        buttons.add(submit);
        form.add(Box.createVerticalStrut(16));
        form.add(buttons);
        for (Component child : form.getComponents())
            if (child instanceof JComponent control)
                control.setAlignmentX(Component.LEFT_ALIGNMENT);
        dialog.add(form);
        dialog.getRootPane().setDefaultButton(submit);
        dialog.getRootPane().registerKeyboardAction(event -> cancel.doClick(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.addWindowListener(new WindowAdapter() {
            /** Requests explicit dismissal through the existing cancellation control. / 通过既有取消控件请求明确关闭。
             * @param event original event notification / 原事件通知
             */
            @Override
            public void windowClosing(WindowEvent event) {
                cancel.doClick();
            }

            /** Focuses the password input when the owned prompt opens. / 所属提示打开时聚焦密码输入。
             * @param event original event notification / 原事件通知
             */
            @Override
            public void windowOpened(WindowEvent event) {
                focus();
            }
        });
        refresh();
        dialog.pack();
        dialog.setSize(Math.max(dialog.getWidth(), UIScale.scale(380)), dialog.getHeight());
        dialog.setLocationRelativeTo(frame);
        dialog.setResizable(false);
    }

    /** Returns the owned password window for admission checks. / 返回所属密码窗口供准入检查。
     * @return original owned password window / 原所属密码窗口
     */
    Window window() {
        return dialog;
    }

    /** Shows the single owned modal password prompt. / 显示唯一所属模态密码提示。
     */
    void show() {
        dialog.setVisible(true);
    }

    /** Focuses the temporary password input. / 聚焦临时密码输入。
     */
    void focus() {
        password.requestFocusInWindow();
    }

    /** Updates security captions and controls from the original facade. / 从原门面更新安全文案及控件。
     */
    void refresh() {
        var confirmation = security.privacyState();
        boolean failed = confirmation.failure().isPresent();
        privacy.setVisible(confirmation.pending() || failed);
        if (privacy.isVisible()) messages.bind(privacy,
            confirmation.pending() ? "security.privacy.pending" : "security.privacy.failed");
        retry.setVisible(failed);
        retry.setEnabled(failed && !confirmation.pending() && !busy);
        LocaleBinding.bind(summary, LocalizedMessage.of("security.tasks", "count",
            security.securityState().activeTasks()), messages.catalog());
    }

    /** Transfers a cleared temporary password to one authorization worker. / 将已从控件清空的临时密码交给唯一授权工作线程。
     */
    private void unlock() {
        if (busy) return;
        busy = true;
        submit.setEnabled(false);
        password.setEnabled(false);
        cancel.setEnabled(false);
        char[] supplied = password.getPassword();
        password.setText("");
        new SwingWorker<Void, Void>() {
            /** Unlocks through the original facade and clears password characters on every exit. / 通过原门面解锁并在每个退出路径清空密码内容。
             * @return no result / 无返回值
             * @throws Exception if original authorization fails / 原授权失败时
             */
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    security.unlock(supplied);
                    return null;
                } finally {
                    Arrays.fill(supplied, '\0');
                }
            }

            /** Restores controls and reports the original unlock result on the EDT. / 在 EDT 恢复控件并报告原解锁结果。
             */
            @Override
            protected void done() {
                busy = false;
                submit.setEnabled(true);
                password.setEnabled(true);
                cancel.setEnabled(true);
                try {
                    get();
                    completed.run();
                } catch (Exception failure) {
                    if (!dialog.isDisplayable()) return;
                    status.setText(messages.text("security.unlock.failed"));
                    status.setToolTipText(messages.safe(failure));
                    focus();
                }
            }
        }.execute();
    }

    /** Clears the password and disposes the owned prompt. / 清空密码并释放所属提示窗口。 */
    @Override
    public void close() {
        password.setText("");
        dialog.dispose();
    }
}
