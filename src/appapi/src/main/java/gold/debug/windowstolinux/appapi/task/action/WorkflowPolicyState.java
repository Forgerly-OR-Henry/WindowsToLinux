package gold.debug.windowstolinux.appapi.task.action;

import java.util.Objects;

/** Confirmed policy of one live host-issued workflow grant. / 一项活动宿主流程许可的已确认策略。
 * @param mode effective review mode / 已生效审核模式
 * @param revision scope-local revision, initially zero / 作用域内修订，初始为零 */
public record WorkflowPolicyState(CommandReviewMode mode, long revision) {
    public WorkflowPolicyState {
        Objects.requireNonNull(mode);
        if (revision < 0) throw new IllegalArgumentException("Negative workflow policy revision");
    }
}
