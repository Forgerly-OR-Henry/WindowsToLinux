package gold.debug.windowstolinux.api.task;

import java.util.List;

/**
 * Exact reviewed effect and revision bound to task resources. / 绑定任务资源的精确待审核副作用与修订。
 * @param type reviewed action category / 已审核动作类别
 * @param serverIds copied distinct server identities admitted with the task / 随任务接纳的互不重复服务器身份副本
 * @param target owned target of the requested operation / 请求操作的所属目标
 * @param revision author-owned business revision identity / 作者所属业务修订标识
 * @param exactAction exact action text covered by approval / 审批覆盖的精确操作文本
 * @param effectSummary description of the proposed external effect / 拟执行的外部副作用说明
 */
public record ActionIntent(String type, List<String> serverIds, String target, String revision, String exactAction,
        String effectSummary) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param type reviewed action category / 已审核动作类别
     * @param serverIds copied distinct server identities admitted with the task / 随任务接纳的互不重复服务器身份副本
     * @param target owned target of the requested operation / 请求操作的所属目标
     * @param revision author-owned business revision identity / 作者所属业务修订标识
     * @param exactAction exact action text covered by approval / 审批覆盖的精确操作文本
     * @param effectSummary description of the proposed external effect / 拟执行的外部副作用说明
     */
    public ActionIntent {
        java.util.Objects.requireNonNull(type, "type");
        serverIds = List.copyOf(serverIds);
        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(exactAction, "exactAction");
        java.util.Objects.requireNonNull(effectSummary, "effectSummary");
    }
}
