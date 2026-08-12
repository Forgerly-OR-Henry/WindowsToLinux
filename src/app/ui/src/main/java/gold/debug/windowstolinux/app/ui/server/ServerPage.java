package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.shell.PageMessages;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationResult;
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
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the server form, credentials-in-memory state, and server workflows. / 持有服务器表单、内存凭据状态与服务器流程。 */
public final class ServerPage implements ServerContext {
    private final JFrame owner;
    private final DesktopApplicationService service;
    private final PageMessages messages;
    private final JTextField id = new JTextField("server-one", 20);
    private final JTextField host = new JTextField(20);
    private final JTextField port = new JTextField("22", 6);
    private final JTextField username = new JTextField(20);
    private final JPasswordField password = new JPasswordField(20);
    private final JComboBox<CredentialStorageMode> credentialMode = new JComboBox<>(CredentialStorageMode.values());
    private final JPasswordField masterPassword = new JPasswordField(20);
    private final JTextArea output = DesktopComponents.outputArea();
    private final JPanel panel;

    /** Creates the stateful page controller. / 创建有状态页面控制器。 */
    public ServerPage(JFrame owner, DesktopApplicationService service, DesktopComponents components, PageMessages messages) {
        this.owner = owner;
        this.service = service;
        this.messages = messages;
        messages.localize(credentialMode, "credential.mode.");
        credentialMode.addActionListener(event -> masterPassword.setEnabled(
                credentialMode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
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
    }

    @Override public ServerProfile profile() {
        String serverId = id.getText().trim();
        return new ServerProfile(serverId, host.getText().trim(), Integer.parseInt(port.getText().trim()),
                username.getText().trim(), "ssh/" + serverId + "/password", credentialMode());
    }
    @Override public CredentialStorageMode credentialMode() {
        return (CredentialStorageMode) credentialMode.getSelectedItem();
    }
    @Override public char[] masterPassword() { return masterPassword.getPassword(); }

    @Override public boolean confirmFingerprint(String fingerprint) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        try {
            SwingUtilities.invokeAndWait(() -> accepted.set(JOptionPane.showConfirmDialog(owner,
                    messages.text("fingerprint.confirm", Map.of("fingerprint", fingerprint)),
                    messages.text("fingerprint.confirm.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION));
        } catch (Exception ignored) {
            return false;
        }
        return accepted.get();
    }

    private JPanel createPanel(DesktopComponents c) {
        JPanel page = c.pagePanel();
        JPanel card = c.card(new BorderLayout(0, 12));
        card.add(c.sectionHeading(messages.text("section.connection.title"), messages.text("section.connection.description")),
                BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        c.addField(form, 0, 0, messages.text("field.serverId"), id);
        c.addField(form, 0, 1, messages.text("field.host"), host);
        c.addField(form, 1, 0, messages.text("field.sshPort"), port);
        c.addField(form, 1, 1, messages.text("field.sshUser"), username);
        c.addField(form, 2, 0, messages.text("field.sshPassword"), password);
        c.addField(form, 2, 1, messages.text("field.credentialStorage"), credentialMode);
        c.addField(form, 3, 0, messages.text("field.masterPassword"), masterPassword);
        card.add(form, BorderLayout.CENTER);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton save = c.secondaryButton(messages.text("button.saveServer"));
        save.addActionListener(event -> save());
        JButton verify = c.primaryButton(messages.text("button.verifyServer"));
        verify.addActionListener(event -> verify());
        JButton prepare = c.secondaryButton(messages.text("button.prepareEnvironment"));
        prepare.addActionListener(event -> prepare(prepare));
        actions.add(save); actions.add(verify); actions.add(prepare);
        card.add(actions, BorderLayout.SOUTH);
        page.add(card, BorderLayout.NORTH);
        page.add(c.outputCard(messages.text("section.verification.title"),
                messages.text("section.verification.description"), output), BorderLayout.CENTER);
        return page;
    }

    private void save() {
        try {
            service.saveServerProfile(profile(), credentialMode(), masterPassword(), password.getPassword());
            output.setText(messages.text("server.saved"));
            password.setText("");
            masterPassword.setText("");
        } catch (Exception exception) {
            output.setText(messages.text("server.saveFailed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void verify() {
        try {
            ServerProfile profile = profile();
            CredentialStorageMode mode = credentialMode();
            char[] master = masterPassword();
            output.setText(messages.text("server.connecting"));
            new SwingWorker<gold.debug.windowstolinux.shared.model.server.ServerCapabilities, Void>() {
                @Override protected gold.debug.windowstolinux.shared.model.server.ServerCapabilities doInBackground() throws Exception {
                    return service.verifyServer(profile, mode, master, ServerPage.this::confirmFingerprint);
                }
                @Override protected void done() {
                    try {
                        var value = get();
                        output.setText(messages.text("server.capabilities", Map.ofEntries(
                                Map.entry("os", value.operatingSystem()), Map.entry("architecture", value.architecture()),
                                Map.entry("java21", value.java21Available()), Map.entry("maven", value.mavenAvailable()),
                                Map.entry("tar", value.tarAvailable()), Map.entry("curl", value.curlAvailable()),
                                Map.entry("systemd", value.systemdAvailable()), Map.entry("sudo", value.nonInteractiveSudoAvailable()),
                                Map.entry("limits", value.buildLimitToolsAvailable()), Map.entry("space", value.availableBytes()))));
                    } catch (Exception exception) {
                        output.setText(messages.text("server.verifyFailed", Map.of("detail", messages.safe(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            output.setText(messages.text("server.invalid", Map.of("detail", messages.safe(exception))));
        }
    }

    private void prepare(JButton trigger) {
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
            trigger.setEnabled(false);
            output.setText(messages.text("environment.preparing"));
            new SwingWorker<EnvironmentPreparationResult, Void>() {
                @Override protected EnvironmentPreparationResult doInBackground() throws Exception {
                    return service.prepareEnvironmentWithStoredPassword(saved, saved.credentialMode(), master,
                            ServerPage.this::confirmFingerprint, true);
                }
                @Override protected void done() {
                    trigger.setEnabled(true);
                    try { output.setText(environmentSummary(get())); }
                    catch (Exception exception) { output.setText(messages.text("environment.incomplete",
                            Map.of("detail", messages.safe(exception)))); }
                }
            }.execute();
        } catch (Exception exception) {
            output.setText(messages.text("environment.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private String environmentSummary(EnvironmentPreparationResult result) {
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
