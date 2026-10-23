package gold.debug.windowstolinux.mod.deployment.ui.agent;

import java.awt.datatransfer.DataFlavor;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import javax.swing.*;

import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRemote;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService;
import gold.debug.windowstolinux.mod.deployment.ui.source.DeploymentLocalSourceControls;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;

/** Captures one autonomous local position or credential-free Git remote before task admission. / 在任务准入前抓取一个自主本地位置或不含凭据的Git远端。 */
final class DeploymentAgentSourceControls {
    /** Actual card rows. / 实际卡片行。 */
    private final JPanel panel = DeploymentPageStyle.form();

    /** Explicit source transport. / 明确来源传输。 */
    private final JComboBox<String> route = new JComboBox<>(new String[]{"LOCAL", "GIT"});

    /** Current localized labels. / 当前本地化标签。 */
    private final JLabel routeLabel = new JLabel(), remoteLabel = new JLabel();

    /** Shared authentic local picker. / 共用真实本地选择器。 */
    private final DeploymentLocalSourceControls local;

    /** Original advanced Git reference and executable. / 原高级Git引用及可执行文件。 */
    private final DeploymentAgentGitForm git = new DeploymentAgentGitForm();

    /** Remote metadata input or explicit selected display. / 远端元数据输入或明确已选显示。 */
    private final JTextField remote = new JTextField(24);

    /** Actual remote metadata card. / 实际远端元数据卡片。 */
    private final JPanel remotePanel = DeploymentPageStyle.form();

    /** Explicit metadata accept and clear actions. / 明确元数据接纳及清除操作。 */
    private final JButton accept = new JButton(), clear = new JButton(), paste = new JButton();

    /** Current text resolver. / 当前文案解析器。 */
    private final Function<String, String> text;

    /** Current page edit invalidation. / 当前页面编辑撤销。 */
    private final Runnable changed;

    /** Safe original feedback. / 安全原反馈。 */
    private final Consumer<String> feedback;

    /** Exact accepted remote metadata, never a downloaded repository. / 精确已接受远端元数据，不是下载的仓库。 */
    private String selectedRemote;

    /** Actual source edit availability. / 实际来源编辑可用性。 */
    private boolean enabled, clearAllowed, updating;

    /** Shares the picker without exposing static project CRUD to autonomous tasks. / 共用选择器，不向自主任务暴露标准项目CRUD。
     * @param service original common picker / 原共用选择器
     * @param text localized text / 本地化文案
     * @param worker original page worker / 原页面工作线程
     * @param generation original page generation / 原页面代次
     * @param active original page acceptance / 原页面接纳
     * @param changed actual metadata edit / 实际元数据编辑
     * @param feedback safe status / 安全状态
     * @param availabilityChanged original admission refresh / 原准入刷新 */
    DeploymentAgentSourceControls(DeploymentSourceSelectionService service, Function<String, String> text,
                                  Executor worker, LongSupplier generation, LongPredicate active, Runnable changed,
                                  Consumer<String> feedback, Runnable availabilityChanged) {
        this.text = text;
        this.changed = changed;
        this.feedback = feedback;
        panel.setName("deployment.agent.sourceCard");
        local = new DeploymentLocalSourceControls(service, text, worker, generation, active,
            selection -> {
                changed.run();
                refresh();
            }, feedback, availabilityChanged);
        route.setName("deployment.agent.source");
        remote.setName("deployment.agent.sourceRemote");
        accept.setName("deployment.agent.sourceAccept");
        clear.setName("deployment.agent.sourceClear");
        route.setRenderer((list, value, index, selected, focus) -> new DefaultListCellRenderer()
            .getListCellRendererComponent(list, value == null ? "" : text.apply("choice." + value), index, selected, focus));
        DeploymentPageStyle.field(panel, routeLabel, route);
        panel.add(local.panel());
        DeploymentPageStyle.field(remotePanel, remoteLabel, remote);
        var actions = new JPanel();
        actions.add(accept);
        actions.add(clear);
        actions.add(paste);
        remotePanel.add(actions);
        panel.add(remotePanel);
        route.addActionListener(ignored -> {
            if (!updating) {
                changed.run();
                refresh();
            }
        });
        accept.addActionListener(ignored -> acceptRemote(remote.getText()));
        remote.addActionListener(ignored -> acceptRemote(remote.getText()));
        clear.addActionListener(ignored -> {
            selectedRemote = null;
            remote.setText("");
            git.loadRemote("");
            changed.run();
            refresh();
        });
        paste.addActionListener(ignored -> TransferHandler.getPasteAction().actionPerformed(
            new java.awt.event.ActionEvent(remote, java.awt.event.ActionEvent.ACTION_PERFORMED, "paste")));
        var transfer = new SourceTransfer();
        panel.setTransferHandler(transfer);
        remote.setTransferHandler(transfer);
        localize();
        refresh();
    }

    /** Returns the real shared source card. / 返回真实共用来源卡片。
     * @return original card / 原卡片 */
    JPanel component() {
        return panel;
    }

    /** Returns original advanced Git controls. / 返回原高级Git控件。
     * @return fixed reference fields / 固定引用字段 */
    DeploymentAgentGitForm git() {
        return git;
    }

    /** Captures only a previously completed local picker value. / 仅抓取此前已完成本地选择器值。
     * @return explicit location metadata / 明确位置元数据 */
    Optional<LocalLocation> localLocation() {
        return gitSelected() ? Optional.empty() : local.selection();
    }

    /** Reports the visible route without fetching it. / 报告可见路径，不获取来源。
     * @return whether Git is explicit / 是否明确Git */
    boolean gitSelected() {
        return "GIT".equals(route.getSelectedItem());
    }

    /** Reports original picker occupancy. / 报告原选择器占用。
     * @return actual outstanding task / 实际未完成任务 */
    boolean busy() {
        return local.busy();
    }

    /** Requires an explicit source before actual autonomous admission. / 实际自主准入前要求明确来源。
     * @return selected metadata and no unresolved picker / 已选元数据且无未决选择器 */
    boolean ready() {
        return !busy() && (gitSelected() ? selectedRemote != null : local.selection().isPresent());
    }

    /** Captures the exact accepted remote plus original advanced reference. / 抓取精确已接受远端及原高级引用。
     * @return original immutable Git input / 原不可变Git输入 */
    DeploymentAgentGitForm.Input captureGit() {
        if (!gitSelected() || selectedRemote == null || !selectedRemote.equals(git.remote()))
            throw new IllegalArgumentException("Git source metadata was not selected");
        return git.capture();
    }

    /** Accepts bounded credential-free metadata without downloading or starting a task. / 接受有界且不含凭据的元数据，不下载或启动任务。
     * @param candidate explicitly entered remote / 明确输入远端 */
    private void acceptRemote(String candidate) {
        if (!enabled || busy() || selectedRemote != null || local.selection().isPresent()) return;
        try {
            String value = candidate.trim();
            DeploymentGitRemote.parse(value);
            selectedRemote = value;
            remote.setText(value);
            git.loadRemote(value);
            updating = true;
            route.setSelectedItem("GIT");
            updating = false;
            changed.run();
            refresh();
        } catch (RuntimeException invalid) {
            updating = false;
            feedback.accept(text.apply("source.invalid"));
        }
    }

    /** Revokes only an unanswered original short picker. / 仅撤销未答原短选择器。 */
    void revoke() {
        local.revoke();
    }

    /** Applies original owner availability, never changing a captured running task. / 应用原所属可用性，不改变已抓取运行任务。
     * @param enabled original idle unlocked page / 原空闲未锁页面
     * @param clearAllowed original metadata may be cleared / 原元数据可清除 */
    void availability(boolean enabled, boolean clearAllowed) {
        this.enabled = enabled;
        this.clearAllowed = clearAllowed;
        refresh();
    }

    /** Reflects the selected metadata without implicit source replacement. / 反映已选元数据，不隐式替换来源。 */
    private void refresh() {
        boolean occupied = selectedRemote != null || local.selection().isPresent();
        route.setEnabled(enabled && !busy() && !occupied);
        local.panel().setVisible(!gitSelected());
        remotePanel.setVisible(gitSelected());
        local.availability(enabled, clearAllowed, !gitSelected());
        remote.setEnabled(enabled && !busy());
        remote.setEditable(selectedRemote == null);
        accept.setEnabled(enabled && !busy() && !occupied);
        paste.setEnabled(enabled && !busy() && !occupied);
        clear.setEnabled(clearAllowed && selectedRemote != null);
        git.configure(enabled && !busy(), gitSelected(), text.apply("allowLocal"));
        panel.revalidate();
    }

    /** Updates text while preserving every actual metadata and reference choice. / 更新文案，保留各实际元数据及引用选择。 */
    void localize() {
        routeLabel.setText(text.apply("source"));
        remoteLabel.setText(text.apply("gitUrl"));
        accept.setText(text.apply("source.acceptGit"));
        clear.setText(text.apply("source.clear"));
        paste.setText(text.apply("source.paste"));
        local.localize();
        route.repaint();
    }

    /** Routes one explicit metadata gesture, requiring clear before replacement. / 分流一个明确元数据交互，替换前要求清除。 */
    private final class SourceTransfer extends TransferHandler {
        /** Accepts only an empty current source card. / 仅接受空的当前来源卡片。
         * @param support original gesture / 原交互
         * @return currently allowed metadata / 当前允许元数据 */
        @Override
        public boolean canImport(TransferSupport support) {
            return enabled && !busy() && selectedRemote == null && local.selection().isEmpty()
                && (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || support.isDataFlavorSupported(DataFlavor.stringFlavor));
        }

        /** Keeps Git input pure and local input on the real shared short-task route. / 保持Git输入纯值，本地输入走真实共用短任务路径。
         * @param support one original transfer / 一个原转交
         * @return whether metadata was accepted / 是否接纳元数据 */
        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    String value = ((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor)).trim();
                    if (value.contains("\n") || value.contains("\r")) return false;
                    if (!value.startsWith("file:") && value.contains("://") || gitSelected() && value.startsWith("file:")) {
                        acceptRemote(value);
                        return selectedRemote != null;
                    }
                }
                updating = true;
                route.setSelectedItem("LOCAL");
                updating = false;
                refresh();
                return local.panel().getTransferHandler().importData(support);
            } catch (Exception invalid) {
                updating = false;
                feedback.accept(text.apply("source.invalid"));
                return false;
            }
        }
    }
}
