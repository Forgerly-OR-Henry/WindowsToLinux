package gold.debug.windowstolinux.appapi.task;

import java.util.List;
import java.util.Optional;

/**
 * Exact reviewed effect and revision bound to task resources. / 绑定任务资源的精确待审核副作用与修订。
 * @param type reviewed action category / 已审核动作类别
 * @param serverIds copied distinct server identities admitted with the task / 随任务接纳的互不重复服务器身份副本
 * @param target owned target of the requested operation / 请求操作的所属目标
 * @param revision author-owned business revision identity / 作者所属业务修订标识
 * @param exactAction exact action text covered by approval / 审批覆盖的精确操作文本
 * @param effectSummary description of the proposed external effect / 拟执行的外部副作用说明
 * @param semantics optional explicit business-validated operation and targets / 可选明确的经业务校验操作及目标
 */
public record ActionIntent(String type, List<String> serverIds, String target, String revision, String exactAction,
                           String effectSummary, Optional<ActionSemantics> semantics) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param type reviewed action category / 已审核动作类别
     * @param serverIds copied distinct server identities admitted with the task / 随任务接纳的互不重复服务器身份副本
     * @param target owned target of the requested operation / 请求操作的所属目标
     * @param revision author-owned business revision identity / 作者所属业务修订标识
     * @param exactAction exact action text covered by approval / 审批覆盖的精确操作文本
     * @param effectSummary description of the proposed external effect / 拟执行的外部副作用说明
     * @param semantics explicit business meaning, absent for unclassified actions / 明确业务含义，未分类动作时为空
     */
    public ActionIntent {
        java.util.Objects.requireNonNull(type, "type");
        serverIds = List.copyOf(serverIds);
        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(exactAction, "exactAction");
        java.util.Objects.requireNonNull(effectSummary, "effectSummary");
        java.util.Objects.requireNonNull(semantics, "semantics");
    }

    /** Describes an unclassified effect that still requires individual review. / 描述仍须逐次审核的未分类副作用。
     * @param type action category / 动作类别
     * @param serverIds admitted servers / 准入服务器
     * @param target exact effect target / 精确副作用目标
     * @param revision business revision / 业务修订
     * @param exactAction complete effect / 完整副作用
     * @param effectSummary readable effect description / 可读副作用描述 */
    public ActionIntent(String type, List<String> serverIds, String target, String revision, String exactAction,
                        String effectSummary) {
        this(type, serverIds, target, revision, exactAction, effectSummary, Optional.empty());
    }

    /** Attaches validated business meaning while preserving every exact effect field. / 附加已校验业务含义并保留每个精确副作用字段。
     * @param value meaning derived from the resolved business operation / 从已解析业务操作得到的含义
     * @return immutable classified intent / 不可变已分类意图 */
    public ActionIntent withSemantics(ActionSemantics value) {
        return new ActionIntent(type, serverIds, target, revision, exactAction, effectSummary, Optional.of(value));
    }
}
