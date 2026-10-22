package gold.debug.windowstolinux.app.ui.mod;

import java.awt.BorderLayout;
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
    private final DesktopCardList<ModStoreGateway.Release> list = new DesktopCardList<>(rows, 48);

    /** Original page actions. / 原页面动作。 */
    private final Controls buttons;

    /** Current safe view feedback. / 当前安全视图反馈。 */
    private final JLabel status = new JLabel();

    /** Original plan facts and explicit draft edits. / 原计划事实及明确草稿编辑。 */
    private final ModStoreSelectionPane selectionView;

    /** Original batch facts. / 原批次事实。 */
    private final ModStoreFeedback feedback;

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
        this.messages = messages;
        this.installed = installed;
        buttons = new Controls(components, messages);
        confirmation = new ModStoreConfirmation(components, messages);
        selectionView = new ModStoreSelectionPane(messages, true, this::change);
        feedback = new ModStoreFeedback(messages);
        setName("mods.store");
        components.stylePage(this);
        setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        search.setName("store.search");
        status.putClientProperty("html.disable", true);
        search.addActionListener(event -> query());
        buttons.retry.addActionListener(event -> query());
        buttons.download.addActionListener(event -> confirm());
        buttons.cancel.addActionListener(event -> cancel());
        buttons.check.addActionListener(event -> checkOriginal());
        var header = new JPanel(new BorderLayout(8, 8));
        header.add(messages.bind(new JLabel(), "store.search"), BorderLayout.WEST);
        header.add(search);
        header.add(buttons.retry, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        list.setName("store.results");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((view, value, index, selected, focused) -> {
            var renderer = new DefaultListCellRenderer();
            renderer.putClientProperty("html.disable", true);
            return renderer.getListCellRendererComponent(view, value == null ? "" : value.title() + " · " + value.version()
                + " · " + installation(value), index, selected, focused);
        });
        components.cardList(list, false);
        list.addListSelectionListener(event -> selectRoot());
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), selectionView);
        split.setResizeWeight(.3);
        add(split);
        var footer = new JPanel(new BorderLayout(4, 4));
        footer.add(status, BorderLayout.NORTH);
        footer.add(feedback);
        footer.add(buttons.row, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        components.scrollPage(this);
        controls();
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
            messages.bind(status, "store.keywordLong");
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
        messages.bind(status, "store.searching");
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
            messages.bind(status, rows.isEmpty() ? "store.empty" : service.canInstall() ? "store.ready" : "store.classMode");
            if (selection != null) resolve();
            controls();
        }, failure -> {
            querying = false;
            if (!present(generation)) {
                if (!locked && !closed) query();
                return;
            }
            catalog = null;
            rows.clear();
            selectionView.display(null, null);
            var original = failure instanceof java.util.concurrent.ExecutionException wrapper && wrapper.getCause() instanceof Exception cause
                ? cause : failure;
            if (original instanceof gold.debug.windowstolinux.appapi.task.result.ApiException api
                && api.problem().eventCode().equals("MOD_STORE_UNAVAILABLE"))
                messages.bind(status, "store.unavailable");
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
            catalog.releases().stream().filter(release -> (release.title() + " " + release.owner() + " " + release.version())
                .toLowerCase(Locale.ROOT).contains(keyword)).forEach(rows::addElement);
            if (selection != null)
                for (int index = 0; index < rows.size(); index++) {
                    var release = rows.get(index);
                    if (release.owner().equals(selection.root().owner()) && release.version().equals(selection.root().version()))
                        list.setSelectedIndex(index);
                }
        } finally {
            filtering = false;
        }
        if (selection != null && list.getSelectedValue() == null)
            clearSelectionDraft();
    }

    /** Starts a new explicit root draft with optional branches unselected. / 开始新的明确主项草稿，可选分支不选中。 */
    private void selectRoot() {
        if (filtering || locked || closed || querying || run != null || checking || catalog == null) return;
        var release = list.getSelectedValue();
        if (release != null)
            change(new ModStoreSelection(new ReleaseKey(release.owner(), release.version()), Set.of(), Map.of()));
        else
            clearSelectionDraft();
    }

    /** Revokes only the unsubmitted selection and its readers, retaining accepted batch facts. / 仅撤销未提交选择及其读取，保留已接纳批次事实。 */
    private void clearSelectionDraft() {
        revision++;
        readToken.set(true);
        confirmation.revoke();
        selection = null;
        plan = null;
        selectionView.display(null, null);
        messages.bind(status, rows.isEmpty() ? "store.empty" : service.canInstall() ? "store.ready" : "store.classMode");
        controls();
    }

    /** Invalidates every former approval and recomputes the explicit selection. / 使全部旧批准失效并重算明确选择。
     * @param value immutable user draft / 不可变用户草稿 */
    private void change(ModStoreSelection value) {
        if (locked || closed || querying || run != null || checking || catalog == null) return;
        selection = value;
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
        messages.bind(status, "store.resolving");
        controls();
        DesktopTaskRunner.run(() -> service.plan(catalogRevision, requested, token::get), result -> {
            if (!finishResolution(generation, draft)) return;
            plan = result;
            selection = result.selection();
            selectionView.display(catalog, result);
            messages.bind(status, result.valid() ? service.canInstall() ? "store.planReady" : "store.classMode"
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
        var review = new ModStoreSelectionPane(messages, false, ignored -> {
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
        messages.bind(status, "store.downloading");
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
                messages.bind(status, "store.outcome." + result.outcome().name().toLowerCase(Locale.ROOT));
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
        messages.bind(status, "store.cancelling");
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
        buttons.check.setEnabled(active && !querying && !resolving && feedback.original().isPresent());
        buttons.row.setWheelScrollingEnabled(!locked && !closed);
        buttons.row.getHorizontalScrollBar().setEnabled(!locked && !closed);
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
            feedback.clear();
            LocaleBinding.unbind(status);
            status.setText("");
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
        feedback.clear();
        LocaleBinding.unbind(status);
        status.setText("");
        controls();
    }

    /** Owns cancellation independently from the Swing worker handle. / 独立于 Swing 工作线程句柄持有取消。 */
    private static final class BatchRun {
        /** Cooperative request observed by the actual store service. / 实际商店服务观测的合作请求。 */
        private final AtomicBoolean cancel = new AtomicBoolean();
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

        /** Complete original buttons with horizontal overflow at the current font scale. / 当前字体缩放下具有水平溢出滚动的完整原按钮。 */
        private final JScrollPane row;

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
            var bar = new JToolBar();
            bar.setFloatable(false);
            bar.add(check);
            bar.add(cancel);
            bar.add(download);
            row = new JScrollPane(bar, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED) {
                /** Reserves complete button and scrollbar heights from actual scaled widgets. / 根据实际缩放控件保留完整按钮及滚动条高度。
                 * @return preferred size with actual viewport and outer borders / 包含实际视口及外边框的首选大小 */
                @Override
                public java.awt.Dimension getPreferredSize() {
                    var size = super.getPreferredSize();
                    var edge = getInsets();
                    var viewportEdge = getViewportBorder() == null ? new java.awt.Insets(0, 0, 0, 0)
                        : getViewportBorder().getBorderInsets(this);
                    size.height = bar.getPreferredSize().height + getHorizontalScrollBar().getPreferredSize().height
                        + edge.top + edge.bottom + viewportEdge.top + viewportEdge.bottom;
                    return size;
                }
            };
            row.setBorder(BorderFactory.createEmptyBorder());
        }
    }
}
