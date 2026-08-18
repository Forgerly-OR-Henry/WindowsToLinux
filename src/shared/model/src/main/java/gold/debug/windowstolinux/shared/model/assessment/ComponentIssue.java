package gold.debug.windowstolinux.shared.model.assessment;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.List;
import java.util.Objects;

/**
 * One component-scoped input requirement or hard safety rejection.
 *
 * <p>一个组件范围的输入要求或硬安全拒绝。
 *
 * @param severity issue severity / 问题严重性
 * @param code stable machine-readable code / 稳定机器可读代码
 * @param componentIds affected component identifiers / 受影响的组件标识符
 * @param message localized explanation / 本地化说明
 */
public record ComponentIssue(Severity severity, String code, List<String> componentIds, LocalizedMessage message) {
    /** Validates one component-scoped issue. / 验证一个组件范围问题。 */
    public ComponentIssue {
        severity = Objects.requireNonNull(severity, "severity");
        code = Objects.requireNonNull(code, "code").trim();
        if (!code.matches("[A-Z][A-Z0-9_]{2,63}")) throw new IllegalArgumentException("invalid component issue code");
        componentIds = List.copyOf(Objects.requireNonNull(componentIds, "componentIds").stream().sorted().toList());
        if (componentIds.isEmpty() || componentIds.stream().anyMatch(value -> !value.matches("[a-z0-9][a-z0-9-]{0,62}"))) {
            throw new IllegalArgumentException("component issues require bounded component identifiers");
        }
        message = Objects.requireNonNull(message, "message");
    }

    /** Component issue severity. / 组件问题严重性。 */
    public enum Severity {
        /** Missing deterministic user input. / 缺少确定性用户输入。 */
        REQUIRES_INPUT,
        /** Unsafe contradiction that stops target mutation. / 阻止目标修改的不安全矛盾。 */
        SAFETY_REJECTION
    }
}
