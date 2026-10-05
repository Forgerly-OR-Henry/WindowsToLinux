package gold.debug.windowstolinux.app.ui.mod;

import java.awt.BorderLayout;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

import gold.debug.windowstolinux.app.service.mod.store.ModStoreBatchResult;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreProgress;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.language.LocalizedMessage;

/** Presents original per-package receipts independently from transfer stages. / 独立于传输阶段展示原逐包回执。 */
final class ModStoreFeedback extends JPanel {
    /** Current localized display support. / 当前本地化展示支持。 */
    private final PageMessagePresenter messages;

    /** Actual immutable package facts, including completed work after cancellation. / 包含取消后已完成工作的实际不可变包事实。 */
    private List<ModStoreBatchResult.PackageResult> packages = List.of();

    /** Actual current transfer indicator. / 实际当前传输指示。 */
    private final JProgressBar progress = new JProgressBar(0, 100);

    /** Actual dependency-readiness summary. / 实际依赖就绪摘要。 */
    private final JLabel summary = new JLabel();

    /** Original facts model. / 原事实模型。 */
    private final ResultModel model;

    /** Scrollable original package results. / 可滚动的原包结果。 */
    private final JTable table;

    /** Copyable complete original receipt and diagnostic for the selected row. / 所选行可复制的完整原回执及诊断。 */
    private final JTextArea receipt = new JTextArea(3, 24);

    /** Creates empty feedback without inventing operation results. / 创建空反馈，不虚构操作结果。
     * @param messages current display support / 当前展示支持 */
    ModStoreFeedback(PageMessagePresenter messages) {
        super(new BorderLayout(4, 4));
        this.messages = messages;
        model = new ResultModel();
        table = new JTable(model);
        setName("store.feedback");
        progress.setName("store.progress");
        progress.setStringPainted(true);
        table.setName("store.packageResults");
        var renderer = new javax.swing.table.DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", true);
        table.setDefaultRenderer(Object.class, renderer);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setPreferredScrollableViewportSize(new java.awt.Dimension(500, table.getRowHeight() * 4));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> receipt());
        receipt.setName("store.receiptDetails");
        receipt.setEditable(false);
        receipt.setLineWrap(true);
        receipt.setWrapStyleWord(true);
        add(summary, BorderLayout.NORTH);
        var facts = new JPanel(new BorderLayout(4, 4));
        facts.add(new JScrollPane(table), BorderLayout.NORTH);
        facts.add(new JScrollPane(receipt));
        add(facts);
        add(progress, BorderLayout.SOUTH);
        clear();
    }

    /** Starts a visible batch without claiming completion. / 开始展示批次，不宣称完成。 */
    void begin() {
        packages = List.of();
        model.fireTableDataChanged();
        messages.bind(summary, "store.downloading");
        progress.setVisible(true);
        progress.setValue(0);
        progress.setIndeterminate(true);
        setVisible(true);
    }

    /** Displays actual progress and already observed original package outcomes. / 展示实际进度及已观测的原包结果。
     * @param value actual progress / 实际进度
     * @param facts original package observations / 原包观测 */
    void progress(ModStoreProgress value, List<ModStoreBatchResult.PackageResult> facts) {
        packages = List.copyOf(facts);
        model.fireTableDataChanged();
        if (!packages.isEmpty()) table.setRowSelectionInterval(packages.size() - 1, packages.size() - 1);
        progress.setIndeterminate(value.totalBytes() == 0);
        if (value.totalBytes() > 0) progress.setValue((int) (100.0 * value.completedBytes() / value.totalBytes()));
        progress.setString(value.completedPackages() + "/" + value.totalPackages());
        LocaleBinding.bind(summary, LocalizedMessage.of("store.stage." + value.stage().name().toLowerCase(Locale.ROOT),
            "package", value.key().owner() + "@" + value.key().version()), messages.catalog());
    }

    /** Displays the actual batch result while retaining all original package facts. / 展示实际批次结果并保留全部原包事实。
     * @param result actual completed batch / 实际已返回批次 */
    void result(ModStoreBatchResult result) {
        packages = result.packages();
        model.fireTableDataChanged();
        if (!packages.isEmpty()) table.setRowSelectionInterval(0, 0);
        progress.setVisible(false);
        messages.bind(summary, result.allSelectedReady() ? "store.result.ready"
            : result.requiredReady() ? "store.result.optionalPartial" : "store.result.requiredIncomplete");
        setVisible(true);
    }

    /** Ends indeterminate transfer presentation while preserving already observed facts. / 结束不定传输展示并保留已观测事实。 */
    void uncertain() {
        progress.setVisible(false);
        messages.bind(summary, "store.failed");
    }

    /** Returns only an actual original operation requiring explicit checking. / 仅返回需要明确核对的实际原操作。
     * @return original operation identity, if present / 存在时的原操作身份 */
    java.util.Optional<java.util.UUID> original() {
        return packages.stream().filter(value -> value.state() == ModStoreBatchResult.State.UNKNOWN)
            .map(ModStoreBatchResult.PackageResult::operationId).flatMap(java.util.Optional::stream).findFirst();
    }

    /** Shows the actual original reconciliation separately from its earlier batch history. / 将实际原核对结果与此前批次历史分开展示。
     * @param result actual original reconciliation / 实际原核对 */
    void reconciled(gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation.Receipt> result) {
        LocaleBinding.unbind(summary);
        summary.setText(messages.text("store.outcome." + result.outcome().name().toLowerCase(Locale.ROOT)) + "  "
            + result.value().map(receipt -> receipt.operationId() + "  " + receipt.directory() + "  " + receipt.originalCleanup()).orElse("")
            + result.problem().map(messages::safe).orElse(""));
    }

    /** Removes private view data without deleting service receipts or installed files. / 移除私有视图数据，不删除服务回执或安装文件。 */
    void clear() {
        packages = List.of();
        model.fireTableDataChanged();
        LocaleBinding.unbind(summary);
        summary.setText("");
        progress.setVisible(false);
        receipt.setText("");
        setVisible(false);
    }

    /** Shows all selected original facts without relying on clipped table cells. / 展示全部所选原事实，不依赖被裁切表格单元。
     */
    private void receipt() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= packages.size()) {
            receipt.setText("");
            return;
        }
        var value = packages.get(row);
        receipt.setText(value.key().owner() + "@" + value.key().version() + "\n" + value.state() + "\n"
            + value.receipt().map(original -> original.operationId() + "\n" + original.directory() + "\n" + original.originalCleanup())
            .orElseGet(() -> value.operationId().map(Object::toString).orElse(""))
            + value.problem().map(problem -> "\n" + messages.safe(problem)).orElse(""));
        receipt.setCaretPosition(0);
    }

    /** Presents exact original package results through current localization. / 通过当前本地化展示精确原包结果。 */
    private final class ResultModel extends AbstractTableModel {
        /** Returns actual observed package count. / 返回实际已观测包数量。
         * @return count / 数量 */
        @Override
        public int getRowCount() {
            return packages.size();
        }

        /** Returns the original facts column count. / 返回原事实列数。
         * @return count / 数量 */
        @Override
        public int getColumnCount() {
            return 4;
        }

        /** Resolves current column labels. / 解析当前列标签。
         * @param column model column / 模型列
         * @return localized heading / 本地化标题 */
        @Override
        public String getColumnName(int column) {
            return messages.text(new String[]{"store.package", "store.result", "store.receipt", "store.problem"}[column]);
        }

        /** Returns original state, operation identity and safe diagnostic facts. / 返回原状态、操作身份及安全诊断事实。
         * @param row model row / 模型行
         * @param column model column / 模型列
         * @return original fact / 原事实 */
        @Override
        public Object getValueAt(int row, int column) {
            var value = packages.get(row);
            return switch (column) {
                case 0 -> value.key().owner() + "@" + value.key().version();
                case 1 -> messages.text("store.state." + value.state().name().toLowerCase(Locale.ROOT));
                case 2 ->
                    value.receipt().map(receipt -> receipt.operationId() + "  " + receipt.directory() + "  " + receipt.originalCleanup())
                        .orElseGet(() -> value.operationId().map(Object::toString).orElse(""));
                default -> value.problem().map(messages::safe).orElse("");
            };
        }
    }
}
