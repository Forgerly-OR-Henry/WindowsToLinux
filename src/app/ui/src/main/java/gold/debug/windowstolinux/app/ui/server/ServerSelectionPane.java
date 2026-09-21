package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * A target card opening an independent searchable server picker. / 打开独立可搜索服务器选择窗口的目标卡片。
 */
public final class ServerSelectionPane extends JPanel {
    /**
     * Bound server application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的服务器应用门面协作对象。
     */
    private final ServerApplicationFacade service;
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;
    /**
     * The themed desktop component factory.
     * <p>主题化桌面组件工厂。
     */
    private final DesktopComponentFactory c;
    /**
     * Explicitly selected item or state.
     * <p>显式选择的项目或状态。
     */
    private final Consumer<ServerProfile> selected;
    /**
     * Swing control for choose.
     * <p>选择对应的 Swing 控件。
     */
    private final JButton choose;
    /**
     * Swing control for clear.
     * <p>清空对应的 Swing 控件。
     */
    private final JButton clear;
    /**
     * Swing control for endpoint.
     * <p>端点对应的 Swing 控件。
     */
    private final JLabel endpoint = new JLabel();
    /**
     * Connection or provider settings supplied to the operation.
     * <p>提供给操作的连接或提供者设置。
     */
    private ServerProfile profile;
    /**
     * Desired id.
     * <p>期望标识。
     */
    private String desiredId;
    /**
     * Whether saved choices are being loaded and selection callbacks must be deferred.
     * <p>是否正在加载已保存选项且须延后选择回调。
     */
    private boolean loading;

    /**
     * Creates a card; saved summaries load when shown. / 创建卡片，在展示时加载已保存摘要。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param messages localized message resolver / 本地化消息解析器
     * @param selected explicitly selected item or state / 显式选择的项目或状态
     */
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

    /**
     * Returns the saved selection or null. / 返回已保存选择，无选择返回 null。
     *
     * @return the saved selection or null / 已保存选择，无选择返回 null
     */
    public ServerProfile profile() { return profile; }
    /**
     * Captures selection even while its background refresh is pending. / 在后台刷新尚未完成时仍能捕获选择。
     *
     * @return selected id text / 已选标识文本
     */
    public String selectedId() { return desiredId != null ? desiredId : profile == null ? "" : profile.id(); }
    /**
     * Restores selection without overwriting unsaved server context. / 恢复选择，不覆盖未保存服务器上下文。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     */
    public void select(String serverId) {
        desiredId = serverId; choose.setEnabled(serverId.isBlank()); clear.setEnabled(!serverId.isBlank()); reload();
    }

    /**
     * Refreshes the dated summary in the background. / 在后台刷新带时间的摘要。
     */
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

    /**
     * Opens the shared server picker, also used with isolated preview data. / 打开共用服务器选择器，也支持隔离预览数据。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     */
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

    /**
     * Refreshes server selection pane.
     * <p>刷新服务器选择面板。
     *
     * @param summary summary / 摘要
     */
    private void refresh(ServerSummary summary) {
        choose.setText(profile == null ? messages.text("server.choose") : profile.displayName());
        choose.setEnabled(profile == null); clear.setEnabled(profile != null);
        endpoint.setText(profile == null ? "" : profile.host() + " : " + profile.sshPort());
        String description = summary == null ? null : ServerInventoryPane.description(summary, messages);
        choose.setToolTipText(description); endpoint.setToolTipText(description);
        revalidate(); repaint();
    }
}
