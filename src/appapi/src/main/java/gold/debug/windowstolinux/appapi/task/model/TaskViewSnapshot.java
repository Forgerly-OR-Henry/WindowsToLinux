package gold.debug.windowstolinux.appapi.task.model;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.control.TaskControlSnapshot;

/** Display-only projection, independent of durable task outcomes. / 独立于持久任务结果的纯展示投影。
 * @param category visible group / 可见分组
 * @param fraction actual fraction, absent when unknown / 真实比例，未知时为空
 * @param controls original Mod control observation / 原 Mod 控制观察
 * @param pendingInterventions unresolved host review requests / 未决宿主核验请求数 */
public record TaskViewSnapshot(Category category, Optional<Double> fraction, TaskControlSnapshot controls,
                               int pendingInterventions) {
    /** Presentation grouping independent of durable task state. / 独立于持久任务状态的展示分组。 */
    public enum Category {
        /** Original task is progressing. / 原任务正在推进。 */
        RUNNING,
        /** Original input or review is pending. / 原输入或核验待处理。 */
        WAITING_USER,
        /** Original work requires recovery or explicit continuation. / 原工作需要恢复或明确继续。 */
        WAITING_RECOVERY
    }

    /** Checks presentation bounds without asserting business completion. / 检查展示范围，不断言业务完成。
     * @param category display group / 展示分组
     * @param fraction actual progress or unknown / 真实进度或未知
     * @param controls original author controls / 原作者控制
     * @param pendingInterventions unresolved host requests / 未决宿主请求 */
    public TaskViewSnapshot {
        java.util.Objects.requireNonNull(category);
        java.util.Objects.requireNonNull(fraction);
        java.util.Objects.requireNonNull(controls);
        if (pendingInterventions < 0) throw new IllegalArgumentException("Negative intervention count");
        if (fraction.isPresent() && (!Double.isFinite(fraction.get()) || fraction.get() < 0 || fraction.get() > 1))
            throw new IllegalArgumentException("Invalid task progress fraction");
    }

    /** Creates a task projection without pending host review. / 创建无待处理宿主核验的任务投影。
     * @param category visible group / 可见分组
     * @param fraction actual progress or unknown / 真实进度或未知
     * @param controls original control observation / 原控制观察 */
    public TaskViewSnapshot(Category category, Optional<Double> fraction, TaskControlSnapshot controls) {
        this(category, fraction, controls, 0);
    }
}
