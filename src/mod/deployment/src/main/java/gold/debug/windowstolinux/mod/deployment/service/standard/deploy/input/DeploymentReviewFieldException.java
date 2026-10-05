package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input;

import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Identifies an actual invalid input without exposing its entered value in UI diagnostics. / 标识实际无效输入，不在界面诊断暴露输入值。 */
public final class DeploymentReviewFieldException extends IllegalArgumentException {
    /** Exact original input key, never the submitted content. / 精确原输入键，非提交内容。 */
    private final String field;

    /** Safe public rejection containing only the original field identity. / 仅包含原字段身份的安全公开拒绝原因。 */
    private final Problem problem;

    /** Preserves the original validation cause for controlled diagnostics. / 为受控诊断保留原校验原因。
     * @param field original bounded key / 原有界键
     * @param cause original validation failure / 原校验失败
     * @throws IllegalArgumentException if the original field identity is invalid / 原字段身份无效时 */
    public DeploymentReviewFieldException(String field, IllegalArgumentException cause) {
        super("Original deployment review field requires correction", cause);
        if (field == null || !field.matches("[A-Za-z][A-Za-z0-9]{0,127}"))
            throw new IllegalArgumentException("Invalid review field identity");
        this.field = field;
        problem = new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_REVIEW_FIELD_INVALID",
            "Original deployment review field requires correction", Map.of("field", field), Optional.empty());
    }

    /** Returns only the original field identity. / 仅返回原字段身份。
     * @return exact key / 精确键 */
    public String field() {
        return field;
    }

    /** Returns the safe public rejection without the submitted content or parser message. / 返回不含提交内容或解析器消息的安全公开拒绝原因。
     * @return original structured field rejection / 原结构化字段拒绝原因 */
    public Problem problem() {
        return problem;
    }
}
