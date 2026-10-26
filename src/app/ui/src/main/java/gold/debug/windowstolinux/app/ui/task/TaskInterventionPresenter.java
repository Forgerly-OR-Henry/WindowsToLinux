package gold.debug.windowstolinux.app.ui.task;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.task.PlatformInterventionService;
import gold.debug.windowstolinux.app.ui.control.DesktopDialogs;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;

/** One host window for original-task questions, without blocking navigation. / 原任务问题共用一个宿主窗口，不阻断页面导航。 */
public final class TaskInterventionPresenter implements AutoCloseable {
    /** Platform window that owns privacy and native dialog lifetime. / 持有隐私及原生对话框寿命的平台窗口。 */
    private final JFrame owner;

    /** Current captions for the host review flow. / 宿主核验流程的当前文字。 */
    private final MessageCatalog messages;

    /** Original-task queue and fresh acceptance boundary. / 原任务队列及重新接受校验边界。 */
    private final PlatformInterventionService service;

    /** Queue observation released when this presenter closes. / 此呈现器关闭时释放的队列观察。 */
    private final AutoCloseable subscription;

    /** Stable content reused across consecutive original-task questions. / 在连续原任务问题间复用的稳定内容。 */
    private final JPanel body = new JPanel(new BorderLayout(0, 12));

    /** Mount for one fresh author form or the checking indicator. / 单个新作者表单或检查指示器的挂载位置。 */
    private final JPanel form = new JPanel(new BorderLayout());

    /** Non-secret explanation above the current question. / 当前问题上方的非秘密说明。 */
    private final JTextArea summary = DesktopDialogs.prose("");

    /** Safe validation feedback without exposing author input. / 不暴露作者输入的安全校验反馈。 */
    private final JLabel error = new JLabel();

    /** Activity indicator between consecutive preflight questions. / 连续预检问题之间的活动指示器。 */
    private final JProgressBar checking = new JProgressBar();

    /** Explicit acceptance of the currently mounted question. / 明确接受当前已挂载问题的按钮。 */
    private final JButton accept = new JButton();

    /** Defers the original task's questions without deciding them. / 延期原任务问题但不决定其结果的按钮。 */
    private final JButton later = new JButton();

    /** Requests original cooperative cancellation through a worker. / 通过工作线程请求原任务协作取消的按钮。 */
    private final JButton cancel = new JButton();

    /** Current disposable form opening, distinct from a pending request. / 当前可释放表单打开，与待处理请求不同。 */
    private PlatformInterventionService.HostSession active;

    /** Single modeless review window shared by all mounted questions. / 全部已挂载问题共用的唯一非模态核验窗口。 */
    private JDialog dialog;

    /** Original task whose checks continue between mounted questions. / 在已挂载问题之间继续检查的原任务。 */
    private java.util.UUID checkingTask;

    /** Presentation lifetime, privacy state and reentrant-refresh guard. / 展示寿命、隐私状态及刷新重入保护。 */
    private boolean closed, locked, refreshing;

    /** Binds one host window to the original task queue without creating a business task. / 将一个宿主窗口绑定到原任务队列，不创建业务任务。
     * @param owner actual platform window / 实际平台窗口
     * @param messages current host captions / 当前宿主文字
     * @param service original-task review coordinator / 原任务核验协调器 */
    public TaskInterventionPresenter(JFrame owner, MessageCatalog messages, PlatformInterventionService service) {
        this.owner = Objects.requireNonNull(owner);
        this.messages = Objects.requireNonNull(messages);
        this.service = Objects.requireNonNull(service);
        body.add(summary, BorderLayout.NORTH);
        body.add(form, BorderLayout.CENTER);
        body.add(error, BorderLayout.SOUTH);
        accept.setName("task.intervention.accept");
        later.setName("task.intervention.later");
        cancel.setName("task.intervention.cancel");
        checking.setName("task.intervention.checking");
        checking.setIndeterminate(true);
        accept.addActionListener(ignored -> submit());
        later.addActionListener(ignored -> defer());
        cancel.addActionListener(ignored -> {
            java.util.UUID task = active != null ? active.pending().task().id() : checkingTask;
            if (task == null) return;
            cancel.setEnabled(false);
            new SwingWorker<Void, Void>() {
                /** Requests cooperative cancellation with fresh host authorization away from the EDT. / 离开 EDT 并以新宿主授权请求协作取消。
                 * @return no presentation payload / 无展示载荷
                 * @throws Exception if original cancellation is unavailable / 原取消不可用时 */
                @Override
                protected Void doInBackground() throws Exception {
                    service.cancelTask(task);
                    return null;
                }

                /** Defers cleared questions only after cancellation succeeds, retaining errors otherwise. / 仅取消成功后延期已清问题，失败则保留错误。 */
                @Override
                protected void done() {
                    cancel.setEnabled(true);
                    try {
                        get();
                        defer();
                    } catch (Exception unavailable) {
                        error.setText(messages.text("intervention.unavailable"));
                    }
                }
            }.execute();
        });
        subscription = service.observe(() -> SwingUtilities.invokeLater(this::refresh));
    }

    /** Clears visible input for a lock transition and restores only eligible pending display. / 为锁定转换清除可见输入，仅恢复具备资格的待处理展示。
     * @param value current platform privacy state / 当前平台隐私状态 */
    public void locked(boolean value) {
        requireEdt();
        locked = value;
        service.setLocked(value);
        if (value) defer();
        else refresh();
    }

    /** Mounts ready original-task questions while preserving the one-window checking lifetime. / 挂载就绪原任务问题，并保留单窗口检查寿命。 */
    private void refresh() {
        requireEdt();
        if (closed || refreshing) return;
        refreshing = true;
        try {
            if (locked || !owner.isDisplayable()) {
                dismiss();
                return;
            }
            var pending = service.pending();
            if (active != null && !active.active()) {
                active.defer();
                active = null;
                form.removeAll();
            }
            if (active != null) {
                return;
            }
            var next = pending.stream().filter(PlatformInterventionService.Pending::ready).findFirst();
            if (next.isEmpty()) {
                if (checkingTask != null && !service.taskVisible(checkingTask)) defer();
                else if (checkingTask == null || !service.taskActive(checkingTask)) dismiss();
                return;
            }
            try {
                active = service.open(next.orElseThrow().requestId());
                if (active == null) return;
                checkingTask = null;
                form.removeAll();
                form.add(active.component(), BorderLayout.CENTER);
                summary.setText(active.pending().spec().summary());
                summary.setVisible(!summary.getText().isBlank());
                error.setText("");
                accept.setText(active.pending().spec().acceptLabel());
                later.setText(messages.text("intervention.later"));
                cancel.setText(messages.text("intervention.cancel"));
                accept.setEnabled(true);
                cancel.setEnabled(true);
                if (dialog == null) {
                    dialog = new JDialog(owner, messages.text("intervention.title"), Dialog.ModalityType.MODELESS);
                    dialog.setName("task.intervention.dialog");
                    dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                    dialog.addWindowListener(new WindowAdapter() {
                        /** Treats native close as task-wide deferral, preserving unresolved decisions. / 将原生关闭视为整项任务延期，保留未决决定。
                         * @param event original native close event / 原原生关闭事件 */
                        @Override
                        public void windowClosing(WindowEvent event) {
                            defer();
                        }
                    });
                    var actions = new JPanel();
                    actions.add(later);
                    actions.add(cancel);
                    actions.add(accept);
                    DesktopDialogs.install(dialog, body, actions, 780, 0);
                    dialog.getRootPane().setDefaultButton(accept);
                }
                dialog.setTitle(active.pending().spec().title());
                DesktopDialogs.fit(dialog);
                dialog.setVisible(true);
            } catch (gold.debug.windowstolinux.appapi.task.result.ApiException unavailable) {
                error.setText(messages.text("intervention.unavailable"));
            }
        } finally {
            refreshing = false;
        }
    }

    /** Accepts validated input and retains the window only when original checks continue. / 接受已校验输入，仅原检查继续时保留窗口。 */
    private void submit() {
        if (active == null || !active.active()) return;
        var original = active;
        boolean keepOpen = original.pending().spec().keepOpen();
        try {
            original.submit();
            active = null;
            form.removeAll();
            error.setText("");
            if (keepOpen) {
                checkingTask = original.pending().task().id();
                summary.setVisible(true);
                summary.setText(messages.text("intervention.checking"));
                accept.setEnabled(false);
                form.add(checking, BorderLayout.NORTH);
                body.revalidate();
                body.repaint();
            } else dismiss();
            SwingUtilities.invokeLater(this::refresh);
        } catch (gold.debug.windowstolinux.appapi.task.result.ApiException | IllegalArgumentException invalid) {
            error.setText(messages.text("intervention.invalid"));
            DesktopDialogs.fit(dialog);
        }
    }

    /** Discards visible input and defers the original task even between questions. / 丢弃可见输入，并在问题间隙也延期原任务。 */
    private void defer() {
        if (checkingTask != null) service.deferTask(checkingTask);
        if (active != null) active.defer();
        active = null;
        checkingTask = null;
        dismiss();
    }

    /** Hides the shared native window after its form has been accepted, cleared or deferred. / 表单已接受、清除或延期后隐藏共用原生窗口。 */
    private void dismiss() {
        checkingTask = null;
        if (dialog != null) dialog.setVisible(false);
        form.removeAll();
        error.setText("");
    }

    /** Defers remaining questions and releases this presenter's queue observation. / 延期剩余问题并释放此呈现器的队列观察。 */
    @Override
    public void close() {
        requireEdt();
        if (closed) return;
        closed = true;
        defer();
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
        try {
            subscription.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Intervention observer cleanup failed", failure);
        }
    }

    /** Confines native-window and author-form lifetime changes to the EDT. / 将原生窗口及作者表单寿命变更限制到 EDT。 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Intervention window requires EDT");
    }
}
