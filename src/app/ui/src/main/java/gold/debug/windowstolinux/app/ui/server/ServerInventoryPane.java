package gold.debug.windowstolinux.app.ui.server;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Searchable card inventory shared by the page and target picker. / 服务器页面和目标选择窗口共用的可搜索卡片列表。
 */
public final class ServerInventoryPane extends JPanel {
    /**
     * Bound server application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的服务器应用门面协作对象。
     */
    private final ServerApplicationFacade service;
    /**
     * The themed desktop component factory.
     * <p>主题化桌面组件工厂。
     */
    private final DesktopComponentFactory c;
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;
    /**
     * Explicitly selected item or state.
     * <p>显式选择的项目或状态。
     */
    private final Consumer<ServerProfile> selected;
    /**
     * Picker.
     * <p>选择器。
     */
    private final boolean picker;
    /**
     * Swing control for search.
     * <p>搜索对应的 Swing 控件。
     */
    private final JTextField search = new JTextField();
    /**
     * Swing control for cards.
     * <p>卡片集合对应的 Swing 控件。
     */
    private final JPanel cards = new JPanel();
    /**
     * Swing control for status.
     * <p>状态对应的 Swing 控件。
     */
    private final JLabel status = new JLabel();
    /**
     * Ordered contents supplied to the current conversion or validation.
     * <p>提供给当前转换或校验的有序内容。
     */
    private List<ServerSummary> values = List.of();
    /**
     * Whether saved choices are being loaded and selection callbacks must be deferred.
     * <p>是否正在加载已保存选项且须延后选择回调。
     */
    private boolean loading;
    /**
     * Checking.
     * <p>检查中。
     */
    private final java.util.Set<String> checking = new java.util.HashSet<>();

    /**
     * Builds an inventory with either selection or management actions. / 创建带选择或管理操作的列表。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param messages localized message resolver / 本地化消息解析器
     * @param picker picker / 选择器
     * @param selected explicitly selected item or state / 显式选择的项目或状态
     */
    public ServerInventoryPane(ServerApplicationFacade service, DesktopComponentFactory c, PageMessagePresenter messages,
                               boolean picker, Consumer<ServerProfile> selected) {
        super(new BorderLayout(0, 16)); setOpaque(false);
        this.service = service; this.c = c; this.messages = messages; this.picker = picker; this.selected = selected;
        JPanel toolbar = c.transparent(new BorderLayout(12, 0));
        search.putClientProperty("JTextField.placeholderText", messages.text("server.search"));
        search.getAccessibleContext().setAccessibleName(messages.text("server.search")); toolbar.add(search);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton add = c.primaryButton(messages.text("auto.addServer")); add.addActionListener(event -> edit(null));
        JButton refresh = c.secondaryButton(messages.text("auto.refreshServers")); refresh.addActionListener(event -> reload());
        for (JComponent control : new JComponent[]{search, refresh, add})
            control.putClientProperty(FlatClientProperties.MINIMUM_HEIGHT, 36);
        actions.add(refresh); actions.add(add); toolbar.add(actions, BorderLayout.EAST); add(toolbar, BorderLayout.NORTH);
        cards.setOpaque(false); cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(cards); scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false); scroll.getVerticalScrollBar().setUnitIncrement(20); add(scroll); add(status, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            /**
             * Inserts update.
             * <p>插入更新。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            public void insertUpdate(DocumentEvent event) { render(); }
            /**
             * Removes update.
             * <p>移除更新。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            public void removeUpdate(DocumentEvent event) { render(); }
            /**
             * Responds to a document change by updating the associated form state.
             * <p>响应文档变更并更新关联表单状态。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            public void changedUpdate(DocumentEvent event) { render(); }
        });
        addHierarchyListener(event -> { if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) reload(); });
    }

    /**
     * Refreshes summaries off the EDT. / 在后台刷新摘要。
     */
    public void reload() {
        if (service == null || loading) return;
        loading = true; status.setText(messages.text("server.loading"));
        DesktopTaskExecutor.run(service::listServerSummaries, result -> { values = result; loading = false; render(); },
                failure -> { loading = false; status.setText(messages.safe(failure)); });
    }

    /**
     * Restores a non-secret search query. / 恢复不含秘密的搜索条件。
     *
     * @param query query / 查询
     */
    public void search(String query) { search.setText(query); }
    /**
     * Returns the current search query. / 返回当前搜索条件。
     *
     * @return the current search query / 当前搜索条件
     */
    public String search() { return search.getText(); }

    /**
     * Rebuilds saved server cards and their connection-check controls.
     * <p>重建已保存服务器卡片及其连接检查控件。
     */
    private void render() {
        cards.removeAll();
        var matches = values.stream().filter(value -> value.matches(search.getText())).toList();
        for (ServerSummary value : matches) {
            JPanel card = c.card(new BorderLayout(16, 10));
            card.setBorder(BorderFactory.createEmptyBorder(20, 18, 20, 18));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(160)));
            JPanel details = c.transparent(new GridLayout(0, 1, 0, 6));
            JLabel title = new JLabel(value.profile().displayName()); title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
            title.setIcon(DesktopIcons.icon("server", 22, title::getForeground)); details.add(title);
            details.add(new JLabel(value.profile().host() + " : " + value.profile().sshPort() + "  ·  " + value.profile().username()));
            details.add(new JLabel(checking.contains(value.profile().id()) ? messages.text("server.checking") : description(value, messages))); card.add(details);
            JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            JButton action = c.secondaryButton(messages.text(picker ? "server.select" : "button.verifyServer"));
            action.setEnabled(picker || !checking.contains(value.profile().id()));
            action.addActionListener(event -> { if (picker) selected.accept(value.profile()); else check(value, action); });
            JButton edit = c.secondaryButton(messages.text("server.edit")); edit.addActionListener(event -> edit(value.profile()));
            for (JButton button : new JButton[]{action, edit})
                button.putClientProperty(FlatClientProperties.MINIMUM_HEIGHT, 36);
            actions.add(action); actions.add(edit); card.add(actions, BorderLayout.EAST);
            cards.add(card); cards.add(Box.createVerticalStrut(12));
        }
        status.setText(matches.isEmpty() ? messages.text("server.empty") : ""); cards.revalidate(); cards.repaint();
    }

    /**
     * Opens the saved server editor and refreshes the inventory after editing.
     * <p>打开已保存服务器编辑器，并在编辑后刷新清单。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     */
    private void edit(ServerProfile profile) {
        new ServerProfileDialog(SwingUtilities.getWindowAncestor(this), service, c, messages, profile,
                saved -> { selected.accept(saved); reload(); }).setVisible(true);
        reload();
    }

    /**
     * Starts a bounded connection check for one server and updates its displayed observation.
     * <p>为一台服务器启动有界连接检查，并更新其显示观测。
     *
     * @param summary summary / 摘要
     * @param button button / 按钮
     */
    private void check(ServerSummary summary, JButton button) {
        var profile = summary.profile();
        if (checking.contains(profile.id())) return;
        JPasswordField password = new JPasswordField(24);
        if (profile.credentialMode() == gold.debug.windowstolinux.shared.model.security.CredentialStorageMode.MASTER_PASSWORD
                && JOptionPane.showConfirmDialog(this, password, messages.text("field.masterPassword"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        char[] master = password.getPassword(); password.setText("");
        checking.add(profile.id());
        button.setEnabled(false); button.setText(messages.text("server.checking"));
        DesktopTaskExecutor.run(() -> {
            try { return service.verifyServerRecovering(profile, profile.credentialMode(), master,
                    fingerprint -> ServerTrustPrompt.confirm(this, messages, fingerprint),
                    new gold.debug.windowstolinux.app.ui.recovery.RecoveryInteractionPresenter(this,
                            service instanceof gold.debug.windowstolinux.app.service.contract.AiApplicationFacade ai ? ai : null, c, messages)); }
            finally { java.util.Arrays.fill(master, '\0'); }
        }, result -> { checking.remove(profile.id()); selected.accept(profile); reload(); }, failure -> {
            checking.remove(profile.id()); reload(); JOptionPane.showMessageDialog(this, messages.text("server.verifyFailed", java.util.Map.of("detail", messages.safe(failure))));
        });
    }

    /**
     * Formats recent connectivity without equating failure with shutdown. / 显示最近连接结果，不把失败等同于关机。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param messages localized message resolver / 本地化消息解析器
     * @return recent connectivity without equating failure with shutdown / 显示最近连接结果，不把失败等同于关机
     */
    public static String description(ServerSummary value, PageMessagePresenter messages) {
        String state = messages.text(value.checkedAt().isEmpty() ? "server.unchecked" : value.connected() ? "server.connected" : "server.failed");
        String time = value.checkedAt().map(date -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(date)).orElse("");
        return state + (time.isEmpty() ? "" : "  ·  " + time) + (value.operatingSystem().isBlank() ? "" : "  ·  " + value.operatingSystem());
    }
}
