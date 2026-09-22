package gold.debug.windowstolinux.app.ui.shell;

import javax.swing.JPanel;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.BackupApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.ui.ai.AiPage;
import gold.debug.windowstolinux.app.ui.backup.BackupPage;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.deployment.automatic.DeploymentPage;
import gold.debug.windowstolinux.app.ui.deployment.multi.MultiComponentPage;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.managed.ManagedPage;
import gold.debug.windowstolinux.app.ui.server.ServerPage;
import gold.debug.windowstolinux.app.ui.setting.SettingPage;
import gold.debug.windowstolinux.app.ui.setting.UiDebugDialog;
import gold.debug.windowstolinux.app.ui.shell.DesktopDisplayChangeHandler;

/**
 * Wires page controllers, narrow cross-page contexts, navigation, and whole-window state.
 *
 *  <p>装配页面控制器、窄页面上下文、导航及整个窗口状态。
 */
final class DesktopPageCoordinator {
    /**
     * Navigator.
     * <p>导航器。
     */
    private final PageNavigationController navigator;

    /**
     * Deployment.
     * <p>部署。
     */
    private final DeploymentPage deployment;

    /**
     * The multi-component application page state.
     * <p>多组件应用页面状态。
     */
    private final MultiComponentPage multiComponent;

    /**
     * Server identity or selected server configuration.
     * <p>服务器身份或所选服务器配置。
     */
    private final ServerPage server;

    /**
     * Managed.
     * <p>受管。
     */
    private final ManagedPage managed;

    /**
     * The local backup page state.
     * <p>本地备份页面状态。
     */
    private final BackupPage backup;

    /**
     * The supplied ai page.
     * <p>所提供的AI页面。
     */
    private final AiPage ai;

    /**
     * Settings.
     * <p>设置。
     */
    private final SettingPage settings;

    /**
     * Stores the current navigation page identifier.
     * <p>保存当前导航页面标识。
     */
    private String currentPage = "deployment";

    /**
     * Initializes desktop page coordinator through its shared constructor contract.
     * <p>通过共享构造契约初始化Desktop页面协调器。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param catalog catalog / 目录
     * @param appearance appearance / 外观
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param appearanceChangeListener appearance change listener / 外观变更监听器
     * @param navigator navigator / 导航器
     */
    <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopPageCoordinator(
            DesktopFrame owner, T service, MessageCatalog catalog, DesktopDisplayConfiguration appearance,
            DesktopComponentFactory components, DesktopDisplayChangeHandler appearanceChangeListener,
            PageNavigationController navigator) {
        this(owner, service, catalog, appearance, components, appearanceChangeListener, navigator,
                FailureReportStore.disabled());
    }

    /**
     * Initializes desktop page coordinator through its shared constructor contract.
     * <p>通过共享构造契约初始化Desktop页面协调器。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param catalog catalog / 目录
     * @param appearance appearance / 外观
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param appearanceChangeListener appearance change listener / 外观变更监听器
     * @param navigator navigator / 导航器
     * @param reports reports / 报告集合
     */
    <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopPageCoordinator(
            DesktopFrame owner, T service, MessageCatalog catalog, DesktopDisplayConfiguration appearance,
            DesktopComponentFactory components, DesktopDisplayChangeHandler appearanceChangeListener,
            PageNavigationController navigator, FailureReportStore reports) {
        this(owner, service, catalog, appearance, components, appearanceChangeListener, navigator, reports, false);
    }

    /**
     * Binds the supplied dependencies and state for desktop page coordinator.
     * <p>为Desktop页面协调器绑定传入的依赖及状态。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param catalog catalog / 目录
     * @param appearance appearance / 外观
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param appearanceChangeListener appearance change listener / 外观变更监听器
     * @param navigator navigator / 导航器
     * @param reports reports / 报告集合
     * @param uiDebugEnabled ui debug enabled / 界面Debug启用
     */
    <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopPageCoordinator(
            DesktopFrame owner, T service, MessageCatalog catalog, DesktopDisplayConfiguration appearance,
            DesktopComponentFactory components, DesktopDisplayChangeHandler appearanceChangeListener,
            PageNavigationController navigator, FailureReportStore reports, boolean uiDebugEnabled) {
        this.navigator = navigator;
        PageMessagePresenter messages = new PageMessagePresenter(catalog, reports);
        server = new ServerPage(owner, service, components, messages);
        managed = new ManagedPage(service, server, components, messages);
        backup = new BackupPage(owner, service, components, messages);
        deployment = new DeploymentPage(owner, service, server, components, messages,
                () -> navigator.show("components", "nav.components", "page.components.description"),
                managed::selectApplication, () -> navigator.show("ai", "nav.ai", "page.ai.description"));
        multiComponent = new MultiComponentPage(owner, service, server, components, messages,
                () -> navigator.show("servers", "nav.servers", "page.servers.description"), managed::selectApplication);
        ai = new AiPage(service, deployment, components, messages);
        settings = new SettingPage(components, messages, appearance,
                selected -> appearanceChangeListener.apply(owner, selected), uiDebugEnabled ? () -> {
                    for (java.awt.Window window : owner.getOwnedWindows()) {
                        if (window instanceof UiDebugDialog && window.isDisplayable()) {
                            window.setVisible(true);
                            window.toFront();
                            return;
                        }
                    }
                    new UiDebugDialog(owner, components, catalog,
                            page -> navigator.show(page, "nav." + page, "page." + page + ".description"))
                            .setVisible(true);
                } : null);
    }

    /**
     * Returns deployment panel.
     * <p>返回部署面板。
     *
     * @return deployment panel / 部署面板
     */
    JPanel deploymentPanel() {
        return deployment.panel();
    }

    /**
     * Returns multi component panel.
     * <p>返回多组件面板。
     *
     * @return multi component panel / 多组件面板
     */
    JPanel multiComponentPanel() {
        return multiComponent.panel();
    }

    /**
     * Returns managed applications panel.
     * <p>返回受管应用集合面板。
     *
     * @return managed applications panel / 受管应用集合面板
     */
    JPanel managedApplicationsPanel() {
        return managed.panel();
    }

    /**
     * Returns backup panel.
     * <p>返回备份面板。
     *
     * @return backup panel / 备份面板
     */
    JPanel backupPanel() {
        return backup.panel();
    }

    /**
     * Returns server panel.
     * <p>返回服务器面板。
     *
     * @return server panel / 服务器面板
     */
    JPanel serverPanel() {
        return server.panel();
    }

    /**
     * Returns ai panel.
     * <p>返回AI面板。
     *
     * @return ai panel / AI面板
     */
    JPanel aiPanel() {
        return ai.panel();
    }

    /**
     * Returns settings panel.
     * <p>返回设置面板。
     *
     * @return settings panel / 设置面板
     */
    JPanel settingsPanel() {
        return settings.panel();
    }

    /**
     * Stores the current navigation page identifier.
     * <p>保存当前导航页面标识。
     *
     * @param page page / 页面
     */
    void currentPage(String page) {
        currentPage = page;
    }

    /**
     * Displays inspector controls.
     * <p>展示检查器控件集合。
     *
     * @param page page / 页面
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     */
    void showInspectorControls(String page, JPanel host) {
        host.removeAll();
        var pane = inspector(page);
        if (pane != null)
            host.add(pane.headerControls(), java.awt.BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
    }

    /**
     * Returns the page's advanced-options pane, or null when that page has none.
     * <p>返回页面的高级选项面板；该页面没有面板时返回 null。
     *
     * @param page page / 页面
     * @return advanced pane for the page, or null when unavailable / 页面高级面板；不可用时为 null
     */
    gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane inspector(String page) {
        JPanel panel = panels().get(page);
        return panel instanceof gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane pane ? pane : null;
    }

    /**
     * Binds inspectors.
     * <p>绑定检查器集合。
     *
     * @param controller controller / 控制器
     */
    void bindInspectors(gold.debug.windowstolinux.app.ui.component.AdvancedWindowHost controller) {
        panels().values().forEach(panel -> {
            if (panel instanceof gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane pane)
                pane.bind(controller);
        });
    }

    /**
     * Builds desktop view state from the supplied capture view state inputs.
     * <p>根据所提供捕获视图状态输入构建Desktop视图状态。
     *
     * @return desktop view state from the supplied capture view state inputs / 根据所提供捕获视图状态输入构建Desktop视图状态
     */
    DesktopViewState captureViewState() {
        java.util.Map<String, Boolean> expanded = new java.util.LinkedHashMap<>();
        panels().forEach((name, panel) -> {
            if (panel instanceof gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane pane)
                expanded.put(name, pane.expanded());
        });
        return new DesktopViewState(currentPage, deployment.captureState(), multiComponent.captureState(),
                server.captureState(), managed.captureState(), backup.captureState(), ai.captureState(),
                settings.captureState(), expanded, deployment.captureSelection(), deployment.captureHandoffs());
    }

    /**
     * Restores each page's saved draft and selection state after the frame has been rebuilt.
     * <p>窗口重建后恢复各页面保存的草稿及选择状态。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    void restoreViewState(DesktopViewState state) {
        deployment.restoreState(state.deployment());
        multiComponent.restoreState(state.multiComponent());
        server.restoreState(state.server());
        managed.restoreState(state.managed());
        backup.restoreState(state.backup());
        ai.restoreState(state.ai());
        settings.restoreState(state.settings());
        deployment.restoreSelection(state.deploymentSelection());
        deployment.restoreHandoffs(state.handoffs());
        panels().forEach((name, panel) -> {
            if (panel instanceof gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane pane)
                pane.setExpanded(state.expanded().getOrDefault(name, false));
        });
        navigator.show(state.page(), "nav." + state.page(), "page." + state.page() + ".description");
    }

    /**
     * Returns panels.
     * <p>返回面板集合。
     *
     * @return panels / 面板集合
     */
    private java.util.Map<String, JPanel> panels() {
        return java.util.Map.of("deployment", deployment.panel(), "components", multiComponent.panel(), "servers",
                server.panel(), "applications", managed.panel(), "backup", backup.panel(), "ai", ai.panel(), "settings",
                settings.panel());
    }
}
