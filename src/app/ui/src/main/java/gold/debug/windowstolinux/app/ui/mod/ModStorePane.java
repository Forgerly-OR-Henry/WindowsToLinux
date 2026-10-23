package gold.debug.windowstolinux.app.ui.mod;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.mod.store.ModStoreCatalog;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreGateway;
import gold.debug.windowstolinux.app.service.mod.store.ModStorePlan;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreService;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.ReleaseKey;
import gold.debug.windowstolinux.app.ui.control.DesktopCardList;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.control.DesktopIcons;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;

/** Reviews complete revision-bound store plans and retains actual batch results. / 审阅完整修订绑定商店计划并保留实际批次结果。 */
public final class ModStorePane extends JPanel implements AutoCloseable {
    /** Original store service. / 原商店服务。 */
    private final ModStoreService service;

    /** Current localized messages. / 当前本地化消息。 */
    private final PageMessagePresenter messages;

    /** Actual installed inventory refresh. / 实际已装清单刷新。 */
    private final Runnable installed;

    /** Preserved explicit search keyword. / 保留的明确搜索词。 */
    private final JTextField search = new JTextField();

    /** Visible releases filtered from the full catalog. / 从完整目录筛选的可见发布。 */
    private final DefaultListModel<ModStoreGateway.Release> rows = new DefaultListModel<>();

    /** Exact main-release selection. / 精确主发布选择。 */
    private final DesktopCardList<ModStoreGateway.Release> list = new DesktopCardList<>(rows, 116);

    private final DesktopPartFactory components;

    private final ModCatalogSurface surface;

    private String pendingOwner;

    /** Original page actions. / 原页面动作。 */
    private final Controls buttons;

    /** Current safe view feedback. / 当前安全视图反馈。 */
    private final JLabel status = new JLabel();

    /** Original plan facts and explicit draft edits. / 原计划事实及明确草稿编辑。 */
    private final ModStoreSelectionPane selectionView;

    /** Original batch facts. / 原批次事实。 */
    private final ModStoreFeedback feedback;

    private final TaskArea taskArea;

    /** Revocable full-plan confirmation. / 可撤销完整计划确认。 */
    private final ModStoreConfirmation confirmation;

    /** Actual current complete catalog. / 实际当前完整目录。 */
    private ModStoreCatalog catalog;

    /** Current immutable explicit draft. / 当前不可变明确草稿。 */
    private ModStoreSelection selection;

    /** Current confirmed-service proposal, absent while recomputing. / 当前服务提案，重算期间为空。 */
    private ModStorePlan plan;

    /** Independent accepted installation and its cooperative cancellation. / 独立已接纳安装及其合作取消。 */
    private BatchRun run;

    /** Cooperative cancellation of short read-only catalog and planning work. / 短只读目录及规划工作的合作取消。 */
    private AtomicBoolean readToken = new AtomicBoolean();

    /** Privacy epoch and draft revision rejecting old approvals. / 拒绝旧批准的隐私代数及草稿修订。 */
    private long epoch, revision;

    /** Actual view, query, planning and confirmation lifecycle flags. / 实际视图、查询、规划及确认生命周期标记。 */
    private boolean locked = true, closed, querying, resolving, filtering, checking, pendingQuery;

    /** Creates a query-free view with stable current controls. / 创建不查询且控件稳定的当前视图。
     * @param service actual store composition / 实际商店装配
     * @param components actual themed controls / 实际主题控件
     * @param messages current safe messages / 当前安全消息
     * @param installed refreshes actual installed inventory / 刷新实际已装清单 */
    public ModStorePane(ModStoreService service, DesktopPartFactory components, PageMessagePresenter messages, Runnable installed) {
        super(new BorderLayout(12, 12));
        this.service = service;
        this.components = components;
        this.messages = messages;
        this.installed = installed;
        buttons = new Controls(components, messages);
        confirmation = new ModStoreConfirmation(components, messages);
        selectionView = new ModStoreSelectionPane(components, messages, true, this::change);
        feedback = new ModStoreFeedback(messages);
        setName("mods.store");
        components.stylePage(this);
        search.setName("store.search");
        components.styleSearchField(search);
        status.putClientProperty("html.disable", true);
        search.addActionListener(event -> query());
        buttons.retry.addActionListener(event -> query());
        buttons.download.addActionListener(event -> confirm());
        buttons.cancel.addActionListener(event -> cancel());
        buttons.check.addActionListener(event -> checkOriginal());
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                filterCurrent();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                filterCurrent();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                filterCurrent();
            }
        });
        list.setName("store.results");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((view, value, index, selected, focused) -> {
            var release = selected && selection != null && catalog != null && value.owner().equals(selection.root().owner())
                ? catalog.releases().stream().filter(candidate -> candidate.owner().equals(selection.root().owner())
                && candidate.version().equals(selection.root().version())).findFirst().orElse(value) : value;
            var renderer = new JPanel(new BorderLayout(UIScale.scale(12), 0));
            renderer.setOpaque(false);
            renderer.add(new JLabel(DesktopIcons.icon("mod/puzzle", 30, () -> UIManager.getColor("Component.accentColor"))), BorderLayout.WEST);
            var text = new JPanel(new GridBagLayout());
            text.setOpaque(false);
            var name = new JLabel(release.title());
            name.putClientProperty("html.disable", true);
            name.setFont(name.getFont().deriveFont(Font.BOLD, UIScale.scale(16f)));
            var description = new JLabel(release.owner());
            description.putClientProperty("html.disable", true);
            description.setFont(description.getFont().deriveFont(Font.PLAIN, UIScale.scale(14f)));
            description.setForeground(UIManager.getColor("Label.disabledForeground"));
            var state = new JLabel(release.version() + " · " + installation(release));
            state.putClientProperty("html.disable", true);
            state.setForeground(UIManager.getColor("Label.disabledForeground"));
            var cell = new GridBagConstraints();
            cell.gridx = 0;
            cell.gridy = 0;
            cell.weightx = 1;
            cell.fill = GridBagConstraints.HORIZONTAL;
            text.add(name, cell);
            cell.gridy++;
            cell.insets = UIScale.scale(new java.awt.Insets(3, 0, 0, 0));
            text.add(description, cell);
            cell.gridy++;
            text.add(state, cell);
            renderer.add(text);
            return renderer;
        });
        components.cardList(list, false);
        list.addListSelectionListener(event -> selectRoot());
        surface = new ModCatalogSurface(components, messages, list, search, buttons.retry, true);
        surface.details(selectionView);
        surface.footer(buttons.row);
        surface.expanded(false);
        add(surface);
        taskArea = new TaskArea();
        add(taskArea, BorderLayout.SOUTH);
        controls();
    }

    /** Binds the installed workspace switch without losing accepted task ownership. / 绑定已下载工作区切换，不丢失已接纳任务归属。 */
    public void workspaceActions(Runnable showInstalled) {
        surface.modeAction(() -> {
            leaveDetails();
            showInstalled.run();
        });
    }

    /** Locates a requested owner in the actual catalog, never starts acquisition. / 在实际目录定位请求的所有者，不启动获取。 */
    public void selectOwner(String owner) {
        leaveDetails();
        search.setText("");
        pendingOwner = owner;
        if (catalog != null && !querying) locateOwner();
        else if (!locked && !closed) query();
    }

    /** Discards only this detail session's unsubmitted edits. / 仅放弃当前详情会话的未提交编辑。 */
    public void leaveDetails() {
        pendingOwner = null;
        filtering = true;
        list.clearSelection();
        filtering = false;
        clearSelectionDraft();
    }

    private void locateOwner() {
        if (pendingOwner == null) return;
        String owner = pendingOwner;
        pendingOwner = null;
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).owner().equals(owner)) {
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
                return;
            }
        }
        showStatus("modui.store.ownerUnavailable");
    }

    private void filterCurrent() {
        if (catalog != null && !querying && !filtering && !locked && !closed && run == null && !checking) filter();
    }

    /** Returns only the exact physically verified registry flags for this catalog release. / 仅返回此目录发布精确物理核验登记标记。
     * @param release original catalog release / 原目录发布
     * @return localized actual installed state / 本地化实际安装状态 */
    private String installation(ModStoreGateway.Release release) {
        return catalog == null ? "" : catalog.installed().stream().filter(value -> value.manifest().id().equals(release.owner())
                && value.manifest().version().toString().equals(release.version())).findFirst().map(value ->
                messages.text("store.installed." + (value.enabled() ? "enabled" : "disabled") + "." + (value.selected() ? "selected" : "unselected")))
            .orElse(messages.text("store.notInstalled"));
    }

    /** Retrieves a full catalog before locally filtering the preserved keyword. / 先获取完整目录，再按保留搜索词在本机筛选。 */
    public void query() {
        if (locked || closed || querying || run != null || checking) return;
        if (search.getText().length() > 256) {
            showStatus("store.keywordLong");
            return;
        }
        confirmation.revoke();
        readToken.set(true);
        revision++;
        plan = null;
        if (resolving) {
            pendingQuery = true;
            controls();
            return;
        }
        var token = new AtomicBoolean();
        readToken = token;
        long generation = epoch;
        querying = true;
        pendingQuery = false;
        showStatus("store.searching");
        controls();
        DesktopTaskRunner.run(() -> service.catalog(token::get), result -> {
            querying = false;
            if (!present(generation)) {
                if (!locked && !closed) query();
                return;
            }
            catalog = result;
            filter();
            service.lastResult().ifPresent(feedback::result);
            showStatus(rows.isEmpty() ? "store.empty" : service.canInstall() ? "store.ready" : "store.classMode");
            if (selection != null) resolve();
            locateOwner();
            controls();
        }, failure -> {
            querying = false;
            if (!present(generation)) {
                if (!locked && !closed) query();
                return;
            }
            catalog = null;
            rows.clear();
            clearSelectionDraft();
            var original = failure instanceof java.util.concurrent.ExecutionException wrapper && wrapper.getCause() instanceof Exception cause
                ? cause : failure;
            if (original instanceof gold.debug.windowstolinux.appapi.task.result.ApiException api
                && api.problem().eventCode().equals("MOD_STORE_UNAVAILABLE"))
                showStatus("store.unavailable");
            else problem(failure);
            controls();
        });
    }

    /** Filters only visible main releases while retaining full resolver input. / 仅筛选可见主发布，保留完整解析输入。 */
    private void filter() {
        filtering = true;
        try {
            rows.clear();
            String keyword = search.getText().toLowerCase(Locale.ROOT);
            ModStoreCatalogRows.owners(catalog.releases(), keyword).forEach(rows::addElement);
            if (selection != null)
                for (int index = 0; index < rows.size(); index++) {
                    var release = rows.get(index);
                    if (release.owner().equals(selection.root().owner()))
                        list.setSelectedIndex(index);
                }
        } finally {
            filtering = false;
        }
        if (selection != null && list.getSelectedValue() == null)
            clearSelectionDraft();
        surface.count(rows.size());
    }

    /** Starts a new explicit root draft with optional branches unselected. / 开始新的明确主项草稿，可选分支不选中。 */
    private void selectRoot() {
        if (filtering || locked || closed || querying || run != null || checking || catalog == null) return;
        var release = list.getSelectedValue();
        if (release != null) {
            if (selection != null && selection.root().owner().equals(release.owner())) return;
            surface.detailHeading(release.title(), release.owner(), this::leaveDetails);
            surface.expanded(true);
            selectionView.display(catalog, null);
            change(new ModStoreSelection(new ReleaseKey(release.owner(), release.version()), Set.of(), Map.of()));
        } else
            clearSelectionDraft();
    }

    /** Revokes only the unsubmitted selection and its readers, retaining accepted batch facts. / 仅撤销未提交选择及其读取，保留已接纳批次事实。 */
    private void clearSelectionDraft() {
        revision++;
        if (!querying) readToken.set(true);
        confirmation.revoke();
        selection = null;
        plan = null;
        selectionView.display(null, null);
        surface.expanded(false);
        showStatus(rows.isEmpty() ? "store.empty" : service.canInstall() ? "store.ready" : "store.classMode");
        controls();
    }

    /** Invalidates every former approval and recomputes the explicit selection. / 使全部旧批准失效并重算明确选择。
     * @param value immutable user draft / 不可变用户草稿 */
    private void change(ModStoreSelection value) {
        if (locked || closed || querying || run != null || checking || catalog == null) return;
        selection = value;
        catalog.releases().stream().filter(release -> release.owner().equals(value.root().owner()) && release.version().equals(value.root().version()))
            .findFirst().ifPresent(release -> surface.detailHeading(release.title(), release.owner(), this::leaveDetails));
        list.repaint();
        revision++;
        confirmation.revoke();
        plan = null;
        resolve();
    }

    /** Requests one revision-bound worker resolution, rejecting older results. / 请求一次修订绑定的工作线程解析，并拒绝旧结果。 */
    private void resolve() {
        if (selection == null || catalog == null || locked || closed || run != null) return;
        readToken.set(true);
        if (resolving || querying) return;
        var token = new AtomicBoolean();
        readToken = token;
        long generation = epoch, draft = revision, catalogRevision = catalog.revision();
        var requested = selection;
        resolving = true;
        showStatus("store.resolving");
        controls();
        DesktopTaskRunner.run(() -> service.plan(catalogRevision, requested, token::get), result -> {
            if (!finishResolution(generation, draft)) return;
            plan = result;
            selection = result.selection();
            selectionView.display(catalog, result);
            showStatus(result.valid() ? service.canInstall() ? "store.planReady" : "store.classMode"
                : result.conflicts().stream().anyMatch(value -> value.code().equals("MOD_STORE_RESOLUTION_INCOMPLETE"))
                ? "store.incomplete" : "store.conflict");
            controls();
        }, failure -> {
            if (!finishResolution(generation, draft)) return;
            problem(failure);
            controls();
        });
    }

    /** Retires the sole original reader before submitting only the latest pending draft. / 先结束唯一原读取，再仅提交最新待处理草稿。
     * @param generation completed reader privacy epoch / 已完成读取的隐私代数
     * @param draft completed reader draft revision / 已完成读取的草稿修订
     * @return whether its actual result still belongs to the current draft / 实际结果是否仍属于当前草稿 */
    private boolean finishResolution(long generation, long draft) {
        resolving = false;
        if (locked || closed) return false;
        if (pendingQuery || !present(generation)) {
            query();
            return false;
        }
        if (revision != draft) {
            resolve();
            controls();
            return false;
        }
        return true;
    }

    /** Confirms the entire exact plan and rejects any changed lifetime or draft. / 确认整个精确计划并拒绝寿命或草稿变化。 */
    private void confirm() {
        if (locked || closed || querying || resolving || checking || run != null || plan == null || !plan.valid() || !service.canInstall())
            return;
        long generation = epoch, draft = revision;
        var approved = plan;
        var review = new ModStoreSelectionPane(components, messages, false, ignored -> {
        });
        review.display(catalog, approved);
        review.interactive(true);
        if (!confirmation.show(this, review) || !present(generation) || revision != draft || plan != approved || run != null)
            return;
        start(approved, generation);
    }

    /** Accepts one installation with a separately owned cooperative token. / 使用独立持有的合作 token 接纳一次安装。
     * @param approved actual exact approved plan / 实际精确已批准计划
     * @param generation original view epoch / 原视图代数 */
    private void start(ModStorePlan approved, long generation) {
        var accepted = new BatchRun();
        run = accepted;
        plan = null;
        feedback.begin();
        showStatus("store.downloading");
        controls();
        DesktopTaskRunner.run(() -> service.install(approved.revision(), accepted.cancel::get, value -> {
            var facts = service.packageResults(value.batchId());
            SwingUtilities.invokeLater(() -> {
                if (run == accepted && present(generation)) feedback.progress(value, facts);
            });
        }), result -> {
            if (run != accepted) return;
            run = null;
            if (present(generation)) {
                feedback.result(result);
                showStatus("store.outcome." + result.outcome().name().toLowerCase(Locale.ROOT));
                installed.run();
                controls();
                query();
            } else if (!locked && !closed) query();
        }, failure -> {
            if (run != accepted) return;
            run = null;
            if (present(generation)) {
                feedback.uncertain();
                problem(failure);
                controls();
            } else if (!locked && !closed) query();
        });
    }

    /** Requests cancellation without interrupting installation or discarding its payload. / 请求取消，不中断安装或丢弃其载荷。 */
    private void cancel() {
        if (locked || closed || run == null) return;
        run.cancel.set(true);
        showStatus("store.cancelling");
        controls();
    }

    /** Explicitly observes an original uncertain operation without replaying downloads. / 明确观测原不确定操作，不重放下载。 */
    private void checkOriginal() {
        if (locked || closed || run != null || querying || resolving || checking) return;
        var operation = feedback.original();
        if (operation.isEmpty()) return;
        long generation = epoch;
        checking = true;
        controls();
        DesktopTaskRunner.run(() -> service.reconcile(operation.orElseThrow()), result -> {
            checking = false;
            if (present(generation)) {
                feedback.reconciled(result);
                installed.run();
                controls();
            } else if (!locked && !closed) query();
        }, failure -> {
            checking = false;
            if (present(generation)) {
                problem(failure);
                controls();
            } else if (!locked && !closed) query();
        });
    }

    /** Presents a safe original failure without replacing retained package facts. / 展示安全原失败，不替换已保留包事实。
     * @param failure original exception / 原异常 */
    private void problem(Exception failure) {
        LocaleBinding.unbind(status);
        status.setText(messages.safe(failure));
        status.setVisible(true);
    }

    private void showStatus(String key) {
        messages.bind(status, key);
        status.setVisible(!key.equals("store.ready") && !key.equals("store.planReady"));
    }

    /** Checks whether an original callback still belongs to this visible lifetime. / 检查原回调是否仍属于此可见寿命。
     * @param generation original epoch / 原代数
     * @return whether presentation is allowed / 是否允许呈现 */
    private boolean present(long generation) {
        return !closed && !locked && epoch == generation;
    }

    /** Derives control availability from the actual current view and worker state. / 从实际当前视图及工作线程状态导出控件可用性。 */
    private void controls() {
        boolean active = !locked && !closed && run == null && !checking;
        search.setEnabled(active && !querying);
        buttons.retry.setEnabled(active && !querying);
        list.setEnabled(active && !querying);
        selectionView.interactive(active && !querying && catalog != null);
        buttons.download.setEnabled(active && !querying && !resolving && plan != null && plan.valid() && service.canInstall());
        buttons.cancel.setEnabled(!locked && !closed && run != null && !run.cancel.get());
        buttons.cancel.setVisible(run != null);
        buttons.check.setEnabled(active && !querying && !resolving && feedback.original().isPresent());
        buttons.check.setVisible(feedback.original().isPresent());
        buttons.row.revalidate();
        taskArea.refresh();
    }

    /** Revokes drafts and private view data at privacy boundaries without interrupting accepted installs. / 在隐私边界撤销草稿及私有视图数据，不中断已接纳安装。
     * @param value actual privacy state / 实际隐私状态 */
    public void setLocked(boolean value) {
        if (closed || locked == value) return;
        locked = value;
        epoch++;
        revision++;
        readToken.set(true);
        confirmation.revoke();
        if (locked) {
            catalog = null;
            selection = null;
            plan = null;
            filtering = true;
            rows.clear();
            filtering = false;
            selectionView.display(null, null);
            surface.expanded(false);
            surface.count(0);
            feedback.clear();
            LocaleBinding.unbind(status);
            status.setText("");
            status.setVisible(false);
        }
        controls();
        if (!locked) query();
    }

    /** Releases only this view and its unsubmitted read work, keeping accepted batch facts. / 仅释放此视图及未提交读取工作，保留已接纳批次事实。 */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        epoch++;
        revision++;
        readToken.set(true);
        confirmation.revoke();
        catalog = null;
        selection = null;
        plan = null;
        rows.clear();
        selectionView.display(null, null);
        surface.expanded(false);
        surface.count(0);
        feedback.clear();
        LocaleBinding.unbind(status);
        status.setText("");
        status.setVisible(false);
        controls();
    }

    /** Owns cancellation independently from the Swing worker handle. / 独立于 Swing 工作线程句柄持有取消。 */
    private static final class BatchRun {
        /** Cooperative request observed by the actual store service. / 实际商店服务观测的合作请求。 */
        private final AtomicBoolean cancel = new AtomicBoolean();
    }

    /** Keeps original task facts independent from selected Mod details and bounded on small windows. / 原任务事实独立于所选Mod详情，并在小窗口内限高。 */
    private final class TaskArea extends JPanel {
        private final JToggleButton reveal = new JToggleButton();

        private final JScrollPane results;

        private TaskArea() {
            super(new BorderLayout(0, UIScale.scale(8)));
            setName("store.taskArea");
            setOpaque(false);
            messages.bind(reveal, "modui.store.task");
            reveal.setName("store.taskToggle");
            var summary = components.transparent(new BorderLayout(0, UIScale.scale(4)));
            summary.add(status, BorderLayout.NORTH);
            summary.add(feedback.heading());
            var header = components.transparent(new BorderLayout(UIScale.scale(12), 0));
            header.add(summary);
            var toggle = components.transparent(new GridBagLayout());
            toggle.add(reveal);
            header.add(toggle, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);
            var facts = components.pagePanel();
            facts.setLayout(new BorderLayout());
            facts.add(feedback, BorderLayout.NORTH);
            results = new JScrollPane(facts, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            results.setName("store.taskScroll");
            results.setBorder(BorderFactory.createEmptyBorder());
            results.getVerticalScrollBar().putClientProperty("desktop.inventory.scrollbar", true);
            results.getVerticalScrollBar().setUnitIncrement(UIScale.scale(20));
            results.setVisible(false);
            add(results);
            reveal.addActionListener(event -> refresh());
        }

        private void refresh() {
            reveal.setVisible(feedback.isVisible());
            reveal.setEnabled(!locked && !closed);
            results.setVisible(feedback.isVisible() && reveal.isSelected());
            setVisible(status.isVisible() || feedback.heading().isVisible());
            revalidate();
            ModStorePane.this.revalidate();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setBorder(new com.formdev.flatlaf.ui.FlatEmptyBorder(0, 22, 18, 22));
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            var size = super.getPreferredSize();
            int available = ModStorePane.this.getHeight();
            int limit = available > 0 ? available / 3 : UIScale.scale(200);
            size.height = Math.min(size.height, limit);
            return size;
        }
    }

    /** Groups only stable page controls, independent from business state. / 仅聚合稳定页面控件，独立于业务状态。 */
    private static final class Controls {
        /** Explicit query retry. / 明确查询重试。 */
        private final JButton retry;

        /** Entire-plan approval. / 整个计划批准。 */
        private final JButton download;

        /** Cooperative batch cancellation. / 合作批次取消。 */
        private final JButton cancel;

        /** Original uncertain-operation observation. / 原不确定操作观测。 */
        private final JButton check;

        /** Complete original buttons wrapping at the current font scale. / 当前字体缩放下可换行的完整原按钮。 */
        private final JPanel row;

        /** Creates stable explicitly named page controls. / 创建稳定且明确命名的页面控件。
         * @param components actual current controls / 实际当前控件
         * @param messages current messages / 当前消息 */
        private Controls(DesktopPartFactory components, PageMessagePresenter messages) {
            retry = messages.bind(components.secondaryButton(""), "store.retry");
            retry.setName("store.retry");
            download = messages.bind(components.primaryButton(""), "store.install");
            download.setName("store.install");
            cancel = messages.bind(components.secondaryButton(""), "store.cancel");
            cancel.setName("store.cancel");
            check = messages.bind(components.secondaryButton(""), "store.check");
            check.setName("store.check");
            row = components.transparent(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, UIScale.scale(8), UIScale.scale(4)));
            row.add(check);
            row.add(cancel);
            row.add(download);
        }
    }
}
