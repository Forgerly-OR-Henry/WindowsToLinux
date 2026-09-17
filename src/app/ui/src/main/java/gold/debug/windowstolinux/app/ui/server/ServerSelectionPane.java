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
    private final JButton clear;
    private final JLabel endpoint = new JLabel();
    private ServerProfile profile;
    private String desiredId;
    private boolean loading;

    /** Creates a card; saved summaries load when shown. / 创建卡片，在展示时加载已保存摘要。 */
    public ServerSelectionPane(ServerApplicationFacade service, DesktopComponentFactory c,
                               PageMessagePresenter messages, Consumer<ServerProfile> selected) {
        super(new BorderLayout(0, 8)); setOpaque(false);
        this.service = service; this.messages = messages; this.c = c; this.selected = selected;
        choose = c.secondaryButton(messages.text("server.choose"));
        choose.setIcon(DesktopIcons.icon("server", 30, choose::getForeground));
        choose.setDisabledIcon(choose.getIcon());
        choose.setHorizontalTextPosition(SwingConstants.CENTER); choose.setVerticalTextPosition(SwingConstants.BOTTOM);
        choose.setIconTextGap(10);
        choose.setPreferredSize(new Dimension(0, com.formdev.flatlaf.util.UIScale.scale(88)));
        choose.addActionListener(event -> open(SwingUtilities.getWindowAncestor(this)));
        endpoint.setPreferredSize(new Dimension(0, endpoint.getFontMetrics(endpoint.getFont()).getHeight()));
        clear = c.secondaryButton(messages.text("server.clear")); clear.setEnabled(false);
        clear.addActionListener(event -> {
            profile = null; desiredId = ""; refresh(null);
        });
        JPanel footer = c.transparent(new BorderLayout(8, 0)); footer.add(endpoint);
        footer.add(clear, BorderLayout.EAST);
        add(choose, BorderLayout.CENTER); add(footer, BorderLayout.SOUTH);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) reload();
        });
    }

    /** Returns the saved selection or null. / 返回已保存选择，无选择返回 null。 */
    public ServerProfile profile() { return profile; }
    /** Captures selection even while its background refresh is pending. / 在后台刷新尚未完成时仍能捕获选择。 */
    public String selectedId() { return desiredId != null ? desiredId : profile == null ? "" : profile.id(); }
    /** Restores selection without overwriting unsaved server context. / 恢复选择，不覆盖未保存服务器上下文。 */
    public void select(String serverId) {
        desiredId = serverId; choose.setEnabled(serverId.isBlank()); clear.setEnabled(!serverId.isBlank()); reload();
    }

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
            desiredId = profile == null ? "" : null; loading = false; refresh(chosen);
            if (!restoring && profile != null) selected.accept(profile);
        }, failure -> { loading = false; endpoint.setText(messages.safe(failure)); });
    }

    /** Opens the shared server picker, also used with isolated preview data. / 打开共用服务器选择器，也支持隔离预览数据。 */
    public void open(Window owner) {
        if (service == null || profile != null || !selectedId().isBlank()) return;
        JDialog dialog = new JDialog(owner, messages.text("server.choose"), Dialog.ModalityType.APPLICATION_MODAL);
        ServerInventoryPane inventory = new ServerInventoryPane(service, c, messages, true, value -> {
            profile = value; refresh(null); selected.accept(value); select(value.id()); dialog.dispose();
        });
        inventory.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        dialog.setContentPane(inventory); dialog.setSize(780, 560); dialog.setMinimumSize(new Dimension(620, 420));
        dialog.setLocationRelativeTo(owner); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setVisible(true); reload();
    }

    private void refresh(ServerSummary summary) {
        choose.setText(profile == null ? messages.text("server.choose") : profile.displayName());
        choose.setEnabled(profile == null); clear.setEnabled(profile != null);
        endpoint.setText(profile == null ? "" : profile.host() + " : " + profile.sshPort());
        String description = summary == null ? null : ServerInventoryPane.description(summary, messages);
        choose.setToolTipText(description); endpoint.setToolTipText(description);
        revalidate(); repaint();
    }
}
