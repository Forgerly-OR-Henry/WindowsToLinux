package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.BackupApplicationFacade;
import gold.debug.windowstolinux.app.ui.ai.AiPage;
import gold.debug.windowstolinux.app.ui.backup.BackupPage;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.shell.DesktopDisplayChangeHandler;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.deployment.single.DeploymentPage;
import gold.debug.windowstolinux.app.ui.deployment.multi.MultiComponentPage;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.managed.ManagedPage;
import gold.debug.windowstolinux.app.ui.server.ServerPage;
import gold.debug.windowstolinux.app.ui.setting.SettingPage;
import gold.debug.windowstolinux.app.ui.setting.UiDebugDialog;

import javax.swing.JPanel;

/**
 * Wires page controllers, narrow cross-page contexts, navigation, and whole-window state.
 *
 * <p>装配页面控制器、窄页面上下文、导航及整个窗口状态。
 */
final class DesktopPageCoordinator {
    private final PageNavigationController navigator;
    private final DeploymentPage deployment;
    private final MultiComponentPage multiComponent;
    private final ServerPage server;
    private final ManagedPage managed;
    private final BackupPage backup;
    private final AiPage ai;
    private final SettingPage settings;
    private String currentPage = "deployment";

    <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopPageCoordinator(DesktopFrame owner, T service, MessageCatalog catalog,
                           DesktopDisplayConfiguration appearance, DesktopComponentFactory components,
                           DesktopDisplayChangeHandler appearanceChangeListener, PageNavigationController navigator) {
        this(owner, service, catalog, appearance, components, appearanceChangeListener, navigator,
                FailureReportStore.disabled());
    }

    <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopPageCoordinator(DesktopFrame owner, T service, MessageCatalog catalog,
                           DesktopDisplayConfiguration appearance, DesktopComponentFactory components,
                           DesktopDisplayChangeHandler appearanceChangeListener, PageNavigationController navigator,
                           FailureReportStore reports) {
        this(owner, service, catalog, appearance, components, appearanceChangeListener, navigator, reports, false);
    }

    <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopPageCoordinator(DesktopFrame owner, T service, MessageCatalog catalog,
                           DesktopDisplayConfiguration appearance, DesktopComponentFactory components,
                           DesktopDisplayChangeHandler appearanceChangeListener, PageNavigationController navigator,
                           FailureReportStore reports, boolean uiDebugEnabled) {
        this.navigator = navigator;
        PageMessagePresenter messages = new PageMessagePresenter(catalog, reports);
        server = new ServerPage(owner, service, components, messages);
        managed = new ManagedPage(service, server, components, messages);
        backup = new BackupPage(owner, service, components, messages);
        deployment = new DeploymentPage(owner, service, server, components, messages,
                () -> navigator.show("components", "nav.components", "page.components.description"), managed::selectApplication);
        multiComponent = new MultiComponentPage(owner, service, server, components, messages,
                () -> navigator.show("servers", "nav.servers", "page.servers.description"), managed::selectApplication);
        ai = new AiPage(service, deployment, components, messages);
        settings = new SettingPage(components, messages, appearance,
                selected -> appearanceChangeListener.apply(owner, selected), uiDebugEnabled ? () -> {
                    for (java.awt.Window window : owner.getOwnedWindows()) {
                        if (window instanceof UiDebugDialog && window.isDisplayable()) {
                            window.setVisible(true); window.toFront(); return;
                        }
                    }
                    new UiDebugDialog(owner, components, catalog,
                            page -> navigator.show(page, "nav." + page, "page." + page + ".description")).setVisible(true);
                } : null);
    }

    JPanel deploymentPanel() { return deployment.panel(); }
    JPanel multiComponentPanel() { return multiComponent.panel(); }
    JPanel managedApplicationsPanel() { return managed.panel(); }
    JPanel backupPanel() { return backup.panel(); }
    JPanel serverPanel() { return server.panel(); }
    JPanel aiPanel() { return ai.panel(); }
    JPanel settingsPanel() { return settings.panel(); }

    void currentPage(String page) { currentPage = page; }

    void showInspectorControls(String page, JPanel host) {
        host.removeAll();
        var pane = inspector(page);
        if (pane != null) host.add(pane.headerControls(), java.awt.BorderLayout.CENTER);
        host.revalidate(); host.repaint();
    }

    gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane inspector(String page) {
        JPanel panel = panels().get(page);
        return panel instanceof gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane pane ? pane : null;
    }

    void bindInspectors(gold.debug.windowstolinux.app.ui.component.AdvancedWindowHost controller) {
        panels().values().forEach(panel -> {
            if (panel instanceof gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane pane) pane.bind(controller);
        });
    }

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

    private java.util.Map<String, JPanel> panels() {
        return java.util.Map.of("deployment", deployment.panel(), "components", multiComponent.panel(),
                "servers", server.panel(), "applications", managed.panel(), "backup", backup.panel(),
                "ai", ai.panel(), "settings", settings.panel());
    }
}
