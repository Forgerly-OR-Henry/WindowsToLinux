package gold.debug.windowstolinux.appapi.task.action;

import java.util.Set;

/** Business-validated operation meaning and actual targets bound to an exact effect. / 绑定精确副作用的、经业务校验的操作含义及实际目标。
 * @param operation stable versioned operation identifier, not display text / 稳定且带语义版本的操作标识，不是显示文字
 * @param targets complete resolved target set / 完整已解析目标集合 */
public record ActionSemantics(String operation, Set<ActionTarget> targets) {
    /** Copies the complete semantic key without fuzzy matching. / 复制完整语义键，不进行模糊匹配。
     * @param operation stable operation identity / 稳定操作身份
     * @param targets actual target set / 实际目标集合 */
    public ActionSemantics {
        if (operation == null || !operation.matches("[a-z][a-z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Invalid semantic operation identity");
        targets = Set.copyOf(targets);
        if (targets.isEmpty() || targets.size() > 128)
            throw new IllegalArgumentException("A bounded actual target set is required");
    }
}
