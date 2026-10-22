package gold.debug.windowstolinux.app.ui.server;

import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.server.ServerSummary;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.ui.extension.PlatformUiExtensions;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionService;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionPointRegistration;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionMount;

/** Owns server-page layout and dated metadata without submitting operations. / 管理服务器页面布局与带时间元数据，不提交业务操作。 */
final class ServerPagePresentation extends JPanel {
    /** Original controls whose listeners remain owned by the page controller. / 监听器仍由页面控制器持有的原控件。
     * @param add add configuration / 添加配置
     * @param edit edit selected configuration / 编辑所选配置
     * @param delete confirmed deletion / 已确认删除
     * @param refresh refresh inventory / 刷新清单
     * @param check authentication check / 认证检查
     * @param rescue selected-target rescue / 所选目标救援
     * @param more additional actions / 更多操作
     * @param cancel cancel original task / 取消原任务
     * @param reconcile reconcile original task / 核对原任务
     * @param failedRescue captured-target rescue / 捕获目标救援 */
    record Controls(JButton add, JButton edit, JButton delete, JButton refresh, JButton check, JButton rescue,
                    JButton more, JButton cancel, JButton reconcile, JButton failedRescue) {
    }

    /** Current visual factory and localized captions. / 当前视觉工厂与本地化文案。 */
    private final DesktopPartFactory components;

    /** Safe localized display text. / 安全的本地化展示文本。 */
    private final PageMessagePresenter messages;

    /** Original inventory model and selection. / 原清单模型与选择。 */
    private final JTable table;

    /** Actual saved summaries supplied by the controller. / 控制器提供的实际保存摘要。 */
    private final Supplier<List<ServerSummary>> profiles;

    /** Original output component and its text selection. / 原输出组件与文本选择。 */
    private final JTextArea output;

    /** Original action controls. / 原操作控件。 */
    private final Controls buttons;

    /** Original provider menu with independent extension identity. / 具有独立扩展身份的原提供方菜单。 */
    private ServerExtensionMenu extensionMenu;

    /** APP point and actual mount. / APP 位置及实际挂载。 */
    private UiExtensionPointRegistration<ServerId> extensionPoint;

    /** Transient server selection forwarded to contributors. / 转发给贡献者的临时服务器选择。 */
    private UiExtensionMount<ServerId> extensionMount;

    /** Selected connection metadata. / 所选连接元数据。 */
    private final ServerDetailsPane details;

    /** Count, empty-state, operation feedback and original record identity. / 数量、空状态、操作反馈及原记录身份。 */
    private final JLabel count = new JLabel(), empty = new JLabel(), feedback = new JLabel(), recordTarget = new JLabel();

    /** Section captions rescaled in place. / 原地重新缩放的分区标题。 */
    private final JLabel inventoryTitle = new JLabel(), recordTitle = new JLabel(), tasksTitle = new JLabel();

    /** Builds presentation around original controls and models. / 围绕原控件与模型构建展示。
     * @param components themed factory / 主题工厂
     * @param messages localized safe text / 本地化安全文本
     * @param table original inventory / 原清单
     * @param search original search draft / 原搜索草稿
     * @param profiles saved summaries / 保存摘要
     * @param checks original pending tasks / 原未结束任务
     * @param output original output / 原输出
     * @param buttons original action controls / 原操作控件 */
    ServerPagePresentation(DesktopPartFactory components, PageMessagePresenter messages, JTable table,
                           JTextField search, Supplier<List<ServerSummary>> profiles, JComboBox<TaskSnapshot> checks,
                           JTextArea output, Controls buttons) {
        super(new BorderLayout());
        setOpaque(false);
        this.components = components;
        this.messages = messages;
        this.table = table;
        this.profiles = profiles;
        this.output = output;
        this.buttons = buttons;
        var inventory = inventory(search);
        details = new ServerDetailsPane(components, messages, actions(), records(), tasks(checks));
        var workspace = components.transparent(new BorderLayout());
        workspace.setLayout(new java.awt.LayoutManager() {
            /** Keeps composition-owned children unchanged. / 保持组合持有的子组件不变。
             * @param name original layout name / 原布局名称
             * @param child original child / 原子组件 */
            public void addLayoutComponent(String name, Component child) {
            }

            /** Leaves removed-child ownership to Swing. / 将已移除子组件归属留给Swing。
             * @param child original child / 原子组件 */
            public void removeLayoutComponent(Component child) {
            }

            /** Reserves enough height for all controls and evidence. / 为全部控件与证据预留足够高度。
             * @param parent original workspace / 原工作区
             * @return preferred dimensions / 首选尺寸 */
            public Dimension preferredLayoutSize(Container parent) {
                int height = Math.max(inventory.getPreferredSize().height, details.getPreferredSize().height);
                return new Dimension(com.formdev.flatlaf.util.UIScale.scale(1100), Math.max(com.formdev.flatlaf.util.UIScale.scale(600), height));
            }

            /** Allows the surrounding page to provide scrolling. / 允许周围页面提供滚动。
             * @param parent original workspace / 原工作区
             * @return flexible minimum / 可伸缩最小尺寸 */
            public Dimension minimumLayoutSize(Container parent) {
                return new Dimension(0, 0);
            }

            /** Keeps inventory and detail widths at the selected ratio. / 将清单与详情宽度保持在选定比例。
             * @param parent original workspace / 原工作区 */
            public void layoutContainer(Container parent) {
                int gap = com.formdev.flatlaf.util.UIScale.scale(16);
                int left = (int) ((parent.getWidth() - gap) * .4);
                inventory.setBounds(0, 0, left, parent.getHeight());
                details.setBounds(left + gap, 0, parent.getWidth() - left - gap, parent.getHeight());
            }
        });
        workspace.add(inventory);
        workspace.add(details);
        add(workspace, BorderLayout.CENTER);
    }

    /** Builds wrapping actions and the primary check. / 构建换行操作及主要检查按钮。
     * @return owned presentation region / 所属展示区域 */
    private JComponent actions() {
        var check = buttons.check();
        var edit = buttons.edit();
        var rescue = buttons.rescue();
        var more = buttons.more();
        var actions = components.transparent(new BorderLayout(12, 0));
        var secondaryActions = components.transparent(new FlowLayout(FlowLayout.LEFT, 8, 4));
        extensionMenu = new ServerExtensionMenu(more, buttons.delete());
        var checkGroup = components.transparent(new BorderLayout(0, 4));
        checkGroup.add(check, BorderLayout.NORTH);
        var checkCaption = messages.bind(new JLabel(), "server.authOnly");
        checkCaption.setForeground(UIManager.getColor("Label.disabledForeground"));
        checkGroup.add(checkCaption, BorderLayout.SOUTH);
        secondaryActions.add(edit);
        secondaryActions.add(rescue);
        secondaryActions.add(more);
        actions.add(secondaryActions, BorderLayout.CENTER);
        actions.add(checkGroup, BorderLayout.EAST);

        return actions;
    }

    /** Declares the actual APP menu point. / 声明实际 APP 菜单位置。
     * @param service host-bound extensions / 宿主绑定扩展
     * @throws ApiException if registration fails / 登记失败时 */
    void installExtensions(UiExtensionService service) throws ApiException {
        if (extensionPoint != null) throw new IllegalStateException("Server extensions already installed");
        var target = PlatformUiExtensions.SERVER_MORE;
        extensionPoint = service.provide(target.id(), target.contextType(), target.kind());
        extensionMount = extensionPoint.mount(extensionMenu, Optional.empty());
    }

    /** Applies original selected identity and business readiness. / 应用原选择身份及业务可用状态。
     * @param server original selected target / 原所选目标
     * @param enabled whether the page accepts interaction / 页面是否接受交互 */
    void extensionContext(Optional<ServerId> server, boolean enabled) {
        extensionMenu.refresh();
        if (extensionMount == null) return;
        try {
            extensionMount.update(server, enabled);
        } catch (ApiException failure) {
            feedback(messages.safe(failure));
        }
    }

    /** Releases the actual point before page disposal. / 页面释放前清理实际位置。 */
    void closeExtensions() {
        extensionMenu.hide();
        if (extensionPoint == null) return;
        try {
            extensionPoint.close();
        } catch (ApiException failure) {
            throw new IllegalStateException("Server extension cleanup remains pending", failure);
        }
    }

    /** Builds inventory heading and search. / 构建清单标题及搜索区。
     * @param query original search draft / 原搜索草稿
     * @return owned presentation region / 所属展示区域 */
    private JComponent inventory(JTextField query) {
        var add = buttons.add();
        var refresh = buttons.refresh();
        var inventory = components.transparent(new BorderLayout(0, 12));
        var heading = components.transparent(new BorderLayout(8, 12));
        var titleRow = components.transparent(new BorderLayout(8, 0));
        messages.bind(inventoryTitle, "server.inventory");
        inventoryTitle.setFont(inventoryTitle.getFont().deriveFont(Font.BOLD, com.formdev.flatlaf.util.UIScale.scale(20f)));
        var titleCopy = components.transparent(new BorderLayout(8, 0));
        titleCopy.add(inventoryTitle, BorderLayout.WEST);
        titleCopy.add(count, BorderLayout.CENTER);
        count.setName("servers.count");
        titleRow.add(titleCopy, BorderLayout.CENTER);
        titleRow.add(add, BorderLayout.EAST);
        heading.add(titleRow, BorderLayout.NORTH);
        var search = components.transparent(new BorderLayout(8, 0));
        search.add(query, BorderLayout.CENTER);
        query.putClientProperty("JTextField.placeholderText", messages.text("server.search"));
        query.putClientProperty("JTextField.leadingIcon",
            gold.debug.windowstolinux.app.ui.control.DesktopIcons.icon("action/search", 16,
                () -> javax.swing.UIManager.getColor("Label.disabledForeground")));
        search.add(refresh, BorderLayout.EAST);
        heading.add(search, BorderLayout.CENTER);
        feedback.setName("servers.feedback");
        heading.add(feedback, BorderLayout.SOUTH);
        inventory.add(heading, BorderLayout.NORTH);
        inventory.add(installInventory(), BorderLayout.CENTER);

        return inventory;
    }

    /** Builds original unfinished-task controls. / 构建原未结束任务控件。
     * @param checks original task selection / 原任务选择
     * @return owned presentation region / 所属展示区域 */
    private JComponent tasks(JComboBox<TaskSnapshot> checks) {
        var cancel = buttons.cancel();
        var reconcile = buttons.reconcile();
        var tasks = components.transparent(new BorderLayout(8, 8));
        messages.bind(tasksTitle, "server.pending");
        tasksTitle.setFont(tasksTitle.getFont().deriveFont(Font.BOLD, com.formdev.flatlaf.util.UIScale.scale(18f)));
        tasks.add(tasksTitle, BorderLayout.NORTH);
        var taskActions = components.transparent(new BorderLayout(8, 8));
        checks.setName("servers.checks");
        checks.setRenderer(new DefaultListCellRenderer() {
            /**
             * Formats original task identity and state without secret data. / 格式化原任务身份及状态，不含秘密数据。
             * @param list owning list / 所属列表
             * @param value task snapshot / 任务快照
             * @param index row index / 行索引
             * @param selected whether selected / 是否选中
             * @param focus whether focused / 是否聚焦
             * @return rendered row / 已呈现行
             */
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focus) {
                String label = value instanceof TaskSnapshot task
                    ? task.progressText() + " · " + messages.text("platform.task." + task.state()) + " · "
                    + task.id()
                    : messages.text("server.noChecks");
                return super.getListCellRendererComponent(list, label, index, selected, focus);
            }
        });
        taskActions.add(checks, BorderLayout.CENTER);
        var recoveryActions = components.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        recoveryActions.add(cancel);
        recoveryActions.add(reconcile);
        taskActions.add(recoveryActions, BorderLayout.EAST);
        tasks.add(taskActions, BorderLayout.CENTER);

        return tasks;
    }

    /** Builds the actual output surface and captured rescue action. / 构建实际输出表面及捕获目标救援动作。
     * @return owned presentation region / 所属展示区域 */
    private JComponent records() {
        var failedRescue = buttons.failedRescue();
        var recordPane = components.transparent(new BorderLayout(0, 12));
        var recordHeading = components.transparent(new BorderLayout(0, 6));
        messages.bind(recordTitle, "server.records");
        recordTitle.setFont(recordTitle.getFont().deriveFont(Font.BOLD, com.formdev.flatlaf.util.UIScale.scale(18f)));
        recordHeading.add(recordTitle, BorderLayout.NORTH);
        recordTarget.setName("servers.recordTarget");
        recordHeading.add(recordTarget, BorderLayout.CENTER);
        recordPane.add(recordHeading, BorderLayout.NORTH);
        output.setName("servers.output");
        output.setRows(8);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, com.formdev.flatlaf.util.UIScale.scale(13)));
        output.setBorder(BorderFactory.createCompoundBorder(null, new com.formdev.flatlaf.ui.FlatEmptyBorder(14, 16, 14, 16)));
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        var logSurface = components.outputCard("", "", output);
        logSurface.remove(0);
        recordPane.add(logSurface, BorderLayout.CENTER);
        failedRescue.setVisible(false);
        var failureActions = components.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        failureActions.add(failedRescue);
        recordPane.add(failureActions, BorderLayout.SOUTH);

        return recordPane;
    }

    /** Configures card presentation while retaining the original selection model. / 保留原选择模型并设置卡片展示。
     * @return original inventory in its scrollable surface / 可滚动表面中的原清单 */
    private JComponent installInventory() {
        table.setName("servers.table");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setTableHeader(null);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(com.formdev.flatlaf.util.UIScale.scale(116));
        table.putClientProperty("desktop.inventory.cards", true);
        while (table.getColumnModel().getColumnCount() > 1)
            table.removeColumn(table.getColumnModel().getColumn(1));
        table.getColumnModel().getColumn(0).setCellRenderer((view, value, selected, focused, row, column) -> {
            int model = table.convertRowIndexToModel(row);
            return serverCard(profiles.get().get(model), selected);
        });
        var inventoryScroll = new JScrollPane(table);
        inventoryScroll.setPreferredSize(new Dimension(400, com.formdev.flatlaf.util.UIScale.scale(470)));
        inventoryScroll.setBorder(BorderFactory.createEmptyBorder());
        components.stylePage(table);
        var content = components.transparent(new BorderLayout(0, 8));
        empty.setName("servers.empty");
        content.add(empty, BorderLayout.NORTH);
        content.add(inventoryScroll, BorderLayout.CENTER);
        return content;
    }

    /** Renders the original saved summary on the shared card surface. / 在共用卡片表面渲染原保存摘要。
     * @param summary original saved summary / 原保存摘要
     * @param selected original selection / 原选择
     * @return one inventory card / 一个清单卡片 */
    private JComponent serverCard(ServerSummary summary, boolean selected) {
        var content = components.transparent(new BorderLayout(12, 0));
        var icon = new JLabel(gold.debug.windowstolinux.app.ui.control.DesktopIcons.icon("server/server", 30,
            () -> javax.swing.UIManager.getColor("Component.focusColor")));
        content.add(icon, BorderLayout.WEST);
        var copy = components.transparent(new GridLayout(2, 1, 0, 6));
        var name = new JLabel(summary.profile().displayName());
        name.putClientProperty("html.disable", true);
        name.setFont(name.getFont().deriveFont(Font.BOLD, com.formdev.flatlaf.util.UIScale.scale(16f)));
        var endpoint = new JLabel(ServerDetailsPane.endpoint(summary));
        endpoint.putClientProperty("html.disable", true);
        endpoint.setFont(new Font(Font.MONOSPACED, Font.PLAIN, com.formdev.flatlaf.util.UIScale.scale(13)));
        copy.add(name);
        copy.add(endpoint);
        content.add(copy, BorderLayout.CENTER);
        var observed = components.transparent(new GridLayout(2, 1, 0, 6));
        var state = components.badge(ServerDetailsPane.status(summary, messages));
        state.setForeground(ServerDetailsPane.statusColor(summary));
        boolean dark = com.formdev.flatlaf.FlatLaf.isLafDark();
        state.setBackground(summary.checkedAt().isEmpty() ? UIManager.getColor("Button.background")
            : summary.connected() ? new Color(dark ? 0x214B36 : 0xDCF6E8) : new Color(dark ? 0x572E32 : 0xFFE4E4));
        observed.add(state);
        var checked = new JLabel(summary.checkedAt().map(value -> DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault()).format(value)).orElse("—"));
        checked.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        observed.add(checked);
        content.add(observed, BorderLayout.EAST);
        content.setToolTipText(summary.profile().displayName() + " · " + ServerDetailsPane.endpoint(summary)
            + " · " + ServerDetailsPane.status(summary, messages) + " · " + ServerDetailsPane.checkedAt(summary));
        return gold.debug.windowstolinux.app.ui.display.DesktopListCard.row(content, selected, null);
    }

    /** Updates actual metadata and exact record ownership. / 更新实际元数据及精确记录归属。
     * @param summary selected saved summary / 所选保存摘要
     * @param total actual inventory size / 实际清单大小
     * @param text actual output or saved summary / 实际输出或保存摘要
     * @param target original record caption / 原记录标题
     * @param identity original record identity / 原记录身份 */
    void show(ServerSummary summary, int total, String text, String target, String identity) {
        details.show(summary);
        count.setText(table.getRowCount() == total
            ? messages.text("server.inventoryCount", Map.of("count", total))
            : messages.text("server.inventoryFiltered", Map.of("shown", table.getRowCount(), "total", total)));
        empty.setVisible(table.getRowCount() == 0);
        empty.setText(messages.text(total == 0 ? "server.inventoryEmpty" : "server.searchEmpty"));
        recordTarget.setText(target);
        recordTarget.setToolTipText(identity);
        if (!output.getText().equals(text)) output.setText(text);
    }

    /** Updates non-secret failure feedback. / 更新非秘密失败反馈。
     * @param text safe operation feedback / 安全操作反馈 */
    void feedback(String text) {
        feedback.setText(text);
        feedback.setToolTipText(text);
        feedback.setVisible(!text.isBlank());
    }

    /** Enables additional target actions with the controller's original gate. / 按控制器原门禁启用更多目标操作。
     * @param enabled admitted target availability / 已准入目标可用性 */
    void targetEnabled(boolean enabled) {
        buttons.more().setEnabled(enabled);
    }

    /** Rescales existing captions and rows without replacing controls. / 重新缩放已有标题与行，不替换控件。 */
    void localize() {
        inventoryTitle.setFont(inventoryTitle.getFont().deriveFont(Font.BOLD, com.formdev.flatlaf.util.UIScale.scale(20f)));
        for (var title : new JLabel[]{recordTitle, tasksTitle})
            title.setFont(title.getFont().deriveFont(Font.BOLD, com.formdev.flatlaf.util.UIScale.scale(18f)));
        table.setRowHeight(com.formdev.flatlaf.util.UIScale.scale(116));
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, com.formdev.flatlaf.util.UIScale.scale(13)));
    }
}
