package gold.debug.windowstolinux.mod.deployment.ui.application;

import java.awt.*;
import java.util.function.Function;
import javax.swing.*;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentRuntimeSettings;

/** Edits literal launch fields; the next dialog reviews the product-rendered real command. */
final class DeploymentRuntimeSettingsForm {
    final JPanel panel = new JPanel(new GridBagLayout());
    private final JComboBox<String> component = new JComboBox<>();
    private final JTextField port = new JTextField(12), url = new JTextField(36), entry = new JTextField(36), arguments = new JTextField(36);
    private final JLabel error = new JLabel();
    private final DeploymentRuntimeSettings.Form original;
    private final Function<String, String> text;
    private int previousPort;
    DeploymentRuntimeSettingsForm(DeploymentRuntimeSettings.Form form, Function<String, String> text) {
        this.original = form;
        this.text = text;
        form.components().forEach(v -> component.addItem(v.component()));
        port.setName("applications.runtime.port"); url.setName("applications.runtime.url");
        entry.setName("applications.runtime.entrypoint"); arguments.setName("applications.runtime.arguments");
        component.setName("applications.runtime.component");
        add("runtimeComponent", component, 0, text); add("runtimePort", port, 1, text);
        add("runtimeUrl", url, 2, text); add("runtimeEntry", entry, 3, text); add("runtimeArguments", arguments, 4, text);
        var note = new JTextArea(text.apply("runtimeHint"));
        note.setEditable(false); note.setLineWrap(true); note.setWrapStyleWord(true); note.setOpaque(false); note.setColumns(44); note.setRows(4);
        var c = new GridBagConstraints(); c.gridx = 0; c.gridy = 5; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(12, 4, 4, 4); panel.add(note, c);
        c.gridy = 6; panel.add(error, c);
        component.addActionListener(e -> fill(form)); fill(form);
        port.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { syncUrl(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { syncUrl(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { syncUrl(); }
        });
        var clearError = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { error.setText(""); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { error.setText(""); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { error.setText(""); }
        };
        for (var field : java.util.List.of(port, url, entry, arguments)) field.getDocument().addDocumentListener(clearError);
    }
    private void fill(DeploymentRuntimeSettings.Form form) {
        var v = form.components().stream().filter(item -> item.component().equals(component.getSelectedItem())).findFirst().orElseThrow();
        port.setText(v.port()); url.setText(v.accessUrl()); entry.setText(v.entrypoint()); arguments.setText(v.arguments());
        previousPort = v.port().isBlank() ? 0 : Integer.parseInt(v.port());
        port.setEnabled(!v.port().isBlank()); url.setEnabled(!v.accessUrl().isBlank());
    }
    private void syncUrl() {
        try {
            int selected = Integer.parseInt(port.getText());
            if (selected < 1 || selected > 65535) return;
            if (!url.getText().isBlank()) {
                var u = java.net.URI.create(url.getText());
                int original = u.getPort() == -1 ? u.getScheme().equalsIgnoreCase("https") ? 443 : 80 : u.getPort();
                if (original == previousPort)
                    url.setText(new java.net.URI(u.getScheme(), u.getUserInfo(), u.getHost(), selected, u.getPath(), u.getQuery(), u.getFragment()).toString());
            }
            previousPort = selected;
        } catch (IllegalArgumentException | java.net.URISyntaxException invalid) { }
    }
    DeploymentRuntimeSettings.Values values() {
        var previous = original.components().stream().filter(v -> v.component().equals(component.getSelectedItem())).findFirst().orElseThrow();
        String selectedPort = port.getText().isBlank() ? previous.port() : port.getText().trim();
        if (!selectedPort.isBlank()) {
            try {
                int value = Integer.parseInt(selectedPort);
                if (value < 1 || value > 65535) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                port.requestFocusInWindow();
                throw new IllegalArgumentException(text.apply("runtimePortInvalid"));
            }
        }
        if (!url.getText().isBlank()) {
            try {
                var value = gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentEndpoint
                    .requireAccessUrl(java.net.URI.create(url.getText().trim()));
                int urlPort = value.getPort() < 1 ? value.getScheme().equalsIgnoreCase("https") ? 443 : 80 : value.getPort();
                if (!selectedPort.equals(Integer.toString(urlPort))) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                url.requestFocusInWindow();
                throw new IllegalArgumentException(text.apply("runtimeUrlInvalid"));
            }
        }
        try {
            new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCommand(entry.getText().trim(),
                gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentCommandText.parse(arguments.getText()));
        } catch (IllegalArgumentException invalid) {
            entry.requestFocusInWindow();
            throw new IllegalArgumentException(text.apply("runtimeCommandInvalid"));
        }
        return new DeploymentRuntimeSettings.Values((String) component.getSelectedItem(), selectedPort, url.getText().trim(), entry.getText().trim(), arguments.getText());
    }
    void problem(IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
    private void add(String key, JComponent field, int row, Function<String, String> text) {
        var c = new GridBagConstraints(); c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(5, 4, 5, 12);
        var label = new JLabel(text.apply(key)); label.setLabelFor(field);
        panel.add(label, c); c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(field, c);
    }
}
