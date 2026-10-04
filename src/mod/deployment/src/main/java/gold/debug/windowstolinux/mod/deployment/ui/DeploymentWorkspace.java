package gold.debug.windowstolinux.mod.deployment.ui;

import java.awt.BorderLayout;
import java.util.ResourceBundle;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.PageFactory;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;

/** Owns lazy deployment views inside one published root. / 在唯一已发布根内持有按需创建的部署视图。 */
public final class DeploymentWorkspace implements ModPage {
    /** Stable internal identities also accepting old standalone page targets. / 稳定内部身份，同时接收旧独立页面目标。 */
    private static final String[] NAVIGATION = {"static-deployment", "agent-deployment", "applications"};

    /** Initial navigation restored before any business view exists. / 任何业务视图创建前恢复的初始导航。 */
    private int initialSelection;

    /** Internal view factories, without created services or windows. / 内部视图工厂，不创建服务或窗口。 */
    private final PageFactory[] factories;

    /** Actual author instances retained until successful cleanup. / 保留至成功清理的实际作者实例。 */
    private final ModPage[] instances = new ModPage[3];

    /** Successfully created components, distinct from partial instances. / 成功创建的组件，与部分实例区分。 */
    private final JComponent[] components = new JComponent[3];

    /** Single mounted root. / 唯一挂载根。 */
    private final JPanel root = new JPanel(new BorderLayout());

    /** Author-owned internal navigation. / 作者所属内部导航。 */
    private final JTabbedPane tabs = new JTabbedPane();

    /** Stable internal containers without placeholder business logic. / 无占位业务逻辑的稳定内部容器。 */
    private final JPanel[] slots = {new JPanel(new BorderLayout()), new JPanel(new BorderLayout()), new JPanel(new BorderLayout())};

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
    private boolean opening, closed;

    /** Captures factories without creating any internal view. / 捕获工厂，不创建内部视图。
     * @param standard standard and assisted view / 标准及辅助视图
     * @param agent autonomous view / 自主视图
     * @param applications application management view / 应用管理视图 */
    public DeploymentWorkspace(PageFactory standard, PageFactory agent, PageFactory applications) {
        factories = new PageFactory[]{java.util.Objects.requireNonNull(standard),
            java.util.Objects.requireNonNull(agent), java.util.Objects.requireNonNull(applications)};
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
        showSelected();
        tabs.addChangeListener(ignored -> {
            if (opening || closed) return;
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
        return NAVIGATION[tabs.getTabCount() == 0 ? initialSelection : tabs.getSelectedIndex()];
    }

    /** Restores a known internal page before creation or within the retained root. / 创建前或保留根内恢复已知内部页面。
     * @param id stable internal identity / 稳定内部身份 */
    @Override
    public void restoreNavigation(String id) {
        requireEdt();
        for (int index = 0; index < NAVIGATION.length; index++)
            if (NAVIGATION[index].equals(id)) {
                initialSelection = index;
                if (tabs.getTabCount() != 0) tabs.setSelectedIndex(index);
                return;
            }
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
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", environment.locale());
        String[] keys = {"workspace.standard", "workspace.agent", "workspace.applications"};
        for (int index = 0; index < keys.length; index++) tabs.setTitleAt(index, messages.getString(keys[index]));
        problem.setText(messages.getString("workspace.failed"));
        retry.setText(messages.getString("workspace.retry"));
        retry.setEnabled(!environment.locked() && !closed);
    }

    /** Releases each actual view; successful steps are not repeated after a sibling fails. / 释放每个实际视图；其他视图失败后不重复已成功步骤。
     * @throws Exception if any owned view still needs cleanup / 任何所属视图仍需清理时 */
    @Override
    public void close() throws Exception {
        requireEdt();
        closed = true;
        Exception failure = null;
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
