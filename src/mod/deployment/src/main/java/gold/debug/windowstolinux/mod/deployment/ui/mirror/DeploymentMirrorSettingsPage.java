package gold.debug.windowstolinux.mod.deployment.ui.mirror;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.*;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.service.mirror.*;

/** Mod-owned settings contribution. All database work uses admitted short tasks off the EDT. */
public final class DeploymentMirrorSettingsPage implements ModPage {
    public static final String MESSAGES = "gold.debug.windowstolinux.mod.deployment.ui.mirror.mirror-settings";
    private final DeploymentMirrorTasks service;
    private final MirrorCatalog catalog;
    private JPanel root;
    private DeploymentMirrorSettingsEditor editor;
    private JLabel status;
    private JButton save, reload, credential;
    private final java.util.List<String> credentialReferences = new ArrayList<>();
    private JComboBox<ServerChoice> server;
    private JComboBox<CountryChoice> country;
    private ResourceBundle messages;
    private UiEnvironment environment;
    private boolean busy, closed, loadingCountry;
    private TaskHandle<?> pending;

    public DeploymentMirrorSettingsPage(ModContext context) {
        service = new DeploymentMirrorTasks(context);
        catalog = MirrorCatalog.load();
    }

    private record ServerChoice(String id, String label) {
        @Override public String toString() { return label; }
    }
    private record CountryChoice(String code, String label) {
        @Override public String toString() { return label; }
    }

    @Override public JComponent create(UiEnvironment environment) {
        if (root != null) throw new IllegalStateException("Mirror settings already mounted");
        this.environment = environment;
        messages = ResourceBundle.getBundle(MESSAGES, environment.locale());
        root = new JPanel(new BorderLayout(8, 8));
        root.setName("deployment.mirror.settings");
        editor = new DeploymentMirrorSettingsEditor(catalog, messages,
            () -> DeploymentMirrorPrivacy.confirm(root, messages));
        var header = new JPanel(new BorderLayout());
        var explanation = new JTextArea(messages.getString("description"));
        explanation.setLineWrap(true); explanation.setWrapStyleWord(true);
        explanation.setEditable(false); explanation.setOpaque(false);
        header.add(explanation, BorderLayout.NORTH);
        var correction = new JPanel(new FlowLayout(FlowLayout.LEADING));
        correction.add(new JLabel(messages.getString("region.server")));
        server = new JComboBox<>(); country = new JComboBox<>();
        country.addItem(new CountryChoice("", messages.getString("region.detect")));
        catalog.countries().keySet().stream().sorted(Comparator.comparing(code ->
            new Locale.Builder().setRegion(code).build().getDisplayCountry(environment.locale())))
            .forEach(code -> country.addItem(new CountryChoice(code,
                new Locale.Builder().setRegion(code).build().getDisplayCountry(environment.locale()) + " (" + code + ")")));
        correction.add(server); correction.add(country);
        header.add(correction, BorderLayout.SOUTH);
        server.addActionListener(event -> selectCountry());
        country.addActionListener(event -> {
            if (!loadingCountry && server.getSelectedItem() instanceof ServerChoice selected
                && country.getSelectedItem() instanceof CountryChoice selectedCountry)
                editor.region(selected.id(), selectedCountry.code());
        });
        root.add(header, BorderLayout.NORTH);
        root.add(new JScrollPane(editor), BorderLayout.CENTER);
        var footer = new JPanel(new FlowLayout(FlowLayout.LEADING));
        save = new JButton(messages.getString("save")); reload = new JButton(messages.getString("reload"));
        credential = new JButton(messages.getString("credential.create"));
        status = new JLabel();
        footer.add(save); footer.add(reload); footer.add(credential); footer.add(status);
        root.add(footer, BorderLayout.SOUTH);
        save.addActionListener(event -> {
            try {
                var settings = editor.values();
                DeploymentMirrorTasks.validate(settings, catalog);
                run(() -> service.save(settings), saved -> {
                    editor.load(saved); selectCountry(); status.setText(messages.getString("saved"));
                });
            } catch (IllegalArgumentException invalid) { status.setText(invalid.getMessage()); }
        });
        reload.addActionListener(event -> load());
        credential.addActionListener(event -> createCredential());
        load();
        return root;
    }

    private void load() {
        run(service::read, view -> {
            editor.load(view.settings());
            credentialReferences.clear(); credentialReferences.addAll(view.credentials());
            editor.credentials(credentialReferences);
            loadingCountry = true;
            try {
                server.removeAllItems();
                for (ServerInfo item : view.servers()) server.addItem(new ServerChoice(item.ref().id(), item.name()));
            } finally { loadingCountry = false; }
            selectCountry();
            status.setText(messages.getString("ready"));
            controls();
        });
    }

    private void createCredential() {
        if (busy || environment.locked() || !(server.getSelectedItem() instanceof ServerChoice selected)) return;
        var username = new JTextField(24);
        var password = new JPasswordField(24);
        var ca = new JTextArea(5, 32);
        var form = new JPanel(); form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(new JLabel(messages.getString("credential.username"))); form.add(username);
        form.add(new JLabel(messages.getString("credential.password"))); form.add(password);
        form.add(new JLabel(messages.getString("credential.ca"))); form.add(new JScrollPane(ca));
        int selectedOption = JOptionPane.showConfirmDialog(root, form, messages.getString("credential.create"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] secret = password.getPassword(); password.setText("");
        try {
            if (selectedOption != JOptionPane.OK_OPTION || busy || closed || environment.locked()) return;
            if (username.getText().contains(":")) throw new IllegalArgumentException(messages.getString("credential.invalid"));
            var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("formatVersion", 1).put("caPem", ca.getText().trim());
            if (!username.getText().isEmpty() || secret.length > 0) {
                var combined = java.nio.CharBuffer.allocate(username.getText().length() + 1 + secret.length);
                combined.put(username.getText()).put(':').put(secret).flip();
                var encoded = java.nio.charset.StandardCharsets.UTF_8.encode(combined);
                byte[] raw = new byte[encoded.remaining()]; encoded.get(raw);
                try { node.put("authorization", "Basic " + Base64.getEncoder().encodeToString(raw)); }
                finally { Arrays.fill(raw, (byte)0); Arrays.fill(combined.array(), '\0'); if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte)0); }
            }
            if (!node.has("authorization") && ca.getText().isBlank()) throw new IllegalArgumentException(messages.getString("credential.invalid"));
            byte[] owned = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(node);
            run(() -> service.credential(new gold.debug.windowstolinux.appapi.server.model.ServerId(selected.id()), owned), saved -> {
                String reference = saved.reference().identifier() + ":" + saved.reference().revision();
                credentialReferences.add(reference); editor.credentials(credentialReferences);
                status.setText(messages.getString("credential.created") + " " + reference);
            });
        } catch (Exception invalid) { status.setText(messages.getString("credential.invalid")); }
        finally { Arrays.fill(secret, '\0'); ca.setText(""); username.setText(""); }
    }

    private void selectCountry() {
        if (editor == null || server == null || !(server.getSelectedItem() instanceof ServerChoice selected)) return;
        String code = editor.regions().getOrDefault(selected.id(), "");
        loadingCountry = true;
        try {
            for (int i = 0; i < country.getItemCount(); i++) if (country.getItemAt(i).code().equals(code)) {
                country.setSelectedIndex(i); break;
            }
        } finally { loadingCountry = false; }
    }

    private <T> void run(Callable<TaskHandle<T>> submit, Consumer<T> completed) {
        if (busy || closed || environment.locked()) return;
        busy = true; controls(); status.setText(messages.getString("working"));
        new SwingWorker<TaskHandle<T>, Void>() {
            @Override protected TaskHandle<T> doInBackground() throws Exception { return submit.call(); }
            @Override protected void done() {
                try {
                    var handle = get(); pending = handle;
                    if (closed) handle.requestCancel();
                    handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                        pending = null; busy = false;
                        if (closed) return;
                        controls();
                        if (failure != null || result == null || result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()) {
                            status.setText(messages.getString("failed"));
                            return;
                        }
                        completed.accept(result.value().orElseThrow());
                    }));
                } catch (Exception unavailable) {
                    busy = false;
                    if (!closed) { controls(); status.setText(messages.getString("failed")); }
                }
            }
        }.execute();
    }

    private void controls() {
        save.setEnabled(!busy && !closed && !environment.locked());
        reload.setEnabled(!busy && !closed && !environment.locked());
        server.setEnabled(!busy && !environment.locked()); country.setEnabled(!busy && !environment.locked());
        credential.setEnabled(!busy && !closed && !environment.locked() && server.getItemCount() > 0);
    }

    @Override public void environmentChanged(UiEnvironment environment) {
        this.environment = environment;
        if (root != null) controls();
    }

    @Override public void close() throws Exception {
        closed = true;
        if (pending != null) pending.requestCancel();
    }
}
