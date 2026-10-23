package gold.debug.windowstolinux.app.ui.ai;

import java.awt.*;
import java.net.URI;
import java.util.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;

/** Explicit single-URL configuration editor. / 单 URL 配置的显式编辑器。 */
final class PlatformModelDialog extends JDialog {
    private final PlatformModelService service;

    private final PageMessagePresenter messages;

    private final Runnable saved;

    private final String id, credential;

    private final JTextField name = new JTextField(28), endpoint = new JTextField(28), model = new JTextField(28),
        limit = new JTextField(12);

    private final JPasswordField key = new JPasswordField(28);

    private final JComboBox<ModelReasoningLevel> reasoning = new JComboBox<>(ModelReasoningLevel.values());

    private final JComboBox<ModelProtocol> protocol = new JComboBox<>(ModelProtocol.values());

    private final JButton save;

    private final JLabel status = new JLabel();

    private final JPanel form;

    private boolean busy;

    PlatformModelDialog(Window owner, PlatformModelService service, DesktopPartFactory c,
                        PageMessagePresenter messages, AiModelInfo existing, OptionalInt outputLimit, Runnable saved) {
        super(owner, messages.text(existing == null ? "ai.models.add" : "ai.models.edit"), ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.messages = messages;
        this.saved = saved;
        id = existing == null ? "model-" + UUID.randomUUID().toString().substring(0, 12) : existing.profile().id();
        credential = existing == null ? "ai/provider/" + id + "/api-key" : existing.profile().credentialKey();
        form = c.card(new GridBagLayout());
        var fields = new JTextField[]{name, endpoint, model, key, limit};
        var labels = new String[]{"ai.models.name", "ai.models.endpoint", "field.model", "field.apiKey", "platform.models.limit"};
        var names = new String[]{"model.name", "model.endpoint", "model.model", "model.key", "model.limit"};
        for (int i = 0; i < fields.length; i++) {
            fields[i].setName(names[i]);
            c.addField(form, i, 0, messages, labels[i], fields[i]);
        }
        reasoning.setName("model.reasoningEffort");
        protocol.setName("model.protocol");
        messages.localize(reasoning, "ai.models.reasoningEffort.");
        messages.localize(protocol, "ai.models.protocol.");
        c.addField(form, 5, 0, messages, "ai.models.reasoningEffort", reasoning);
        c.addField(form, 6, 0, messages, "ai.models.protocol", protocol);
        if (existing != null) {
            name.setText(existing.name());
            endpoint.setText(existing.profile().endpoint().toString());
            model.setText(existing.profile().model());
            reasoning.setSelectedItem(existing.profile().reasoningEffort());
            protocol.setSelectedItem(existing.profile().protocol());
            key.putClientProperty("JTextField.placeholderText", messages.text("ai.models.keepKey"));
        }
        limit.setText(outputLimit.isPresent() ? Integer.toString(outputLimit.getAsInt()) : "4096");
        save = messages.bind(c.primaryButton(""), "ai.models.saveConfiguration");
        save.setName("model.save");
        save.addActionListener(event -> saveConfiguration());
        var cancel = messages.bind(c.secondaryButton(""), "button.cancel");
        cancel.setName("model.cancel");
        cancel.addActionListener(event -> {
            if (!busy) dispose();
        });
        var actions = c.transparent(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel);
        actions.add(save);
        var footer = c.transparent(new BorderLayout(0, 8));
        footer.add(status);
        footer.add(actions, BorderLayout.SOUTH);
        var body = c.pagePanel();
        body.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        body.add(new JScrollPane(form));
        body.add(footer, BorderLayout.SOUTH);
        setContentPane(body);
        setName("platform.model.editor");
        setSize(700, 540);
        setMinimumSize(new Dimension(560, 480));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                if (!busy) dispose();
            }
        });
    }

    private void saveConfiguration() {
        if (busy || service == null) return;
        char[] input = key.getPassword();
        key.setText("");
        try {
            var profile = new AiModelProfile(id, URI.create(endpoint.getText().strip()), model.getText().strip(), credential,
                (ModelReasoningLevel) reasoning.getSelectedItem(), (ModelProtocol) protocol.getSelectedItem());
            var displayName = name.getText().strip();
            int ceiling = Integer.parseInt(limit.getText().strip());
            busy = true;
            save.setEnabled(false);
            for (var child : form.getComponents()) child.setEnabled(false);
            DesktopTaskRunner.run(() -> {
                service.save(profile, displayName, input, ceiling);
                return true;
            }, ignored -> {
                busy = false;
                saved.run();
                dispose();
            }, failure -> {
                Arrays.fill(input, '\0');
                busy = false;
                save.setEnabled(true);
                for (var child : form.getComponents()) child.setEnabled(true);
                status.setText(messages.safe(failure));
            });
        } catch (Exception failure) {
            Arrays.fill(input, '\0');
            status.setText(messages.text("ai.models.invalidForm"));
        }
    }

    static JDialog preview(Window owner, DesktopPartFactory c, PageMessagePresenter messages) {
        var dialog = new PlatformModelDialog(owner, null, c, messages, null, OptionalInt.of(4096), () -> {
        });
        dialog.name.setText(messages.text("debug.sample.model"));
        dialog.endpoint.setText(messages.text("debug.sample.endpoint"));
        dialog.model.setText(messages.text("debug.sample.modelId"));
        dialog.status.setText(messages.text("debug.blocked"));
        dialog.save.setEnabled(false);
        return dialog;
    }

    @Override
    public void dispose() {
        key.setText("");
        super.dispose();
    }
}
