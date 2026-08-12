package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.ui.ai.AiPage;
import gold.debug.windowstolinux.app.ui.appearance.DesktopAppearance;
import gold.debug.windowstolinux.app.ui.appearance.DesktopAppearanceChangeListener;
import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.deployment.DeploymentPage;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.managed.ManagedPage;
import gold.debug.windowstolinux.app.ui.server.ServerPage;
import gold.debug.windowstolinux.app.ui.settings.SettingsPage;

import javax.swing.JPanel;

/**
 * Wires page controllers, narrow cross-page contexts, navigation, and whole-window state.
 *
 * <p>装配页面控制器、窄页面上下文、导航及整个窗口状态。
 */
final class DesktopPageCoordinator {
    private final PageNavigator navigator;
    private final DeploymentPage deployment;
    private final ServerPage server;
    private final ManagedPage managed;
    private final AiPage ai;
    private final SettingsPage settings;
    private String currentPage = "deployment";

    DesktopPageCoordinator(DesktopFrame owner, DesktopApplicationService service, MessageCatalog catalog,
                           DesktopAppearance appearance, DesktopComponents components,
                           DesktopAppearanceChangeListener appearanceChangeListener, PageNavigator navigator) {
        this.navigator = navigator;
        PageMessages messages = new PageMessages(catalog);
        server = new ServerPage(owner, service, components, messages);
        managed = new ManagedPage(service, server, components, messages);
        deployment = new DeploymentPage(owner, service, server, components, messages,
                () -> navigator.show("servers", "nav.servers", "page.servers.description"), managed::selectApplication);
        ai = new AiPage(service, deployment, components, messages);
        settings = new SettingsPage(components, messages, appearance,
                selected -> appearanceChangeListener.apply(owner, selected));
    }

    JPanel deploymentPanel() { return deployment.panel(); }
    JPanel managedApplicationsPanel() { return managed.panel(); }
    JPanel serverPanel() { return server.panel(); }
    JPanel aiPanel() { return ai.panel(); }
    JPanel settingsPanel() { return settings.panel(); }

    void currentPage(String page) { currentPage = page; }

    DesktopViewState captureViewState() {
        return new DesktopViewState(currentPage, deployment.captureState(), server.captureState(), managed.captureState(),
                ai.captureState(), settings.captureState());
    }

    void restoreViewState(DesktopViewState state) {
        deployment.restoreState(state.deployment());
        server.restoreState(state.server());
        managed.restoreState(state.managed());
        ai.restoreState(state.ai());
        settings.restoreState(state.settings());
        navigator.show(state.page(), "nav." + state.page(), "page." + state.page() + ".description");
    }
}
