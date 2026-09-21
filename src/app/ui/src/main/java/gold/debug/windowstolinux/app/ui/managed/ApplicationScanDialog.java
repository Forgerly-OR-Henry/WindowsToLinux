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

/**
 * Independent read-only discovery followed by explicit per-candidate adoption. / 独立只读发现窗口，随后按候选明确接管。
 */
public final class ApplicationScanDialog extends JDialog {
    /**
     * Bound managed application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的受管应用门面协作对象。
     */
    private final ManagedApplicationFacade service;
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
     * Adopted.
     * <p>已接管。
     */
    private final Consumer<String> adopted;
    /**
     * Server-profile and authenticated-session service.
     * <p>服务器资料及已认证会话服务。
     */
    private final JComboBox<ServerProfile> servers = new JComboBox<>();
    /**
     * Swing control for master.
     * <p>主对应的 Swing 控件。
     */
    private final JPasswordField master = new JPasswordField(18);
    /**
     * Swing control for candidates.
     * <p>候选集合对应的 Swing 控件。
     */
    private final JPanel candidates = new JPanel();
    /**
     * Swing control for status.
     * <p>状态对应的 Swing 控件。
     */
    private final JTextArea status = DesktopComponentFactory.outputArea();
    /**
     * Runs read-only application discovery on the selected server and replaces the displayed candidate list.
     * <p>在所选服务器执行只读应用发现，并替换显示的候选列表。
     */
    private final JButton scan;
    /**
     * Typed outcome produced by the delegated operation.
     * <p>被委派操作产生的类型化结果。
     */
    private ApplicationScan result;
    /**
     * Whether a page action is in progress and conflicting controls must remain disabled.
     * <p>页面动作是否正在进行且冲突控件须保持禁用。
     */
    private boolean busy;

    /**
     * Lists saved servers without loading their credentials. / 列出已保存服务器，不加载其凭据。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param messages localized message resolver / 本地化消息解析器
     * @param adopted adopted / 已接管
     */
    public ApplicationScanDialog(Window owner, ManagedApplicationFacade service, DesktopComponentFactory c,
                                 PageMessagePresenter messages, Consumer<String> adopted) {
        super(owner, messages.text("apps.add"), ModalityType.APPLICATION_MODAL);
        this.service = service; this.c = c; this.messages = messages; this.adopted = adopted;
        servers.setRenderer(new DefaultListCellRenderer() {
            /**
             * Returns list cell renderer component.
             * <p>返回列表Cell渲染器组件。
             *
             * @param list list / 列表
             * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
             * @param index index / 索引
             * @param selected explicitly selected item or state / 显式选择的项目或状态
             * @param focus focus / 焦点
             * @return list cell renderer component / 列表Cell渲染器组件
             */
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
            /**
             * Handles the user's close request through the owning window's cleanup path.
             * <p>通过所属窗口的清理路径处理用户关闭请求。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override public void windowClosing(WindowEvent event) { if (!busy) dispose(); }
            /**
             * Completes resource cleanup after the Swing window has closed.
             * <p>在 Swing 窗口关闭后完成资源清理。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override public void windowClosed(WindowEvent event) { master.setText(""); }
        });
        scan.setEnabled(false);
        DesktopTaskExecutor.run(service::listServerProfiles, values -> { values.forEach(servers::addItem); scan.setEnabled(!values.isEmpty()); }, failure -> status.setText(messages.safe(failure)));
    }

    /**
     * Runs read-only application discovery on the selected server and replaces the displayed candidate list.
     * <p>在所选服务器执行只读应用发现，并替换显示的候选列表。
     */
    private void scan() {
        if (busy || !(servers.getSelectedItem() instanceof ServerProfile server)) return;
        char[] unlock = master.getPassword(); setBusy(true); result = null; candidates.removeAll(); candidates.repaint();
        status.setText(messages.text("apps.scanning"));
        DesktopTaskExecutor.run(() -> {
            try { return service.scanApplications(server.id(), unlock, fingerprint -> ServerTrustPrompt.confirm(this, messages, fingerprint)); }
            finally { Arrays.fill(unlock, '\0'); }
        }, value -> { result = value; setBusy(false); render(); }, failure -> { setBusy(false); status.setText(messages.safe(failure)); });
    }

    /**
     * Rebuilds discovery result controls from the current scan and selection state.
     * <p>根据当前扫描及选择状态重建发现结果控件。
     */
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

    /**
     * Runs confirmed adoption of the selected discovery result in a desktop worker using the entered unlock password.
     * <p>使用输入的解锁密码，在桌面工作线程执行所选发现结果的确认接管。
     *
     * @param candidate candidate / 候选
     */
    private void adopt(DiscoveredApplication candidate) {
        if (busy || result == null) return;
        ApplicationScan selected = result; char[] unlock = master.getPassword(); setBusy(true);
        DesktopTaskExecutor.run(() -> {
            try { return service.adoptApplication(selected, candidate, unlock, fingerprint -> ServerTrustPrompt.confirm(this, messages, fingerprint)); }
            finally { Arrays.fill(unlock, '\0'); }
        }, key -> { setBusy(false); status.setText(messages.text("apps.adopted", Map.of("application", candidate.name()))); adopted.accept(key); },
                failure -> { setBusy(false); status.setText(messages.safe(failure)); });
    }

    /**
     * Updates whether a page action is in progress and conflicting controls must remain disabled.
     * <p>更新页面动作是否正在进行且冲突控件须保持禁用。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private void setBusy(boolean value) {
        busy = value; servers.setEnabled(!value); master.setEnabled(!value && servers.getSelectedItem() instanceof ServerProfile server && server.credentialMode() == CredentialStorageMode.MASTER_PASSWORD);
        scan.setEnabled(!value); setActions(candidates, !value);
        if (!value && result != null) render();
    }
    /**
     * Updates actions.
     * <p>更新动作集合。
     *
     * @param parent parent / 父级
     * @param enabled whether this configured capability participates in execution / 当前配置能力是否参与执行
     */
    private static void setActions(Container parent, boolean enabled) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton) child.setEnabled(enabled);
            if (child instanceof Container nested) setActions(nested, enabled);
        }
    }
}
