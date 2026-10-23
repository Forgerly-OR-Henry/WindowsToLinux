package gold.debug.windowstolinux.mod.deployment.ui;

import java.awt.BorderLayout;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.PageFactory;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore;

/** Owns lazy deployment views inside one published root. / 在唯一已发布根内持有按需创建的部署视图。 */
public final class DeploymentWorkspace implements ModPage {
    /** Stable internal identities also accepting old standalone page targets. / 稳定内部身份，同时接收旧独立页面目标。 */
    private static final String[] NAVIGATION = {"static-deployment", "agent-deployment", "applications", "environment"};

    private final boolean unified;

    private String flowNavigation = "deployment-flow";

    /** Initial navigation restored before any business view exists. / 任何业务视图创建前恢复的初始导航。 */
    private int initialSelection;

    /** Internal view factories, without created services or windows. / 内部视图工厂，不创建服务或窗口。 */
    private final PageFactory[] factories;

    /** Actual author instances retained until successful cleanup. / 保留至成功清理的实际作者实例。 */
    private final ModPage[] instances;

    /** Successfully created components, distinct from partial instances. / 成功创建的组件，与部分实例区分。 */
    private final JComponent[] components;

    /** Single mounted root. / 唯一挂载根。 */
    private final JPanel root = new JPanel(new BorderLayout());

    /** Author-owned internal navigation. / 作者所属内部导航。 */
    private final JTabbedPane tabs = new JTabbedPane();

    /** Stable internal containers without placeholder business logic. / 无占位业务逻辑的稳定内部容器。 */
    private final JPanel[] slots;

    /** Production-only original preference query; preview mounting performs no task. / 仅生产使用原偏好查询；预览挂载不产生任务。 */
    private final Callable<TaskHandle<DeploymentPreferenceStore.Selection>> preferenceTask;

    /** Only the production preference observation uses this view worker. / 仅生产偏好观察使用本视图工作线程。 */
    private final ExecutorService worker;

    /** Monitor-protected original query retained through actual completion. / 由本实例监视器保护并保留至实际完成的原查询。 */
    private TaskHandle<DeploymentPreferenceStore.Selection> preferenceHandle;

    /** Explicit host navigation takes precedence over the saved initial mode. / 宿主明确导航优先于保存的初始模式。 */
    private boolean restored, preferenceReady, preferencePending, preferenceFailed;

    /** Localized unsuccessful-opening feedback. / 本地化打开失败反馈。 */
    private final JLabel problem = new JLabel();

    /** Explicit retry after cleanup, disabled while locked. / 清理后的明确重试，锁定时禁用。 */
    private final JButton retry = new JButton();

    /** Visible failure and retry surface. / 可见失败及重试界面。 */
    private final JPanel feedback = new JPanel();

    /** Latest environment used for later lazy creation. / 后续按需创建使用的最新环境。 */
    private UiEnvironment environment;

    /** Original Mod language resources. / 原 Mod 语言资源。 */
    private ResourceBundle messages;

    /** EDT-confined creation and release state. / 限定 EDT 的创建及释放状态。 */
    private boolean opening;

    /** Publishes close to the original preference worker before late admission. / 在迟到准入前将关闭状态发布给原偏好工作线程。 */
    private volatile boolean closed;

    /** Production workspace exposes only the unified flow and installed applications. / 生产工作区只呈现统一流程及已部署应用。 */
    public DeploymentWorkspace(PageFactory flow, PageFactory applications) {
        this(new PageFactory[]{flow, applications}, null);
    }

    /** Captures factories without creating any internal view. / 捕获工厂，不创建内部视图。
     * @param standard standard and assisted view / 标准及辅助视图
     * @param agent autonomous view / 自主视图
     * @param applications application management view / 应用管理视图 */
    public DeploymentWorkspace(PageFactory standard, PageFactory agent, PageFactory applications) {
        this(new PageFactory[]{standard, agent, applications}, null);
    }

    /** Restores the actual saved mode before creating a production business view. / 创建生产业务视图前恢复实际已保存模式。
     * @param standard standard and assisted view / 标准及辅助视图
     * @param agent autonomous view / 自主视图
     * @param applications application management view / 应用管理视图
     * @param environment environment preparation view / 环境准备视图
     * @param preferenceTask original owner preference query / 原所属偏好查询 */
    public DeploymentWorkspace(PageFactory standard, PageFactory agent, PageFactory applications,
                               PageFactory environment, Callable<TaskHandle<DeploymentPreferenceStore.Selection>> preferenceTask) {
        this(new PageFactory[]{standard, agent, applications, environment}, java.util.Objects.requireNonNull(preferenceTask));
    }

    /** Captures fixed factories and creates no task or business page. / 抓取固定工厂，不创建任务或业务页面。
     * @param factories fixed original factories / 固定原工厂
     * @param preferenceTask production query or absent preview query / 生产查询或无查询预览 */
    private DeploymentWorkspace(PageFactory[] factories, Callable<TaskHandle<DeploymentPreferenceStore.Selection>> preferenceTask) {
        unified = factories.length == 2;
        this.factories = factories.clone();
        for (var factory : this.factories) java.util.Objects.requireNonNull(factory);
        this.preferenceTask = preferenceTask;
        preferenceReady = preferenceTask == null;
        instances = new ModPage[factories.length];
        components = new JComponent[factories.length];
        slots = new JPanel[factories.length];
        for (int index = 0; index < slots.length; index++) slots[index] = new JPanel(new BorderLayout());
        worker = preferenceTask == null ? null : Executors.newSingleThreadExecutor(action -> {
            var thread = new Thread(action, "deployment-workspace-preferences");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Creates the root and its initially selected real view on the EDT. / 在 EDT 创建根及其初始所选真实视图。
     * @param environment current host environment / 当前宿主环境
     * @return owned root / 所属根
     * @throws Exception if initial creation fails / 初始创建失败时 */
    @Override
    public JComponent create(UiEnvironment environment) throws Exception {
        requireEdt();
        if (closed || root.getComponentCount() != 0) throw new IllegalStateException("Workspace already used");
        this.environment = environment;
        root.setName("deployment.workspace");
        tabs.setName("deployment.workspace.tabs");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        retry.setName("deployment.workspace.retry");
        retry.addActionListener(ignored -> {
            if (!preferenceReady && !restored) {
                restoreMode();
                return;
            }
            int index = tabs.getSelectedIndex();
            try {
                if (instances[index] != null && components[index] == null) {
                    instances[index].close();
                    instances[index] = null;
                }
                showSelected();
            } catch (Exception | Error failure) {
                rethrowFatal(failure);
                feedback.setVisible(true);
            }
        });
        for (var slot : slots) tabs.addTab("", slot);
        tabs.setSelectedIndex(initialSelection);
        feedback.add(problem);
        feedback.add(retry);
        feedback.setVisible(false);
        root.add(tabs, BorderLayout.CENTER);
        root.add(feedback, BorderLayout.SOUTH);
        localize();
        if (preferenceReady || restored) showSelected();
        else restoreMode();
        tabs.addChangeListener(ignored -> {
            if (opening || closed || (!preferenceReady && !restored)) return;
            try {
                showSelected();
            } catch (Exception | Error failure) {
                rethrowFatal(failure);
                feedback.setVisible(true);
            }
        });
        return root;
    }

    /** Captures the selected internal page without its drafts. / 捕获所选内部页面，不含其草稿。
     * @return stable internal identity / 稳定内部身份 */
    @Override
    public String navigationId() {
        requireEdt();
        int selected = tabs.getTabCount() == 0 ? initialSelection : tabs.getSelectedIndex();
        return unified ? selected == 0 ? "deployment-flow" : "applications" : NAVIGATION[selected];
    }

    /** Restores a known internal page before creation or within the retained root. / 创建前或保留根内恢复已知内部页面。
     * @param id stable internal identity / 稳定内部身份 */
    @Override
    public void restoreNavigation(String id) {
        requireEdt();
        if (unified) {
            int selected;
            if (java.util.Set.of("deployment-flow", "static-deployment", "agent-deployment", "environment").contains(id)) {
                selected = 0;
                flowNavigation = id;
                if (instances[0] != null) instances[0].restoreNavigation(id);
            } else if (id.equals("applications")) selected = 1;
            else return;
            restored = true;
            initialSelection = selected;
            if (tabs.getTabCount() != 0) tabs.setSelectedIndex(selected);
            return;
        }
        for (int index = 0; index < factories.length; index++)
            if (NAVIGATION[index].equals(id)) {
                restored = true;
                initialSelection = index;
                if (tabs.getTabCount() != 0) {
                    tabs.setEnabled(true);
                    if (tabs.getSelectedIndex() == index) try {
                        showSelected();
                    } catch (Exception | Error failure) {
                        rethrowFatal(failure);
                        feedback.setVisible(true);
                    }
                    else tabs.setSelectedIndex(index);
                }
                return;
            }
    }

    /** Observes one authentic original preference task, never inferring a mode from a label. / 观察一项真实原偏好任务，不从标签推断模式。 */
    private void restoreMode() {
        if (preferencePending || closed || environment.locked()) return;
        preferencePending = true;
        preferenceFailed = false;
        tabs.setEnabled(false);
        feedback.setVisible(true);
        localize();
        worker.execute(() -> {
            try {
                var handle = preferenceTask.call();
                synchronized (this) {
                    preferenceHandle = handle;
                }
                handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                    preferencePending = false;
                    synchronized (this) {
                        if (preferenceHandle == handle) {
                            preferenceHandle = null;
                        }
                    }
                    if (closed || restored) return;
                    if (failure != null || result == null || result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()) {
                        preferenceFailure();
                        return;
                    }
                    preferenceReady = true;
                    initialSelection = result.value().orElseThrow().mode().equals("AGENT") ? 1 : 0;
                    opening = true;
                    tabs.setSelectedIndex(initialSelection);
                    opening = false;
                    tabs.setEnabled(true);
                    try {
                        showSelected();
                    } catch (Exception | Error error) {
                        rethrowFatal(error);
                        feedback.setVisible(true);
                    }
                    localize();
                }));
                if (closed) cancelPreference();
            } catch (Exception failure) {
                SwingUtilities.invokeLater(this::preferenceFailure);
            }
        });
    }

    /** Leaves invalid or unconfirmed preferences visible and retryable without opening a substitute mode. / 保留非法或未确认偏好，可重试且不打开替代模式。 */
    private void preferenceFailure() {
        preferencePending = false;
        if (closed || restored) return;
        preferenceFailed = true;
        feedback.setVisible(true);
        localize();
    }

    /** Cancels only this original short query; its durable cleanup remains with the host. / 仅取消本原短查询；持久清理仍由宿主负责。
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if original cancellation cannot be confirmed / 原取消无法确认时 */
    private void cancelPreference() throws gold.debug.windowstolinux.appapi.task.result.ApiException {
        final TaskHandle<DeploymentPreferenceStore.Selection> handle;
        synchronized (this) {
            handle = preferenceHandle;
        }
        if (handle != null) handle.requestCancel();
    }

    /** Creates only a new selected view, retaining failed partial instances until cleanup. / 仅创建新的所选视图，失败的部分实例保留至清理。
     * @throws Exception if creation or retained cleanup fails / 创建或保留清理失败时 */
    private void showSelected() throws Exception {
        int index = tabs.getSelectedIndex();
        if (components[index] != null) {
            feedback.setVisible(false);
            return;
        }
        if (instances[index] != null) throw new IllegalStateException("View cleanup pending");
        opening = true;
        try {
            instances[index] = java.util.Objects.requireNonNull(factories[index].create());
            if (unified && index == 0) instances[index].restoreNavigation(flowNavigation);
            var component = java.util.Objects.requireNonNull(instances[index].create(environment));
            if (component.getParent() != null) throw new IllegalStateException("View is already mounted");
            components[index] = component;
            slots[index].add(component, BorderLayout.CENTER);
            slots[index].revalidate();
            feedback.setVisible(false);
        } finally {
            opening = false;
        }
    }

    /** Updates all retained views, including those currently hidden. / 更新所有保留视图，包括当前隐藏视图。
     * @param environment current host environment / 当前宿主环境 */
    @Override
    public void environmentChanged(UiEnvironment environment) {
        requireEdt();
        this.environment = environment;
        localize();
        if (!environment.locked() && !preferenceReady && !restored && !preferencePending) restoreMode();
        RuntimeException failure = null;
        for (var page : instances)
            if (page != null) try {
                page.environmentChanged(environment);
            } catch (RuntimeException | Error error) {
                rethrowFatal(error);
                var problem = new IllegalStateException("Internal view environment failed", error);
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        if (failure != null) throw failure;
    }

    /** Localizes internal navigation without rebuilding author controls. / 本地化内部导航，不重建作者控件。 */
    private void localize() {
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", environment.locale(),
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
        String[] keys = unified ? new String[]{"workspace.flow", "workspace.installed"}
            : new String[]{"workspace.standard", "workspace.agent", "workspace.applications", "workspace.environment"};
        for (int index = 0; index < tabs.getTabCount(); index++)
            tabs.setTitleAt(index, messages.getString(keys[index]));
        if (unified && tabs.getTabCount() == 2) {
            tabs.setIconAt(0, gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle.icon("folder", 20));
            tabs.setIconAt(1, gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle.icon("app", 20));
        }
        problem.setText(messages.getString(preferencePending ? "workspace.loading" : preferenceFailed ? "workspace.preferenceFailed" : "workspace.failed"));
        retry.setText(messages.getString("workspace.retry"));
        retry.setEnabled(!environment.locked() && !closed && !preferencePending);
    }

    /** Releases each actual view; successful steps are not repeated after a sibling fails. / 释放每个实际视图；其他视图失败后不重复已成功步骤。
     * @throws Exception if any owned view still needs cleanup / 任何所属视图仍需清理时 */
    @Override
    public void close() throws Exception {
        requireEdt();
        closed = true;
        Exception failure = null;
        try {
            cancelPreference();
        } catch (gold.debug.windowstolinux.appapi.task.result.ApiException retained) {
            failure = retained;
        }
        if (worker != null) worker.shutdown();
        for (int index = 0; index < instances.length; index++)
            if (instances[index] != null) try {
                instances[index].close();
                instances[index] = null;
                components[index] = null;
                slots[index].removeAll();
            } catch (Exception | Error error) {
                rethrowFatal(error);
                var problem = new Exception("Internal view cleanup failed", error);
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        if (failure != null) throw failure;
    }

    /** Keeps view ownership on the EDT. / 将视图归属限定在 EDT。 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Workspace requires EDT");
    }

    /** Preserves fatal VM failures at the original EDT boundary. / 在原 EDT 边界保留致命虚拟机失败。
     * @param failure original failure / 原失败 */
    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }
}
