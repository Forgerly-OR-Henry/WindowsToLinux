package gold.debug.windowstolinux.mod.deployment.ui.environment;

import java.awt.BorderLayout;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.environment.DeploymentEnvironmentWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.DeploymentStandardPageService;
import gold.debug.windowstolinux.mod.deployment.ui.feedback.DeploymentTaskFeedback;

/** Prepares an explicitly selected original server through the real recoverable environment workflow. / 经真实可恢复环境流程准备明确选定的原服务器。 */
public final class DeploymentEnvironmentPage implements ModPage {
    /** Actual short server lookup and host navigation. / 实际短服务器查询及宿主导航。 */
    private final DeploymentStandardPageService lookups;

    /** Original environment task submission and recovery. / 原环境任务提交及恢复。 */
    private final DeploymentEnvironmentWorkflow workflow;

    /** Owned root retaining its selection and original task. / 保留选择及原任务的所属根。 */
    private final JPanel root = new JPanel(new BorderLayout(8, 8));

    /** Explicit saved server selection, initially empty. / 初始为空的明确保存服务器选择。 */
    private final JComboBox<ServerInfo> servers = new JComboBox<>();

    /** Actual short lookup. / 实际短查询。 */
    private final JButton refresh = new JButton();

    /** Original target preparation. / 原目标准备。 */
    private final JButton prepare = new JButton();

    /** Actual finite server management destination. / 实际有限服务器管理目的页。 */
    private final JButton manage = new JButton();

    /** Fixed supported scope and actual query issue. / 固定支持范围及实际查询问题。 */
    private final JTextArea scope = new JTextArea(5, 50);

    /** All page storage and admission stays off the EDT. / 页面全部存储及准入均在EDT外。 */
    private final ExecutorService actions = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "deployment-environment-page");
        thread.setDaemon(true);
        return thread;
    });

    /** Rejects callbacks from a revoked or replaced view. / 拒绝已撤销或替换视图的回调。 */
    private final AtomicLong generation = new AtomicLong();

    /** Actual original outstanding environment task. / 实际原未完成环境任务。 */
    private volatile TaskHandle<Void> current;

    /** Actual short lookup, closed separately from accepted preparation. / 实际短查询，与已准入准备分开关闭。 */
    private volatile TaskHandle<?> lookup;

    /** Current localized scope. / 当前本地化范围。 */
    private ResourceBundle messages;

    /** Actual finite original task feedback. / 实际有限原任务反馈。 */
    private DeploymentTaskFeedback feedback;

    /** Current page lifecycle and admission state. / 当前页面生命周期及准入状态。 */
    private volatile boolean closed, locked, busy, lookupPending;

    /** Prevents duplicate root construction. / 阻止重复根构建。 */
    private boolean created;

    /** Binds current services without querying storage. / 绑定当前服务，不查询存储。
     * @param lookups original short server lookup / 原短服务器查询
     * @param workflow original environment workflow / 原环境流程 */
    public DeploymentEnvironmentPage(DeploymentStandardPageService lookups, DeploymentEnvironmentWorkflow workflow) {
        this.lookups = java.util.Objects.requireNonNull(lookups);
        this.workflow = java.util.Objects.requireNonNull(workflow);
    }

    /** Creates the actual selectable target and original workflow controls. / 创建实际可选目标及原流程控件。
     * @param environment actual host state / 实际宿主状态
     * @return owned root / 所属根 */
    @Override
    public JComponent create(UiEnvironment environment) {
        requireEdt();
        if (closed || created) throw new IllegalStateException("Environment page already used");
        created = true;
        root.setName("deployment.environment");
        servers.setName("deployment.environment.server");
        refresh.setName("deployment.environment.refresh");
        prepare.setName("deployment.environment.prepare");
        manage.setName("deployment.environment.servers");
        scope.setName("deployment.environment.scope");
        servers.setRenderer((list, value, index, selected, focus) -> {
            var label = new JLabel(value == null ? "" : value.name() + " · " + value.username() + "@" + value.host() + ":" + value.port());
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        scope.setEditable(false);
        scope.setLineWrap(true);
        scope.setWrapStyleWord(true);
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        var controls = new JPanel();
        controls.add(servers);
        controls.add(refresh);
        controls.add(manage);
        controls.add(prepare);
        var north = new JPanel(new BorderLayout(4, 4));
        north.add(controls, BorderLayout.NORTH);
        north.add(scope, BorderLayout.CENTER);
        root.add(gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle.card("environment.title", north), BorderLayout.NORTH);
        feedback = new DeploymentTaskFeedback(lookups.feedback(), actions, () -> navigate(PlatformPageKind.TASKS),
            lookups.configuration().view());
        root.add(gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle.card("environment.feedback", feedback.component()), BorderLayout.CENTER);
        refresh.addActionListener(ignored -> refresh());
        prepare.addActionListener(ignored -> prepare());
        manage.addActionListener(ignored -> navigate(PlatformPageKind.SERVERS));
        servers.addActionListener(ignored -> availability());
        environmentChanged(environment);
        gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle.scrollPage(root);
        if (!locked) refresh();
        return root;
    }

    /** Applies locale and revokes only unanswered original consent on lock. / 应用语言，锁定时仅撤销未答原确认。
     * @param environment actual host state / 实际宿主状态 */
    @Override
    public void environmentChanged(UiEnvironment environment) {
        requireEdt();
        locked = environment.locked();
        generation.incrementAndGet();
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.environment.environment-page", environment.locale());
        refresh.setText(messages.getString("refresh"));
        prepare.setText(messages.getString("prepare"));
        manage.setText(messages.getString("servers"));
        scope.setText(locked ? "" : messages.getString("scope"));
        if (feedback != null) feedback.configure(environment.locale(), locked || closed);
        if (locked) revokeUnanswered();
        availability();
        gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle.refresh(root, messages);
    }

    /** Refreshes saved non-secret profiles without selecting one implicitly. / 刷新保存的非秘密档案，不隐式选择服务器。 */
    private void refresh() {
        if (closed || locked || busy || lookupPending) return;
        lookupPending = true;
        availability();
        long view = generation.get();
        actions.execute(() -> {
            try {
                var handle = lookups.servers();
                lookup = handle;
                if (closed || locked) handle.requestCancel();
                handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                    if (lookup == handle) lookup = null;
                    lookupPending = false;
                    if (closed || locked || generation.get() != view) {
                        availability();
                        return;
                    }
                    if (failure != null || result == null || result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()) {
                        scope.setText(messages.getString("failed"));
                        availability();
                        return;
                    }
                    var previous = (ServerInfo) servers.getSelectedItem();
                    servers.removeAllItems();
                    result.value().orElseThrow().forEach(servers::addItem);
                    servers.setSelectedItem(null);
                    if (previous != null) for (int index = 0; index < servers.getItemCount(); index++)
                        if (servers.getItemAt(index).equals(previous)) servers.setSelectedIndex(index);
                    availability();
                }));
            } catch (Exception unavailable) {
                lookupPending = false;
                failure(view);
            }
        });
    }

    /** Freezes the explicit displayed profile before original task admission. / 原任务准入前冻结明确展示的档案。 */
    private void prepare() {
        if (closed || locked || busy || lookupPending || servers.getSelectedItem() == null) return;
        var original = (ServerInfo) servers.getSelectedItem();
        busy = true;
        availability();
        long view = generation.get();
        actions.execute(() -> {
            try {
                var handle = workflow.submit(original);
                current = handle;
                if ((closed || locked) && workflow.awaitingConsent(handle.id())) handle.requestCancel();
                SwingUtilities.invokeLater(() -> {
                    if (!closed && current == handle) feedback.watch(handle);
                });
                handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                    if (current == handle) busy = false;
                    if (!closed) availability();
                }));
            } catch (Exception unavailable) {
                busy = false;
                failure(view);
            }
        });
    }

    /** Opens a finite actual platform destination through original UI authority. / 经原UI授权打开有限实际平台目的页。
     * @param target explicit finite destination / 明确有限目的页 */
    private void navigate(PlatformPageKind target) {
        if (closed || locked) return;
        try {
            lookups.openPlatform(target);
        } catch (ApiException refused) {
            scope.setText(messages.getString("failed"));
        }
    }

    /** Presents only safe failure text on the original current view. / 仅在原当前视图显示安全失败文字。
     * @param view original captured view generation / 原捕获视图代次 */
    private void failure(long view) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && !locked && generation.get() == view) {
                scope.setText(messages.getString("failed"));
                availability();
            }
        });
    }

    /** Cancels short lookup and unconfirmed consent, retaining confirmed background work. / 取消短查询及未确认确认，保留已确认后台工作。 */
    private void revokeUnanswered() {
        var query = lookup;
        var original = current;
        if (actions.isShutdown()) return;
        actions.execute(() -> {
            try {
                if (query != null && query.snapshot().endedAt().isEmpty()) query.requestCancel();
                if (original != null && workflow.awaitingConsent(original.id())) original.requestCancel();
            } catch (ApiException unavailable) { /* Original task authority retains its actual outcome. */ }
        });
    }

    /** Derives admission from the actual current view and explicit selection. / 从实际当前视图及明确选择确定准入。 */
    private void availability() {
        servers.setEnabled(!closed && !locked && !busy && !lookupPending);
        refresh.setEnabled(!closed && !locked && !busy && !lookupPending);
        prepare.setEnabled(!closed && !locked && !busy && !lookupPending && servers.getSelectedItem() != null);
        manage.setEnabled(!closed && !locked);
    }

    /** Stops page refresh resources while confirmed original preparation remains in the background. / 停止页面刷新资源，已确认原准备继续后台执行。 */
    @Override
    public void close() {
        requireEdt();
        if (closed) return;
        closed = true;
        generation.incrementAndGet();
        if (feedback != null) feedback.close();
        revokeUnanswered();
        actions.shutdown();
        scope.setText("");
        availability();
    }

    /** Enforces actual Swing ownership. / 强制实际Swing归属。 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Environment page requires EDT");
    }
}
