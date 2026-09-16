package gold.debug.windowstolinux.app.ui.managed;

import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.server.ServerContext;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;

import javax.swing.JButton;
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
    private final JTextArea details = DesktopComponentFactory.outputArea();
    private final JPanel panel;
    private final javax.swing.JComboBox<gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot> inventory = new javax.swing.JComboBox<>();
    private final JButton access = new JButton();
    private boolean loading;
    private boolean busy;


    /** Creates the stateful page controller. / 创建有状态页面控制器。 */
    public ManagedPage(ManagedApplicationFacade service, ServerContext serverContext,
                       DesktopComponentFactory components, PageMessagePresenter messages) {
        this.service = service;
        this.serverContext = serverContext;
        this.messages = messages;
        panel = createPanel(components);
        panel.addHierarchyListener(event -> { if (panel.isShowing() && inventory.getItemCount() == 0) refresh(); });
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
        if (service != null) refresh();
    }

    private JPanel createPanel(DesktopComponentFactory c) {
        JPanel page = c.pagePanel();
        var advanced = new gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane(page, c, messages);
        JPanel controls = c.card(new BorderLayout(0, 10));
        controls.add(c.sectionHeading(messages.text("section.lifecycle.title"),
                messages.text("section.lifecycle.description")), BorderLayout.NORTH);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton refresh = c.secondaryButton(messages.text("button.refreshApplications"));
        refresh.addActionListener(event -> refresh());
        actions.add(refresh);

        advanced.field("field.applicationId", applicationId);
        details.setRows(12); advanced.field("managed.details", new javax.swing.JScrollPane(details));
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
            if (action == LifecycleAction.ENABLE_AUTOSTART || action == LifecycleAction.DISABLE_AUTOSTART)
                advanced.addOption(button);
            else actions.add(button);
        }
        inventory.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list,
                    value instanceof gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot snapshot
                        ? snapshot.application().id() + "  ·  " + snapshot.application().server().host() : "", index, selected, focus);
            }
        });
        inventory.addActionListener(event -> {
            if (loading) return;
            if (inventory.getSelectedItem() instanceof gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot snapshot) {
                applicationId.setText(snapshot.application().id());
                showDetails(snapshot);
            }
        });
        access.addActionListener(event -> {
            if (inventory.getSelectedItem() instanceof gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot snapshot)
                snapshot.runtimeConfiguration().flatMap(config -> config.userAccessUrl()).ifPresent(url -> {
                    try { java.awt.Desktop.getDesktop().browse(url.url()); }
                    catch (Exception failure) { output.setText(messages.safe(failure)); }
                });
        });
        access.setEnabled(false);
        JPanel selection = c.transparent(new BorderLayout(0, 8));
        selection.add(inventory, BorderLayout.NORTH); selection.add(access, BorderLayout.CENTER); selection.add(actions, BorderLayout.SOUTH);
        controls.add(selection, BorderLayout.CENTER);
        page.add(controls, BorderLayout.NORTH);
        page.add(c.outputCard(messages.text("section.applicationOutput.title"),
                messages.text("section.applicationOutput.description"), output), BorderLayout.CENTER);
        return advanced;
    }

    private void refresh() {
        if (loading || busy || service == null) return;
        loading = true;
        DesktopTaskExecutor.run(service::listManagedApplicationSummaries, applications -> {
            String selected = applicationId.getText();
            inventory.removeAllItems(); applications.forEach(inventory::addItem);
            if (!selected.isBlank()) inventory.setSelectedIndex(-1);
            applications.stream().filter(item -> item.application().id().equals(selected)).findFirst().ifPresent(inventory::setSelectedItem);
            loading = false;
            if (inventory.getSelectedItem() instanceof gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot snapshot) {
                if (selected.isBlank()) applicationId.setText(snapshot.application().id());
                showDetails(snapshot);
            } else { details.setText(""); access.setEnabled(false); access.setText(messages.text("managed.noWebEntry")); }
            if (output.getText().isBlank()) output.setText(messages.text(applications.isEmpty() ? "applications.none" : "managed.statusUnknown"));
        }, failure -> { loading = false; output.setText(messages.safe(failure)); });
    }

    private void execute(LifecycleAction action) {
        if (busy || loading) return;
        try {
            String selected = applicationId.getText().trim();
            if (selected.isBlank()) {
                throw new IllegalArgumentException(messages.text("lifecycle.selectApplication"));
            }
            char[] master = serverContext.masterPassword();
            output.setText(messages.text("lifecycle.running", Map.of(
                    "action", messages.text("lifecycle.action." + action.name().toLowerCase(Locale.ROOT)))));
            setBusy(true);
            DesktopTaskExecutor.run(
                    () -> service.executePersistedLifecycleWithStoredPassword(selected, action, master),
                    result -> { setBusy(false); output.setText(messages.text(
                            result.accepted() ? "lifecycle.accepted" : "lifecycle.rejected",
                            Map.of("message", messages.catalog().text(result.message()),
                                    "observation", messages.lifecycle(result.observation().orElse(null))))
                            + "\n" + messages.text("failure.operation.summary",
                            Map.of("operationId", result.operationIdentity().toString()))
                            + result.failure().map(failure -> "\n" + failure.code()).orElse("")
                            + result.nonFatalFailures().stream().map(failure -> "\n" + messages.text(
                                    "failure.warning.summary", Map.of("code", failure.code(),
                                            "message", messages.catalog().text(failure.userMessage()),
                                            "recovery", messages.text("failure.recovery."
                                                    + failure.recoveryDisposition().name()
                                                    .toLowerCase(Locale.ROOT)))))
                            .reduce("", String::concat)); },
                    exception -> { setBusy(false); output.setText(messages.text("lifecycle.failed",
                            Map.of("detail", messages.safe(exception)))); });
        } catch (Exception exception) {
            output.setText(messages.text("lifecycle.startFailed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        ((gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane) panel).setBusy(value);
    }

    private void showDetails(gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot snapshot) {
        var url = snapshot.runtimeConfiguration().flatMap(config -> config.userAccessUrl());
        access.setEnabled(url.isPresent());
        access.setText(url.map(value -> value.url().toString()).orElse(messages.text("managed.noWebEntry")));
        details.setText(messages.text("managed.details.summary", Map.of("application", snapshot.application().id(),
                "server", snapshot.application().server().host(), "unit", snapshot.application().systemdUnit(),
                "release", snapshot.currentReleaseSha256().orElse("-"),
                "runtime", snapshot.runtimeConfiguration().map(this::runtimeSummary).orElse("-"))));
        details.setCaretPosition(0);
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
