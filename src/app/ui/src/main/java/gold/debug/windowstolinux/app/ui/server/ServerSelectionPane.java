package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** A target card opening an independent searchable server picker. / 打开独立可搜索服务器选择窗口的目标卡片。 */
public final class ServerSelectionPane extends JPanel {
    private final ServerApplicationFacade service;
    private final PageMessagePresenter messages;
    private final DesktopComponentFactory c;
    private final Consumer<ServerProfile> selected;
    private final JButton choose;
    private final JLabel endpoint = new JLabel(), evidence = new JLabel();
    private ServerProfile profile;
    private String desiredId;
    private boolean loading;

    /** Creates a card; saved summaries load when shown. / 创建卡片，在展示时加载已保存摘要。 */
    public ServerSelectionPane(ServerApplicationFacade service, DesktopComponentFactory c,
                               PageMessagePresenter messages, Consumer<ServerProfile> selected) {
        super(new BorderLayout(0, 8)); setOpaque(false);
        this.service = service; this.messages = messages; this.c = c; this.selected = selected;
        choose = c.secondaryButton(messages.text("server.choose"));
        choose.putClientProperty("JButton.buttonType", null); choose.putClientProperty("FlatLaf.style", "arc: 16");
        choose.setIcon(DesktopIcons.icon("server", 30, choose::getForeground));
        choose.setPreferredSize(new Dimension(0, 72)); choose.addActionListener(event -> open());
        JPanel details = c.transparent(new GridLayout(0, 1, 0, 6)); details.add(endpoint); details.add(evidence);
        add(choose, BorderLayout.CENTER); add(details, BorderLayout.SOUTH);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) reload();
        });
    }

    /** Returns the saved selection or null. / 返回已保存选择，无选择返回 null。 */
    public ServerProfile profile() { return profile; }
    /** Captures selection even while its background refresh is pending. / 在后台刷新尚未完成时仍能捕获选择。 */
    public String selectedId() { return desiredId != null ? desiredId : profile == null ? "" : profile.id(); }
    /** Restores selection without overwriting unsaved server context. / 恢复选择，不覆盖未保存服务器上下文。 */
    public void select(String serverId) { desiredId = serverId; reload(); }

    /** Refreshes the dated summary in the background. / 在后台刷新带时间的摘要。 */
    public void reload() {
        if (service == null || loading) return;
        loading = true;
        DesktopTaskExecutor.run(service::listServerSummaries, values -> {
            boolean restoring = desiredId != null;
            String id = selectedId();
            ServerSummary chosen = values.stream().filter(value -> value.profile().id().equals(id)).findFirst().orElse(null);
            if (chosen == null && !restoring && id.isBlank() && !values.isEmpty()) chosen = values.getFirst();
            profile = chosen == null ? null : chosen.profile();
            choose.setText(profile == null ? messages.text("server.choose") : profile.displayName());
            endpoint.setText(profile == null ? messages.text("auto.server.hint") : profile.host() + " : " + profile.sshPort());
            evidence.setText(chosen == null ? "" : ServerInventoryPane.description(chosen, messages));
            evidence.setToolTipText(evidence.getText()); desiredId = null; loading = false;
            if (!restoring && profile != null) selected.accept(profile);
        }, failure -> { loading = false; evidence.setText(messages.safe(failure)); });
    }

    private void open() {
        if (service == null) return;
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), messages.text("server.choose"), Dialog.ModalityType.APPLICATION_MODAL);
        ServerInventoryPane inventory = new ServerInventoryPane(service, c, messages, true, value -> {
            profile = value; selected.accept(value); select(value.id()); dialog.dispose();
        });
        inventory.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        dialog.setContentPane(inventory); dialog.setSize(780, 560); dialog.setMinimumSize(new Dimension(620, 420));
        dialog.setLocationRelativeTo(this); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setVisible(true); reload();
    }
}
