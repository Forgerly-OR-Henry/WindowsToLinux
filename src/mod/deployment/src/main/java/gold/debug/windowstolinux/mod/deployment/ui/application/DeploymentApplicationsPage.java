package gold.debug.windowstolinux.mod.deployment.ui.application;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseRecord;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationList;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationsService;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentExternalApplications;
import gold.debug.windowstolinux.mod.deployment.ui.delivery.DeploymentDeliveryPanel;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentDialogs;

/** Displays saved applications and admits exact owned management actions. / 展示已保存应用并接纳精确所属管理动作。 */
public final class DeploymentApplicationsPage implements ModPage {
    /** Original generation task admission and recovery. / 原代次任务准入及恢复。 */
    private final DeploymentApplicationsService service;

    /** Verified public usage and concrete desktop actions for the current selection. / 当前选择的已核实公开用法与具体桌面动作。 */
    private final DeploymentDeliveryPanel delivery;

    /** EDT-owned page container. / EDT所属页面容器。 */
    private final JPanel root = new JPanel(new BorderLayout(8, 8));

    /** Unified saved identities retaining their original management payloads. / 保留原管理载荷的统一保存身份。 */
    private final JList<DeploymentApplicationList.Entry> applications = new JList<>();

    /** Configured authorized scan endpoints. / 已配置授权扫描端点。 */
    private final JComboBox<ServerInfo> servers = new JComboBox<>();

    /** Fresh external scan choices. / 新外部扫描选项。 */
    private final JComboBox<DeploymentExternalApplications.Discovered> discovered = new JComboBox<>();

    /** Local typed category filter with an explicit all choice. / 含明确全部选项的本地类型类别筛选。 */
    private final JComboBox<String> categories = new JComboBox<>(new String[]{"ALL", "APP", "WEBSITE"});

    /** Original saved server identity filter, including an explicit all choice. / 原保存服务器身份筛选，包括明确全部选项。 */
    private final JComboBox<gold.debug.windowstolinux.appapi.server.model.ServerId> serverFilter = new JComboBox<>();

    /** Complete local facts retained for side-effect-free filtering. / 为无副作用筛选保留的完整本地事实。 */
    private List<DeploymentApplicationList.Entry> inventory = List.of();

    /** Distinguishes first loading from a user's explicit cleared selection. / 区分首次加载与用户明确清空选择。 */
    private boolean loaded;

    /** Visible selected identity, state and bindings. / 可见所选身份、状态及绑定。 */
    private final JTextArea details = new JTextArea(14, 60);

    private final JPanel information = DeploymentPageStyle.form();

    private final JPanel components = DeploymentPageStyle.form();

    private final JLabel objectName = DeploymentPageStyle.title(null, 24);

    private final JLabel inventoryCount = new JLabel();

    private JPanel discovery;

    private final JTextArea discoveryIssues = new JTextArea(3, 36);

    private final JPopupMenu more = new JPopupMenu();

    private final JButton moreButton = new JButton();

    private JDialog discoveryWindow;

    private JScrollPane detailScroll;

    /** Original durable action snapshots and failures. / 原持久动作快照及失败。 */
    private final JTextArea tasks = new JTextArea(5, 60);

    private JPanel logBody;

    private final List<String> taskMessages = new ArrayList<>();

    private String taskSnapshots = "";

    private JDialog logsWindow;

    /** EDT-owned interactive action controls. / EDT所属交互动作控件。 */
    private final List<JButton> controls = new ArrayList<>();

    /** Stable localisation identity of each action control. / 每个动作控件的稳定本地化身份。 */
    private final Map<JButton, String> keys = new LinkedHashMap<>();

    /** Owned worker for admission and local queries outside the EDT. / 在EDT外准入及本地查询的所属工作线程。 */
    private final ExecutorService actions = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "deployment-applications-page");
        thread.setDaemon(true);
        return thread;
    });

    /** Invalidates callbacks belonging to an older locale or closed view. / 使旧语言或已关闭视图的回调失效。 */
    private final AtomicLong generation = new AtomicLong();

    /** Current locale's original action messages. / 当前语言的原动作消息。 */
    private ResourceBundle messages;

    /** Prevents updates after the actual page has closed. / 实际页面关闭后阻止更新。 */
    private volatile boolean closed;

    /** Current host security lock state. / 当前宿主安全锁状态。 */
    private boolean locked;

    /** Prevents concurrent submissions from this view. / 阻止此视图并发提交。 */
    private boolean busy;

    /** Original accepted action retained for explicit cancellation. / 为明确取消保留的原已接纳动作。 */
    private volatile TaskHandle<?> current;

    /** Unsubmitted original dialogs revoked by lock or close. / 锁定及关闭时撤销的原未提交对话框。 */
    private final Map<JDialog, String> inputDialogs = new LinkedHashMap<>();

    /** Creates one lazy applications view. / 创建一个惰性应用视图。
     * @param service original generation service / 原代次服务 */
    public DeploymentApplicationsPage(DeploymentApplicationsService service) {
        this(service, new DeploymentDeliveryPanel());
    }

    /** Binds an owned concrete delivery view for actual interaction verification. / 绑定所属具体交付视图以验证实际交互。
     * @param service original generation service / 原代次服务
     * @param delivery actual verified delivery view / 实际已核实交付视图 */
    public DeploymentApplicationsPage(DeploymentApplicationsService service, DeploymentDeliveryPanel delivery) {
        this.service = Objects.requireNonNull(service);
        this.delivery = Objects.requireNonNull(delivery);
    }

    /** Creates the real page on the EDT and loads original saved lists. / 在EDT创建真实页面并加载原保存清单。
     * @param environment current host locale and lock state / 当前宿主语言及锁状态
     * @return actual owned Swing page / 实际所属Swing页面 */
    @Override
    public JComponent create(UiEnvironment environment) {
        requireEdt();
        locale(environment);
        root.setBorder(BorderFactory.createEmptyBorder(DeploymentPageStyle.scale(14), DeploymentPageStyle.scale(16),
            DeploymentPageStyle.scale(14), DeploymentPageStyle.scale(16)));
        applications.setName("applications.inventory");
        applications.putClientProperty("desktop.inventory.outlined", true);
        categories.setName("applications.category");
        serverFilter.setName("applications.serverFilter");
        servers.setName("applications.scanServer");
        discovered.setName("applications.discovered");
        details.setName("applications.details");
        information.setName("applications.information");
        components.setName("applications.components");
        tasks.setName("applications.tasks");
        var inventoryBody = new JPanel(new BorderLayout(0, DeploymentPageStyle.scale(12)));
        var inventoryTop = DeploymentPageStyle.form();
        var titleRow = new JPanel(new java.awt.GridBagLayout());
        var titleAlignment = new java.awt.GridBagConstraints();
        titleAlignment.anchor = java.awt.GridBagConstraints.WEST;
        titleRow.add(DeploymentPageStyle.title("style.applications", 20), titleAlignment);
        titleAlignment.gridx = 1;
        titleAlignment.weightx = 1;
        titleRow.add(inventoryCount, titleAlignment);
        var refreshRow = new JPanel(new BorderLayout());
        button(refreshRow, "refresh", this::refresh);
        var inventoryHeading = new ResponsiveHeading(titleRow, refreshRow);
        inventoryTop.add(inventoryHeading);
        var filters = new JPanel(new GridLayout(1, 2, DeploymentPageStyle.scale(8), 0));
        filters.add(categories);
        filters.add(serverFilter);
        filters.setBorder(BorderFactory.createEmptyBorder(DeploymentPageStyle.scale(12), 0, 0, 0));
        inventoryTop.add(filters);
        applications.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        applications.setFixedCellHeight(DeploymentPageStyle.scale(116));
        inventoryBody.add(inventoryTop, BorderLayout.NORTH);
        inventoryBody.add(DeploymentPageStyle.scroll(applications), BorderLayout.CENTER);
        var discoverEntry = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        button(discoverEntry, "discover", this::openDiscovery);
        inventoryBody.add(discoverEntry, BorderLayout.SOUTH);

        var row = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 4));
        button(row, "logs", this::openLogs);
        button(row, "inspect", this::inspect);
        for (var action : DeploymentLifecycleAction.values())
            if (!action.autostart()) button(row, action.name().toLowerCase(Locale.ROOT), () -> lifecycle(action));
        var metadata = DeploymentPageStyle.form();
        more.add(metadata);
        for (var action : DeploymentLifecycleAction.values())
            if (action.autostart()) button(metadata, action.name().toLowerCase(Locale.ROOT), () -> lifecycle(action));
        button(metadata, "edit", this::edit);
        button(metadata, "history", this::history);
        button(metadata, "reattach", () -> {
            more.setVisible(false);
            openDiscovery();
        });
        moreButton.setName("applications.more");
        moreButton.setComponentPopupMenu(more);
        moreButton.addActionListener(event -> more.show(moreButton, 0, moreButton.getHeight()));
        row.add(moreButton);
        var scan = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 4));
        scan.add(servers);
        button(scan, "scan", this::scan);
        var adoption = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 4));
        button(adoption, "adopt", this::adopt);
        button(adoption, "reattachConfirm", this::reattach);
        discoveryIssues.setEditable(false);
        discoveryIssues.setName("applications.discoveryIssues");
        discoveryIssues.setLineWrap(true);
        discoveryIssues.setWrapStyleWord(true);
        discovery = DeploymentApplicationForms.discovery(scan, discovered, discoveryIssues, adoption);
        configureRenderers();

        applications.addListSelectionListener(ignored -> {
            if (!Boolean.TRUE.equals(applications.getClientProperty("inventory.loading")))
                showSelection();
        });
        categories.addActionListener(ignored -> filter(selected() == null ? null : selected().key()));
        serverFilter.addActionListener(ignored -> {
            if (!Boolean.TRUE.equals(serverFilter.getClientProperty("inventory.loading")))
                filter(selected() == null ? null : selected().key());
        });
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setRows(2);
        details.setVisible(false);
        details.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                changed();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                changed();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                changed();
            }

            private void changed() {
                details.setVisible(!details.getText().isBlank());
                root.revalidate();
            }
        });
        tasks.setEditable(false);
        tasks.putClientProperty("deployment.style.log", true);
        tasks.setLineWrap(true);
        tasks.setWrapStyleWord(true);
        tasks.setRows(4);
        var detailBody = new JPanel(new BorderLayout(0, DeploymentPageStyle.scale(12)));
        var objectHeading = new JPanel(new BorderLayout(DeploymentPageStyle.scale(12), 0));
        objectHeading.add(new JLabel(DeploymentPageStyle.icon("app", 32)), BorderLayout.WEST);
        objectHeading.add(objectName, BorderLayout.CENTER);
        detailBody.add(objectHeading, BorderLayout.NORTH);
        var sections = new DetailColumn();
        sections.add(DeploymentPageStyle.section("style.information", information));
        sections.add(Box.createVerticalStrut(DeploymentPageStyle.scale(12)));
        sections.add(DeploymentPageStyle.section("style.components", components));
        sections.add(Box.createVerticalStrut(DeploymentPageStyle.scale(12)));
        sections.add(DeploymentPageStyle.section("style.access", delivery));
        sections.add(details);
        detailScroll = DeploymentPageStyle.scroll(sections);
        detailScroll.setName("applications.detailScroll");
        detailBody.add(detailScroll, BorderLayout.CENTER);
        var right = new JPanel(new BorderLayout(0, DeploymentPageStyle.scale(14)));
        right.add(DeploymentPageStyle.outer(detailBody), BorderLayout.CENTER);
        var actionsCard = DeploymentPageStyle.card("style.actions", row);
        actionsCard.setName("applications.actionBar");
        right.add(actionsCard, BorderLayout.SOUTH);
        var logActions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        button(logActions, "refreshLogs", this::refreshTasks);
        button(logActions, "cancel", this::cancel);
        logBody = DeploymentApplicationForms.logs(tasks, logActions);
        var applicationViews = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, DeploymentPageStyle.outer(inventoryBody), right);
        applicationViews.setResizeWeight(0.34);
        applicationViews.setPreferredSize(new java.awt.Dimension(600, 260));
        applicationViews.setMinimumSize(new java.awt.Dimension(0, 160));
        applicationViews.setDividerLocation(0.34);
        applicationViews.setDividerSize(DeploymentPageStyle.scale(14));
        applicationViews.setContinuousLayout(true);
        applicationViews.setBorder(BorderFactory.createEmptyBorder());
        applicationViews.addComponentListener(new java.awt.event.ComponentAdapter() {
            /** Stacks inventory and details when both columns would be cramped. / 两列过窄时纵排清单与详情。
             * @param event current size / 当前尺寸 */
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                int orientation = applicationViews.getWidth() < DeploymentPageStyle.scale(620)
                    ? JSplitPane.VERTICAL_SPLIT
                    : JSplitPane.HORIZONTAL_SPLIT;
                if (applicationViews.getOrientation() != orientation
                    || !Boolean.TRUE.equals(applicationViews.getClientProperty("applications.divider.initialized"))) {
                    applicationViews.setOrientation(orientation);
                    applicationViews.setPreferredSize(
                        new java.awt.Dimension(600, orientation == JSplitPane.VERTICAL_SPLIT ? 420 : 260));
                    applicationViews.setDividerLocation(orientation == JSplitPane.VERTICAL_SPLIT ? 0.4 : 0.34);
                    applicationViews.putClientProperty("applications.divider.initialized", true);
                    applicationViews.revalidate();
                }
            }
        });
        root.add(applicationViews, BorderLayout.CENTER);
        localize();
        refresh();
        return root;
    }

    /** Binds saved identities and filters to their original localized renderers. / 为保存身份与筛选绑定原本地化呈现器。 */
    private void configureRenderers() {
        applications.setCellRenderer((list, value, index, selected, focus) -> {
            var copy = new JPanel(new BorderLayout(DeploymentPageStyle.scale(14), 0));
            copy.setOpaque(false);
            var lines = DeploymentPageStyle.form();
            var name = new JLabel(value == null ? "" : value.name());
            name.putClientProperty("html.disable", true);
            name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD, (float) DeploymentPageStyle.scale(16)));
            lines.add(name);
            lines.add(Box.createVerticalStrut(DeploymentPageStyle.scale(4)));
            var detail = new JLabel(value == null
                ? ""
                : text(value.managed().isPresent() ? "managed" : "external") + " · " + value.server().id());
            detail.putClientProperty("html.disable", true);
            detail.setFont(detail.getFont().deriveFont((float) DeploymentPageStyle.scale(14)));
            detail.setForeground(UIManager.getColor("Label.disabledForeground"));
            lines.add(detail);
            var centered = new JPanel(new java.awt.GridBagLayout());
            centered.setOpaque(false);
            var alignment = new java.awt.GridBagConstraints();
            alignment.weightx = 1;
            alignment.anchor = java.awt.GridBagConstraints.WEST;
            centered.add(lines, alignment);
            copy.add(centered, BorderLayout.CENTER);
            copy.add(new JLabel(DeploymentPageStyle.icon("app", 30)), BorderLayout.WEST);
            copy.setToolTipText(value == null ? null : value.name() + " · " + value.server().id());
            return copy;
        });
        categories.setRenderer((list, value, index, selected, focus) -> label(list,
            value == null ? "" : text(value.equals("ALL") ? "allCategories" : value), selected));
        serverFilter.setRenderer((list, value, index, selected, focus) -> label(list,
            value == null ? text("allServers") : value.id(), selected));
        servers.setRenderer(
            (list, value, index, selected, focus) -> label(list, value == null ? "" : value.name(), selected));
        discovered.setRenderer((list, value, index, selected, focus) -> label(list,
            value == null ? "" : value.name() + " · " + value.state(), selected));

    }

    /** Updates localisation and admission availability on the EDT. / 在EDT更新本地化及准入可用性。
     * @param environment current host locale and lock state / 当前宿主语言及锁状态 */
    @Override
    public void environmentChanged(UiEnvironment environment) {
        requireEdt();
        locale(environment);
        more.setVisible(false);
        if (locked) {
            generation.incrementAndGet();
            if (discoveryWindow != null) discoveryWindow.dispose();
            if (logsWindow != null) logsWindow.setVisible(false);
            for (var dialog : List.copyOf(inputDialogs.keySet())) dialog.dispose();
        } else {
            if (discoveryWindow != null && discoveryWindow.isDisplayable()) {
                discoveryWindow.setTitle(text("style.discovery"));
                DeploymentDialogs.refresh(discoveryWindow, messages);
            }
            for (var dialog : List.copyOf(inputDialogs.keySet())) {
                dialog.setTitle(text(inputDialogs.get(dialog)));
                DeploymentDialogs.refresh(dialog, messages);
            }
        }
        localize();
    }

    /** Stops page submissions without inventing completion of accepted tasks. / 停止页面提交，不捏造已接纳任务完成。
     */
    @Override
    public void close() {
        requireEdt();
        closed = true;
        generation.incrementAndGet();
        more.setVisible(false);
        if (discoveryWindow != null) discoveryWindow.dispose();
        if (logsWindow != null) logsWindow.dispose();
        for (var dialog : List.copyOf(inputDialogs.keySet())) dialog.dispose();
        delivery.clear();
        delivery.enabled(false);
        availability();
        actions.shutdown();
    }

    /** Reloads both saved origins, scan endpoints and durable action snapshots. / 重载两类保存来源、扫描端点及持久动作快照。 */
    private void refresh() {
        var original = selected();
        String key = original == null ? null : original.key();
        run(service::inventory, values -> {
            inventory = values;
            var target = (gold.debug.windowstolinux.appapi.server.model.ServerId) serverFilter.getSelectedItem();
            serverFilter.putClientProperty("inventory.loading", true);
            serverFilter.removeAllItems();
            serverFilter.addItem(null);
            values.stream().map(DeploymentApplicationList.Entry::server).distinct()
                .sorted(Comparator.comparing(gold.debug.windowstolinux.appapi.server.model.ServerId::id))
                .forEach(serverFilter::addItem);
            if (values.stream().anyMatch(value -> value.server().equals(target)))
                serverFilter.setSelectedItem(target);
            serverFilter.putClientProperty("inventory.loading", false);
            filter(!loaded && !values.isEmpty() ? values.getFirst().key() : key);
            loaded = true;
            run(service::servers, targets -> {
                var old = (ServerInfo) servers.getSelectedItem();
                servers.removeAllItems();
                targets.forEach(servers::addItem);
                if (old != null)
                    servers.setSelectedItem(
                        targets.stream().filter(value -> value.ref().equals(old.ref())).findFirst().orElse(null));
            });
        });
        refreshTasks();
    }

    /** Refreshes retained management tasks without replacing the active action or application selection. / 刷新留存管理任务，不替换活动动作或应用选择。 */
    private void refreshTasks() {
        if (closed || locked) return;
        long view = generation.get();
        actions.execute(() -> {
            try {
                var snapshots = service.tasks();
                SwingUtilities.invokeLater(() -> {
                    if (!closed && view == generation.get()) {
                        taskSnapshots = snapshots.stream()
                            .map(value -> value.progressText() + " · " + value.state() + " · " + value.id())
                            .collect(java.util.stream.Collectors.joining("\n"));
                        renderTaskLogs();
                    }
                });
            } catch (Exception failure) {
                showFailure();
            }
        });
    }

    /** Opens one modeless owned task view; closing it never cancels original work. / 打开所属非模态任务窗口，关闭窗口不取消原任务。 */
    private void openLogs() {
        if (closed || locked) return;
        if (logsWindow == null || !logsWindow.isDisplayable()) {
            logsWindow = DeploymentDialogs.window(root, text("style.results"), logBody, null, 780, 480);
            logsWindow.setName("applications.logs.window");
            DeploymentPageStyle.refresh((JComponent) logsWindow.getContentPane(), messages);
        }
        logsWindow.setVisible(true);
        logsWindow.toFront();
        availability();
        refreshTasks();
    }

    private void appendTaskMessage(String value) {
        taskMessages.add(value);
        renderTaskLogs();
    }

    private void renderTaskLogs() {
        String feedback = String.join("\n", taskMessages);
        tasks.setText(taskSnapshots + (taskSnapshots.isBlank() || feedback.isBlank() ? "" : "\n") + feedback);
        String latest = taskMessages.isEmpty() ? taskSnapshots.lines().findFirst().orElse(null) : taskMessages.getLast();
        keys.forEach((button, key) -> {
            if (key.equals("logs")) button.setToolTipText(latest == null ? null : text("logs") + ": " + latest);
        });
    }

    /** Requests an observation through the selected entry's original management path. / 沿所选条目的原管理路径请求观察。 */
    private void inspect() {
        var selected = selected();
        if (selected == null)
            return;
        if (selected.managed().isPresent())
            run(() -> service.inspect(selected.managed().orElseThrow().application()), value -> refresh());
        else
            run(() -> service.externalAction(selected.external().orElseThrow(), "REFRESH_STATUS"), value -> refresh());
    }

    /** Submits a fixed lifecycle action through the selected original payload. / 通过所选原载荷提交固定生命周期动作。
     * @param action fixed selected lifecycle action / 固定所选生命周期动作 */
    private void lifecycle(DeploymentLifecycleAction action) {
        var selected = selected();
        if (selected == null)
            return;
        if (selected.managed().isPresent())
            run(() -> service.lifecycle(selected.managed().orElseThrow(), action), value -> {
                feedback(value.before() + " → " + value.after());
                refresh();
            });
        else if (!action.autostart())
            run(() -> service.externalAction(selected.external().orElseThrow(), action.name()), value -> refresh());
    }

    /** Edits the selected original presentation without granting runtime control. / 编辑所选原展示，不授予运行控制。 */
    private void edit() {
        var selected = selected();
        if (selected == null)
            return;
        var edited = editor(selected.name(), selected.category(), selected.accessUrl(),
            selected.external().isPresent());
        if (edited == null)
            return;
        if (selected.managed().isPresent())
            run(() -> service.edit(selected.managed().orElseThrow(), edited.name(), edited.category(), edited.url()),
                value -> refresh());
        else
            run(() -> service.editExternal(selected.external().orElseThrow(), edited.name(), edited.category(),
                edited.url()), value -> refresh());
    }

    /** Presents the old and new exact association before admitting replacement under its original server. / 在原服务器下接纳替换前呈现新旧精确关联。 */
    private void reattach() {
        var entry = selected();
        var original = entry == null ? null : entry.external().orElse(null);
        var candidate = (DeploymentExternalApplications.Discovered) discovered.getSelectedItem();
        var server = (gold.debug.windowstolinux.appapi.server.model.ServerInfo) servers.getSelectedItem();
        if (original == null || candidate == null || server == null || !server.ref().equals(original.server())
            || !candidate.kind().equals(original.target().kind())
            || !candidate.identity().equals(original.target().identity())) {
            feedback(text("reattachSelection"));
            return;
        }
        run(() -> service.reviewReattachment(original, candidate), review -> {
            long view = generation.get();
            var body = DeploymentApplicationForms.reattachment(() -> java.text.MessageFormat.format(text("reattachReview"), original.id(),
                original.target().kind(), original.target().identity(), original.target().fingerprint(),
                candidate.fingerprint(), review.oldEndpoint(),
                review.endpoint().username() + "@" + review.endpoint().host() + ":" + review.endpoint().port(),
                original.displayName(), original.category(), original.accessUrl().orElse("")));
            var choice = new JOptionPane(null, JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            var dialog = DeploymentDialogs.option(root, text("reattach"), body, choice, 780, 480);
            boolean accepted = decide(dialog, choice, "reattach");
            if (!accepted || view != generation.get() || !entry.equals(selected())
                || !candidate.equals(discovered.getSelectedItem()) || !server.equals(servers.getSelectedItem())) return;
            run(() -> service.reattach(review), value -> {
                feedback(text("adopted") + value);
                refresh();
            });
        });
    }

    /** Collects bounded presentation choices without changing runtime identity. / 收集有界展示选项，不更改运行身份。
     * @param name original visible name / 原可见名称
     * @param category original visible category / 原可见类别
     * @param accessUrl original visible website link / 原可见网站链接
     * @param editableCategory whether adopted metadata allows category editing / 接管元数据是否允许类别编辑
     * @return selected edit or absence on cancellation / 所选编辑或取消时无值 */
    private EditChoice editor(String name, String category, Optional<String> accessUrl, boolean editableCategory) {
        var form = DeploymentApplicationForms.editor(name, category, accessUrl, editableCategory, this::text);
        var panel = form.panel();
        var nameField = form.name();
        var categories = form.category();
        var url = form.url();
        var options = new JOptionPane(null, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        var dialog = DeploymentDialogs.option(root, text("edit"), panel, options, 540, 360);
        if (!decide(dialog, options, "edit")) return null;
        String selected = (String) categories.getSelectedItem();
        return new EditChoice(nameField.getText(), selected,
            selected.equals("WEBSITE") && !url.getText().isBlank()
                ? Optional.of(url.getText().trim())
                : Optional.empty());
    }

    /** Waits for the original live decision without granting a stale view authority. / 等待原活动决定，不授予过期视图权限。
     * @param dialog owned original dialog / 所属原对话框
     * @param options original decision controls / 原决定控件
     * @param titleKey original localization key / 原本地化键
     * @return whether the current original view confirmed / 当前原视图是否确认 */
    private boolean decide(JDialog dialog, JOptionPane options, String titleKey) {
        long view = generation.get();
        inputDialogs.put(dialog, titleKey);
        DeploymentDialogs.refresh(dialog, messages);
        try {
            dialog.setVisible(true);
            return !closed && !locked && generation.get() == view
                && Integer.valueOf(JOptionPane.OK_OPTION).equals(options.getValue());
        } finally {
            inputDialogs.remove(dialog);
            dialog.dispose();
        }
    }

    /** Selects one exact retained release for original-task switch or rollback. / 为原任务切换或回滚选择一个精确保留发布。 */
    private void history() {
        var entry = selected();
        if (entry == null || entry.managed().isEmpty())
            return;
        var selected = entry.managed().orElseThrow();
        run(() -> service.history(selected.application()), histories -> {
            var choices = histories.stream()
                .filter(value -> value.state() == DeploymentReleaseRecord.DeploymentReleaseState.RETIRED)
                .map(value -> new HistoryChoice(value.identity().id(),
                    value.createdAt() + " · " + value.identity().sha256()))
                .toArray(HistoryChoice[]::new);
            if (choices.length == 0) {
                feedback(text("noHistory"));
                return;
            }
            var selection = new JComboBox<>(choices);
            selection.setSelectedItem(choices[0]);
            var body = DeploymentApplicationForms.history(selection, this::text);
            var options = new JOptionPane(null, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            var dialog = DeploymentDialogs.option(root, text("history"), body, options, 650, 260);
            if (decide(dialog, options, "history") && entry.equals(selected())
                && selection.getSelectedItem() instanceof HistoryChoice choice)
                run(() -> service.switchRelease(selected, choice.id()), value -> refresh());
        });
    }

    /** Scans only the selected configured endpoint and excludes managed candidates. / 仅扫描所选配置端点并排除受管候选。 */
    private void scan() {
        var server = (ServerInfo) servers.getSelectedItem();
        if (server == null)
            return;
        run(() -> service.scan(server.ref()), scan -> {
            discovered.removeAllItems();
            scan.applications().stream().filter(value -> !value.managed()).forEach(discovered::addItem);
            discoveryIssues.setText(String.join("\n", scan.issues()));
        });
    }

    /** Adopts one selected exact discovered identity after provider revalidation. / 提供方重核后接管一个所选精确发现身份。 */
    private void adopt() {
        var server = (ServerInfo) servers.getSelectedItem();
        var row = (DeploymentExternalApplications.Discovered) discovered.getSelectedItem();
        if (server != null && row != null)
            run(() -> service.adopt(server.ref(), row), value -> {
                feedback(text("adopted") + value);
                refresh();
            });
    }

    /** Reads the current EDT-owned original inventory payload. / 读取当前EDT所属原清单载荷。
     * @return selected saved entry or absence / 所选保存条目或无 */
    private DeploymentApplicationList.Entry selected() {
        return (DeploymentApplicationList.Entry) applications.getSelectedValue();
    }

    /** Shows saved business category, URL and origin-specific dates without claiming live state. / 展示保存的业务类别、URL及来源时间，不宣称实时状态。 */
    private void showSelection() {
        var selected = selected();
        String key = selected == null ? null : selected.key();
        boolean changed = !Objects.equals(key, details.getClientProperty("inventory.key"));
        Point previousPosition = detailScroll == null ? new Point() : detailScroll.getViewport().getViewPosition();
        information.removeAll();
        components.removeAll();
        if (selected == null) {
            details.setText("");
            details.putClientProperty("inventory.body", null);
            details.putClientProperty("inventory.key", null);
            delivery.clear();
            objectName.setText("");
            information.revalidate();
            components.revalidate();
            restoreDetailPosition(null, new Point());
            availability();
            return;
        }
        if (!selected.key().equals(details.getClientProperty("inventory.key"))) details.setText("");
        objectName.setText(selected.name());
        objectName.setToolTipText(selected.name());
        objectName.putClientProperty("html.disable", true);
        information.setLayout(new GridLayout(0, 2, DeploymentPageStyle.scale(16), DeploymentPageStyle.scale(10)));
        fact(information, "identity", selected.key());
        fact(information, "origin", text(selected.managed().isPresent() ? "managed" : "external"));
        fact(information, "server", selected.server().id());
        fact(information, "category", text(selected.category()));
        fact(information, "accessUrl", selected.accessUrl().orElse(text("absent")));
        fact(information, selected.managed().isPresent() ? "deployedAt" : "adoptedAt", selected.time().toString());
        if (selected.managed().isPresent()) {
            var managed = selected.managed().orElseThrow();
            fact(information, "savedState", managed.state() == ApplicationRunState.INSTALLED
                ? text("installedAvailable") : text("state." + managed.state()));
            fact(information, "autostart", text("autostart." + managed.autostart()));
            fact(components, "revision", managed.revisionSha256());
            for (var binding : managed.bindings())
                fact(components, "binding", binding.componentId() + " · " + binding.kind() + " · " + binding.path());
            selected.usage().ifPresent(delivery::show);
        } else {
            delivery.clear();
            var external = selected.external().orElseThrow();
            String stateKey = "state." + external.target().state();
            fact(information, "savedState", messages.containsKey(stateKey) ? text(stateKey) : external.target().state());
            fact(components, "runtimeKind", external.target().kind());
            fact(components, "runtimeIdentity", external.target().identity());
            fact(components, "fingerprint", external.target().fingerprint());
            fact(components, "externalCapabilities", external.target().canStart() + " / " + external.target().canStop());
        }
        details.putClientProperty("inventory.key", selected.key());
        information.revalidate();
        components.revalidate();
        restoreDetailPosition(key, changed ? new Point() : previousPosition);
        availability();
    }

    /** Starts a new selection at its heading and preserves existing scroll during restyling. / 新选择归顶，样式更新保留已滚动位置。 */
    private void restoreDetailPosition(String key, Point position) {
        long view = generation.get();
        SwingUtilities.invokeLater(() -> {
            if (closed || detailScroll == null || view != generation.get()
                || !Objects.equals(key, details.getClientProperty("inventory.key"))) return;
            var viewport = detailScroll.getViewport();
            viewport.doLayout();
            int maximum = Math.max(0, viewport.getViewSize().height - viewport.getExtentSize().height);
            viewport.setViewPosition(new Point(0, Math.min(position.y, maximum)));
        });
    }

    /** Keeps original facts structured and copies long values without clipping. / 分区呈现原事实并允许复制长值。 */
    private void fact(JPanel parent, String key, String value) {
        var row = new JPanel(new BorderLayout(0, DeploymentPageStyle.scale(4)));
        row.setOpaque(false);
        var label = new JLabel(text(key));
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        label.putClientProperty("html.disable", true);
        var content = new JTextArea(value);
        content.setName("applications.field." + key);
        content.setEditable(false);
        content.setLineWrap(true);
        content.setWrapStyleWord(true);
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder());
        content.setFont(UIManager.getFont("Label.font").deriveFont((float) DeploymentPageStyle.scale(14)));
        ((javax.swing.text.DefaultCaret) content.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        if (key.equals("savedState")) {
            var entry = selected();
            String state = entry == null ? "UNKNOWN" : entry.managed().map(item -> item.state().name())
                .orElseGet(() -> entry.external().orElseThrow().target().state());
            String marker = state.equals("RUNNING") || state.equals("INSTALLED") ? "✓ "
                : state.equals("DEGRADED") ? "✕ " : "− ";
            content.setText(marker + value);
            if (state.equals("RUNNING") || state.equals("INSTALLED"))
                content.setForeground(new java.awt.Color(31, 145, 85));
            else if (state.equals("DEGRADED")) content.setForeground(new java.awt.Color(205, 66, 66));
        }
        row.add(label, BorderLayout.NORTH);
        row.add(content, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createMatteBorder(0, 0, DeploymentPageStyle.scale(1), 0,
            UIManager.getColor("Panel.background")));
        parent.add(row);
    }

    /** Opens the original explicit scan/adoption controls in one owned view. / 在所属窗口打开原显式扫描与接管控件。 */
    private void openDiscovery() {
        if (closed || locked || busy) return;
        if (discoveryWindow == null || !discoveryWindow.isDisplayable()) {
            discoveryWindow = DeploymentDialogs.window(root, text("style.discovery"), discovery, null, 650, 360);
            discoveryWindow.setName("applications.discovery.window");
            DeploymentPageStyle.refresh((JComponent) discoveryWindow.getContentPane(), messages);
        }
        discoveryWindow.setVisible(true);
        discoveryWindow.toFront();
    }

    /** Applies both filters to already read facts, retaining the exact original choice or clearing it. / 对已读事实应用两项筛选，保留精确原选项或清空。
     * @param key original selected entry identity or absence / 原所选条目身份或无值 */
    private void filter(String key) {
        Optional<String> category = Optional.ofNullable((String) categories.getSelectedItem())
            .filter(value -> !value.equals("ALL"));
        var server = Optional
            .ofNullable((gold.debug.windowstolinux.appapi.server.model.ServerId) serverFilter.getSelectedItem());
        var values = inventory.stream().filter(value -> value.matches(category, server)).toList();
        applications.putClientProperty("inventory.loading", true);
        applications.setListData(values.toArray(DeploymentApplicationList.Entry[]::new));
        applications.setSelectedValue(values.stream().filter(value -> value.key().equals(key)).findFirst().orElse(null),
            true);
        applications.putClientProperty("inventory.loading", false);
        inventoryCount.setText("  " + values.size());
        showSelection();
    }

    /** Requests cooperative cancellation on the actual original accepted handle. / 在实际原已接纳句柄上请求协作取消。 */
    private void cancel() {
        if (closed || locked) return;
        var handle = current;
        if (handle == null)
            return;
        actions.execute(() -> {
            try {
                handle.requestCancel();
            } catch (ApiException failure) {
                showFailure();
            }
        });
    }

    /** Owned admission callback whose actual handle is retained by the page. / 页面保留实际句柄的所属准入回调。
     * @param <T> original task result type / 原任务结果类型 */
    private interface Submission<T> {
        /** Admits the original operation outside the EDT. / 在EDT外接纳原操作。
         * @return actual original task handle / 实际原任务句柄
         * @throws ApiException if admission fails / 准入失败时 */
        TaskHandle<T> submit() throws ApiException;
    }

    /** Performs worker admission and routes only the original completion onto the EDT. / 执行工作线程准入并仅将原完成路由到EDT。
     * @param <T> original task result type / 原任务结果类型
     * @param submission original admission callback / 原准入回调
     * @param completed verified original value callback / 已核实原值回调 */
    private <T> void run(Submission<T> submission, Consumer<T> completed) {
        requireEdt();
        if (closed || locked || busy)
            return;
        busy = true;
        availability();
        long view = generation.get();
        actions.execute(() -> {
            try {
                var handle = submission.submit();
                current = handle;
                SwingUtilities.invokeLater(this::availability);
                handle.completion().whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                    if (current == handle)
                        current = null;
                    busy = false;
                    boolean action = handle.snapshot().type().equals(DeploymentApplicationsService.ACTION_TASK);
                    String summary = error != null || result == null ? text("failed") + " · " + handle.id()
                        : result.outcome() + " · " + result.problem().map(Problem::eventCode).orElse("") + " · " + handle.id();
                    if (!closed && action) appendTaskMessage(summary);
                    if (closed || view != generation.get()) {
                        availability();
                        return;
                    }
                    availability();
                    if (error != null || result == null) {
                        feedback(summary, !action);
                        return;
                    }
                    if (result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent())
                        completed.accept(result.value().orElseThrow());
                    else
                        feedback(summary, !action);
                }));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    availability();
                    if (!closed)
                        feedback(text("failed"));
                });
            }
        });
    }

    /** Registers one localised EDT-owned action control. / 登记一个本地化EDT所属动作控件。
     * @param parent owning action row / 所属动作行
     * @param key stable localisation identity / 稳定本地化身份
     * @param action original EDT action / 原EDT动作 */
    private void button(JPanel parent, String key, Runnable action) {
        var button = new JButton();
        button.setName("applications." + key);
        keys.put(button, key);
        controls.add(button);
        parent.add(button);
        button.addActionListener(ignored -> action.run());
        if (SwingUtilities.isDescendingFrom(parent, more)) button.addActionListener(ignored -> more.setVisible(false));
    }

    /** Loads exact current host localisation and lock state. / 加载精确当前宿主本地化及锁状态。
     * @param environment original current host environment / 原当前宿主环境 */
    private void locale(UiEnvironment environment) {
        locked = environment.locked();
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.application.deployment-applications-page",
            environment.locale());
        delivery.environment(environment);
    }

    /** Applies the current locale to every original action. / 将当前语言应用到每个原动作。 */
    private void localize() {
        root.setBorder(BorderFactory.createEmptyBorder(DeploymentPageStyle.scale(14), DeploymentPageStyle.scale(16),
            DeploymentPageStyle.scale(14), DeploymentPageStyle.scale(16)));
        applications.setFixedCellHeight(DeploymentPageStyle.scale(116));
        keys.forEach((button, key) -> button.setText(text(key)));
        moreButton.setText(text("more"));
        DeploymentPageStyle.refresh(root, messages);
        DeploymentPageStyle.refresh(more, messages);
        DeploymentPageStyle.refresh(discovery, messages);
        if (logsWindow != null && logsWindow.isDisplayable()) {
            logsWindow.setTitle(text("style.results"));
            DeploymentDialogs.refresh(logsWindow, messages);
        }
        categories.setToolTipText(text("categoryFilter"));
        serverFilter.setToolTipText(text("serverFilter"));
        categories.getAccessibleContext().setAccessibleName(text("categoryFilter"));
        serverFilter.getAccessibleContext().setAccessibleName(text("serverFilter"));
        categories.repaint();
        serverFilter.repaint();
        applications.repaint();
        if (selected() != null) showSelection();
        availability();
    }

    /** Applies current lock, submission and page-close gates to all controls. / 将当前锁、提交及页面关闭门槛应用到全部控件。 */
    private void availability() {
        controls.forEach(
            button -> button.setEnabled(!closed && !locked && (!busy || keys.get(button).equals("cancel"))));
        var selected = selected();
        keys.forEach((button, key) -> {
            if (key.equals("inspect") || key.equals("edit"))
                button.setEnabled(!closed && !locked && !busy && selected != null);
            if (key.equals("history"))
                button.setEnabled(!closed && !locked && !busy && selected != null && selected.managed().isPresent());
            if (key.equals("reattach"))
                button.setEnabled(!closed && !locked && !busy && selected != null && selected.external().isPresent());
            if (key.equals("reattachConfirm"))
                button.setEnabled(!closed && !locked && !busy && selected != null && selected.external().isPresent());
            if (key.equals("cancel")) button.setEnabled(!closed && !locked && current != null);
            if (key.equals("logs") || key.equals("refreshLogs")) button.setEnabled(!closed && !locked);
            for (var action : DeploymentLifecycleAction.values())
                if (key.equals(action.name().toLowerCase(Locale.ROOT))) {
                    boolean supported = selected != null && (selected.usage().map(value -> value.actions().contains(action)).orElse(false)
                        || selected.external().map(value -> switch (action) {
                        case START -> value.target().canStart();
                        case STOP -> value.target().canStop();
                        case RESTART -> value.target().canStart() && value.target().canStop();
                        case ENABLE_AUTOSTART, DISABLE_AUTOSTART -> false;
                    }).orElse(false));
                    button.setEnabled(!closed && !locked && !busy && supported);
                }
        });
        delivery.enabled(!closed && !locked && !busy);
        moreButton.setEnabled(!closed && !locked && !busy && selected != null);
        applications.setEnabled(!closed && !locked && !busy);
        servers.setEnabled(!closed && !locked && !busy);
        discovered.setEnabled(!closed && !locked && !busy);
        categories.setEnabled(!closed && !locked && !busy);
        serverFilter.setEnabled(!closed && !locked && !busy);
    }

    /** Resolves a stable original localisation identity. / 解析一个稳定原本地化身份。
     * @param key original message identity / 原消息身份
     * @return current localised message / 当前本地化消息 */
    private String text(String key) {
        return messages.getString(key);
    }

    /** Presents worker failures only while the original page remains open. / 仅在原页面仍打开时呈现工作线程失败。 */
    private void showFailure() {
        SwingUtilities.invokeLater(() -> {
            if (!closed)
                appendTaskMessage(text("failed"));
        });
    }

    /** Preserves action failures in the page and an open discovery view. / 在页面及打开的发现窗口保留动作失败。 */
    private void feedback(String value) {
        feedback(value, true);
    }

    private void feedback(String value, boolean retainInLogs) {
        details.append((details.getText().isBlank() ? "" : "\n") + value);
        if (retainInLogs) appendTaskMessage(value);
        if (discoveryWindow != null && discoveryWindow.isVisible())
            discoveryIssues.append((discoveryIssues.getText().isBlank() ? "" : "\n") + value);
    }

    /** Renders saved identity using the actual list's selected colours. / 使用实际清单选中色彩呈现保存身份。
     * @param list owning Swing list / 所属Swing清单
     * @param text original saved display text / 原保存展示文本
     * @param selected actual selection state / 实际选择状态
     * @return original rendered label / 原呈现标签 */
    private static JLabel label(JList<?> list, String text, boolean selected) {
        var label = new JLabel(text);
        label.setOpaque(true);
        label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
        label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        return label;
    }

    /** Rejects Swing mutations outside the EDT. / 拒绝EDT之外的Swing修改。 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("EDT required");
    }

    /** Immutable selected retained history. / 不可变所选保留历史。
     * @param id exact original release identity / 精确原发布身份
     * @param description original display description / 原展示描述 */
    private record HistoryChoice(UUID id, String description) {
        /** Displays the exact retained identity's description. / 展示精确保留身份的描述。
         * @return original display description / 原展示描述 */
        @Override
        public String toString() {
            return description;
        }
    }

    /** Local presentation choice without runtime authority. / 不含运行权限的本地展示选项。
     * @param name selected display name / 所选展示名称
     * @param category selected allowed category / 所选允许类别
     * @param url selected optional website link / 所选可选网站链接 */
    private record EditChoice(String name, String category, Optional<String> url) {
    }

    /** Keeps action width natural and wraps it below the title in a narrow inventory. / 保留操作自然宽度，窄清单时换到标题下方。 */
    private static final class ResponsiveHeading extends JPanel {
        private final JComponent title;

        private final JComponent action;

        private boolean stacked;

        private ResponsiveHeading(JComponent title, JComponent action) {
            super(null);
            this.title = title;
            this.action = action;
            add(title);
            add(action);
            setOpaque(false);
        }

        private boolean narrow() {
            return getWidth() > 0 && getWidth() < title.getPreferredSize().width + action.getPreferredSize().width
                + DeploymentPageStyle.scale(12);
        }

        @Override
        public Dimension getPreferredSize() {
            var a = title.getPreferredSize();
            var b = action.getPreferredSize();
            return new Dimension(a.width + b.width + DeploymentPageStyle.scale(12),
                narrow() ? a.height + b.height + DeploymentPageStyle.scale(8) : Math.max(a.height, b.height));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public void doLayout() {
            var a = title.getPreferredSize();
            var b = action.getPreferredSize();
            boolean wrap = narrow();
            if (wrap) {
                title.setBounds(0, 0, getWidth(), a.height);
                action.setBounds(Math.max(0, getWidth() - b.width), a.height + DeploymentPageStyle.scale(8), b.width, b.height);
            } else {
                title.setBounds(0, (getHeight() - a.height) / 2,
                    Math.max(0, getWidth() - b.width - DeploymentPageStyle.scale(12)), a.height);
                action.setBounds(getWidth() - b.width, (getHeight() - b.height) / 2, b.width, b.height);
            }
            if (stacked != wrap) {
                stacked = wrap;
                SwingUtilities.invokeLater(this::revalidate);
            }
        }
    }

    /** Width-following details with a single independent scroll surface. / 仅使用一个独立滚动面的随宽详情。 */
    private static final class DetailColumn extends JPanel implements Scrollable {
        private DetailColumn() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        public int getScrollableUnitIncrement(Rectangle visible, int axis, int direction) {
            return DeploymentPageStyle.scale(18);
        }

        public int getScrollableBlockIncrement(Rectangle visible, int axis, int direction) {
            return Math.max(1, visible.height - DeploymentPageStyle.scale(18));
        }
    }
}
