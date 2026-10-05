package gold.debug.windowstolinux.mod.files.file.model;

import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.Problem;

/** Already-returned callback facts independent of final task cleanup. / 独立于任务最终清理的已返回回调事实。
 * @param outcome reports and journal actually observed so far / 至今实际观测的报告及日志
 * @param problem actual failure or unresolved evidence access / 实际失败或未解决的依据访问
 * @param callbackReturned whether author work has returned, without claiming task completion / 作者逻辑是否已返回，不声称任务结束 */
public record FilesObservation(FilesOutcome outcome, Optional<Problem> problem, boolean callbackReturned) {
    /** Retains immutable evidence. / 保留不可变依据。
     * @param outcome actual observations / 实际观测
     * @param problem actual diagnostic / 实际诊断
     * @param callbackReturned actual callback state / 实际回调状态 */
    public FilesObservation {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(problem);
    }
}
