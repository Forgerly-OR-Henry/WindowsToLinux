package gold.debug.windowstolinux.app.ui.task;

import gold.debug.windowstolinux.app.ui.control.DesktopDialogs;
import com.formdev.flatlaf.util.UIScale;

import java.awt.*;
import java.util.concurrent.FutureTask;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.appapi.file.action.DeleteEffect;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Presents full task review in dialogs owned by the platform privacy window. / 在平台隐私窗口所属对话框内呈现完整任务审核。 */
public final class TaskInteractionPresenter
    implements
    PlatformInteractionService.Prompts,
    gold.debug.windowstolinux.app.service.file.FileDirectoryPicker {
    /** Actual owner whose lock controller covers these dialogs. / 锁定控制器遮挡这些对话框的实际所有者。 */
    private final JFrame owner;

    /** Current platform message catalog. / 当前平台消息目录。 */
    private final MessageCatalog messages;

    /** Optional queue for ordinary tasks with an exact original intervention route. / 具有精确原介入路由的普通任务所用可选队列。 */
    private final gold.debug.windowstolinux.app.service.task.PlatformInterventionService interventions;

    /** Worker-side server labels used without changing reviewed server identities. / 不改变待审服务器身份的工作线程侧服务器标签。 */
    private java.util.function.Supplier<java.util.Map<String, String>> serverNames = java.util.Map::of;

    /**
     * Binds presentation to the actual platform window. / 将呈现绑定到实际平台窗口。
     * @param owner owning platform frame / 所属平台窗口
     * @param messages current locale catalog / 当前语言目录
     */
    public TaskInteractionPresenter(JFrame owner, MessageCatalog messages) {
        this(owner, messages, null);
    }

    /** Shares the platform privacy owner with optional original-task review sessions. / 与可选原任务核验会话共用平台隐私所有者。
     * @param owner actual platform window / 实际平台窗口
     * @param messages current host captions / 当前宿主文字
     * @param interventions optional ordinary-task review queue / 可选普通任务核验队列 */
    public TaskInteractionPresenter(JFrame owner, MessageCatalog messages,
                                    gold.debug.windowstolinux.app.service.task.PlatformInterventionService interventions) {
        this.owner = java.util.Objects.requireNonNull(owner);
        this.messages = java.util.Objects.requireNonNull(messages);
        this.interventions = interventions;
    }

    /** Installs worker-side display labels while preserving exact action identities. / 安装工作线程侧展示标签并保留精确动作身份。
     * @param lookup current server labels keyed by original identifiers / 按原标识索引的当前服务器标签 */
    public void serverNames(java.util.function.Supplier<java.util.Map<String, String>> lookup) {
        serverNames = java.util.Objects.requireNonNull(lookup);
    }

    /** Checks original ordinary-task eligibility without routing recovery or short tasks into the queue. / 检查原普通任务资格，避免将恢复或短任务路由到队列。
     * @param task original task observation / 原任务观察
     * @return whether its exact live intervention route is available / 其精确存活介入路由是否可用 */
    private boolean managed(TaskSnapshot task) {
        return interventions != null && interventions.canPresent(task);
    }

    /** Presents an explicit bounded choice in the original task's shared review window. / 在原任务共用核验窗口呈现明确有界选项。
     * @param task original task / 原任务
     * @param cancellation original cooperative signal / 原协作取消信号
     * @param explanation short question meaning / 简短问题含义
     * @param details full details shown only on request / 按需展开的完整详情
     * @param choices offered captions in result-index order / 按结果索引排列的选项文字
     * @param initial safe initially selected index / 安全初始选中索引
     * @return explicitly accepted index, or null after withdrawal / 明确接受的索引，撤回后为空 */
    private Integer choice(TaskSnapshot task, CancelToken cancellation, String explanation, String details,
                           String[] choices, int initial) {
        return queued(task, cancellation,
            new gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionSpec(
                messages.text("intervention.title"), explanation, messages.text("intervention.continue"), true),
            () -> new gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionForm<Integer>() {
                /** Offered alternatives retained only for this form opening. / 仅为本次表单打开保留的备选项。 */
                private final JComboBox<String> options = new JComboBox<>(choices);

                /** Current option and detail view mounted by the host. / 宿主挂载的当前选项及详情视图。 */
                private final JPanel panel = create();

                /** Builds the safe initial choice and optional evidence region. / 创建安全初始选项及可选依据区域。
                 * @return disposable choice view / 可释放选项视图 */
                private JPanel create() {
                    options.setName("task.intervention.choice");
                    options.setSelectedIndex(initial);
                    var content = new JPanel(new BorderLayout(0, 12));
                    content.add(options, BorderLayout.NORTH);
                    content.add(detailContent(details), BorderLayout.CENTER);
                    return content;
                }

                /** Returns this opening's host-mounted choice component. / 返回本次打开的宿主挂载选项组件。
                 * @return current choice panel / 当前选项面板 */
                @Override
                public JComponent component() {
                    return panel;
                }

                /** Captures only the explicitly accepted current selection. / 仅捕获明确接受的当前选项。
                 * @return accepted option index / 已接受选项索引 */
                @Override
                public Integer submit() {
                    return options.getSelectedIndex();
                }

                /** Ends the option-only form lifetime before the host unmounts it. / 在宿主卸载前结束纯选项表单寿命。 */
                @Override
                public void close() {
                }
            });
    }

    /** Keeps complete technical evidence collapsed until the user requests it. / 将完整技术依据收起至用户明确展开。
     * @param text complete reviewed details / 完整待审详情
     * @return detail toggle and bounded evidence view / 详情开关及有界依据视图 */
    private JComponent detailContent(String text) {
        var panel = new JPanel(new BorderLayout(0, 8));
        var details = DesktopDialogs.scroll(DesktopDialogs.prose(text));
        details.setVisible(false);
        var toggle = new JToggleButton(messages.text("intervention.details"));
        toggle.setName("task.intervention.details");
        toggle.addActionListener(ignored -> {
            details.setVisible(toggle.isSelected());
            panel.revalidate();
            var window = SwingUtilities.getWindowAncestor(panel);
            if (window instanceof JDialog owned) DesktopDialogs.fit(owned);
        });
        panel.add(toggle, BorderLayout.NORTH);
        panel.add(details, BorderLayout.CENTER);
        return panel;
    }

    /** Reads display labels on the requesting worker before constructing Swing input. / 在构造 Swing 输入前于请求工作线程读取展示标签。
     * @return current server identifier-to-label mapping / 当前服务器标识与标签映射 */
    private java.util.Map<String, String> names() {
        return serverNames.get();
    }

    /** Waits on the original review future while preserving cancellation and security-generation handling. / 等待原核验未来值并保留取消及安全代次处理。
     * @param <T> accepted input type / 已接受输入类型
     * @param task authenticated original task / 已认证原任务
     * @param cancellation original prompt cancellation / 原提示取消信号
     * @param spec host question presentation / 宿主问题展示
     * @param form fresh EDT form factory / 新 EDT 表单工厂
     * @return accepted input, or null after withdrawal or unavailability / 已接受输入，撤回或不可用后为空 */
    private <T> T queued(TaskSnapshot task, CancelToken cancellation,
                         gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionSpec spec,
                         gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionFormFactory<T> form) {
        gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionSession<T> session = null;
        try {
            session = interventions.request(task, cancellation, spec, form);
            var result = session.completion().toCompletableFuture();
            for (; ; ) {
                if (cancellation.requested()) {
                    session.cancel();
                    return null;
                }
                try {
                    return result.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException waiting) {
                }
            }
        } catch (InterruptedException interrupted) {
            if (session != null) session.cancel();
            Thread.currentThread().interrupt();
            return null;
        } catch (gold.debug.windowstolinux.appapi.task.result.ApiException
                 | java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException unavailable) {
            return null;
        }
    }

    /** Shows the distinct directory or file collision choices. / 显示分别对应目录或文件的冲突选择。
     * @param source actual source / 实际源
     * @param target actual target / 实际目标
     * @param directory directory layer / 目录层
     * @param task original task / 原任务
     * @param cancellation original cancellation / 原取消
     * @return explicit choice or skip on dismissal / 明确选择，关闭时不处理 */
    @Override
    public int fileConflict(gold.debug.windowstolinux.appapi.file.model.FileInfo source,
                            gold.debug.windowstolinux.appapi.file.model.FileInfo target, boolean directory,
                            TaskSnapshot task, CancelToken cancellation) {
        if (managed(task)) {
            Integer selected = choice(task, cancellation, messages.text("intervention.copyConflict"),
                messages.text("file.source") + ": " + source.actual().key() + "\n"
                    + messages.text("file.target") + ": " + target.actual().key() + "\n"
                    + source.bytes() + " / " + target.bytes(),
                new String[]{messages.text("file.copy"), messages.text(directory ? "file.merge" : "transfer.overwrite"), messages.text("file.skip")}, 2);
            return selected == null ? 2 : selected;
        }
        return onEdt(() -> {
            var description = new JTextArea(messages.text(directory ? "file.conflict.directory" : "file.conflict.file") + "\n\n"
                + messages.text("file.source") + ": " + source.actual().key() + "\n" + messages.text("file.target") + ": " + target.actual().key()
                + "\n" + messages.text("transfer.incomingBytes") + ": " + source.bytes() + "\n" + messages.text("transfer.existingBytes") + ": " + target.bytes());
            description.setEditable(false);
            description.setLineWrap(true);
            description.setWrapStyleWord(true);
            description.setName("file.conflict.details");
            var scroll = new JScrollPane(description);
            scroll.setPreferredSize(UIScale.scale(new Dimension(680, 280)));
            String[] options = {messages.text("file.copy"), messages.text(directory ? "file.merge" : "transfer.overwrite"), messages.text("file.skip")};
            var pane = new JOptionPane(new Object[]{task.ownerId() + " @ " + task.ownerVersion(), scroll}, JOptionPane.QUESTION_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, options, options[2]);
            Object result = show(pane, messages.text("file.conflict.title"), cancellation);
            return options[0].equals(result) ? 0 : options[1].equals(result) ? 1 : 2;
        });
    }

    /** Shows the three file-conflict choices, with termination selected initially. / 显示三个文件冲突选项，初始选择终止。
     * @param conflict exact observed conflict / 精确已观察冲突
     * @param server selected server / 选定服务器
     * @param task original task identity / 原任务身份
     * @param cancellation cooperative cancellation / 协作取消
     * @return explicit user choice / 明确用户选择 */
    @Override
    public TransferConflictChoice transferConflict(TransferConflict conflict, ServerId server, TaskSnapshot task,
                                                   CancelToken cancellation) {
        if (managed(task)) {
            Integer selected = choice(task, cancellation, messages.text("intervention.copyConflict"),
                messages.text("transfer.local") + ": " + conflict.request().localPath() + "\n"
                    + messages.text("transfer.remote") + ": " + conflict.request().remoteAbsolutePath() + "\n"
                    + conflict.incomingBytes() + " / " + conflict.existingBytes(),
                new String[]{messages.text("transfer.overwrite"), messages.text("transfer.keepBoth"), messages.text("file.skip")}, 2);
            return selected == null || selected == 2 ? TransferConflictChoice.SKIP
                : selected == 0 ? TransferConflictChoice.OVERWRITE : TransferConflictChoice.KEEP_BOTH;
        }
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
            scroll.setPreferredSize(UIScale.scale(new Dimension(680, 280)));
            String[] options = {messages.text("transfer.overwrite"), messages.text("transfer.keepBoth"),
                messages.text("file.skip")};
            var pane = new JOptionPane(new Object[]{task.ownerId() + " @ " + task.ownerVersion(), scroll},
                JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, options[2]);
            Object result = show(pane, messages.text("transfer.conflict.title"), cancellation);
            return options[0].equals(result)
                ? TransferConflictChoice.OVERWRITE
                : options[1].equals(result) ? TransferConflictChoice.KEEP_BOTH : TransferConflictChoice.SKIP;
        });
    }

    /** Presents original-transfer retry and explicit termination after physical shutdown. / 物理停止后呈现原传输重试及明确终止。
     * @param stall last effective progress and original paths / 最后有效进展及原路径
     * @param server original admitted server / 原准入服务器
     * @param task original task identity / 原任务身份
     * @param cancellation cooperative cancellation / 协作取消
     * @return explicit retry or termination / 明确重试或终止 */
    @Override
    public TransferStallChoice transferStalled(TransferStall stall, ServerId server, TaskSnapshot task,
                                               CancelToken cancellation) {
        if (managed(task)) {
            Integer selected = choice(task, cancellation, messages.text("intervention.transferStopped"),
                messages.text("transfer.stall.explanation") + "\n" + stall.request().localPath() + "\n"
                    + stall.request().remoteAbsolutePath() + "\n" + transferProgress(stall.progress()),
                new String[]{messages.text("transfer.retry"), messages.text("transfer.terminate")}, 1);
            return Integer.valueOf(0).equals(selected) ? TransferStallChoice.RETRY : TransferStallChoice.TERMINATE;
        }
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
            scroll.setPreferredSize(UIScale.scale(new Dimension(680, 300)));
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
    public java.nio.file.Path select(String title, TaskSnapshot task, CancelToken cancellation) {
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
    public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
        return approve(intent, task, cancellation, "");
    }

    /** Keeps the reason for a human choice visible without changing the authorized effect. / 保留人工取舍原因，不改变获准副作用。
     * @param intent exact immutable review / 精确不可变审核
     * @param task original task identity / 原任务身份
     * @param cancellation original privacy and cancellation signal / 原隐私及取消信号
     * @param explanation independent human-readable choice explanation / 独立且供用户阅读的取舍说明
     * @return explicit current approval / 明确且当前有效的同意 */
    @Override
    public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation, String explanation) {
        var labels = names();
        if (interventions != null && interventions.canPresent(task)) {
            return Boolean.TRUE.equals(queued(task, cancellation,
                new gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionSpec(
                    messages.text("intervention.title"), "", messages.text("task.review.allow"), true),
                () -> new gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionForm<Boolean>() {
                    /** Exact reviewed action behind its plain-language overview. / 通俗概览所对应的精确待审动作。 */
                    private final JComponent content = reviewContent(intent, task, labels, true, explanation);

                    /** Returns the action review for this one host opening. / 返回本次宿主打开的动作核验视图。
                     * @return current review component / 当前核验组件 */
                    @Override
                    public JComponent component() {
                        return content;
                    }

                    /** Records explicit acceptance without issuing an action grant. / 记录明确接受，但不签发动作授权。
                     * @return affirmative human decision / 肯定的人工决定 */
                    @Override
                    public Boolean submit() {
                        return Boolean.TRUE;
                    }

                    /** Ends the read-only review before the host releases its component. / 在宿主释放组件前结束只读核验。 */
                    @Override
                    public void close() {
                    }
                }));
        }
        return onEdt(() -> {
            String[] options = {messages.text("task.review.allow"), messages.text("task.review.cancel")};
            var pane = new JOptionPane(reviewContent(intent, task, labels, false, explanation), JOptionPane.WARNING_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, options, options[1]);
            return options[0].equals(show(pane, messages.text("task.review.title"), cancellation));
        });
    }

    /** Couples a readable overview to the unchanged exact action and optional technical evidence. / 将可读概览与未改变的精确动作及可选技术依据对应。
     * @param intent immutable action under review / 待审不可变动作
     * @param task original task identity / 原任务身份
     * @param labels current server display labels / 当前服务器展示标签
     * @param simple whether the compact host-session overview is used / 是否使用紧凑宿主会话概览
     * @param explanation independent human-readable choice explanation / 独立且供用户阅读的取舍说明
     * @return owned overview and expandable exact-action evidence / 所属概览及可展开精确动作依据 */
    private JComponent reviewContent(ActionIntent intent, TaskSnapshot task, java.util.Map<String, String> labels, boolean simple, String explanation) {
        var text = new JTextArea(task.ownerId() + " @ " + task.ownerVersion() + "\n"
            + messages.text("task.review.task") + ": " + task.id() + "\n" + messages.text("task.review.type")
            + ": " + intent.type() + "\n" + messages.text("task.review.servers") + ": "
            + String.join(", ", intent.serverIds()) + "\n" + messages.text("task.review.target") + ": "
            + intent.target() + "\n" + messages.text("task.review.revision") + ": " + intent.revision() + "\n\n"
            + intent.semantics().map(meaning -> messages.text("task.review.semantics") + ": "
            + meaning.operation() + "\n"
            + meaning.targets().stream()
            .map(target -> target.serverId() + " / " + target.kind() + " / " + target.id())
            .sorted().collect(java.util.stream.Collectors.joining("\n"))
            + "\n\n").orElse("")
            + deletionWarning(intent) + intent.effectSummary() + "\n\n" + intent.exactAction());
        text.setName("task.review.exactAction");
        text.setEditable(false);
        text.setCaretPosition(0);
        var scroll = new JScrollPane(text);
        scroll.setPreferredSize(UIScale.scale(new Dimension(760, 450)));
        boolean replacingHostKey = task.ownerId().equals(gold.debug.windowstolinux.app.service.task.PlatformTaskAdmission.PLATFORM_OWNER)
            && intent.type().equals("platform.ssh.replace-trust");
        var summary = simple ? simpleReviewSummary(intent, labels, explanation) : reviewSummary(intent, replacingHostKey);
        summary.setName("task.review.summary");
        var summaryScroll = DesktopDialogs.scroll(summary);
        summaryScroll.setPreferredSize(UIScale.scale(new Dimension(760, 260)));
        var content = new JPanel(new BorderLayout(0, UIScale.scale(8)));
        var overview = new JPanel(new BorderLayout(0, UIScale.scale(6)));
        overview.add(simple ? summary : summaryScroll, BorderLayout.CENTER);
        var details = new JCheckBox(messages.text("task.review.details"));
        details.setName("task.review.details");
        overview.add(details, BorderLayout.SOUTH);
        content.add(overview, BorderLayout.NORTH);
        scroll.setVisible(false);
        content.add(scroll, BorderLayout.CENTER);
        details.addActionListener(ignored -> {
            scroll.setVisible(details.isSelected());
            var window = SwingUtilities.getWindowAncestor(content);
            if (window instanceof JDialog owned) DesktopDialogs.fit(owned);
        });
        return content;
    }

    /** Shows the operation meaning and actual selected servers with readable labels. / 以可读标签显示操作含义及实际所选服务器。
     * @param intent immutable reviewed action / 不可变待审动作
     * @param labels original server identifiers mapped to captions / 原服务器标识与文字映射
     * @param explanation independent human-readable choice explanation / 独立且供用户阅读的取舍说明
     * @return compact action overview / 紧凑动作概览 */
    private JPanel simpleReviewSummary(ActionIntent intent, java.util.Map<String, String> labels, String explanation) {
        var rows = new java.util.ArrayList<JComponent>();
        rows.add(DesktopDialogs.prose(deletionWarning(intent) + intent.semantics()
            .map(value -> operationTitle(value.operation())).orElseGet(() -> operationTitle(intent.type()))));
        if (intent.type().equals("deployment.workflow.authority")) {
            rows.add(DesktopDialogs.prose(messages.text(intent.exactAction().lines().anyMatch("approval=FULL_CONTROL"::equals)
                ? "intervention.authorityFull" : "intervention.authorityReviewed")));
            if (intent.exactAction().lines().anyMatch("rememberAuthorization=true"::equals))
                rows.add(DesktopDialogs.prose(messages.text("intervention.remember")));
        }
        String lifecycle = lifecycleAction(intent).strip();
        if (!lifecycle.isBlank()) rows.add(DesktopDialogs.prose(lifecycle));
        if (!explanation.isBlank()) rows.add(DesktopDialogs.prose(explanation));
        String servers = intent.serverIds().stream()
            .map(id -> labels.getOrDefault(id, messages.text("intervention.selectedServer")))
            .distinct().collect(java.util.stream.Collectors.joining(", "));
        if (!servers.isBlank()) rows.add(factRow(messages.text("intervention.server"), servers, false));
        return DesktopDialogs.fields(rows.toArray(JComponent[]::new));
    }

    /** Labels actual paths and identities without altering the reviewed action. / 标注实际路径与编号，不改审核动作。 */
    private JPanel reviewSummary(ActionIntent intent, boolean replacingHostKey) {
        var rows = new java.util.ArrayList<JComponent>();
        rows.add(DesktopDialogs.prose(replacingHostKey ? messages.text("server.identity.review")
            : deletionWarning(intent) + reviewEffectSummary(intent)));
        var facts = new java.util.ArrayList<String[]>();
        if (replacingHostKey) {
            for (String line : intent.exactAction().split("\\R")) {
                String previous = "Previous SSH wire-key fingerprint=", observed = "Observed SSH wire-key fingerprint=";
                if (line.startsWith(previous))
                    facts.add(new String[]{messages.text("server.identity.previous"), line.substring(previous.length())});
                else if (line.startsWith(observed))
                    facts.add(new String[]{messages.text("server.identity.observed"), line.substring(observed.length())});
                else if (!line.isBlank()) facts.add(new String[]{messages.text("task.review.target"), line});
            }
        } else {
            intent.semantics().ifPresent(meaning -> {
                facts.add(new String[]{messages.text("task.review.operation"), operationTitle(meaning.operation())});
                String lifecycle = lifecycleAction(intent).strip();
                if (!lifecycle.isBlank()) facts.add(new String[]{messages.text("task.review.operation"), lifecycle});
                facts.add(new String[]{messages.text("task.review.operationId"), meaning.operation()});
            });
        }
        facts.add(new String[]{messages.text("task.review.serverId"), String.join(", ", intent.serverIds())});
        intent.semantics().ifPresent(meaning -> meaning.targets().stream()
            .sorted(java.util.Comparator.comparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::serverId)
                .thenComparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::kind)
                .thenComparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::id))
            .forEach(target -> {
                facts.add(new String[]{messages.text("task.review.targetKind"), targetTitle(target.kind())});
                facts.add(new String[]{messages.text(target.id().startsWith("/") || target.id().matches("^[A-Za-z]:[\\\\/].*")
                    ? "task.review.path" : "task.review.targetId"), target.id()});
                if (intent.serverIds().size() != 1 || !intent.serverIds().contains(target.serverId()))
                    facts.add(new String[]{messages.text("task.review.serverId"), target.serverId()});
            }));
        if (intent.semantics().isEmpty() && !replacingHostKey)
            facts.add(new String[]{messages.text("task.review.targetId"), intent.target()});
        rows.add(factRow(messages.text("task.review.field"), messages.text("task.review.value"), true));
        for (var fact : facts) rows.add(factRow(fact[0], fact[1], false));
        return DesktopDialogs.fields(rows.toArray(JComponent[]::new));
    }

    /** Localizes fixed capability warnings without changing the original reviewed contract. / 本地化固定能力警示，不改变原审核契约。 */
    private String reviewEffectSummary(ActionIntent intent) {
        return switch (intent.type()) {
            case "platform.ssh.execute" -> messages.text("task.review.remoteCommand");
            case "platform.sftp.upload" -> messages.text("task.review.upload");
            case "platform.sftp.download" -> messages.text("task.review.download");
            default -> intent.effectSummary();
        };
    }

    private JPanel factRow(String label, String value, boolean heading) {
        var row = new JPanel(new BorderLayout(UIScale.scale(12), UIScale.scale(6)));
        row.setOpaque(false);
        var caption = new JLabel(label);
        caption.putClientProperty("html.disable", true);
        caption.setPreferredSize(new Dimension(UIScale.scale(140), caption.getPreferredSize().height));
        var content = DesktopDialogs.prose(value);
        content.setFont(UIManager.getFont("Label.font"));
        content.getAccessibleContext().setAccessibleName(label);
        caption.setLabelFor(content);
        if (heading) {
            caption.setFont(caption.getFont().deriveFont(Font.BOLD));
            content.setFont(content.getFont().deriveFont(Font.BOLD));
        } else row.add(new JSeparator(), BorderLayout.NORTH);
        row.add(caption, BorderLayout.WEST);
        row.add(content, BorderLayout.CENTER);
        return row;
    }

    private String operationTitle(String operation) {
        String key = switch (operation) {
            case "deployment.helper.probe.v1", "deployment.helper.install.status.v1" -> "checkHelper";
            case "deployment.helper.install.tracked.v1" -> "installHelper";
            case "deployment.handoff.runtime-settings-preview.v1" -> "previewSettings";
            case "deployment.handoff.runtime-settings-derive.v1" -> "prepareSettings";
            case "deployment.handoff.runtime-settings-status.v1" -> "checkSettings";
            case "deployment.handoff.snapshot-retained.v1" -> "saveRecovery";
            case "deployment.handoff.activate-retained.v1" -> "activateVersion";
            case "deployment.handoff.retained-status.v1" -> "checkSettings";
            case "deployment.handoff.finish-retained.v1" -> "finish";
            case "deployment.handoff.managed-target-status.v1" -> "checkStatus";
            case "deployment.candidate.host-probe.v1", "deployment.candidate.capacity.v1",
                 "deployment.build.preflight.v1" -> "checkEnvironment";
            case "deployment.toolchain.prepare.v1", "deployment.toolchain.bind.v1" -> "prepareTools";
            case "deployment.candidate.create.v1", "deployment.candidate.upload.v1", "deployment.candidate.verify.v1",
                 "deployment.candidate.query.v1" -> "prepareSource";
            case "deployment.build.run.v1" -> "build";
            case "deployment.publication.config.v1" -> "configure";
            case "deployment.publication.snapshot.v1" -> "snapshot";
            case "deployment.publication.publish.v1" -> "publish";
            case "deployment.publication.health.v1" -> "checkHealth";
            case "deployment.publication.inspect.v1", "deployment.publication.observe.v1",
                 "deployment.handoff.managed-current-status.v1" -> "checkStatus";
            case "deployment.application.lifecycle.v1", "deployment.publication.stop-old.v1" -> "manageRuntime";
            case "deployment.workflow.v1", "deployment.workflow.authority" -> "reviewWorkflow";
            case "db.install" -> "installDatabase";
            case "db.backup" -> "confirmDatabaseBackup";
            case "db.downtime" -> "allowDatabaseDowntime";
            case "db.replacement" -> "replaceDatabase";
            case "db.restored" -> "confirmDatabaseRestored";
            case "db.existing-schema" -> "changeExistingDatabase";
            case "deployment.assisted.manual-continuation" -> "continueWithoutAdvice";
            case "deployment.assisted.retry" -> "retryReviewedStep";
            case "deployment.assisted.unresolved" -> "unresolvedAdvice";
            case "deployment.assisted.environment-recovery" -> "recoverEnvironment";
            case "deployment.assisted.build-review" -> "reviewBuildChanges";
            case "deployment.agent.git-source" -> "useSelectedSource";
            case "deployment.publication.maintenance-end.v1", "deployment.publication.retain.v1",
                 "deployment.publication.candidate-cleanup.v1" -> "finish";
            default -> "other";
        };
        return messages.text("task.review.operation." + key);
    }

    private String targetTitle(String kind) {
        String key = switch (kind) {
            case "deployment-release" -> "application";
            case "deployment-handoff" -> "runtime";
            case "directory" -> "directory";
            case "deployment-candidate" -> "source";
            case "deployment-snapshot" -> "snapshot";
            case "deployment-configuration" -> "configuration";
            case "deployment-secret" -> "credential";
            case "deployment-build" -> "tools";
            case "deployment-workspace" -> "workspace";
            case "deployment-host" -> "server";
            case "deployment-application-locks" -> "maintenance";
            case "deployment-helper" -> "helper";
            default -> "other";
        };
        return messages.text("task.review.targetKind." + key) + (key.equals("other") ? " (" + kind + ")" : "");
    }

    /** Lets the user choose only an offered exact deletion effect, with refusal selected initially. / 仅允许用户选择已提供的精确删除效果，初始选择暂不处理。
     * @param choices immutable effects available for the observed object / 观测对象可用的不可变效果
     * @param explanation optional metadata-only purpose information / 可选仅元数据用途说明
     * @param task original task identity / 原任务身份
     * @param cancellation cooperative prompt cancellation / 协作提示取消
     * @return selected effect, or null for refusal or dismissal / 选定效果，暂不处理或关闭时为 null */
    @Override
    public DeleteEffect delete(java.util.Map<DeleteEffect, ActionIntent> choices, String explanation, TaskSnapshot task, CancelToken cancellation) {
        if (managed(task)) {
            var effects = new java.util.ArrayList<DeleteEffect>();
            if (choices.containsKey(DeleteEffect.RECYCLE)) effects.add(DeleteEffect.RECYCLE);
            if (choices.containsKey(DeleteEffect.PERMANENT)) effects.add(DeleteEffect.PERMANENT);
            var labels = new java.util.ArrayList<String>();
            for (var effect : effects)
                labels.add(messages.text(effect == DeleteEffect.RECYCLE
                    ? "task.review.delete.recycleChoice" : "task.review.delete.permanentChoice"));
            labels.add(messages.text("task.review.delete.declineChoice"));
            String details = explanation + "\n" + choices.values().stream()
                .map(value -> value.effectSummary() + "\n" + value.exactAction())
                .collect(java.util.stream.Collectors.joining("\n\n"));
            String warning = choices.values().stream().map(this::deletionWarning).distinct()
                .collect(java.util.stream.Collectors.joining("\n"));
            Integer selected = choice(task, cancellation, warning, details, labels.toArray(String[]::new), labels.size() - 1);
            return selected != null && selected < effects.size() ? effects.get(selected) : null;
        }
        return onEdt(() -> {
            var details = new StringBuilder(task.ownerId() + " @ " + task.ownerVersion() + "\n"
                + messages.text("task.review.task") + ": " + task.id() + "\n\n"
                + messages.text(choices.containsKey(DeleteEffect.RECYCLE) ? "task.review.delete.choose" : "task.review.delete.unavailable") + "\n\n");
            for (DeleteEffect effect : DeleteEffect.values()) {
                ActionIntent intent = choices.get(effect);
                if (intent != null)
                    details.append(messages.text(effect == DeleteEffect.RECYCLE ? "task.review.delete.recycleChoice" : "task.review.delete.permanentChoice"))
                        .append("\n").append(deletionWarning(intent)).append(intent.effectSummary()).append("\n\n");
            }
            details.append(explanation);
            var text = new JTextArea(details.toString());
            text.setName("task.review.exactAction");
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setCaretPosition(0);
            var scroll = new JScrollPane(text);
            scroll.setPreferredSize(UIScale.scale(new Dimension(760, 450)));
            String recycle = messages.text("task.review.delete.recycleChoice");
            String permanent = messages.text("task.review.delete.permanentChoice");
            String decline = messages.text("task.review.delete.declineChoice");
            String[] options = choices.containsKey(DeleteEffect.RECYCLE)
                ? new String[]{recycle, permanent, decline} : new String[]{permanent, decline};
            var pane = new JOptionPane(scroll, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, decline);
            Object result = show(pane, messages.text("task.review.delete.title"), cancellation);
            return choices.containsKey(DeleteEffect.RECYCLE) && recycle.equals(result) ? DeleteEffect.RECYCLE
                : permanent.equals(result) ? DeleteEffect.PERMANENT : null;
        });
    }

    /** Shows localized recovery and File/Link facts without changing the reviewed intent. / 显示本地化恢复及 File/Link 事实，不改变审核意图。
     * @param intent complete immutable review / 完整不可变审核
     * @return localized warning for a canonical deletion, otherwise empty / 规范删除的本地化提示，否则为空 */
    private String deletionWarning(ActionIntent intent) {
        if (!intent.type().equals("file-delete")) return "";
        String operation = intent.exactAction().split("\n", 2)[0];
        if (!operation.matches("delete (File|Link) (RECYCLE|PERMANENT)")) return "";
        String semantics = messages.text(operation.startsWith("delete Link ")
            ? "task.review.delete.link" : "task.review.delete.file");
        String recovery = messages.text(operation.endsWith(" RECYCLE")
            ? "task.review.delete.recycle" : "task.review.delete.permanent");
        return semantics + "\n" + recovery + "\n\n";
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
    public char[] text(String title, String prompt, boolean secret, TaskSnapshot task, CancelToken cancellation) {
        if (interventions != null && interventions.canPresent(task)) {
            return queued(task, cancellation,
                new gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionSpec(
                    messages.text("intervention.title"), "", messages.text("intervention.continue"), true),
                () -> new gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionForm<char[]>() {
                    /** Input document scoped to this fresh host opening. / 仅属于本次新宿主打开的输入文档。 */
                    private final JTextField input = secret ? new JPasswordField(40) : new JTextField(40);

                    /** Current prompt and input mounted in the shared review window. / 共用核验窗口中挂载的当前问题及输入。 */
                    private final JPanel content = create();

                    /** Builds the complete question with its selected masking policy. / 按选定遮蔽策略创建完整问题。
                     * @return fresh prompt and input panel / 新问题及输入面板 */
                    private JPanel create() {
                        input.setName("task.input.value");
                        var panel = new JPanel(new BorderLayout(0, 12));
                        String purpose = switch (title) {
                            case "Database administrator credential" ->
                                messages.text("intervention.databaseAdministratorPassword");
                            case "Existing database credential" -> messages.text("intervention.databasePassword");
                            default -> title;
                        };
                        panel.add(DesktopDialogs.prose(secret ? purpose : prompt), BorderLayout.NORTH);
                        if (secret && !prompt.isBlank()) panel.add(detailContent(prompt), BorderLayout.CENTER);
                        panel.add(input, BorderLayout.SOUTH);
                        return panel;
                    }

                    /** Returns only this opening's prompt and input component. / 仅返回本次打开的问题及输入组件。
                     * @return current host-mounted panel / 当前宿主挂载面板 */
                    @Override
                    public JComponent component() {
                        return content;
                    }

                    /** Transfers accepted characters to the original worker for disposable handling. / 将已接受字符交给原工作线程进行可清除处理。
                     * @return caller-owned character buffer / 调用方持有的字符缓冲区 */
                    @Override
                    public char[] submit() {
                        return input instanceof JPasswordField password ? password.getPassword() : input.getText().toCharArray();
                    }

                    /** Clears the input document whenever this form opening ends. / 本次表单打开结束时清除输入文档。 */
                    @Override
                    public void close() {
                        input.setText("");
                    }
                });
        }
        return onEdt(() -> {
            JTextField input = secret ? new JPasswordField(40) : new JTextField(40);
            input.setName("task.input.value");
            var explanation = new JTextArea(prompt);
            explanation.setEditable(false);
            explanation.setLineWrap(true);
            explanation.setWrapStyleWord(true);
            var scroll = new JScrollPane(explanation);
            scroll.setPreferredSize(UIScale.scale(new Dimension(620, 160)));
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
    private Object show(JOptionPane pane, String title, CancelToken cancellation) {
        if (cancellation.requested() || !owner.isDisplayable())
            return null;
        var dialog = DesktopDialogs.create(pane, owner, title);
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

    private String lifecycleAction(ActionIntent intent) {
        if (intent.semantics().filter(value -> value.operation().equals("deployment.application.lifecycle.v1")).isEmpty())
            return "";
        var command = java.util.regex.Pattern.compile(
                "'lifecycle-(?:deployment|container)' '[A-Za-z0-9._-]+' '(start|stop|restart|enable|disable)' '[a-f0-9]{64}' '[a-f0-9]{64}'\\s*\\z")
            .matcher(intent.exactAction());
        return command.find() ? "\n\n" + messages.text("task.review.lifecycle." + command.group(1)) : "";
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
