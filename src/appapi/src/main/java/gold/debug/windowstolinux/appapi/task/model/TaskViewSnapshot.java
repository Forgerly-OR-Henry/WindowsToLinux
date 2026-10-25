package gold.debug.windowstolinux.appapi.task.model;

import java.util.Optional;
import gold.debug.windowstolinux.appapi.task.control.TaskControlSnapshot;

/** Display-only projection, independent of durable task outcomes. / 独立于持久任务结果的纯展示投影。
 * @param category visible group / 可见分组
 * @param fraction actual fraction, absent when unknown / 真实比例，未知时为空
 * @param controls original Mod control observation / 原 Mod 控制观察 */
public record TaskViewSnapshot(Category category, Optional<Double> fraction, TaskControlSnapshot controls) {
    public enum Category { RUNNING, WAITING_USER, WAITING_RECOVERY }

    public TaskViewSnapshot {
        java.util.Objects.requireNonNull(category);
        java.util.Objects.requireNonNull(fraction);
        java.util.Objects.requireNonNull(controls);
        if (fraction.isPresent() && (!Double.isFinite(fraction.get()) || fraction.get() < 0 || fraction.get() > 1))
            throw new IllegalArgumentException("Invalid task progress fraction");
    }
}
