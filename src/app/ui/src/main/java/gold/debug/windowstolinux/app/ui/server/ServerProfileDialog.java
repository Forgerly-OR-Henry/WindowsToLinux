package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Independent server editor with the existing secret store and SSH trust flow. / 独立服务器编辑窗口，复用秘密存储及 SSH 信任流程。 */
public final class ServerProfileDialog extends JDialog {
    private final JTextField name = new JTextField(24), host = new JTextField(24), username = new JTextField(24);
    private final JTextField port = new JTextField("22", 8);
    private final JPasswordField password = new JPasswordField(24), master = new JPasswordField(24);
    private final JComboBox<CredentialStorageMode> storage = new JComboBox<>(CredentialStorageMode.values());
    private final JTextArea status = DesktopComponentFactory.outputArea();
    private final JButton save;
    private final JPanel form;
    private final ServerApplicationFacade service;
    private final PageMessagePresenter messages;
    private final Consumer<ServerProfile> saved;
    private final String id, credentialKey;
    private boolean busy, existing;

    /** Creates an add or edit dialog; null profile starts an independent new identity. / 创建添加或编辑窗口，空配置生成独立新身份。 */
    public ServerProfileDialog(Window owner, ServerApplicationFacade service, DesktopComponentFactory c,
                               PageMessagePresenter messages, ServerProfile profile, Consumer<ServerProfile> saved) {
        super(owner, messages.text(profile == null ? "auto.addServer" : "server.edit"), ModalityType.APPLICATION_MODAL);
        this.service = service; this.messages = messages; this.saved = saved;
        existing = profile != null;
        id = existing ? profile.id() : "server-" + UUID.randomUUID().toString().substring(0, 12);
        credentialKey = existing ? profile.credentialKey() : "ssh/" + id + "/password";
        storage.setSelectedItem(existing ? profile.credentialMode() : CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER);
        messages.localize(storage, "credential.mode.");
        storage.addActionListener(event -> master.setEnabled(storage.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        master.setEnabled(storage.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD);
        if (existing) {
            name.setText(profile.displayName()); host.setText(profile.host()); username.setText(profile.username());
            port.setText(Integer.toString(profile.sshPort())); password.setToolTipText(messages.text("server.password.keep"));
        }
        form = c.card(new GridBagLayout());
        c.addField(form, 0, 0, messages.text("server.name"), name);
        c.addField(form, 1, 0, messages.text("field.host"), host);
        c.addField(form, 2, 0, messages.text("field.sshPort"), port);
        c.addField(form, 3, 0, messages.text("field.sshUser"), username);
        c.addField(form, 4, 0, messages.text("field.sshPassword"), password);
        c.addField(form, 5, 0, messages.text("field.credentialStorage"), storage);
        c.addField(form, 6, 0, messages.text("field.masterPassword"), master);
        save = c.primaryButton(messages.text("button.saveServer")); save.addActionListener(event -> save());
        JButton cancel = c.secondaryButton(messages.text("button.cancel")); cancel.addActionListener(event -> { if (!busy) dispose(); });
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT)); actions.add(cancel); actions.add(save);
        JPanel body = c.pagePanel(); body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        body.add(form, BorderLayout.NORTH); status.setRows(4); status.setLineWrap(true); status.setWrapStyleWord(true);
        body.add(new JScrollPane(status), BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH);
        setContentPane(body); setMinimumSize(new Dimension(560, 490)); pack(); setLocationRelativeTo(owner);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { if (!busy) dispose(); }
            @Override public void windowClosed(WindowEvent event) { password.setText(""); master.setText(""); }
        });
        getRootPane().setDefaultButton(save);
    }

    private void save() {
        if (busy) return;
        try {
            var mode = (CredentialStorageMode) storage.getSelectedItem();
            var profile = new ServerProfile(id, host.getText().trim(), Integer.parseInt(port.getText().trim()),
                    username.getText().trim(), credentialKey, mode, name.getText());
            char[] secret = password.getPassword(), unlock = master.getPassword();
            if (!existing && secret.length == 0) { Arrays.fill(unlock, '\0'); throw new IllegalArgumentException(messages.text("server.password.required")); }
            busy = true; save.setEnabled(false); setInputs(false); status.setText(messages.text("server.connecting"));
            var written = new java.util.concurrent.atomic.AtomicBoolean();
            DesktopTaskExecutor.run(() -> {
                try {
                    service.saveServerProfile(profile, mode, unlock.clone(), secret);
                    written.set(true);
                    service.verifyServer(profile, mode, unlock.clone(), fingerprint -> ServerTrustPrompt.confirm(this, messages, fingerprint));
                    return profile;
                } finally { Arrays.fill(secret, '\0'); Arrays.fill(unlock, '\0'); }
            }, value -> { busy = false; saved.accept(value); dispose(); }, failure -> {
                busy = false; existing = existing || written.get(); save.setEnabled(true); setInputs(true);
                status.setText(messages.text("server.saveCheckFailed", Map.of("detail", messages.safe(failure))));
            });
        } catch (Exception failure) { status.setText(messages.safe(failure)); }
    }

    private void setInputs(boolean enabled) {
        for (Component child : form.getComponents()) child.setEnabled(enabled);
        master.setEnabled(enabled && storage.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD);
    }
}
