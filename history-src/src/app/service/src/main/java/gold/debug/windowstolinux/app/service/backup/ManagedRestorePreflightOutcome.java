package gold.debug.windowstolinux.app.service.backup;

import java.util.List;
import java.util.Objects;

/**
 * Read-only target evidence collected before any restore input is staged. / 在暂存任何恢复输入前收集的只读目标证据。
 *
 * @param targetServerId target server id / 目标服务器标识
 * @param applicationId managed application identifier / 受管应用标识
 * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
 * @param availableBytes available bytes / 可用字节
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record ManagedRestorePreflightOutcome(String targetServerId, String applicationId, String archiveSha256,
        long availableBytes, List<String> evidence) {
    /**
     * Requires complete bounded preflight evidence. / 要求完整且有界的前置证据。
     *
     * @param targetServerId target server id / 目标服务器标识
     * @param applicationId managed application identifier / 受管应用标识
     * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
     * @param availableBytes available bytes / 可用字节
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedRestorePreflightOutcome {
        targetServerId = identifier(targetServerId, "targetServerId");
        applicationId = identifier(applicationId, "applicationId");
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256");
        if (!archiveSha256.matches("[0-9a-f]{64}") || availableBytes < 0) {
            throw new IllegalArgumentException("restore preflight identity or capacity is invalid");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 64 || evidence.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("restore preflight evidence is invalid");
        }
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
