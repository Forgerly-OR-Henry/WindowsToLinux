package gold.debug.windowstolinux.mod.deployment.ui;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.swing.*;

import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;

/** Persists explicit view edits without writing initialization or locale changes. / 持久化明确视图编辑，不写初始化或语言变化。 */
final class DeploymentPreferenceSelection {
    /** Existing serial view worker. / 既有串行视图工作线程。 */
    private final ExecutorService actions;

    /** Original owner task admission. / 原所有者任务准入。 */
    private final Saver saver;

    /** Captures the current deployment view mode. / 抓取当前部署视图模式。 */
    private final Supplier<String> mode;

    /** Restores only confirmed controls. / 仅恢复已确认控件。 */
    private final Consumer<Selection> apply;

    /** Displays safe task identity or failure. / 显示安全任务身份或失败。 */
    private final Consumer<String> failure;

    /** Recomputes original control availability. / 重算原控件可用性。 */
    private final Runnable availability;

    /** Last successfully persisted triple. / 最近成功持久化三值。 */
    private Selection confirmed;

    /** Whether initialization established valid stored values. / 初始化是否建立有效存储值。 */
    private boolean ready;

    /** Whether an explicit write remains active. / 明确写入是否仍活动。 */
    private boolean saving;

    /** Whether this view has closed. / 此视图是否关闭。 */
    private boolean closed;

    /** Observes retained UNKNOWN without waiting for a terminal future. / 无需等待终态Future即可观察留存UNKNOWN。 */
    private Timer observer;

    /** Safe retained write diagnostic, independent of task-list refresh. / 安全留存写入诊断，独立于任务列表刷新。 */
    private String diagnostic = "";

    /** Actual approval choices sharing this confirmed selection. / 共用本项已确认选择的实际审批选项。 */
    private final JComboBox<String> approval;

    /** Actual authorization choices with an enforced legal selection model. / 具有强制合法选择模型的实际授权选项。 */
    private final JComboBox<String> authorization;

    /** Locale-bound policy headings. / 依语言变化的策略标题。 */
    private final JLabel approvalLabel = new JLabel(), authorizationLabel = new JLabel();

    /** Actual policy explanations, independent of any running task. / 独立于运行任务的实际策略说明。 */
    private final JTextArea approvalDescription = description(), authorizationDescription = description();

    /** Suppresses only programmatic confirmed restoration. / 仅抑制程序化已确认恢复。 */
    private boolean restoring;

    /** Current owned locale lookup. / 当前所属语言查询。 */
    private Function<String, String> text;

    /** Binds the current view and original task owner. / 绑定当前视图及原任务所有者。
     * @param actions serial view worker / 串行视图工作线程
     * @param saver actual metadata admission / 实际元数据准入
     * @param mode EDT view mode capture / EDT视图模式抓取
     * @param apply EDT confirmed selection restoration / EDT确认选择恢复
     * @param failure safe original identity display / 安全原身份显示
     * @param availability original control refresh / 原控件刷新 */
    DeploymentPreferenceSelection(ExecutorService actions, Saver saver, Supplier<String> mode,
                                  Consumer<Selection> apply, Consumer<String> failure, Runnable availability) {
        this.actions = Objects.requireNonNull(actions);
        this.saver = Objects.requireNonNull(saver);
        this.mode = Objects.requireNonNull(mode);
        this.apply = Objects.requireNonNull(apply);
        this.failure = Objects.requireNonNull(failure);
        this.availability = Objects.requireNonNull(availability);
        approval = new JComboBox<>(new ChoiceModel(new String[]{"MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL"},
            value -> restoring || ready() && approvalEnabled()));
        authorization = new JComboBox<>(new ChoiceModel(new String[]{"ONCE", "GENERAL", "FULL"},
            value -> Selection.authorizationAllowed(approval(), value) && (restoring || ready() && authorizationEnabled())));
        approval.setName("deployment.preference.approval");
        authorization.setName("deployment.preference.authorization");
        approvalDescription.setName("deployment.preference.approvalDescription");
        authorizationDescription.setName("deployment.preference.authorizationDescription");
        approval.addActionListener(ignored -> policyChanged());
        authorization.addActionListener(ignored -> policyChanged());
    }

    /** Returns the actual selected approval policy. / 返回实际所选审批策略。
     * @return captured policy name / 抓取的策略名称 */
    String approval() {
        return (String) approval.getSelectedItem();
    }

    /** Returns the actual selected authorization policy. / 返回实际所选授权策略。
     * @return captured policy name / 抓取的策略名称 */
    String authorization() {
        return (String) authorization.getSelectedItem();
    }

    /** Checks the original approval surface instead of changing locked drafts. / 检查原审批表面，避免变更锁定草稿。
     * @return current editability / 当前可编辑性 */
    private boolean approvalEnabled() {
        return approval.isEnabled();
    }

    /** Checks the original authorization surface instead of changing admitted drafts. / 检查原授权表面，避免变更已准入草稿。
     * @return current editability / 当前可编辑性 */
    private boolean authorizationEnabled() {
        return authorization.isEnabled();
    }

    /** Builds both owned policy controls using the existing page style. / 使用既有页面样式构建两项所属策略控件。
     * @param text current owned messages / 当前所属文案
     * @return policy surface / 策略表面 */
    JPanel panel(Function<String, String> text) {
        var panel = DeploymentPageStyle.form();
        DeploymentPageStyle.field(panel, approvalLabel, approval);
        DeploymentPageStyle.field(panel, new JLabel(), approvalDescription);
        DeploymentPageStyle.field(panel, authorizationLabel, authorization);
        DeploymentPageStyle.field(panel, new JLabel(), authorizationDescription);
        localize(text);
        return panel;
    }

    /** Relabels original controls without saving or resetting the captured policies. / 重标原控件，不保存或重置已抓取策略。
     * @param text current owned messages / 当前所属文案 */
    void localize(Function<String, String> text) {
        this.text = Objects.requireNonNull(text);
        approvalLabel.setText(text.apply("approval"));
        authorizationLabel.setText(text.apply("authorization"));
        approval.setRenderer((list, value, index, selected, focus) -> new DefaultListCellRenderer()
            .getListCellRendererComponent(list, value == null ? "" : text.apply("approval." + value), index, selected, focus));
        authorization.setRenderer((list, value, index, selected, focus) -> {
            var label = (JLabel) new DefaultListCellRenderer().getListCellRendererComponent(list,
                value == null ? "" : text.apply("authorization." + value), index, selected, focus);
            label.setEnabled(authorization.isEnabled() && Selection.authorizationAllowed(approval(), value));
            return label;
        });
        describe();
    }

    /** Freezes both strategies with their original page and accepted task. / 随原页面与已接纳任务冻结两项策略。
     * @param enabled actual page editability / 实际页面可编辑性 */
    void editable(boolean enabled) {
        approval.setEnabled(enabled);
        authorization.setEnabled(enabled);
        authorization.repaint();
    }

    /** Creates an accessible multiline policy explanation. / 创建可访问的多行策略说明。
     * @return inert owned text surface / 无副作用所属文本表面 */
    private static JTextArea description() {
        var field = new JTextArea(3, 40);
        field.setEditable(false);
        field.setLineWrap(true);
        field.setWrapStyleWord(true);
        field.setOpaque(false);
        return field;
    }

    /** Updates explanations from the actual selected pair. / 按实际所选配对更新说明。 */
    private void describe() {
        if (text == null) return;
        approvalDescription.setText(text.apply("approval.description." + approval()));
        authorizationDescription.setText(text.apply("authorization.description." + approval() + "." + authorization()));
    }

    /** Normalizes only an edited draft, then saves the complete original triple once. / 仅规范已编辑草稿，再一次保存完整原三值。 */
    private void policyChanged() {
        if (restoring) return;
        if (!Selection.authorizationAllowed(approval(), authorization())) {
            restoring = true;
            try {
                authorization.setSelectedItem("GENERAL");
            } finally {
                restoring = false;
            }
        }
        describe();
        authorization.repaint();
        changed();
    }

    /** Keeps grey choices present while rejecting mouse, keyboard and programmatic illegal selection. / 保留灰选项并拒绝鼠标、键盘及程序化非法选择。 */
    private static final class ChoiceModel extends DefaultComboBoxModel<String> {
        /** Exact declared values. / 精确声明值。 */
        private final java.util.Set<String> choices;

        /** Current original policy admission. / 当前原策略准入。 */
        private final Predicate<String> allowed;

        /** Binds the exact policy values and admission predicate. / 绑定精确策略值与准入条件。
         * @param choices declared values / 声明值
         * @param allowed actual selection guard / 实际选择守卫 */
        private ChoiceModel(String[] choices, Predicate<String> allowed) {
            super(choices);
            this.choices = java.util.Set.of(choices);
            this.allowed = allowed;
        }

        /** Rejects unsupported selections before they can enter the view draft. / 在不支持的选择进入视图草稿前拒绝。
         * @param value requested selection / 请求选择 */
        @Override
        public void setSelectedItem(Object value) {
            if (value instanceof String selected && choices.contains(selected) && allowed.test(selected))
                super.setSelectedItem(value);
        }
    }

    /** Returns whether a stored selection is confirmed and editable. / 返回存储选择是否已确认且可编辑。
     * @return actual readiness / 实际就绪状态 */
    boolean ready() {
        return ready && !saving && !closed;
    }

    /** Returns the original safe edit diagnostic without hiding uncertainty. / 返回原安全编辑诊断，不隐藏未知。
     * @return fixed event or original retained identity / 固定事件或原留存身份 */
    String diagnostic() {
        return diagnostic;
    }

    /** Installs valid stored values while suppressing programmatic change events. / 安装有效存储值，同时抑制程序化变化事件。
     * @param selection actual stored triple / 实际存储三值 */
    void load(Selection selection) {
        ready = false;
        confirmed = Objects.requireNonNull(selection);
        restoring = true;
        try {
            approval.setSelectedItem(selection.approval());
            authorization.setSelectedItem(selection.authorization());
            apply.accept(selection);
        } finally {
            restoring = false;
        }
        ready = true;
        describe();
    }

    /** Admits one captured edit before returning to the EDT; no deployment or AI is started. / 返回EDT前接纳一项已抓取编辑，不启动部署或AI。 */
    void changed() {
        if (!ready()) return;
        var selection = new Selection(mode.get(), approval(), authorization());
        if (selection.equals(confirmed)) return;
        saving = true;
        availability.run();
        actions.execute(() -> {
            try {
                var handle = saver.save(selection);
                SwingUtilities.invokeLater(() -> observe(handle));
                handle.completion().whenComplete((result, error) -> SwingUtilities.invokeLater(() -> finish(result, error)));
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> finish(null, error));
            }
        });
    }

    /** Watches original UNKNOWN without cancelling or claiming cleanup. / 观察原UNKNOWN，不取消或声称清理完成。
     * @param handle actual admitted edit / 实际已接纳编辑 */
    private void observe(TaskHandle<Selection> handle) {
        if (closed || !saving) return;
        observer = new Timer(100, ignored -> {
            if (handle.snapshot().state() != TaskState.UNKNOWN) return;
            observer.stop();
            ready = false;
            diagnostic = handle.id() + " UNKNOWN " + handle.snapshot().progressText();
            failure.accept(diagnostic);
            availability.run();
        });
        observer.start();
    }

    /** Applies only confirmed success or restores the previous triple on known failure. / 仅应用确认成功，或在已知失败恢复之前三值。
     * @param result original final result / 原最终结果
     * @param error original callback failure / 原回调失败 */
    private void finish(OperationResult<Selection> result, Throwable error) {
        if (observer != null) observer.stop();
        if (closed) return;
        saving = false;
        if (error == null && result != null && result.outcome() == TaskResult.SUCCEEDED) {
            diagnostic = "";
            load(result.value().orElseThrow());
        } else if (error == null && result != null && result.outcome() == TaskResult.UNKNOWN
            || error instanceof ApiException api && api.problem().code() == ProblemCode.UNKNOWN) {
            ready = false;
            diagnostic = error instanceof ApiException api ? api.problem().eventCode()
                : result.problem().map(Problem::eventCode).orElse("UNKNOWN");
            failure.accept(diagnostic);
        } else {
            load(confirmed);
            diagnostic = error instanceof ApiException api ? api.problem().eventCode()
                : result == null ? "DEPLOYMENT_PREFERENCE_SAVE_FAILED"
                : result.problem().map(Problem::eventCode).orElse("DEPLOYMENT_PREFERENCE_SAVE_FAILED");
            failure.accept(diagnostic);
        }
        availability.run();
    }

    /** Stops only view observation; the accepted metadata task keeps its original lifecycle. / 仅停止视图观察；已接纳元数据任务保持原寿命。 */
    void close() {
        closed = true;
        if (observer != null) observer.stop();
    }

    /** Admits an original metadata write without a fabricated task. / 接纳原元数据写入，不伪造任务。 */
    @FunctionalInterface
    interface Saver {
        /** Saves the captured triple. / 保存已抓取三值。
         * @param selection actual EDT triple / 实际EDT三值
         * @return original task / 原任务
         * @throws ApiException if admission fails / 准入失败时 */
        TaskHandle<Selection> save(Selection selection) throws ApiException;
    }
}
