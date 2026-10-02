package gold.debug.windowstolinux.mod.deployment.ui;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.TaskHandle;

import java.awt.BorderLayout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.ServerInfo;

import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseRecord;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.*;

/** Displays saved applications and admits exact owned management actions. / 展示已保存应用并接纳精确所属管理动作。 */
public final class DeploymentApplicationsPage implements ModPage {
    /** Original generation task admission and recovery. / 原代次任务准入及恢复。 */
    private final DeploymentApplicationsService service;

    /** EDT-owned page container. / EDT所属页面容器。 */
    private final JPanel root = new JPanel(new BorderLayout(8, 8));

    /** Unified saved identities retaining their original management payloads. / 保留原管理载荷的统一保存身份。 */
    private final JList<DeploymentApplicationInventory.Entry> applications = new JList<>();

    /** Configured authorized scan endpoints. / 已配置授权扫描端点。 */
    private final JComboBox<ServerInfo> servers = new JComboBox<>();

    /** Fresh external scan choices. / 新外部扫描选项。 */
    private final JComboBox<DeploymentExternalApplications.Discovered> discovered = new JComboBox<>();

    /** Local typed category filter with an explicit all choice. / 含明确全部选项的本地类型类别筛选。 */
    private final JComboBox<String> categories = new JComboBox<>(new String[]{"ALL", "APP", "WEBSITE"});

    /** Original saved server identity filter, including an explicit all choice. / 原保存服务器身份筛选，包括明确全部选项。 */
    private final JComboBox<gold.debug.windowstolinux.appapi.server.ServerRef> serverFilter = new JComboBox<>();

    /** Complete local facts retained for side-effect-free filtering. / 为无副作用筛选保留的完整本地事实。 */
    private List<DeploymentApplicationInventory.Entry> inventory = List.of();

    /** Distinguishes first loading from a user's explicit cleared selection. / 区分首次加载与用户明确清空选择。 */
    private boolean loaded;

    /** Visible selected identity, state and bindings. / 可见所选身份、状态及绑定。 */
    private final JTextArea details = new JTextArea(14, 60);

    /** Original durable action snapshots and failures. / 原持久动作快照及失败。 */
    private final JTextArea tasks = new JTextArea(5, 60);

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

    /** Owned reattachment review closed on environment or page changes. / 环境或页面变化时关闭的所属重新绑定审阅。
     */
    private JDialog reattachmentConfirmation;

    /** Creates one lazy applications view. / 创建一个惰性应用视图。
     * @param service original generation service / 原代次服务 */
    public DeploymentApplicationsPage(DeploymentApplicationsService service) {
        this.service = Objects.requireNonNull(service);
    }

    /** Creates the real page on the EDT and loads original saved lists. / 在EDT创建真实页面并加载原保存清单。
     * @param environment current host locale and lock state / 当前宿主语言及锁状态
     * @return actual owned Swing page / 实际所属Swing页面 */
    @Override
    public JComponent create(UiEnvironment environment) {
        requireEdt();
        locale(environment);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        var top = DeploymentPageStyle.form();
        applications.setName("applications.inventory");
        categories.setName("applications.category");
        serverFilter.setName("applications.serverFilter");
        servers.setName("applications.scanServer");
        details.setName("applications.details");
        var filters = new JPanel();
        filters.add(categories);
        filters.add(serverFilter);
        top.add(filters);
        var row = new JPanel();
        applications.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        applications.setFixedCellHeight(86);
        button(row, "refresh", this::refresh);
        button(row, "inspect", this::inspect);
        for (var action : DeploymentLifecycleAction.values())
            button(row, action.name().toLowerCase(Locale.ROOT), () -> lifecycle(action));
        button(row, "edit", this::edit);
        button(row, "history", this::history);
        button(row, "reattach", this::reattach);
        top.add(row);
        var scan = new JPanel();
        scan.add(servers);
        scan.add(discovered);
        button(scan, "scan", this::scan);
        button(scan, "adopt", this::adopt);
        top.add(scan);
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
        tasks.setEditable(false);
        root.add(top, BorderLayout.NORTH);
        var applicationViews = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            DeploymentPageStyle.card("style.applications", new JScrollPane(applications)),
            DeploymentPageStyle.card("style.details", new JScrollPane(details)));
        applicationViews.setResizeWeight(0.4);
        applicationViews.setPreferredSize(new java.awt.Dimension(600, 260));
        applicationViews.setMinimumSize(new java.awt.Dimension(0, 160));
        applicationViews.setDividerLocation(300);
        applicationViews.setBorder(BorderFactory.createEmptyBorder());
        applicationViews.addComponentListener(new java.awt.event.ComponentAdapter() {
            /** Stacks inventory and details when both columns would be cramped. / 两列过窄时纵排清单与详情。
             * @param event current size / 当前尺寸 */
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                int orientation = applicationViews.getWidth() < 620
                    ? JSplitPane.VERTICAL_SPLIT
                    : JSplitPane.HORIZONTAL_SPLIT;
                if (applicationViews.getOrientation() != orientation) {
                    applicationViews.setOrientation(orientation);
                    applicationViews.setPreferredSize(
                        new java.awt.Dimension(600, orientation == JSplitPane.VERTICAL_SPLIT ? 420 : 260));
                    applicationViews.setDividerLocation(0.45);
                    applicationViews.revalidate();
                }
            }
        });
        root.add(applicationViews, BorderLayout.CENTER);
        var lower = new JPanel(new BorderLayout());
        lower.add(new JScrollPane(tasks), BorderLayout.CENTER);
        var cancelRow = new JPanel();
        button(cancelRow, "cancel", this::cancel);
        lower.add(cancelRow, BorderLayout.SOUTH);
        root.add(DeploymentPageStyle.card("style.results", lower), BorderLayout.SOUTH);
        DeploymentPageStyle.scrollPage(root);
        localize();
        refresh();
        return root;
    }

    /** Binds saved identities and filters to their original localized renderers. / 为保存身份与筛选绑定原本地化呈现器。 */
    private void configureRenderers() {
        applications.setCellRenderer((list, value, index, selected, focus) -> {
            var copy = DeploymentPageStyle.form();
            var name = new JLabel(value == null ? "" : value.name());
            name.putClientProperty("html.disable", true);
            name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD, name.getFont().getSize2D() * 14f / 13f));
            copy.add(name);
            var detail = new JLabel(value == null
                ? ""
                : text(value.managed().isPresent() ? "managed" : "external") + " · " + value.server().id());
            detail.putClientProperty("html.disable", true);
            copy.add(detail);
            var card = DeploymentPageStyle.card(null, copy);
            card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(
                selected ? UIManager.getColor("Component.focusColor") : UIManager.getColor("Panel.background"), 2,
                true), BorderFactory.createEmptyBorder(12, 14, 12, 14)));
            return card;
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
        generation.incrementAndGet();
        if (reattachmentConfirmation != null)
            reattachmentConfirmation.dispose();
        localize();
    }

    /** Stops page submissions without inventing completion of accepted tasks. / 停止页面提交，不捏造已接纳任务完成。
     */
    @Override
    public void close() {
        requireEdt();
        closed = true;
        generation.incrementAndGet();
        if (reattachmentConfirmation != null)
            reattachmentConfirmation.dispose();
        actions.shutdown();
    }

    /** Reloads both saved origins, scan endpoints and durable action snapshots. / 重载两类保存来源、扫描端点及持久动作快照。 */
    private void refresh() {
        var original = selected();
        String key = original == null ? null : original.key();
        run(service::inventory, values -> {
            inventory = values;
            var target = (gold.debug.windowstolinux.appapi.server.ServerRef) serverFilter.getSelectedItem();
            serverFilter.putClientProperty("inventory.loading", true);
            serverFilter.removeAllItems();
            serverFilter.addItem(null);
            values.stream().map(DeploymentApplicationInventory.Entry::server).distinct()
                .sorted(Comparator.comparing(gold.debug.windowstolinux.appapi.server.ServerRef::id))
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
        actions.execute(() -> {
            try {
                var snapshots = service.tasks();
                SwingUtilities.invokeLater(() -> {
                    if (!closed)
                        tasks.setText(snapshots.stream()
                            .map(value -> value.progressText() + " · " + value.state() + " · " + value.id())
                            .collect(java.util.stream.Collectors.joining("\n")));
                });
            } catch (Exception failure) {
                showFailure();
            }
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
                details.append("\n" + value.before() + " → " + value.after());
                refresh();
            });
        else
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
        var server = (gold.debug.windowstolinux.appapi.server.ServerInfo) servers.getSelectedItem();
        if (original == null || candidate == null || server == null || !server.ref().equals(original.server())
            || !candidate.kind().equals(original.target().kind())
            || !candidate.identity().equals(original.target().identity())) {
            details.setText(text("reattachSelection"));
            return;
        }
        run(() -> service.reviewReattachment(original, candidate), review -> {
            long view = generation.get();
            var body = new JTextArea(java.text.MessageFormat.format(text("reattachReview"), original.id(),
                original.target().kind(), original.target().identity(), original.target().fingerprint(),
                candidate.fingerprint(), review.oldEndpoint(),
                review.endpoint().username() + "@" + review.endpoint().host() + ":" + review.endpoint().port(),
                original.displayName(), original.category(), original.accessUrl().orElse("")), 14, 70);
            body.setEditable(false);
            body.setLineWrap(true);
            body.setWrapStyleWord(true);
            var choice = new JOptionPane(new JScrollPane(body), JOptionPane.WARNING_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION);
            var dialog = choice.createDialog(root, text("reattach"));
            reattachmentConfirmation = dialog;
            try {
                dialog.setVisible(true);
            } finally {
                dialog.dispose();
                if (reattachmentConfirmation == dialog)
                    reattachmentConfirmation = null;
            }
            if (closed || locked || view != generation.get()
                || !Integer.valueOf(JOptionPane.OK_OPTION).equals(choice.getValue()) || !entry.equals(selected())
                || !candidate.equals(discovered.getSelectedItem()) || !server.equals(servers.getSelectedItem()))
                return;
            run(() -> service.reattach(review), value -> {
                details.setText(text("adopted") + value);
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
        var panel = new JPanel(new java.awt.GridLayout(0, 2, 8, 8));
        var nameField = new JTextField(name, 24);
        var categories = new JComboBox<>(new String[]{"APP", "WEBSITE"});
        categories.setSelectedItem(category);
        categories.setEnabled(editableCategory);
        var url = new JTextField(accessUrl.orElse(""), 24);
        url.setEnabled(category.equals("WEBSITE"));
        categories.addActionListener(ignored -> url.setEnabled("WEBSITE".equals(categories.getSelectedItem())));
        panel.add(new JLabel(text("name")));
        panel.add(nameField);
        panel.add(new JLabel(text("category")));
        panel.add(categories);
        panel.add(new JLabel(text("accessUrl")));
        panel.add(url);
        if (JOptionPane.showConfirmDialog(root, panel, text("edit"),
            JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
            return null;
        String selected = (String) categories.getSelectedItem();
        return new EditChoice(nameField.getText(), selected,
            selected.equals("WEBSITE") && !url.getText().isBlank()
                ? Optional.of(url.getText().trim())
                : Optional.empty());
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
                details.append("\n" + text("noHistory"));
                return;
            }
            var choice = (HistoryChoice) JOptionPane.showInputDialog(root, text("chooseHistory"), text("history"),
                JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
            if (choice != null)
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
            details.setText(String.join("\n", scan.issues()));
        });
    }

    /** Adopts one selected exact discovered identity after provider revalidation. / 提供方重核后接管一个所选精确发现身份。 */
    private void adopt() {
        var server = (ServerInfo) servers.getSelectedItem();
        var row = (DeploymentExternalApplications.Discovered) discovered.getSelectedItem();
        if (server != null && row != null)
            run(() -> service.adopt(server.ref(), row), value -> {
                details.setText(text("adopted") + value);
                refresh();
            });
    }

    /** Reads the current EDT-owned original inventory payload. / 读取当前EDT所属原清单载荷。
     * @return selected saved entry or absence / 所选保存条目或无 */
    private DeploymentApplicationInventory.Entry selected() {
        return (DeploymentApplicationInventory.Entry) applications.getSelectedValue();
    }

    /** Shows saved business category, URL and origin-specific dates without claiming live state. / 展示保存的业务类别、URL及来源时间，不宣称实时状态。 */
    private void showSelection() {
        var selected = selected();
        if (selected == null) {
            details.setText("");
            details.putClientProperty("inventory.body", null);
            details.putClientProperty("inventory.key", null);
            availability();
            return;
        }
        String old = (String) details.getClientProperty("inventory.body");
        String tail = selected.key().equals(details.getClientProperty("inventory.key")) && old != null
            && details.getText().startsWith(old) ? details.getText().substring(old.length()) : "";
        String body = java.text.MessageFormat.format(text("entryDetails"), selected.name(), selected.key(),
            text(selected.managed().isPresent() ? "managed" : "external"), selected.server().id(),
            text(selected.category()), selected.accessUrl().orElse(text("absent")),
            selected.deployedAt().map(Object::toString).orElse(text("absent")),
            selected.adoptedAt().map(Object::toString).orElse(text("absent")));
        if (selected.managed().isPresent()) {
            var managed = selected.managed().orElseThrow();
            body += "\n" + managed.state() + "\n" + managed.revisionSha256() + "\n"
                + managed.bindings().stream()
                .map(value -> value.componentId() + " · " + value.kind() + " · " + value.path())
                .collect(java.util.stream.Collectors.joining("\n"));
        } else {
            var external = selected.external().orElseThrow();
            body += "\n" + external.target().kind() + "\n" + external.target().identity() + "\n"
                + external.target().fingerprint() + "\n" + external.target().state();
        }
        details.putClientProperty("inventory.body", body);
        details.putClientProperty("inventory.key", selected.key());
        details.setText(body + tail);
        availability();
    }

    /** Applies both filters to already read facts, retaining the exact original choice or clearing it. / 对已读事实应用两项筛选，保留精确原选项或清空。
     * @param key original selected entry identity or absence / 原所选条目身份或无值 */
    private void filter(String key) {
        Optional<String> category = Optional.ofNullable((String) categories.getSelectedItem())
            .filter(value -> !value.equals("ALL"));
        var server = Optional
            .ofNullable((gold.debug.windowstolinux.appapi.server.ServerRef) serverFilter.getSelectedItem());
        var values = inventory.stream().filter(value -> value.matches(category, server)).toList();
        applications.putClientProperty("inventory.loading", true);
        applications.setListData(values.toArray(DeploymentApplicationInventory.Entry[]::new));
        applications.setSelectedValue(values.stream().filter(value -> value.key().equals(key)).findFirst().orElse(null),
            true);
        applications.putClientProperty("inventory.loading", false);
        showSelection();
    }

    /** Requests cooperative cancellation on the actual original accepted handle. / 在实际原已接纳句柄上请求协作取消。 */
    private void cancel() {
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
                handle.completion().whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                    if (current == handle)
                        current = null;
                    busy = false;
                    if (closed || view != generation.get()) {
                        availability();
                        return;
                    }
                    availability();
                    if (error != null || result == null) {
                        details.append("\n" + text("failed"));
                        return;
                    }
                    if (result.outcome() == Outcome.SUCCEEDED && result.value().isPresent())
                        completed.accept(result.value().orElseThrow());
                    else
                        details.append(
                            "\n" + result.outcome() + " · " + result.problem().map(Problem::eventCode).orElse(""));
                }));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    availability();
                    if (!closed)
                        details.append("\n" + text("failed"));
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
    }

    /** Loads exact current host localisation and lock state. / 加载精确当前宿主本地化及锁状态。
     * @param environment original current host environment / 原当前宿主环境 */
    private void locale(UiEnvironment environment) {
        locked = environment.locked();
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-applications-page",
            environment.locale());
    }

    /** Applies the current locale to every original action. / 将当前语言应用到每个原动作。 */
    private void localize() {
        keys.forEach((button, key) -> button.setText(text(key)));
        DeploymentPageStyle.refresh(root, messages);
        categories.setToolTipText(text("categoryFilter"));
        serverFilter.setToolTipText(text("serverFilter"));
        categories.getAccessibleContext().setAccessibleName(text("categoryFilter"));
        serverFilter.getAccessibleContext().setAccessibleName(text("serverFilter"));
        categories.repaint();
        serverFilter.repaint();
        applications.repaint();
        if (selected() != null && details.getClientProperty("inventory.body") instanceof String body
            && details.getText().startsWith(body))
            showSelection();
        availability();
    }

    /** Applies current lock, submission and page-close gates to all controls. / 将当前锁、提交及页面关闭门槛应用到全部控件。 */
    private void availability() {
        controls.forEach(
            button -> button.setEnabled(!closed && !locked && (!busy || keys.get(button).equals("cancel"))));
        var selected = selected();
        keys.forEach((button, key) -> {
            if (key.equals("history"))
                button.setEnabled(!closed && !locked && !busy && selected != null && selected.managed().isPresent());
            if (key.equals("reattach"))
                button.setEnabled(!closed && !locked && !busy && selected != null && selected.external().isPresent());
        });
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
                tasks.append("\n" + text("failed"));
        });
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
}
