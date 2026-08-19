package gold.debug.windowstolinux.app.ui.managed;

import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.server.ServerContext;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Locale;
import java.util.Map;

/** Owns the managed-application inventory, selection, and lifecycle workflows. / 持有受管应用清单、选择与生命周期流程。 */
public final class ManagedPage {
    private final ManagedApplicationFacade service;
    private final ServerContext serverContext;
    private final PageMessagePresenter messages;
    private final JTextField applicationId = new JTextField(20);
    private final JTextArea output = DesktopComponentFactory.outputArea();
    private final JPanel panel;

    /** Creates the stateful page controller. / 创建有状态页面控制器。 */
    public ManagedPage(ManagedApplicationFacade service, ServerContext serverContext,
                       DesktopComponentFactory components, PageMessagePresenter messages) {
        this.service = service;
        this.serverContext = serverContext;
        this.messages = messages;
        panel = createPanel(components);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() { return panel; }
    /** Captures page state. / 捕获页面状态。 */
    public ManagedPageState captureState() { return new ManagedPageState(applicationId.getText(), output.getText()); }
    /** Restores page state. / 恢复页面状态。 */
    public void restoreState(ManagedPageState state) {
        applicationId.setText(state.applicationId());
        output.setText(state.output());
    }

    /** Selects a successfully deployed application without exposing page components. / 选择成功部署的应用且不公开页面组件。 */
    public void selectApplication(String selectedId) {
        applicationId.setText(selectedId);
        output.setText(messages.text("deployment.selected", Map.of("application", selectedId)));
    }

    private JPanel createPanel(DesktopComponentFactory c) {
        JPanel page = c.pagePanel();
        JPanel controls = c.card(new BorderLayout(0, 10));
        controls.add(c.sectionHeading(messages.text("section.lifecycle.title"),
                messages.text("section.lifecycle.description")), BorderLayout.NORTH);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton refresh = c.secondaryButton(messages.text("button.refreshApplications"));
        refresh.addActionListener(event -> refresh());
        actions.add(refresh);
        actions.add(new JLabel(messages.text("field.applicationId")));
        actions.add(applicationId);
        for (LifecycleAction action : LifecycleAction.values()) {
            JButton button = c.secondaryButton(messages.text("button." + switch (action) {
                case REFRESH_STATUS -> "refreshStatus";
                case START -> "start";
                case STOP -> "stop";
                case RESTART -> "restart";
                case ENABLE_AUTOSTART -> "enableAutostart";
                case DISABLE_AUTOSTART -> "disableAutostart";
            }));
            button.addActionListener(event -> execute(action));
            actions.add(button);
        }
        controls.add(actions, BorderLayout.CENTER);
        page.add(controls, BorderLayout.NORTH);
        page.add(c.outputCard(messages.text("section.applicationOutput.title"),
                messages.text("section.applicationOutput.description"), output), BorderLayout.CENTER);
        return page;
    }

    private void refresh() {
        try {
            var applications = service.listManagedApplicationSummaries();
            output.setText(applications.isEmpty() ? messages.text("applications.none") : applications.stream()
                    .map(summary -> messages.text("applications.summary", Map.of(
                            "application", summary.application().id(), "server", summary.application().server().host(),
                            "unit", summary.application().systemdUnit(),
                            "release", summary.currentReleaseSha256().orElse(messages.text("applications.noRelease")),
                            "runtime", summary.runtimeConfiguration().map(this::runtimeSummary)
                                    .orElse(messages.text("applications.legacyRuntime")))))
                    .reduce("", (left, right) -> left + right + "\n"));
        } catch (Exception exception) {
            output.setText(messages.text("applications.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void execute(LifecycleAction action) {
        try {
            String selected = applicationId.getText().trim();
            if (selected.isBlank()) {
                throw new IllegalArgumentException(messages.text("lifecycle.selectApplication"));
            }
            char[] master = serverContext.masterPassword();
            output.setText(messages.text("lifecycle.running", Map.of(
                    "action", messages.text("lifecycle.action." + action.name().toLowerCase(Locale.ROOT)))));
            DesktopTaskExecutor.run(
                    () -> service.executePersistedLifecycleWithStoredPassword(selected, action, master),
                    result -> output.setText(messages.text(
                            result.accepted() ? "lifecycle.accepted" : "lifecycle.rejected",
                            Map.of("message", messages.catalog().text(result.message()),
                                    "observation", messages.lifecycle(result.observation().orElse(null))))),
                    exception -> output.setText(messages.text("lifecycle.failed",
                            Map.of("detail", messages.safe(exception)))));
        } catch (Exception exception) {
            output.setText(messages.text("lifecycle.startFailed", Map.of("detail", messages.safe(exception))));
        }
    }

    private String runtimeSummary(gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration configuration) {
        if (configuration.healthCheck() instanceof HealthCheck.Http http) {
            return messages.text("runtime.http", Map.of("endpoint", http.endpoint().toASCIIString(),
                    "access", configuration.userAccessUrl().orElseThrow().url().toASCIIString()));
        }
        HealthCheck.Tcp tcp = (HealthCheck.Tcp) configuration.healthCheck();
        return messages.text("runtime.tcp", Map.of("port", tcp.port()));
    }
}
