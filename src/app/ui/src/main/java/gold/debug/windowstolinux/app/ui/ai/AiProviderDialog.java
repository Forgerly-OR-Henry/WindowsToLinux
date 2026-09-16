package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.AiProviderSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;

/** Independent model form; only a successful fixed probe can save it. / 独立模型表单，仅固定探测成功后才能保存。 */
public final class AiProviderDialog extends JDialog {
    private final JTextField name = new JTextField(28), endpoint = new JTextField(28), model = new JTextField(28);
    private final JPasswordField key = new JPasswordField(28), master = new JPasswordField(28);
    private final JComboBox<CredentialStorageMode> mode = new JComboBox<>(CredentialStorageMode.values());
    private final JTextArea status = DesktopComponentFactory.outputArea();
    private final JButton save;
    private final JPanel form;
    private final AiApplicationFacade service;
    private final PageMessagePresenter messages;
    private final String id, credentialKey;
    private final Runnable saved;
    private DesktopTaskHandle task;
    private boolean busy, closing;

    /** Creates either a new model or an editor preserving the exact credential reference. / 创建新模型或保留精确凭据引用的编辑表单。 */
    public AiProviderDialog(Window owner, AiApplicationFacade service, DesktopComponentFactory c,
                            PageMessagePresenter messages, AiProviderSummary existing, Runnable saved) {
        super(owner, messages.text(existing == null ? "ai.models.add" : "ai.models.edit"), ModalityType.APPLICATION_MODAL);
        this.service = service; this.messages = messages; this.saved = saved;
        id = existing == null ? "model-" + UUID.randomUUID().toString().substring(0, 12) : existing.profile().id();
        credentialKey = existing == null ? "ai/provider/" + id + "/api-key" : existing.profile().credentialKey();
        mode.setSelectedItem(existing == null ? CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER : existing.profile().credentialMode()); messages.localize(mode, "credential.mode.");
        if (existing != null) { name.setText(existing.name()); endpoint.setText(existing.profile().chatCompletionsEndpoint().toString()); model.setText(existing.profile().model()); }
        key.putClientProperty("JTextField.placeholderText", messages.text(existing == null ? "field.apiKey" : "ai.models.keepKey"));
        mode.addActionListener(event -> master.setEnabled(mode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        master.setEnabled(mode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD);
        form = c.card(new GridBagLayout());
        c.addField(form, 0, 0, messages.text("ai.models.name"), name);
        c.addField(form, 1, 0, messages.text("field.aiEndpoint"), endpoint);
        c.addField(form, 2, 0, messages.text("field.model"), model);
        c.addField(form, 3, 0, messages.text("field.apiKey"), key);
        c.addField(form, 4, 0, messages.text("field.credentialStorage"), mode);
        c.addField(form, 5, 0, messages.text("field.masterPassword"), master);
        save = c.primaryButton(messages.text("ai.models.testSave")); save.addActionListener(event -> save());
        JButton cancel = c.secondaryButton(messages.text("button.cancel")); cancel.addActionListener(event -> close());
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT)); actions.add(cancel); actions.add(save);
        status.setRows(4); status.setLineWrap(true); status.setWrapStyleWord(true); status.setText(messages.text("ai.models.probeHint"));
        JPanel body = c.pagePanel(); body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20)); body.add(form, BorderLayout.NORTH);
        body.add(new JScrollPane(status)); body.add(actions, BorderLayout.SOUTH); setContentPane(body);
        setSize(620, 500); setMinimumSize(new Dimension(560, 450)); setLocationRelativeTo(owner); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { close(); }
            @Override public void windowClosed(WindowEvent event) { key.setText(""); master.setText(""); }
        });
    }
    private void save() {
        if (busy) return;
        try {
            if (name.getText().isBlank() || name.getText().length() > 120 || name.getText().chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("invalid model display name");
            var profile = new AiProviderProfile(id, URI.create(endpoint.getText().trim()), model.getText().trim(), credentialKey, (CredentialStorageMode) mode.getSelectedItem());
            String label = name.getText(); char[] apiKey = key.getPassword(), unlock = master.getPassword();
            setBusy(true); status.setText(messages.text("ai.models.testing"));
            task = DesktopTaskExecutor.submit(() -> {
                try { service.saveAiConfiguration(profile, label, unlock, apiKey); return true; }
                finally { Arrays.fill(apiKey, '\0'); Arrays.fill(unlock, '\0'); }
            }, value -> { setBusy(false); saved.run(); dispose(); }, failure -> {
                setBusy(false); if (closing) dispose(); else status.setText(messages.safe(failure));
            });
        } catch (IllegalArgumentException failure) { status.setText(messages.text("ai.models.invalidForm")); }
        catch (Exception failure) { status.setText(messages.safe(failure)); }
    }
    private void close() { if (busy && task != null) { closing = true; task.cancel(); status.setText(messages.text("ai.models.cancelling")); } else dispose(); }
    private void setBusy(boolean value) {
        busy = value; save.setEnabled(!value);
        for (Component child : form.getComponents()) child.setEnabled(!value);
        master.setEnabled(!value && mode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD);
    }
}
