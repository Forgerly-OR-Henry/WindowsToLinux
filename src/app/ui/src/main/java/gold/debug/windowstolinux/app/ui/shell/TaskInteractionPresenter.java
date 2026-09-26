package gold.debug.windowstolinux.app.ui.shell;

import java.awt.*;
import java.util.concurrent.FutureTask;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;

/** Presents full task review in dialogs owned by the platform privacy window. / 在平台隐私窗口所属对话框内呈现完整任务审核。 */
public final class TaskInteractionPresenter
        implements
            PlatformInteractionService.Prompts,
            gold.debug.windowstolinux.app.service.source.PlatformSourceAccess.DirectoryPicker {
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

    /** Shows the three file-conflict choices, with termination selected initially. / 显示三个文件冲突选项，初始选择终止。
     * @param conflict exact observed conflict / 精确已观察冲突
     * @param server selected server / 选定服务器
     * @param task original task identity / 原任务身份
     * @param cancellation cooperative cancellation / 协作取消
     * @return explicit user choice / 明确用户选择 */
    @Override
    public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server, TaskSnapshot task,
            CancellationToken cancellation) {
        return onEdt(() -> {
            var description = new JTextArea(messages.text("transfer.conflict.explanation") + "\n\n"
                    + messages.text("task.review.servers") + ": " + server.id() + "\n" + messages.text("transfer.local")
                    + ": " + conflict.request().localPath() + "\n" + messages.text("transfer.remote") + ": "
                    + conflict.request().remoteAbsolutePath() + "\n" + messages.text("transfer.direction") + ": "
                    + messages.text(conflict.upload() ? "transfer.upload" : "transfer.download") + "\n"
                    + messages.text("transfer.incomingBytes") + ": " + conflict.incomingBytes() + "\n"
                    + messages.text("transfer.existingBytes") + ": " + conflict.existingBytes());
            description.setEditable(false);
            description.setLineWrap(true);
            description.setWrapStyleWord(true);
            description.setName("transfer.conflict.details");
            var scroll = new JScrollPane(description);
            scroll.setPreferredSize(new Dimension(680, 280));
            String[] options = {messages.text("transfer.overwrite"), messages.text("transfer.keepBoth"),
                    messages.text("transfer.terminate")};
            var pane = new JOptionPane(new Object[]{task.ownerId() + " @ " + task.ownerVersion(), scroll},
                    JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, options[2]);
            Object result = show(pane, messages.text("transfer.conflict.title"), cancellation);
            return options[0].equals(result)
                    ? TransferConflictChoice.OVERWRITE
                    : options[1].equals(result) ? TransferConflictChoice.KEEP_BOTH : TransferConflictChoice.TERMINATE;
        });
    }

    /** Presents original-transfer retry and explicit termination after physical shutdown. / 物理停止后呈现原传输重试及明确终止。
     * @param stall last effective progress and original paths / 最后有效进展及原路径
     * @param server original admitted server / 原准入服务器
     * @param task original task identity / 原任务身份
     * @param cancellation cooperative cancellation / 协作取消
     * @return explicit retry or termination / 明确重试或终止 */
    @Override
    public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
            CancellationToken cancellation) {
        return onEdt(() -> {
            var description = new JTextArea(messages.text("transfer.stall.explanation") + "\n\n"
                    + messages.text("task.review.servers") + ": " + server.id() + "\n" + messages.text("transfer.local")
                    + ": " + stall.request().localPath() + "\n" + messages.text("transfer.remote") + ": "
                    + stall.request().remoteAbsolutePath() + "\n" + messages.text("transfer.direction") + ": "
                    + messages.text(stall.upload() ? "transfer.upload" : "transfer.download") + "\n"
                    + messages.text("transfer.stall.seconds") + ": " + stall.idleTimeout().toSeconds() + "\n"
                    + transferProgress(stall.progress()));
            description.setEditable(false);
            description.setLineWrap(true);
            description.setWrapStyleWord(true);
            description.setName("transfer.stall.details");
            var scroll = new JScrollPane(description);
            scroll.setPreferredSize(new Dimension(680, 300));
            String[] options = {messages.text("transfer.retry"), messages.text("transfer.terminate")};
            var pane = new JOptionPane(new Object[]{task.ownerId() + " @ " + task.ownerVersion(), scroll},
                    JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, options[1]);
            return options[0].equals(show(pane, messages.text("transfer.stall.title"), cancellation))
                    ? TransferStallChoice.RETRY
                    : TransferStallChoice.TERMINATE;
        });
    }

    /** Formats verification and transfer as separate byte-based stages. / 将核验及传输格式化为独立字节阶段。
     * @param progress actual physical observation / 实际物理观测
     * @return localized stage with known extent if available / 本地化阶段，带可用的已知范围 */
    @Override
    public String transferProgress(TransferProgress progress) {
        String key = switch (progress.stage()) {
            case PREPARING -> "preparing";
            case VERIFYING_SOURCE -> "source";
            case VERIFYING_TARGET -> "target";
            case VERIFYING_PREFIX -> "prefix";
            case TRANSFERRING -> "copy";
            case VERIFYING_STAGING -> "staging";
            case PUBLISHING -> "publishing";
            case VERIFYING_RESULT -> "result";
            case CLEANING -> "cleaning";
        };
        return messages.text("transfer.stage." + key) + (progress.totalBytes() < 0
                ? ""
                : " — " + progress.completedBytes() + " / " + progress.totalBytes() + " "
                        + messages.text("transfer.bytes"));
    }

    /** Confirms a local directory in the platform-owned cancellable modal window. / 在平台所属可取消模态窗口确认本地目录。
     * @param title author-supplied title / 作者提供标题
     * @param task original task identity / 原任务身份
     * @param cancellation cooperative cancellation / 协作取消
     * @return selected directory or null after cancellation / 选定目录或取消后的 null */
    @Override
    public java.nio.file.Path select(String title, TaskSnapshot task, CancellationToken cancellation) {
        return onEdt(() -> {
            var chooser = new JFileChooser();
            chooser.setName("source.directory");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setMultiSelectionEnabled(false);
            chooser.setControlButtonsAreShown(false);
            var pane = new JOptionPane(
                    new Object[]{task.ownerId() + " @ " + task.ownerVersion(),
                            messages.text("task.review.task") + ": " + task.id(), chooser},
                    JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(show(pane, title, cancellation)))
                return null;
            var selected = chooser.getSelectedFile();
            return (selected == null ? chooser.getCurrentDirectory() : selected).toPath();
        });
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
                    + intent.semantics().map(meaning -> messages.text("task.review.semantics") + ": "
                            + meaning.operation() + "\n"
                            + meaning.targets().stream()
                                    .map(target -> target.server().id() + " / " + target.kind() + " / " + target.id())
                                    .sorted().collect(java.util.stream.Collectors.joining("\n"))
                            + "\n\n").orElse("")
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
