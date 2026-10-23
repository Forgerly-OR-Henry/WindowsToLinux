package gold.debug.windowstolinux.mod.deployment.ui.source;

import java.awt.datatransfer.DataFlavor;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;

/** Shares local metadata selection while retaining every original short task until host completion. / 共用本地元数据选择，保留每个原短任务至宿主完成。 */
public final class DeploymentLocalSourceControls {
    /** Actual owned Swing controls. / 实际所属Swing控件。 */
    private static final class View {
        /** Original card rows. / 原卡片行。 */
        private final JPanel panel = DeploymentPageStyle.form();

        /** Explicit position display. / 明确位置显示。 */
        private final JTextField location = new JTextField(24);

        /** Locale-bound position label. / 依语言变化的位置标签。 */
        private final JLabel label = new JLabel();

        /** Original metadata actions. / 原元数据操作。 */
        private final JButton choose = new JButton(), clear = new JButton(), paste = new JButton();

        /** Creates no task or grant. / 不创建任务或授权。 */
        private View() {
        }
    }

    /** Actual owner picker. / 实际所属选择器。 */
    private final DeploymentSourceSelectionService service;

    /** Current page text resolver. / 当前页面文案解析器。 */
    private final Function<String, String> text;

    /** Original page worker. / 原页面工作线程。 */
    private final Executor worker;

    /** Original view generation. / 原视图代次。 */
    private final LongSupplier generation;

    /** Original view acceptance check. / 原视图接纳检查。 */
    private final LongPredicate active;

    /** Revokes original unanswered author input after a real metadata edit. / 真实元数据编辑后撤销原未答作者输入。 */
    private final Consumer<Optional<LocalLocation>> changed;

    /** Safe original view feedback. / 安全原视图反馈。 */
    private final Consumer<String> feedback;

    /** Recomputes the original admission gate. / 重算原准入门槛。 */
    private final Runnable availabilityChanged;

    /** Original card controls. / 原卡片控件。 */
    private final View view = new View();

    /** Position metadata after the original short grant closed. / 原短授权关闭后的位置元数据。 */
    private LocalLocation selected;

    /** Exact original short task. / 精确原短任务。 */
    private TaskHandle<LocalLocation> operation;

    /** Rejects late metadata from an obsolete edit. / 拒绝过期编辑的迟到元数据。 */
    private long revision;

    /** Actual task and edit availability. / 实际任务及编辑可用性。 */
    private boolean busy, enabled, clearAllowed, localRoute = true;

    private boolean tile;

    /** Observes original UNKNOWN without timeout release. / 观察原UNKNOWN，不按超时释放。 */
    private Timer observer;

    /** Binds only the actual picker and current owning page lifetime. / 仅绑定实际选择器及当前所属页面寿命。
     * @param service shared original picker / 共用原选择器
     * @param text current localized text / 当前本地化文案
     * @param worker original page worker / 原页面工作线程
     * @param generation original view generation / 原视图代次
     * @param active original view acceptance / 原视图接纳
     * @param changed actual metadata edit / 实际元数据编辑
     * @param feedback safe feedback / 安全反馈
     * @param availabilityChanged admission refresh / 准入刷新 */
    public DeploymentLocalSourceControls(DeploymentSourceSelectionService service, Function<String, String> text,
                                         Executor worker, LongSupplier generation, LongPredicate active,
                                         Consumer<Optional<LocalLocation>> changed, Consumer<String> feedback, Runnable availabilityChanged) {
        this.service = service;
        this.text = text;
        this.worker = worker;
        this.generation = generation;
        this.active = active;
        this.changed = changed;
        this.feedback = feedback;
        this.availabilityChanged = availabilityChanged;
        view.location.setName("deployment.local.location");
        view.location.setEditable(false);
        view.choose.setName("deployment.local.choose");
        view.clear.setName("deployment.local.clear");
        view.paste.setName("deployment.local.paste");
        DeploymentPageStyle.field(view.panel, view.label, view.location);
        var actions = new JPanel();
        actions.add(view.choose);
        actions.add(view.clear);
        actions.add(view.paste);
        view.panel.add(actions);
        view.choose.addActionListener(ignored -> select(Optional.empty()));
        view.clear.addActionListener(ignored -> {
            selected = null;
            view.location.setText("");
            revoke();
            changed.accept(Optional.empty());
            refresh();
        });
        view.paste.addActionListener(ignored -> TransferHandler.getPasteAction().actionPerformed(
            new java.awt.event.ActionEvent(view.location, java.awt.event.ActionEvent.ACTION_PERFORMED, "paste")));
        var transfer = new LocationTransfer();
        view.location.setTransferHandler(transfer);
        view.panel.setTransferHandler(transfer);
        localize();
    }

    /** Returns the actual source card. / 返回实际来源卡片。
     * @return original panel / 原面板 */
    public JPanel panel() {
        return view.panel;
    }

    /** Large source tile retaining the original picker and transfer authority. / 保留原选择及转交权限的大来源卡片。 */
    public void tile(javax.swing.Icon icon) {
        tile = true;
        view.panel.removeAll();
        view.panel.setLayout(new java.awt.BorderLayout(0, 12));
        view.choose.setIcon(icon);
        view.choose.setPreferredSize(new java.awt.Dimension(240, 112));
        view.choose.setHorizontalTextPosition(SwingConstants.CENTER);
        view.choose.setVerticalTextPosition(SwingConstants.BOTTOM);
        view.choose.setTransferHandler(view.panel.getTransferHandler());
        view.panel.add(view.choose, java.awt.BorderLayout.CENTER);
        var footer = new JPanel(new java.awt.BorderLayout(10, 0));
        footer.setOpaque(false);
        footer.add(view.location, java.awt.BorderLayout.CENTER);
        footer.add(view.clear, java.awt.BorderLayout.EAST);
        view.panel.add(footer, java.awt.BorderLayout.SOUTH);
    }

    /** Imports a user gesture through the same short reauthorization task. / 经同一短重授权任务导入用户交互。 */
    public boolean importSelection(java.awt.datatransfer.Transferable value) {
        return view.panel.getTransferHandler().importData(new TransferHandler.TransferSupport(view.panel, value));
    }

    public void tileTransfer(TransferHandler transfer) {
        view.choose.setTransferHandler(transfer);
    }

    /** Returns metadata only, never an active grant. / 仅返回元数据，不返回活动授权。
     * @return current explicit metadata / 当前明确元数据 */
    public Optional<LocalLocation> selection() {
        return Optional.ofNullable(selected);
    }

    /** Reports original occupancy including UNKNOWN. / 报告含UNKNOWN的原占用。
     * @return actual outstanding short task / 实际未完成短任务 */
    public boolean busy() {
        return busy;
    }

    /** Loads an explicitly selected saved position without granting file access. / 加载明确选中的已存位置，不授予文件访问。
     * @param location original saved metadata / 原已存元数据 */
    public void load(LocalLocation location) {
        selected = location;
        view.location.setText(location.location().toString());
        refresh();
    }

    /** Starts a real host picker or revalidates an imported position. / 启动真实宿主选择器或重验导入位置。
     * @param explicit actual user position / 实际用户位置 */
    private void select(Optional<Path> explicit) {
        if (!enabled || busy || !localRoute) return;
        if (selected != null) {
            feedback.accept(text.apply("source.alreadySelected"));
            return;
        }
        long originalRevision = ++revision, originalView = generation.getAsLong();
        busy = true;
        refresh();
        availabilityChanged.run();
        worker.execute(() -> {
            try {
                var handle = service.select(text.apply("source.chooseTitle"), explicit);
                observe(handle, originalView, originalRevision);
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    if (active.test(originalView) && revision == originalRevision)
                        feedback.accept(text.apply("source.failed"));
                    refresh();
                    availabilityChanged.run();
                });
            }
        });
    }

    /** Accepts metadata only after actual ENDED/SUCCEEDED, preserving old UNKNOWN until recovery. / 仅在实际ENDED/SUCCEEDED后接纳元数据，保留旧UNKNOWN至恢复。
     * @param handle actual original task / 实际原任务
     * @param originalView original page generation / 原页面代次
     * @param originalRevision original source revision / 原来源修订 */
    private void observe(TaskHandle<LocalLocation> handle, long originalView, long originalRevision) {
        SwingUtilities.invokeLater(() -> {
            operation = handle;
            if (!active.test(originalView) || revision != originalRevision) cancel(handle);
            observer = new Timer(100, ignored -> {
                if (handle.snapshot().state() == TaskState.UNKNOWN) {
                    observer.stop();
                    feedback.accept(text.apply("source.unknown") + " " + handle.id());
                }
            });
            observer.start();
        });
        handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (operation == handle) {
                operation = null;
                busy = false;
                if (observer != null) observer.stop();
            }
            if (active.test(originalView) && revision == originalRevision) {
                if (failure == null && result != null && result.outcome() == TaskResult.SUCCEEDED
                    && handle.snapshot().state() == TaskState.ENDED && result.value().isPresent()) {
                    selected = result.value().orElseThrow();
                    view.location.setText(selected.location().toString());
                    changed.accept(Optional.of(selected));
                } else feedback.accept(text.apply("source.failed") + " " + handle.id());
            }
            refresh();
            availabilityChanged.run();
        }));
    }

    /** Revokes late results and cancels only the exact original short operation. / 撤销迟到结果，仅取消精确原短操作。 */
    public void revoke() {
        revision++;
        if (operation != null) cancel(operation);
        if (observer != null) observer.stop();
    }

    /** Sends a cooperative original cancel without claiming cleanup. / 发送协作原取消，不声称已清理。
     * @param handle actual original handle / 实际原句柄 */
    private void cancel(TaskHandle<?> handle) {
        try {
            handle.requestCancel();
        } catch (ApiException retained) {
            feedback.accept(text.apply("source.unknown") + " " + handle.id() + " / " + retained.problem().eventCode());
        }
    }

    /** Keeps a clear gesture distinct from admission of a replacement task. / 区分清除交互与替代任务准入。
     * @param enabled new edits are admitted / 接纳新编辑
     * @param clearAllowed unanswered input can be cleared / 可清除未答输入
     * @param localRoute whether the local route is visible / 是否显示本地路径 */
    public void availability(boolean enabled, boolean clearAllowed, boolean localRoute) {
        this.enabled = enabled;
        this.clearAllowed = clearAllowed;
        this.localRoute = localRoute;
        refresh();
    }

    /** Reflects actual occupancy without altering metadata or task state. / 反映实际占用，不改变元数据或任务状态。 */
    private void refresh() {
        boolean edit = enabled && !busy && localRoute;
        view.choose.setEnabled(edit && selected == null);
        view.paste.setEnabled(edit && selected == null);
        view.clear.setEnabled(clearAllowed && localRoute && (selected != null || busy));
        view.location.setEnabled(enabled && localRoute);
        if (tile) {
            view.choose.setText(selected == null ? text.apply("flow.source.drop") : selected.displayName());
            view.location.setVisible(selected != null || busy);
        }
    }

    /** Preserves metadata across language changes. / 语言变化时保留元数据。 */
    public void localize() {
        view.label.setText(text.apply("source.location"));
        view.choose.setText(text.apply("source.choose"));
        view.clear.setText(text.apply("source.clear"));
        view.paste.setText(text.apply("source.paste"));
        if (tile) refresh();
    }

    /** Imports one explicit local metadata gesture, never reads source content. / 导入一个明确本地元数据交互，不读取来源内容。 */
    private final class LocationTransfer extends TransferHandler {
        /** Rejects replacement until the original choice is explicitly cleared. / 在明确清除原选择前拒绝替换。
         * @param support original gesture / 原交互
         * @return currently admissible metadata / 当前可准入元数据 */
        @Override
        public boolean canImport(TransferSupport support) {
            return enabled && !busy && localRoute && selected == null
                && (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || support.isDataFlavorSupported(DataFlavor.stringFlavor));
        }

        /** Reauthorizes an absolute imported position in its own short task. / 在专有短任务重新授权导入绝对位置。
         * @param support one explicit transfer / 一个明确转交
         * @return whether original validation was admitted / 是否接纳原校验 */
        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                Path path;
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    var values = (java.util.List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (values.size() != 1 || !(values.getFirst() instanceof java.io.File file)) return false;
                    path = file.toPath();
                } else {
                    String value = ((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor)).trim();
                    if (value.contains("\n") || value.contains("\r")) return false;
                    path = value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value);
                }
                if (!path.isAbsolute()) return false;
                select(Optional.of(path));
                return true;
            } catch (Exception invalid) {
                feedback.accept(text.apply("source.invalid"));
                return false;
            }
        }
    }
}
