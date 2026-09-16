package gold.debug.windowstolinux.app.ui.managed;

import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationSummary;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationLifecycleResult;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.server.ServerContext;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Card inventory with combined filters and contract-specific lifecycle operations. / 支持组合筛选及按契约执行生命周期的卡片清单。 */
public final class ManagedPage {
    private final ManagedApplicationFacade service;
    private final ServerContext serverContext;
    private final PageMessagePresenter messages;
    private final DesktopComponentFactory c;
    private final JTextField applicationId = new JTextField();
    private final JTextArea output = DesktopComponentFactory.outputArea();
    private final JComboBox<String> type = new JComboBox<>(new String[]{"", "WEBSITE", "APP"});
    private final JComboBox<ServerChoice> server = new JComboBox<>();
    private final JPanel cards = new JPanel();
    private final JLabel status = new JLabel();
    private final JPanel panel;
    private List<ApplicationSummary> applications = List.of();
    private String desiredServer = "";
    private boolean loading, busy;

    /** Creates the application page without connecting to a server. / 创建应用页面，不连接服务器。 */
    public ManagedPage(ManagedApplicationFacade service, ServerContext serverContext, DesktopComponentFactory c, PageMessagePresenter messages) {
        this.service = service; this.serverContext = serverContext; this.c = c; this.messages = messages; panel = createPanel();
        panel.addHierarchyListener(event -> { if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing()) refresh(); });
    }
    /** Returns the card page. / 返回卡片页面。 */
    public JPanel panel() { return panel; }
    /** Captures filters and selected application across appearance changes. / 在外观变化时捕获筛选及所选应用。 */
    public ManagedPageState captureState() { return new ManagedPageState(applicationId.getText(), output.getText(), (String) type.getSelectedItem(), selectedServer()); }
    /** Restores independent filter and diagnostic state. / 恢复独立筛选及诊断状态。 */
    public void restoreState(ManagedPageState state) {
        applicationId.setText(state.applicationId()); output.setText(state.output()); desiredServer = state.serverFilter();
        loading = true;
        if (!desiredServer.isEmpty()) { server.addItem(new ServerChoice(desiredServer, desiredServer)); server.setSelectedIndex(server.getItemCount() - 1); }
        loading = false;
        type.setSelectedItem(state.typeFilter());
    }
    /** Selects a deployment handoff or newly adopted registration. / 选择部署交接或新接管登记。 */
    public void selectApplication(String key) {
        applicationId.setText(key); output.setText(messages.text("deployment.selected", Map.of("application", key))); refresh();
    }

    private JPanel createPanel() {
        JPanel page = c.pagePanel(); AdvancedOptionsPane advanced = new AdvancedOptionsPane(page, c, messages);
        JPanel toolbar = c.transparent(new BorderLayout(12, 0));
        JPanel filters = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        type.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, messages.text(value == null || value.toString().isEmpty() ? "apps.allTypes" : "apps.category." + value), index, selected, focus);
            }
        });
        type.addActionListener(event -> render()); server.addItem(new ServerChoice("", messages.text("apps.allServers")));
        server.addActionListener(event -> { if (!loading) { desiredServer = selectedServer(); render(); } });
        filters.add(type); filters.add(server); toolbar.add(filters);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton refresh = c.secondaryButton(messages.text("button.refreshApplications")); refresh.addActionListener(event -> refresh());
        JButton add = c.primaryButton(messages.text("apps.add")); add.addActionListener(event -> {
            if (service != null) new ApplicationScanDialog(SwingUtilities.getWindowAncestor(panel), service, c, messages, this::selectApplication).setVisible(true);
        });
        actions.add(refresh); actions.add(add); toolbar.add(actions, BorderLayout.EAST); page.add(toolbar, BorderLayout.NORTH);
        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS)); cards.setOpaque(false);
        JScrollPane scroll = new JScrollPane(cards); scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false); page.add(scroll); page.add(status, BorderLayout.SOUTH);
        advanced.field("field.applicationId", applicationId); output.setRows(12); advanced.addOption(new JScrollPane(output));
        for (LifecycleAction action : List.of(LifecycleAction.ENABLE_AUTOSTART, LifecycleAction.DISABLE_AUTOSTART)) {
            JButton button = c.secondaryButton(messages.text(action == LifecycleAction.ENABLE_AUTOSTART ? "button.enableAutostart" : "button.disableAutostart"));
            button.addActionListener(event -> applications.stream().filter(value -> !value.external() && matchesSelection(value)).findFirst().ifPresent(value -> execute(value, action)));
            advanced.addOption(button);
        }
        return advanced;
    }

    private void refresh() {
        if (service == null || loading || busy) return;
        loading = true; status.setText(messages.text("apps.loading"));
        DesktopTaskExecutor.run(service::listApplications, values -> {
            applications = values; String chosen = desiredServer;
            server.removeAllItems(); server.addItem(new ServerChoice("", messages.text("apps.allServers")));
            values.stream().collect(java.util.stream.Collectors.toMap(ApplicationSummary::serverId, value -> value, (first, next) -> first, java.util.TreeMap::new))
                    .values().forEach(value -> server.addItem(new ServerChoice(value.serverId(), value.serverName())));
            for (int index = 0; index < server.getItemCount(); index++) if (server.getItemAt(index).id.equals(chosen)) server.setSelectedIndex(index);
            loading = false; render();
        }, failure -> { loading = false; status.setText(messages.safe(failure)); });
    }

    private void render() {
        if (loading) return;
        cards.removeAll();
        var shown = applications.stream().filter(value -> value.matches((String) type.getSelectedItem(), selectedServer())).toList();
        for (ApplicationSummary app : shown) cards.add(card(app));
        status.setText(shown.isEmpty() ? messages.text("apps.empty") : ""); cards.revalidate(); cards.repaint();
    }

    private JPanel card(ApplicationSummary app) {
        JPanel wrapper = c.transparent(new BorderLayout()); wrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        JPanel card = c.card(new BorderLayout(0, 12)); wrapper.add(card); wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 198));
        JPanel title = c.transparent(new BorderLayout(12, 0));
        JLabel name = new JLabel(app.name()); name.putClientProperty("html.disable", true); name.setFont(name.getFont().deriveFont(Font.BOLD, 16f));
        name.setIcon(DesktopIcons.icon(app.category().equals("WEBSITE") ? "panels-top-left" : "archive", 22, name::getForeground));
        title.add(name); title.add(c.badge(messages.text("apps.category." + app.category())), BorderLayout.EAST); card.add(title, BorderLayout.NORTH);
        JPanel details = c.transparent(new GridLayout(0, 1, 0, 6));
        details.add(new JLabel(app.serverName() + "  ·  " + app.host()));
        String date = app.deployedAt().map(value -> messages.text("apps.deployedAt", Map.of("time", time(value))))
                .orElseGet(() -> app.adoptedAt().map(value -> messages.text("apps.adoptedAt", Map.of("time", time(value)))).orElse(messages.text("apps.unknownDate")));
        details.add(new JLabel(date + "  ·  " + messages.text("runtime.state." + app.lastState().name().toLowerCase(Locale.ROOT))
                + app.observedAt().map(value -> "  ·  " + messages.text("apps.observedAt", Map.of("time", time(value)))).orElse("")));
        card.add(details);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton access = c.secondaryButton(messages.text("apps.open")); access.setEnabled(app.accessUrl().isPresent());
        access.setToolTipText(app.accessUrl().map(value -> value.url().toString()).orElse(messages.text("managed.noWebEntry")));
        access.addActionListener(event -> app.accessUrl().ifPresent(value -> {
            try { Desktop.getDesktop().browse(value.url()); } catch (Exception failure) { status.setText(messages.safe(failure)); }
        })); actions.add(access);
        for (LifecycleAction action : List.of(LifecycleAction.REFRESH_STATUS, LifecycleAction.START, LifecycleAction.STOP, LifecycleAction.RESTART)) {
            JButton button = c.secondaryButton(messages.text("button." + (action == LifecycleAction.REFRESH_STATUS ? "refreshStatus" : action.name().toLowerCase(Locale.ROOT))));
            button.setEnabled(switch (action) { case START -> app.canStart(); case STOP -> app.canStop(); case RESTART -> app.canStart() && app.canStop(); default -> true; });
            button.addActionListener(event -> execute(app, action)); actions.add(button);
        }
        JButton edit = c.secondaryButton(messages.text("apps.edit")); edit.addActionListener(event -> {
            applicationId.setText(app.key()); new ApplicationPresentationDialog(SwingUtilities.getWindowAncestor(panel), service, c, messages, app, this::refresh).setVisible(true);
        }); actions.add(edit); card.add(actions, BorderLayout.SOUTH);
        card.addMouseListener(new java.awt.event.MouseAdapter() { @Override public void mouseClicked(java.awt.event.MouseEvent event) { applicationId.setText(app.key()); } });
        return wrapper;
    }

    private void execute(ApplicationSummary app, LifecycleAction action) {
        if (busy) return;
        char[] master = serverContext.masterPassword();
        if (app.needsMasterPassword() && master.length == 0) {
            JPasswordField field = new JPasswordField(24);
            if (JOptionPane.showConfirmDialog(panel, field, messages.text("field.masterPassword"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            master = field.getPassword(); field.setText("");
        }
        final char[] unlock = master; applicationId.setText(app.key()); busy = true; ((AdvancedOptionsPane) panel).setBusy(true);
        status.setText(messages.text("lifecycle.running", Map.of("action", messages.text("lifecycle.action." + action.name().toLowerCase(Locale.ROOT)))));
        DesktopTaskExecutor.run(() -> {
            try { return service.executeApplicationLifecycle(app.key(), action, unlock, serverContext::confirmFingerprint); }
            finally { java.util.Arrays.fill(unlock, '\0'); }
        }, result -> { finish(); output.setText(describe(result)); refresh(); }, failure -> { finish(); output.setText(messages.safe(failure)); status.setText(messages.safe(failure)); });
    }

    private String describe(ApplicationLifecycleResult result) {
        String summary = messages.text("runtime.state." + result.state().name().toLowerCase(Locale.ROOT)) + "  ·  " + time(result.observedAt());
        return result.managed().map(value -> summary + "\n" + messages.text(value.accepted() ? "lifecycle.accepted" : "lifecycle.rejected",
                Map.of("message", messages.catalog().text(value.message()), "observation", messages.lifecycle(value.observation().orElse(null))))
                + "\n" + messages.text("failure.operation.summary", Map.of("operationId", value.operationIdentity().toString()))
                + value.failure().map(failure -> "\n" + failure.code()).orElse("")
                + value.nonFatalFailures().stream().map(failure -> "\n" + failure.code() + " " + messages.catalog().text(failure.userMessage())).reduce("", String::concat)).orElse(summary);
    }
    private void finish() { busy = false; ((AdvancedOptionsPane) panel).setBusy(false); }
    private boolean matchesSelection(ApplicationSummary app) { return app.key().equals(applicationId.getText()) || app.key().equals("managed:" + applicationId.getText()); }
    private String selectedServer() { return server.getSelectedItem() instanceof ServerChoice choice ? choice.id : desiredServer; }
    private static String time(Instant time) { return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(time); }
    private record ServerChoice(String id, String label) { @Override public String toString() { return label; } }
}
