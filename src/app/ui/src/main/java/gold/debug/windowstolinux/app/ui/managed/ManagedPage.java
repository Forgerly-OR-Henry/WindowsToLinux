package gold.debug.windowstolinux.app.ui.managed;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
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

/**
 * Card inventory with combined filters and contract-specific lifecycle operations. / 支持组合筛选及按契约执行生命周期的卡片清单。
 */
public final class ManagedPage {
    /**
     * Bound managed application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的受管应用门面协作对象。
     */
    private final ManagedApplicationFacade service;
    /**
     * Server context.
     * <p>服务器上下文。
     */
    private final ServerContext serverContext;
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
     * Swing control for application id.
     * <p>应用标识对应的 Swing 控件。
     */
    private final JTextField applicationId = new JTextField();
    /**
     * Swing control for output.
     * <p>输出对应的 Swing 控件。
     */
    private final JTextArea output = DesktopComponentFactory.outputArea();
    /**
     * Selected member of the supported type set.
     * <p>受支持类型集合中的所选项。
     */
    private final JComboBox<String> type = new JComboBox<>(new String[]{"", "WEBSITE", "APP"});
    /**
     * Server identity or selected server configuration.
     * <p>服务器身份或所选服务器配置。
     */
    private final JComboBox<ServerChoice> server = new JComboBox<>();
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
     * Swing control for panel.
     * <p>面板对应的 Swing 控件。
     */
    private final JPanel panel;
    /**
     * Applications.
     * <p>应用集合。
     */
    private List<ApplicationSummary> applications = List.of();
    /**
     * Desired server.
     * <p>期望服务器。
     */
    private String desiredServer = "";
    /**
     * Whether saved choices are being loaded and selection callbacks must be deferred.
     * <p>是否正在加载已保存选项且须延后选择回调。
     * <p>busy:
     * Whether a page action is in progress and conflicting controls must remain disabled.
     * <p>页面动作是否正在进行且冲突控件须保持禁用。
     */
    private boolean loading, busy;

    /**
     * Creates the application page without connecting to a server. / 创建应用页面，不连接服务器。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param serverContext server context / 服务器上下文
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param messages localized message resolver / 本地化消息解析器
     */
    public ManagedPage(ManagedApplicationFacade service, ServerContext serverContext, DesktopComponentFactory c, PageMessagePresenter messages) {
        this.service = service; this.serverContext = serverContext; this.c = c; this.messages = messages; panel = createPanel();
        panel.addHierarchyListener(event -> { if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing()) refresh(); });
    }
    /**
     * Returns the card page. / 返回卡片页面。
     *
     * @return the card page / 卡片页面
     */
    public JPanel panel() { return panel; }
    /**
     * Captures filters and selected application across appearance changes. / 在外观变化时捕获筛选及所选应用。
     *
     * @return constructed or resolved managed page state / 构造或解析得到的受管页面状态
     */
    public ManagedPageState captureState() { return new ManagedPageState(applicationId.getText(), output.getText(), (String) type.getSelectedItem(), selectedServer()); }
    /**
     * Restores independent filter and diagnostic state. / 恢复独立筛选及诊断状态。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    public void restoreState(ManagedPageState state) {
        applicationId.setText(state.applicationId()); output.setText(state.output()); desiredServer = state.serverFilter();
        loading = true;
        if (!desiredServer.isEmpty()) { server.addItem(new ServerChoice(desiredServer, desiredServer)); server.setSelectedIndex(server.getItemCount() - 1); }
        loading = false;
        type.setSelectedItem(state.typeFilter());
    }
    /**
     * Selects a deployment handoff or newly adopted registration. / 选择部署交接或新接管登记。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     */
    public void selectApplication(String key) {
        applicationId.setText(key); output.setText(messages.text("deployment.selected", Map.of("application", key))); refresh();
    }

    /**
     * Builds the managed-application inventory with filtering, refresh and lifecycle controls.
     * <p>构建受管应用清单，包含筛选、刷新及生命周期控件。
     *
     * @return the managed-application inventory with filtering, refresh and lifecycle controls / 受管应用清单，包含筛选、刷新及生命周期控件
     */
    private JPanel createPanel() {
        JPanel page = c.pagePanel(); AdvancedOptionsPane advanced = new AdvancedOptionsPane(page, c, messages);
        JPanel toolbar = c.transparent(new BorderLayout(12, 0));
        JPanel filters = c.transparent(new GridBagLayout());
        type.setRenderer(new DefaultListCellRenderer() {
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
                return super.getListCellRendererComponent(list, messages.text(value == null || value.toString().isEmpty() ? "apps.allTypes" : "apps.category." + value), index, selected, focus);
            }
        });
        type.addActionListener(event -> render()); server.addItem(new ServerChoice("", messages.text("apps.allServers")));
        server.addActionListener(event -> { if (!loading) { desiredServer = selectedServer(); render(); } });
        GridBagConstraints filterLayout = new GridBagConstraints();
        filterLayout.gridy = 0; filterLayout.weighty = 1; filterLayout.fill = GridBagConstraints.VERTICAL;
        filterLayout.insets = new Insets(0, 0, 0, 8);
        filters.add(type, filterLayout); filters.add(server, filterLayout);
        filterLayout.weightx = 1; filters.add(Box.createHorizontalGlue(), filterLayout); toolbar.add(filters);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton refresh = c.secondaryButton(messages.text("button.refreshApplications")); refresh.addActionListener(event -> refresh());
        JButton add = c.primaryButton(messages.text("apps.add")); add.addActionListener(event -> {
            if (service != null) new ApplicationScanDialog(SwingUtilities.getWindowAncestor(panel), service, c, messages, this::selectApplication).setVisible(true);
        });
        for (JComponent control : new JComponent[]{refresh, add})
            control.putClientProperty(FlatClientProperties.MINIMUM_HEIGHT, 36);
        actions.add(refresh); actions.add(add); toolbar.add(actions, BorderLayout.EAST); page.add(toolbar, BorderLayout.NORTH);
        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS)); cards.setOpaque(false);
        JScrollPane scroll = new JScrollPane(cards); scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false); page.add(scroll); page.add(status, BorderLayout.SOUTH);
        advanced.field("field.applicationId", applicationId); output.setRows(12); advanced.addOption(new JScrollPane(output));
        return advanced;
    }

    /**
     * Loads current application cards asynchronously while preserving the page's selection and busy state.
     * <p>异步加载当前应用卡片，并保留页面选择及忙碌状态。
     */
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

    /**
     * Renders managed page.
     * <p>渲染受管页面。
     */
    private void render() {
        if (loading) return;
        cards.removeAll();
        var shown = applications.stream().filter(value -> value.matches((String) type.getSelectedItem(), selectedServer())).toList();
        for (ApplicationSummary app : shown) cards.add(card(app));
        status.setText(shown.isEmpty() ? messages.text("apps.empty") : ""); cards.revalidate(); cards.repaint();
    }

    /**
     * Builds a managed-application card containing its description, state and available lifecycle actions.
     * <p>构建受管应用卡片，包含说明、状态及可用生命周期动作。
     *
     * @param app app / 应用
     * @return a managed-application card containing its description, state and available lifecycle actions / 受管应用卡片，包含说明、状态及可用生命周期动作
     */
    private JPanel card(ApplicationSummary app) {
        JPanel wrapper = c.transparent(new BorderLayout()); wrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        JPanel card = c.card(new BorderLayout(0, 12)); wrapper.add(card);
        card.setBorder(BorderFactory.createEmptyBorder(20, 18, 20, 18));
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(300)));
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
        })); if (app.accessUrl().isPresent()) actions.add(access);
        addUsage(app, details, actions);
        for (LifecycleAction action : List.of(LifecycleAction.REFRESH_STATUS, LifecycleAction.START, LifecycleAction.STOP, LifecycleAction.RESTART, LifecycleAction.ENABLE_AUTOSTART, LifecycleAction.DISABLE_AUTOSTART)) {
            if (action != LifecycleAction.REFRESH_STATUS && app.usage().map(usage -> !usage.lifecycle()).orElse(false)) continue;
            if (app.external() && (action == LifecycleAction.ENABLE_AUTOSTART || action == LifecycleAction.DISABLE_AUTOSTART)) continue;
            String label = switch (action) { case REFRESH_STATUS -> "refreshStatus"; case ENABLE_AUTOSTART -> "enableAutostart";
                case DISABLE_AUTOSTART -> "disableAutostart"; default -> action.name().toLowerCase(Locale.ROOT); };
            JButton button = c.secondaryButton(messages.text("button." + label));
            button.setEnabled(switch (action) { case START -> app.canStart(); case STOP -> app.canStop(); case RESTART -> app.canStart() && app.canStop(); default -> true; });
            button.addActionListener(event -> execute(app, action)); actions.add(button);
        }
        JButton edit = c.secondaryButton(messages.text("apps.edit")); edit.addActionListener(event -> {
            applicationId.setText(app.key()); new ApplicationPresentationDialog(SwingUtilities.getWindowAncestor(panel), service, c, messages, app, this::refresh).setVisible(true);
        }); actions.add(edit); card.add(actions, BorderLayout.SOUTH);
        for (Component control : actions.getComponents())
            ((JButton) control).putClientProperty(FlatClientProperties.MINIMUM_HEIGHT, 36);
        card.addMouseListener(new java.awt.event.MouseAdapter() {
        /**
         * Handles the mouse click on the associated desktop control.
         * <p>处理关联桌面控件上的鼠标点击。
         *
         * @param event state or UI event being processed / 正在处理的状态或 UI 事件
         */
         @Override public void mouseClicked(java.awt.event.MouseEvent event) { applicationId.setText(app.key()); } });
        return wrapper;
    }

    /**
     * Adds reviewed access and application-command information, indicating when reanalysis is required.
     * <p>添加已审阅访问及应用命令信息，并指示何时需要重新分析。
     *
     * @param app app / 应用
     * @param details details / 详情
     * @param actions actions / 动作集合
     */
    private void addUsage(ApplicationSummary app, JPanel details, JPanel actions) {
        app.usage().ifPresent(usage -> {
            if (!usage.reviewed()) details.add(new JLabel(messages.text("apps.reanalysisRequired")));
            for (String endpoint : usage.endpoints()) details.add(new JLabel(endpoint));
            if (usage.category().equals("APP") && !usage.command().isBlank()) {
                JButton command = c.secondaryButton(messages.text("apps.command"));
                command.addActionListener(event -> {
                    JTextArea text = new JTextArea(usage.command(), 3, 60); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
                    int result = JOptionPane.showOptionDialog(panel, new JScrollPane(text), messages.text("apps.command"),
                            JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                            new String[]{messages.text("apps.copyCommand"), messages.text("button.close")}, null);
                    if (result == 0) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(usage.command()), null);
                });
                actions.add(command);
            }
        });
    }

    /**
     * Submits the selected lifecycle action through the service and updates the card with fresh observation evidence.
     * <p>通过服务提交所选生命周期动作，并使用新观测证据更新卡片。
     *
     * @param app app / 应用
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     */
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

    /**
     * Formats observed runtime state, observation time and the managed action's acceptance result.
     * <p>格式化观测运行状态、观测时间及受管动作的准入结果。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @return observed runtime state, observation time and the managed action's acceptance result / 观测运行状态、观测时间及受管动作的准入结果
     */
    private String describe(ApplicationLifecycleResult result) {
        String summary = messages.text("runtime.state." + result.state().name().toLowerCase(Locale.ROOT)) + "  ·  " + time(result.observedAt());
        return result.managed().map(value -> summary + "\n" + messages.text(value.accepted() ? "lifecycle.accepted" : "lifecycle.rejected",
                Map.of("message", messages.catalog().text(value.message()), "observation", messages.lifecycle(value.observation().orElse(null))))
                + "\n" + messages.text("failure.operation.summary", Map.of("operationId", value.operationIdentity().toString()))
                + value.failure().map(failure -> "\n" + failure.code()).orElse("")
                + value.nonFatalFailures().stream().map(failure -> "\n" + failure.code() + " " + messages.catalog().text(failure.userMessage())).reduce("", String::concat)).orElse(summary);
    }
    /**
     * Finishes managed page.
     * <p>完成受管页面。
     */
    private void finish() { busy = false; ((AdvancedOptionsPane) panel).setBusy(false); }
    /**
     * Reports whether the selection condition holds for this contract.
     * <p>判断当前契约是否满足选择条件。
     *
     * @param app app / 应用
     * @return true when selection condition holds for this contract, false otherwise / 当前契约是否满足选择条件时为 true，否则为 false
     */
    private boolean matchesSelection(ApplicationSummary app) { return app.key().equals(applicationId.getText()) || app.key().equals("managed:" + applicationId.getText()); }
    /**
     * Returns selected server.
     * <p>返回已选服务器。
     *
     * @return selected server / 已选服务器
     */
    private String selectedServer() { return server.getSelectedItem() instanceof ServerChoice choice ? choice.id : desiredServer; }
    /**
     * Formats an instant to minute precision in the system's default time zone.
     * <p>按系统默认时区将时刻格式化到分钟精度。
     *
     * @param time time / 时间
     * @return an instant to minute precision in the system's default time zone / 按系统默认时区将时刻格式化到分钟精度
     */
    private static String time(Instant time) { return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(time); }
    /**
     * Pairs the server identity with its label for managed-application selection.
     * <p>将服务器身份与受管应用选择所用标签配对。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param label label / 标签
     */
    private record ServerChoice(String id, String label) {
    /**
     * Returns label.
     * <p>返回标签。
     *
     * @return label / 标签
     */
     @Override public String toString() { return label; } }
}
