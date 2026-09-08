package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/** Saved server selection with an in-place, secret-clearing add dialog. */
public final class ServerSelectionPane extends JPanel {
    private final JComboBox<ServerProfile> servers = new JComboBox<>();
    private final ServerApplicationFacade service;
    private final PageMessagePresenter messages;
    private final DesktopComponentFactory components;
    private final Consumer<ServerProfile> selected;
    private String desiredId;
    private boolean loading;

    /** Creates a non-secret selector. Loading starts when it is first shown. */
    public ServerSelectionPane(ServerApplicationFacade service, DesktopComponentFactory components,
                               PageMessagePresenter messages, Consumer<ServerProfile> selected) {
        super(new BorderLayout(6, 12));
        this.service = service; this.messages = messages; this.components = components; this.selected = selected;
        setOpaque(false);
        servers.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selection, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof ServerProfile p
                        ? p.id() + "  (" + p.username() + "@" + p.host() + ")" : "", index, selection, focus);
            }
        });
        servers.addActionListener(event -> { if (!loading && profile() != null) selected.accept(profile()); });
        add(servers, BorderLayout.NORTH);
        JPanel actions = components.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton create = components.secondaryButton(messages.text("auto.addServer"));
        create.addActionListener(event -> addServer());
        JButton refresh = components.secondaryButton(messages.text("auto.refreshServers"));
        refresh.addActionListener(event -> reload());
        actions.add(create); actions.add(refresh); add(actions, BorderLayout.CENTER);
        addHierarchyListener(event -> { if (isShowing() && servers.getItemCount() == 0 && service != null) reload(); });
    }

    /** Returns the selected saved profile or null. */
    public ServerProfile profile() { return (ServerProfile) servers.getSelectedItem(); }

    /** Remembers the selection across a theme or language change. */
    public void select(String serverId) { desiredId = serverId; if (service != null) reload(); }

    /** Refreshes the saved inventory on a background thread. */
    public void reload() {
        if (service == null || loading) return;
        loading = true;
        String id = profile() == null ? "" : profile().id();
        DesktopTaskExecutor.run(service::listServerProfiles, values -> {
            boolean restored = desiredId != null;
            String chosen = restored ? desiredId : id;
            servers.removeAllItems(); values.forEach(servers::addItem);
            if (restored || !chosen.isBlank()) servers.setSelectedIndex(-1);
            values.stream().filter(value -> value.id().equals(chosen)).findFirst().ifPresent(servers::setSelectedItem);
            desiredId = null; loading = false;
            if (!restored && profile() != null) selected.accept(profile());
        }, failure -> { loading = false; JOptionPane.showMessageDialog(this, messages.safe(failure)); });
    }

    private void addServer() {
        JTextField host = new JTextField(22), username = new JTextField(22), port = new JTextField("22");
        JTextField id = new JTextField("server-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        JPasswordField password = new JPasswordField(22), master = new JPasswordField(22);
        JComboBox<CredentialStorageMode> storage = new JComboBox<>(CredentialStorageMode.values());
        storage.setSelectedItem(CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER);
        master.setEnabled(false);
        storage.addActionListener(event -> master.setEnabled(storage.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        messages.localize(storage, "credential.mode.");
        JPanel form = components.transparent(new GridBagLayout());
        components.addField(form, 0, 0, messages.text("field.host"), host);
        components.addField(form, 1, 0, messages.text("field.sshUser"), username);
        components.addField(form, 2, 0, messages.text("field.sshPassword"), password);
        AdvancedOptionsPane pane = new AdvancedOptionsPane(form, components, messages);
        pane.field("field.serverId", id); pane.field("field.sshPort", port);
        pane.field("field.credentialStorage", storage); pane.field("field.masterPassword", master);
        try {
            if (JOptionPane.showConfirmDialog(this, pane, messages.text("auto.addServer"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            CredentialStorageMode mode = (CredentialStorageMode) storage.getSelectedItem();
            ServerProfile profile = new ServerProfile(id.getText().trim(), host.getText().trim(),
                    Integer.parseInt(port.getText().trim()), username.getText().trim(),
                    "ssh/" + id.getText().trim() + "/password", mode);
            char[] secret = password.getPassword(), unlock = master.getPassword();
            DesktopTaskExecutor.run(() -> {
                try {
                    if (service.findServerProfile(profile.id()).isPresent()) throw new IllegalArgumentException("server identifier already exists");
                    service.saveServerProfile(profile, mode, unlock, secret); return profile;
                } finally { Arrays.fill(secret, '\0'); Arrays.fill(unlock, '\0'); }
            }, saved -> { selected.accept(saved); select(saved.id()); }, failure -> JOptionPane.showMessageDialog(this,
                    messages.text("auto.invalid", Map.of("detail", messages.safe(failure)))));
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(this, messages.text("auto.invalid", Map.of("detail", messages.safe(failure))));
        } finally { password.setText(""); master.setText(""); }
    }
}
