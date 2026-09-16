package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the server form, credentials-in-memory state, and server workflows. / 持有服务器表单、内存凭据状态与服务器流程。 */
public final class ServerPage implements ServerContext {
    private final JFrame owner;
    private final ServerApplicationFacade service;
    private final PageMessagePresenter messages;
    private final JTextField id = new JTextField("server-one", 20);
    private final JTextField host = new JTextField(20);
    private final JTextField port = new JTextField("22", 6);
    private final JTextField username = new JTextField(20);
    private final JPasswordField password = new JPasswordField(20);
    private final JComboBox<CredentialStorageMode> credentialMode = new JComboBox<>(CredentialStorageMode.values());
    private final JPasswordField masterPassword = new JPasswordField(20);
    private final JTextArea output = DesktopComponentFactory.outputArea();
    private final JPanel panel;
    private boolean busy;
    private ServerInventoryPane inventory;
    private String displayName = "server-one";
    private String credentialKey = "ssh/server-one/password";

    /** Creates the stateful page controller. / 创建有状态页面控制器。 */
    public ServerPage(JFrame owner, ServerApplicationFacade service, DesktopComponentFactory components, PageMessagePresenter messages) {
        this.owner = owner;
        this.service = service;
        this.messages = messages;
        credentialMode.setSelectedItem(CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER);
        messages.localize(credentialMode, "credential.mode.");
        credentialMode.addActionListener(event -> masterPassword.setEnabled(
                credentialMode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        masterPassword.setEnabled(false);
        panel = createPanel(components);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() { return panel; }

    /** Captures unsaved state including temporary password copies. / 捕获包含临时密码副本的未保存状态。 */
    public ServerPageState captureState() {
        return new ServerPageState(id.getText(), host.getText(), port.getText(), username.getText(), password.getPassword(),
                credentialMode(), masterPassword(), output.getText(), displayName, inventory.search(), credentialKey);
    }

    /** Restores unsaved state. / 恢复未保存状态。 */
    public void restoreState(ServerPageState state) {
        id.setText(state.id());
        host.setText(state.host());
        port.setText(state.port());
        username.setText(state.username());
        password.setText(new String(state.password()));
        credentialMode.setSelectedItem(state.credentialMode());
        masterPassword.setText(new String(state.masterPassword()));
        output.setText(state.output());
        displayName = state.displayName(); credentialKey = state.credentialKey(); inventory.search(state.search());
    }

    /** Performs the {@code profile} operation. / 执行 {@code profile} 操作。 */
    @Override public ServerProfile profile() {
        String serverId = id.getText().trim();
        return new ServerProfile(serverId, host.getText().trim(), Integer.parseInt(port.getText().trim()),
                username.getText().trim(), credentialKey, credentialMode(), displayName);
    }
    /** Synchronizes saved selection with manual server and component operations. / 同步已保存选择与手动服务器及组件操作。 */
    @Override public void selectProfile(ServerProfile profile) {
        credentialKey = profile.credentialKey();
        displayName = profile.displayName(); id.setText(profile.id()); host.setText(profile.host()); port.setText(Integer.toString(profile.sshPort()));
        username.setText(profile.username()); credentialMode.setSelectedItem(profile.credentialMode()); password.setText("");
    }
    /** Performs the {@code credentialMode} operation. / 执行 {@code credentialMode} 操作。 */
    @Override public CredentialStorageMode credentialMode() {
        return (CredentialStorageMode) credentialMode.getSelectedItem();
    }
    /** Performs the {@code masterPassword} operation. / 执行 {@code masterPassword} 操作。 */
    @Override public char[] masterPassword() { return masterPassword.getPassword(); }

    /** Performs the {@code confirmFingerprint} operation. / 执行 {@code confirmFingerprint} 操作。 */
    @Override public boolean confirmFingerprint(String fingerprint) {
        return ServerTrustPrompt.confirm(owner, messages, fingerprint);
    }

    private JPanel createPanel(DesktopComponentFactory c) {
        JPanel page = c.pagePanel();
        var advanced = new gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane(page, c, messages);
        inventory = new ServerInventoryPane(service, c, messages, false, this::selectProfile);
        page.add(inventory, BorderLayout.CENTER);
        advanced.field("field.masterPassword", masterPassword);
        JButton prepare = c.secondaryButton(messages.text("button.prepareEnvironment"));
        prepare.addActionListener(event -> prepare(prepare)); advanced.addOption(prepare);
        advanced.addOption(new javax.swing.JScrollPane(output));
        return advanced;
    }

    private void setBusy(boolean value) {
        busy = value;
        ((gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane) panel).setBusy(value);
    }

    private void prepare(JButton trigger) {
        if (busy) return;
        try {
            ServerProfile entered = profile();
            ServerProfile saved = service.findServerProfile(entered.id()).orElseThrow(
                    () -> new IllegalStateException(messages.text("environment.serverSaveFirst")));
            if (!saved.equals(entered)) {
                throw new IllegalStateException(messages.text("environment.serverChanged"));
            }
            if (!gold.debug.windowstolinux.app.ui.component.SystemPreparationDialog.confirmEnvironment(owner, messages,
                    Map.of("serverId", saved.id(), "host", saved.host(), "port", saved.sshPort(), "username", saved.username()))) {
                return;
            }
            char[] master = masterPassword();
            setBusy(true);
            output.setText(messages.text("environment.preparing"));
            DesktopTaskExecutor.run(
                    () -> service.prepareEnvironmentWithStoredPassword(saved, saved.credentialMode(), master,
                            ServerPage.this::confirmFingerprint, true, plan ->
                                    gold.debug.windowstolinux.app.ui.component.SystemPreparationDialog.confirm(owner, messages,
                                            Map.of("serverId", saved.id(), "host", saved.host(),
                                                    "security", plan.securityState().name(), "reboot",
                                                    plan.state() == gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState.UNPREPARED
                                                            || plan.state() == gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState.REBOOT_PENDING))),
                    result -> {
                        setBusy(false);
                        output.setText(environmentSummary(result));
                    },
                    exception -> {
                        setBusy(false);
                        output.setText(messages.text("environment.incomplete",
                                Map.of("detail", messages.safe(exception))));
                    });
        } catch (Exception exception) {
            output.setText(messages.text("environment.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private String environmentSummary(EnvironmentSetupResult result) {
        var value = result.capabilities();
        return messages.text("environment.completed", Map.ofEntries(
                Map.entry("os", value.operatingSystem()), Map.entry("architecture", value.architecture()),
                Map.entry("java21", availability(value.java21Available())), Map.entry("maven", availability(value.mavenAvailable())),
                Map.entry("tar", availability(value.tarAvailable())), Map.entry("curl", availability(value.curlAvailable())),
                Map.entry("systemd", availability(value.systemdAvailable())), Map.entry("socket", availability(value.socketInspectionAvailable())),
                Map.entry("limits", availability(value.buildLimitToolsAvailable())), Map.entry("sudo", availability(value.nonInteractiveSudoAvailable())),
                Map.entry("space", value.availableBytes())));
    }

    private String availability(boolean value) { return messages.text(value ? "availability.ready" : "availability.notReady"); }
}
