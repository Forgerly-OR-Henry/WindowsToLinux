package gold.debug.windowstolinux.mod.deployment.ui;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskState;

import java.awt.BorderLayout;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.swing.*;


import gold.debug.windowstolinux.mod.deployment.service.agent.*;

/** Displays original evidence and explicit safe-boundary controls without holding task capabilities. / 展示原依据及明确安全边界控件，不持有任务能力。 */
final class DeploymentAgentEvidencePanel {
    /** Actual owner operations. / 实际所有者操作。 */
    private final DeploymentAgentPageService service;

    /** Owning page executor. / 所属页面执行器。 */
    private final Executor actions;

    /** Current original worker identity only. / 仅当前原工作线程身份。 */
    private final Supplier<UUID> current;

    /** Actual panel. / 实际面板。 */
    private final JPanel root = new JPanel(new BorderLayout(4, 4));

    /** Selected original business records. / 所选原业务记录。 */
    private final JComboBox<DeploymentAgentHistory.Entry> entries = new JComboBox<>();

    /** Complete bounded event view. / 完整有界事件视图。 */
    private final JTextArea detail = new JTextArea(8, 60);

    /** Exact localized controls. / 精确本地化控件。 */
    private final Map<String, JButton> buttons = new LinkedHashMap<>();

    /** Current locale. / 当前语言。 */
    private ResourceBundle messages;

    /** Revoked or locked view. / 已撤销或锁定视图。 */
    private boolean unavailable;

    /** Query generation prevents stale updates. / 查询代次阻止过时更新。 */
    private long generation;

    /** Creates the actual task controls and evidence view. / 创建实际任务控件及依据视图。
     * @param service actual owner / 实际所有者
     * @param actions original background actions / 原后台动作
     * @param current actual live identity supplier / 实际活动身份提供器 */
    DeploymentAgentEvidencePanel(DeploymentAgentPageService service, Executor actions, Supplier<UUID> current) {
        this.service = service;
        this.actions = actions;
        this.current = current;
        var controls = new JPanel();
        for (String key : List.of("pause", "resume", "refreshModels", "history", "events", "inspect")) {
            var button = new JButton();
            buttons.put(key, button);
            controls.add(button);
            button.addActionListener(ignored -> perform(key));
        }
        entries.setRenderer((list, value, index, selected, focus) -> new JLabel(
            value == null ? "" : value.id() + "  " + value.state() + "  " + value.updatedAt()));
        var north = new JPanel(new BorderLayout());
        north.add(controls, BorderLayout.NORTH);
        north.add(entries, BorderLayout.SOUTH);
        detail.setEditable(false);
        root.add(north, BorderLayout.NORTH);
        root.add(new JScrollPane(detail), BorderLayout.CENTER);
    }

    /** Returns the actual owned component. / 返回实际所属组件。
     * @return panel / 面板 */
    JComponent component() {
        return root;
    }

    /** Applies current locale and admission without mutating an original task. / 应用当前语言及准入，不修改原任务。
     * @param messages actual resource bundle / 实际资源包
     * @param unavailable lock or closed state / 锁定或关闭状态 */
    void configure(ResourceBundle messages, boolean unavailable) {
        this.messages = messages;
        this.unavailable = unavailable;
        generation++;
        buttons.forEach((key, button) -> {
            button.setText(messages.getString(key));
            button.setEnabled(!unavailable);
        });
        entries.setEnabled(!unavailable);
    }

    /** Starts one explicitly selected real operation. / 启动一项明确选择的真实操作。
     * @param key selected operation / 所选操作 */
    private void perform(String key) {
        if (unavailable)
            return;
        var selected = (DeploymentAgentHistory.Entry) entries.getSelectedItem();
        UUID live = current.get();
        long view = generation;
        actions.execute(() -> {
            try {
                switch (key) {
                    case "pause", "resume", "refreshModels" -> {
                        if (live == null)
                            throw new IllegalStateException("No original live worker");
                        service.control(live, switch (key) {
                            case "pause" -> DeploymentAgentControl.CommandAction.PAUSE;
                            case "resume" -> DeploymentAgentControl.CommandAction.RESUME;
                            default -> DeploymentAgentControl.CommandAction.REFRESH_MODELS;
                        });
                        show(view, key + " " + service.controlState(live).orElseThrow());
                    }
                    case "history" -> observe(service.history(), view, values -> {
                        entries.removeAllItems();
                        values.forEach(entries::addItem);
                    });
                    case "events" -> {
                        if (selected == null)
                            throw new IllegalStateException("Select original history");
                        observe(service.events(selected.id()), view,
                            value -> detail.setText(value.entry() + "\n"
                                + value.events().stream()
                                .map(event -> event.id() + " " + event.type() + " " + event.action()
                                    + " " + event.detail())
                                .collect(java.util.stream.Collectors.joining("\n"))));
                    }
                    case "inspect" -> inspect(selected, view);
                    default -> throw new IllegalArgumentException("Unknown agent control");
                }
            } catch (Exception failure) {
                show(view, diagnostic(failure));
            }
        });
    }

    /** Separates metadata request from actual terminal readonly inspection. / 区分元数据请求与实际终态只读检查。
     * @param selected exact original row / 精确原行
     * @param view actual generation / 实际代次
     * @throws Exception if original ownership or admission fails / 原所有权或准入失败时 */
    private void inspect(DeploymentAgentHistory.Entry selected, long view) throws Exception {
        if (selected == null)
            throw new IllegalStateException("Select original history");
        boolean retained = service.tasks().stream()
            .anyMatch(task -> task.id().equals(selected.id()) && task.state() == TaskState.UNKNOWN);
        if (retained)
            observe(service.requestInspection(selected.id()), view, request -> detail
                .setText(messages.getString("inspectWaiting") + "\n" + selected.id() + " / " + request));
        else
            observe(service.inspect(selected), view, facts -> detail.setText(facts.toString()));
    }

    /** Displays only the actual callback result; UNKNOWN remains visible without pretending completion. / 仅展示实际回调结果，UNKNOWN保持可见，不伪称完成。
     * @param task actual query / 实际查询
     * @param view view generation / 视图代次
     * @param rendered EDT result renderer / EDT结果渲染器
     * @param <T> actual returned type / 实际返回类型 */
    private <T> void observe(TaskHandle<T> task, long view, java.util.function.Consumer<T> rendered) {
        task.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (unavailable || generation != view)
                return;
            if (failure != null)
                detail.setText(diagnostic(failure));
            else if (result.outcome() == Outcome.SUCCEEDED)
                rendered.accept(result.value().orElseThrow());
            else
                detail.setText(result.problem().map(this::diagnostic).orElse(result.outcome().toString()));
        }));
    }

    /** Displays only classified public evidence, never raw exception details. / 仅展示已分类公开依据，不展示原始异常细节。
     * @param failure actual callback failure / 实际回调失败
     * @return safe localized diagnostic / 安全本地化诊断 */
    private String diagnostic(Throwable failure) {
        if ((failure instanceof java.util.concurrent.CompletionException
            || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return failure instanceof ApiException api ? diagnostic(api.problem()) : messages.getString("evidenceError");
    }

    /** Renders a public event and its original diagnostic identity. / 渲染公开事件及其原诊断身份。
     * @param problem classified public problem / 已分类公开问题
     * @return public evidence / 公开依据 */
    private String diagnostic(Problem problem) {
        return problem.eventCode() + problem.diagnosticId().map(id -> " / " + id).orElse("");
    }

    /** Posts visible control facts only to the current EDT view. / 仅向当前EDT视图提交可见控制事实。
     * @param view original generation / 原代次
     * @param value actual fact / 实际事实 */
    private void show(long view, String value) {
        SwingUtilities.invokeLater(() -> {
            if (!unavailable && generation == view)
                detail.setText(value);
        });
    }
}
