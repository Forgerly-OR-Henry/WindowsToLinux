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
    private ServerSelectionPane selection;

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
                credentialMode(), masterPassword(), output.getText());
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
        selection.select(state.id());
    }

    /** Performs the {@code profile} operation. / 执行 {@code profile} 操作。 */
    @Override public ServerProfile profile() {
        String serverId = id.getText().trim();
        return new ServerProfile(serverId, host.getText().trim(), Integer.parseInt(port.getText().trim()),
                username.getText().trim(), "ssh/" + serverId + "/password", credentialMode());
    }
    /** Synchronizes saved selection with manual server and component operations. */
    @Override public void selectProfile(ServerProfile profile) {
        id.setText(profile.id()); host.setText(profile.host()); port.setText(Integer.toString(profile.sshPort()));
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
        AtomicBoolean accepted = new AtomicBoolean(false);
        try {
            SwingUtilities.invokeAndWait(() -> accepted.set(JOptionPane.showConfirmDialog(owner,
                    messages.text("fingerprint.confirm", Map.of("fingerprint", fingerprint)),
                    messages.text("fingerprint.confirm.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION));
        } catch (Exception ignored) {
            // A failed or interrupted confirmation must reject host trust. / 确认失败或中断时必须拒绝主机信任。
            return false;
        }
        return accepted.get();
    }

    private JPanel createPanel(DesktopComponentFactory c) {
        JPanel page = c.pagePanel();
        var advanced = new gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane(page, c, messages);
        JPanel card = c.card(new BorderLayout(0, 12));
        card.add(c.sectionHeading(messages.text("section.connection.title"), messages.text("section.connection.description")),
                BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        advanced.field("field.serverId", id);
        c.addField(form, 0, 0, messages.text("field.host"), host);
        advanced.field("field.sshPort", port);
        c.addField(form, 1, 0, messages.text("field.sshUser"), username);
        c.addField(form, 2, 0, messages.text("field.sshPassword"), password);
        advanced.field("field.credentialStorage", credentialMode);
        advanced.field("field.masterPassword", masterPassword);
        card.add(form, BorderLayout.CENTER);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton save = c.secondaryButton(messages.text("button.saveServer"));
        save.addActionListener(event -> verify(true));
        JButton verify = c.primaryButton(messages.text("button.verifyServer"));
        verify.addActionListener(event -> verify(false));
        JButton prepare = c.secondaryButton(messages.text("button.prepareEnvironment"));
        prepare.addActionListener(event -> prepare(prepare));
        actions.add(save); advanced.addOption(verify); advanced.addOption(prepare);
        card.add(actions, BorderLayout.SOUTH);
        JPanel top = c.transparent(new BorderLayout(0, 12));
        selection = new ServerSelectionPane(service, c, messages, this::selectProfile);
        top.add(selection, BorderLayout.NORTH);
        top.add(card, BorderLayout.CENTER);
        page.add(top, BorderLayout.NORTH);
        page.add(c.outputCard(messages.text("section.verification.title"),
                messages.text("section.verification.description"), output), BorderLayout.CENTER);
        return advanced;
    }

    private void verify(boolean save) {
        if (busy) return;
        try {
            ServerProfile profile = profile();
            CredentialStorageMode mode = credentialMode();
            char[] master = masterPassword();
            char[] secret = save ? password.getPassword() : new char[0];
            output.setText(messages.text("server.connecting"));
            setBusy(true);
            DesktopTaskExecutor.run(
                    () -> {
                        try {
                            if (save && (secret.length > 0 || !service.findServerProfile(profile.id()).filter(profile::equals).isPresent()))
                                service.saveServerProfile(profile, mode, master.clone(), secret);
                            return service.verifyServer(profile, mode, master.clone(), ServerPage.this::confirmFingerprint);
                        } finally { java.util.Arrays.fill(master, '\0'); java.util.Arrays.fill(secret, '\0'); }
                    },
                    value -> {
                        setBusy(false); if (save) { password.setText(""); selection.select(profile.id()); }
                        output.setText(messages.text("server.capabilities", Map.ofEntries(
                            Map.entry("os", value.operatingSystem()), Map.entry("architecture", value.architecture()),
                            Map.entry("java21", availability(value.java21Available())), Map.entry("maven", availability(value.mavenAvailable())),
                            Map.entry("tar", availability(value.tarAvailable())), Map.entry("curl", availability(value.curlAvailable())),
                            Map.entry("systemd", availability(value.systemdAvailable())), Map.entry("sudo", availability(value.nonInteractiveSudoAvailable())),
                            Map.entry("limits", availability(value.buildLimitToolsAvailable())), Map.entry("space", value.availableBytes()))));
                    },
                    exception -> { setBusy(false); output.setText(messages.text("server.verifyFailed",
                            Map.of("detail", messages.safe(exception)))); });
        } catch (Exception exception) {
            setBusy(false);
            output.setText(messages.text("server.invalid", Map.of("detail", messages.safe(exception))));
        }
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
            String confirmation = messages.text("environment.confirm", Map.of("serverId", saved.id(), "host", saved.host(),
                    "port", saved.sshPort(), "username", saved.username()));
            if (JOptionPane.showConfirmDialog(owner, confirmation, messages.text("environment.confirm.title"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
            char[] master = masterPassword();
            setBusy(true);
            output.setText(messages.text("environment.preparing"));
            DesktopTaskExecutor.run(
                    () -> service.prepareEnvironmentWithStoredPassword(saved, saved.credentialMode(), master,
                            ServerPage.this::confirmFingerprint, true),
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
