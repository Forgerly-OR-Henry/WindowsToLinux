package gold.debug.windowstolinux.app.ui.shell;

import java.awt.*;
import java.util.concurrent.FutureTask;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.appapi.task.*;

/** Presents full task review in dialogs owned by the platform privacy window. / 在平台隐私窗口所属对话框内呈现完整任务审核。 */
public final class TaskInteractionPresenter implements PlatformInteractionService.Prompts {
    /** Actual owner whose lock controller covers these dialogs. / 锁定控制器遮挡这些对话框的实际所有者。 */
    private final JFrame owner;

    /** Current platform message catalog. / 当前平台消息目录。 */
    private final MessageCatalog messages;

    /**
     * Binds presentation to the actual platform window. / 将呈现绑定到实际平台窗口。
     * @param owner owning platform frame / 所属平台窗口
     * @param messages current locale catalog / 当前语言目录
     */
    public TaskInteractionPresenter(JFrame owner, MessageCatalog messages) {
        this.owner = java.util.Objects.requireNonNull(owner);
        this.messages = java.util.Objects.requireNonNull(messages);
    }

    /**
     * Shows the complete immutable review, with cancellation selected by default. / 显示完整不可变审核，默认选择取消。
     * @param intent complete proposed effect / 完整拟执行副作用
     * @param task original task identity / 原始任务身份
     * @param cancellation cooperative prompt cancellation / 协作提示取消
     * @return explicit approval only / 仅明确同意时为真
     */
    @Override
    public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken cancellation) {
        return onEdt(() -> {
            var text = new JTextArea(task.ownerId() + " @ " + task.ownerVersion() + "\n"
                    + messages.text("task.review.task") + ": " + task.id() + "\n" + messages.text("task.review.type")
                    + ": " + intent.type() + "\n" + messages.text("task.review.servers") + ": "
                    + String.join(", ", intent.serverIds()) + "\n" + messages.text("task.review.target") + ": "
                    + intent.target() + "\n" + messages.text("task.review.revision") + ": " + intent.revision() + "\n\n"
                    + intent.effectSummary() + "\n\n" + intent.exactAction());
            text.setName("task.review.exactAction");
            text.setEditable(false);
            text.setCaretPosition(0);
            var scroll = new JScrollPane(text);
            scroll.setPreferredSize(new Dimension(760, 450));
            String[] options = {messages.text("task.review.allow"), messages.text("task.review.cancel")};
            var pane = new JOptionPane(scroll, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options,
                    options[1]);
            return options[0].equals(show(pane, messages.text("task.review.title"), cancellation));
        });
    }

    /**
     * Returns disposable characters and clears the owned input control before closing. / 返回可清除字符，并在关闭前清空所属输入控件。
     * @param title input title / 输入标题
     * @param prompt full question / 完整问题
     * @param secret whether input must be masked / 是否必须遮蔽输入
     * @param task original task identity / 原始任务身份
     * @param cancellation cooperative prompt cancellation / 协作提示取消
     * @return disposable characters or null on cancellation / 可清除字符或取消时的 null
     */
    @Override
    public char[] text(String title, String prompt, boolean secret, TaskSnapshot task, CancellationToken cancellation) {
        return onEdt(() -> {
            JTextField input = secret ? new JPasswordField(40) : new JTextField(40);
            input.setName("task.input.value");
            var explanation = new JTextArea(prompt);
            explanation.setEditable(false);
            explanation.setLineWrap(true);
            explanation.setWrapStyleWord(true);
            var scroll = new JScrollPane(explanation);
            scroll.setPreferredSize(new Dimension(620, 160));
            try {
                var pane = new JOptionPane(new Object[]{task.ownerId() + " @ " + task.ownerVersion(), scroll, input},
                        JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
                if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(show(pane, title, cancellation)))
                    return null;
                return input instanceof JPasswordField password
                        ? password.getPassword()
                        : input.getText().toCharArray();
            } finally {
                input.setText("");
            }
        });
    }

    /**
     * Owns the modal window and cancellation timer until actual disposal. / 持有模态窗口及取消计时器直至实际销毁。
     * @param pane complete dialog contents / 完整对话框内容
     * @param title reviewed dialog title / 已审核对话框标题
     * @param cancellation non-blocking cancellation signal / 非阻塞取消信号
     * @return selected value, or null for cancelled or closed dialogs / 所选值，取消或关闭时为 null
     */
    private Object show(JOptionPane pane, String title, CancellationToken cancellation) {
        if (cancellation.requested() || !owner.isDisplayable())
            return null;
        var dialog = pane.createDialog(owner, title);
        dialog.setName("task.interaction.dialog");
        var timer = new Timer(50, event -> {
            if (cancellation.requested())
                dialog.dispose();
        });
        try {
            timer.start();
            dialog.setVisible(true);
            return cancellation.requested() ? null : pane.getValue();
        } finally {
            timer.stop();
            dialog.dispose();
        }
    }

    /**
     * Marshals modal controls without moving task execution onto the event thread. / 调度模态控件，不将任务执行移至事件线程。
     * @param <T> dialog result type / 对话框结果类型
     * @param action owned dialog work / 所属对话框工作
     * @return dialog result / 对话框结果
     */
    private static <T> T onEdt(java.util.concurrent.Callable<T> action) {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Task review must be requested from a worker");
        var future = new FutureTask<>(action);
        try {
            SwingUtilities.invokeAndWait(future);
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Task review wait was interrupted");
        } catch (Exception failure) {
            throw new IllegalStateException("Task review could not be presented");
        }
    }
}
