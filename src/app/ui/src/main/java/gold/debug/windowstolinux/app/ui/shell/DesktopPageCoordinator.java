package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.ui.ai.AiPage;
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
    private final AiPage ai;
    private final SettingPage settings;
    private String currentPage = "deployment";

    DesktopPageCoordinator(DesktopFrame owner, DesktopApplicationFacade service, MessageCatalog catalog,
                           DesktopDisplayConfiguration appearance, DesktopComponentFactory components,
                           DesktopDisplayChangeHandler appearanceChangeListener, PageNavigationController navigator) {
        this(owner, service, catalog, appearance, components, appearanceChangeListener, navigator,
                FailureReportStore.disabled());
    }

    DesktopPageCoordinator(DesktopFrame owner, DesktopApplicationFacade service, MessageCatalog catalog,
                           DesktopDisplayConfiguration appearance, DesktopComponentFactory components,
                           DesktopDisplayChangeHandler appearanceChangeListener, PageNavigationController navigator,
                           FailureReportStore reports) {
        this.navigator = navigator;
        PageMessagePresenter messages = new PageMessagePresenter(catalog, reports);
        server = new ServerPage(owner, service, components, messages);
        managed = new ManagedPage(service, server, components, messages);
        deployment = new DeploymentPage(owner, service, server, components, messages,
                () -> navigator.show("servers", "nav.servers", "page.servers.description"), managed::selectApplication);
        multiComponent = new MultiComponentPage(owner, service, server, components, messages,
                () -> navigator.show("servers", "nav.servers", "page.servers.description"), managed::selectApplication);
        ai = new AiPage(service, deployment, components, messages);
        settings = new SettingPage(components, messages, appearance,
                selected -> appearanceChangeListener.apply(owner, selected));
    }

    JPanel deploymentPanel() { return deployment.panel(); }
    JPanel multiComponentPanel() { return multiComponent.panel(); }
    JPanel managedApplicationsPanel() { return managed.panel(); }
    JPanel serverPanel() { return server.panel(); }
    JPanel aiPanel() { return ai.panel(); }
    JPanel settingsPanel() { return settings.panel(); }

    void currentPage(String page) { currentPage = page; }

    DesktopViewState captureViewState() {
        return new DesktopViewState(currentPage, deployment.captureState(), multiComponent.captureState(),
                server.captureState(), managed.captureState(), ai.captureState(), settings.captureState());
    }

    void restoreViewState(DesktopViewState state) {
        deployment.restoreState(state.deployment());
        multiComponent.restoreState(state.multiComponent());
        server.restoreState(state.server());
        managed.restoreState(state.managed());
        ai.restoreState(state.ai());
        settings.restoreState(state.settings());
        navigator.show(state.page(), "nav." + state.page(), "page." + state.page() + ".description");
    }
}
