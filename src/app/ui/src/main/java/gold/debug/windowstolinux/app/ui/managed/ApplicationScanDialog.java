package gold.debug.windowstolinux.app.ui.managed;

import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationScan;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.server.ServerTrustPrompt;
import gold.debug.windowstolinux.shared.model.lifecycle.DiscoveredApplication;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Independent read-only discovery followed by explicit per-candidate adoption. / 独立只读发现窗口，随后按候选明确接管。 */
public final class ApplicationScanDialog extends JDialog {
    private final ManagedApplicationFacade service;
    private final DesktopComponentFactory c;
    private final PageMessagePresenter messages;
    private final Consumer<String> adopted;
    private final JComboBox<ServerProfile> servers = new JComboBox<>();
    private final JPasswordField master = new JPasswordField(18);
    private final JPanel candidates = new JPanel();
    private final JTextArea status = DesktopComponentFactory.outputArea();
    private final JButton scan;
    private ApplicationScan result;
    private boolean busy;

    /** Lists saved servers without loading their credentials. / 列出已保存服务器，不加载其凭据。 */
    public ApplicationScanDialog(Window owner, ManagedApplicationFacade service, DesktopComponentFactory c,
                                 PageMessagePresenter messages, Consumer<String> adopted) {
        super(owner, messages.text("apps.add"), ModalityType.APPLICATION_MODAL);
        this.service = service; this.c = c; this.messages = messages; this.adopted = adopted;
        servers.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                var label = super.getListCellRendererComponent(list, value instanceof ServerProfile profile ? profile.displayName() + "  ·  " + profile.host() : "", index, selected, focus);
                ((JComponent) label).putClientProperty("html.disable", true); return label;
            }
        });
        servers.addActionListener(event -> {
            result = null; candidates.removeAll(); candidates.revalidate(); candidates.repaint();
            master.setEnabled(servers.getSelectedItem() instanceof ServerProfile profile && profile.credentialMode() == CredentialStorageMode.MASTER_PASSWORD);
        });
        JPanel controls = c.card(new BorderLayout(12, 8)); controls.add(servers); scan = c.primaryButton(messages.text("apps.scan"));
        scan.addActionListener(event -> scan()); controls.add(scan, BorderLayout.EAST);
        JPanel unlock = c.transparent(new FlowLayout(FlowLayout.LEFT)); unlock.add(new JLabel(messages.text("field.masterPassword"))); unlock.add(master); controls.add(unlock, BorderLayout.SOUTH);
        candidates.setLayout(new BoxLayout(candidates, BoxLayout.Y_AXIS)); candidates.setOpaque(false);
        JScrollPane scroll = new JScrollPane(candidates); scroll.getVerticalScrollBar().setUnitIncrement(20); scroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel body = c.pagePanel(); body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20)); body.add(controls, BorderLayout.NORTH); body.add(scroll);
        status.setRows(4); status.setLineWrap(true); status.setWrapStyleWord(true); body.add(new JScrollPane(status), BorderLayout.SOUTH); setContentPane(body);
        setSize(840, 650); setMinimumSize(new Dimension(680, 480)); setLocationRelativeTo(owner); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { if (!busy) dispose(); }
            @Override public void windowClosed(WindowEvent event) { master.setText(""); }
        });
        scan.setEnabled(false);
        DesktopTaskExecutor.run(service::listServerProfiles, values -> { values.forEach(servers::addItem); scan.setEnabled(!values.isEmpty()); }, failure -> status.setText(messages.safe(failure)));
    }

    private void scan() {
        if (busy || !(servers.getSelectedItem() instanceof ServerProfile server)) return;
        char[] unlock = master.getPassword(); setBusy(true); result = null; candidates.removeAll(); candidates.repaint();
        status.setText(messages.text("apps.scanning"));
        DesktopTaskExecutor.run(() -> {
            try { return service.scanApplications(server.id(), unlock, fingerprint -> ServerTrustPrompt.confirm(this, messages, fingerprint)); }
            finally { Arrays.fill(unlock, '\0'); }
        }, value -> { result = value; setBusy(false); render(); }, failure -> { setBusy(false); status.setText(messages.safe(failure)); });
    }

    private void render() {
        candidates.removeAll();
        for (DiscoveredApplication app : result.candidates()) {
            JPanel card = c.card(new BorderLayout(12, 8)); card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 138));
            JPanel details = c.transparent(new GridLayout(0, 1, 0, 5));
            JLabel title = new JLabel(app.name()); title.putClientProperty("html.disable", true); title.setFont(title.getFont().deriveFont(Font.BOLD)); details.add(title);
            details.add(new JLabel(app.target().kind().name() + "  ·  " + app.target().identity()));
            details.add(new JLabel(messages.text("runtime.state." + app.state().name().toLowerCase(Locale.ROOT)) + "  ·  "
                    + messages.text(result.adoptable(app) ? "apps.lifecycleOnly" : "apps.ownershipRequired")));
            details.add(new JLabel(messages.text("button.refreshStatus") + (app.canStart() ? " / " + messages.text("button.start") : "")
                    + (app.canStop() ? " / " + messages.text("button.stop") : "")
                    + (app.canStart() && app.canStop() ? " / " + messages.text("button.restart") : "")));
            card.add(details);
            JButton attach = c.secondaryButton(messages.text(result.registrations().containsKey(app.target().key()) ? "apps.attachAgain" : "apps.adopt"));
            attach.setEnabled(result.adoptable(app)); attach.addActionListener(event -> adopt(app)); card.add(attach, BorderLayout.EAST);
            candidates.add(card); candidates.add(Box.createVerticalStrut(12));
        }
        String heading = messages.text(result.candidates().isEmpty() ? "apps.scanEmpty" : "apps.scanFound", Map.of("count", result.candidates().size()));
        status.setText(heading + result.issues().stream().map(issue -> "\n" + messages.text("apps.scanIssue." + issue.name().toLowerCase(Locale.ROOT))).reduce("", String::concat));
        candidates.revalidate(); candidates.repaint();
    }

    private void adopt(DiscoveredApplication candidate) {
        if (busy || result == null) return;
        ApplicationScan selected = result; char[] unlock = master.getPassword(); setBusy(true);
        DesktopTaskExecutor.run(() -> {
            try { return service.adoptApplication(selected, candidate, unlock, fingerprint -> ServerTrustPrompt.confirm(this, messages, fingerprint)); }
            finally { Arrays.fill(unlock, '\0'); }
        }, key -> { setBusy(false); status.setText(messages.text("apps.adopted", Map.of("application", candidate.name()))); adopted.accept(key); },
                failure -> { setBusy(false); status.setText(messages.safe(failure)); });
    }

    private void setBusy(boolean value) {
        busy = value; servers.setEnabled(!value); master.setEnabled(!value && servers.getSelectedItem() instanceof ServerProfile server && server.credentialMode() == CredentialStorageMode.MASTER_PASSWORD);
        scan.setEnabled(!value); setActions(candidates, !value);
        if (!value && result != null) render();
    }
    private static void setActions(Container parent, boolean enabled) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton) child.setEnabled(enabled);
            if (child instanceof Container nested) setActions(nested, enabled);
        }
    }
}
