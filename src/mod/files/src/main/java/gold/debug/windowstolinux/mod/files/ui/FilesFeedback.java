package gold.debug.windowstolinux.mod.files.ui;

import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.file.action.FileOperation;
import gold.debug.windowstolinux.appapi.task.log.LogReadResult;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.mod.files.file.model.FilesObservation;

/** Shows original task state, per-object reports and durable link steps. / 显示原任务状态、逐项报告及持久链接步骤。 */
final class FilesFeedback extends JPanel {
    /** Actual state observations indexed by original task. / 按原任务索引的实际状态观测。 */
    private final LinkedHashMap<UUID, TaskSnapshot> states = new LinkedHashMap<>();

    /** Actual callback facts independent of completion. / 独立于完成的实际回调事实。 */
    private final LinkedHashMap<UUID, FilesObservation> observations = new LinkedHashMap<>();

    /** Latest actual diagnostic windows, retaining original IDs. / 保留原身份的最新实际诊断窗口。 */
    private final LinkedHashMap<UUID, LogReadResult> logs = new LinkedHashMap<>();

    /** Non-editable result surface. / 不可编辑结果界面。 */
    private final JTextArea text = new JTextArea(2, 20);

    /** Scroll input owner. / 滚动输入所有者。 */
    private final JScrollPane scroll = new JScrollPane(text);

    /** Current catalog. / 当前文案目录。 */
    private final FilesMessages messages;

    /** Last view-side problem. / 最近的视图问题。 */
    private String problem = "";

    /** Mounts an original evidence display. / 挂载原依据展示。
     * @param messages shared catalog / 共享文案目录 */
    FilesFeedback(FilesMessages messages) {
        super(new BorderLayout());
        this.messages = messages;
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setName("files.feedback");
        add(scroll);
    }

    /** Records an actual original state and optional returned callback facts. / 记录实际原状态及可选返回回调事实。
     * @param state original snapshot / 原快照
     * @param observation actual callback observation / 实际回调观测 */
    void observe(TaskSnapshot state, Optional<FilesObservation> observation) {
        states.put(state.id(), state);
        observation.ifPresent(value -> observations.put(state.id(), value));
        while (states.size() > 100) {
            var ended = states.values().stream().filter(value -> value.state() == gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED).findFirst();
            if (ended.isEmpty()) break;
            var id = ended.orElseThrow().id();
            states.remove(id);
            observations.remove(id);
            logs.remove(id);
        }
        render();
    }

    /** Replaces a task's diagnostic window using original stable records. / 使用原稳定记录替换任务诊断窗口。
     * @param task original task identity / 原任务身份
     * @param value actual log read / 实际日志读取 */
    void logs(UUID task, LogReadResult value) {
        if (!states.containsKey(task)) return;
        logs.put(task, value);
        render();
    }

    /** Shows a submission or observation problem without claiming operation failure. / 展示提交及观测问题，不宣称操作失败。
     * @param failure original failure / 原失败 */
    void problem(Throwable failure) {
        problem = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        render();
    }

    /** Returns an original move eligible for explicit link-only checking. / 返回可供明确链接续作核对的原移动。
     * @return original operation, when planned links remain / 存在计划链接未更新时的原操作 */
    Optional<FileOperation> continuation() {
        var actual = new LinkedHashMap<UUID, FileOperation>();
        observations.values().forEach(value -> value.outcome().operations().forEach(operation -> actual.put(operation.id(), operation)));
        return actual.values().stream()
            .filter(operation -> operation.type().startsWith("MOVE"))
            .filter(operation -> operation.steps().stream().anyMatch(step -> step.phase().equals("PLANNED_LINK")
                && operation.steps().stream().noneMatch(done -> done.phase().equals("LINK_UPDATED") && done.source().equals(step.source()))))
            .findFirst();
    }

    /** Re-renders original facts in the current language without discarding progress. / 以当前语言重绘原事实，不丢弃进度。 */
    void render() {
        int caret = text.getCaretPosition();
        var output = new StringBuilder(messages.text("evidence")).append('\n');
        if (!problem.isEmpty()) output.append(problem).append('\n');
        for (var state : states.values()) {
            output.append(state.id()).append("  ").append(state.state()).append("  ").append(state.progressText()).append('\n');
            state.blockers().forEach(value -> output.append(value).append('\n'));
            var value = observations.get(state.id());
            if (value != null) {
                value.problem().ifPresent(failure -> output.append(failure.eventCode()).append(": ").append(failure.message()).append('\n'));
                for (var report : value.outcome().reports())
                    for (var item : report.items())
                        output.append(item.outcome()).append("  ").append(item.source().key()).append(" → ")
                            .append(item.target().map(target -> target.key()).orElse("—")).append("  ").append(item.detail()).append('\n');
                for (var operation : value.outcome().operations()) {
                    output.append(operation.id()).append("  ").append(operation.type()).append("  ").append(operation.phase()).append('\n');
                    for (var step : operation.steps())
                        output.append(step.phase()).append("  ").append(step.source().key())
                            .append(" → ").append(step.target().map(target -> target.key()).orElse("—")).append("  ").append(step.detail()).append('\n');
                }
            }
            var log = logs.get(state.id());
            if (log != null) {
                log.records().stream().sorted(java.util.Comparator.comparing(LogReadResult.Record::recordedAt)).forEach(record ->
                    output.append(record.event().eventCode()).append("  ").append(record.event().message()).append("  ")
                        .append(record.event().details().orElse("")).append(record.truncated() ? " […]" : "").append('\n'));
                log.problem().ifPresent(failure -> output.append(failure.eventCode()).append(": ").append(failure.message()).append('\n'));
            }
        }
        text.setText(output.toString());
        text.setCaretPosition(Math.min(caret, text.getDocument().getLength()));
    }

    /** Blocks view scrolling during host lock. / 宿主锁定时阻断视图滚动。
     * @param enabled whether interaction is allowed / 是否允许交互 */
    void interactive(boolean enabled) {
        text.setEnabled(enabled);
        scroll.setWheelScrollingEnabled(enabled);
        scroll.getVerticalScrollBar().setEnabled(enabled);
        scroll.getHorizontalScrollBar().setEnabled(enabled);
    }
}
