package gold.debug.windowstolinux.shared.ai.collaboration.invocation;

import gold.debug.windowstolinux.shared.ai.collaboration.advice.RoleAdviceAssessment;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiCollaborationRoleKind;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Credential-free evidence for one explicitly selected role invocation. / 一次显式选择角色调用的不含凭据证据。
 *
 * @param role role / 角色
 * @param providerId provider id / 提供者标识
 * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
 * @param redactedInputSummary redacted input summary / 已脱敏输入摘要
 * @param inputSha256 input sha 256 / 输入SHA256
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param output destination receiving the produced content / 接收所生成内容的目标
 * @param validationDetail validation detail / 校验详情
 * @param observedAt observed at / 已观测时刻
 */
public record AiInvocationEvidence(
        AiCollaborationRoleKind role,
        String providerId,
        String model,
        String redactedInputSummary,
        String inputSha256,
        AiInvocationStatus status,
        Optional<RoleAdviceAssessment> output,
        String validationDetail,
        Instant observedAt
) {
    /**
     * Validates the auditable evidence without retaining provider response bodies. / 验证可审计证据且不保留提供者响应正文。
     *
     * @param role role / 角色
     * @param providerId provider id / 提供者标识
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param redactedInputSummary redacted input summary / 已脱敏输入摘要
     * @param inputSha256 input sha 256 / 输入SHA256
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param validationDetail validation detail / 校验详情
     * @param observedAt observed at / 已观测时刻
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AiInvocationEvidence {
        role = Objects.requireNonNull(role, "role");
        providerId = bounded(providerId, "providerId", 64);
        model = bounded(model, "model", 128);
        redactedInputSummary = bounded(redactedInputSummary, "redactedInputSummary", 8_192);
        inputSha256 = Objects.requireNonNull(inputSha256, "inputSha256");
        if (!inputSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("inputSha256 is invalid");
        status = Objects.requireNonNull(status, "status");
        output = Objects.requireNonNull(output, "output");
        validationDetail = bounded(validationDetail, "validationDetail", 256);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if ((status == AiInvocationStatus.VALIDATED) != output.isPresent()) {
            throw new IllegalArgumentException("validated status and output must agree");
        }
    }

    /**
     * Rejects content exceeding the explicit size or count bound.
     * <p>拒绝超出显式大小或数量限制的内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param maximum maximum / 最大
     * @return bounded text / 有界文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String bounded(String value, String name, int maximum) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank() || value.length() > maximum) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
