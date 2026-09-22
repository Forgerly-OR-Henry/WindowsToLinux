package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable identity and safety approvals for one offline migration attempt. / 单次离线迁移尝试的不可变身份及安全批准。
 *
 * @param migrationId migration id / 迁移标识
 * @param applicationId managed application identifier / 受管应用标识
 * @param sourceServerId source server id / 源码服务器标识
 * @param targetServerId target server id / 目标服务器标识
 * @param estimatedBytes estimated bytes / 估计字节
 * @param stopWindowApproved stop window approved / 停止窗口已批准
 */
public record OfflineMigrationRequest(String migrationId, String applicationId, String sourceServerId,
        String targetServerId, long estimatedBytes, boolean stopWindowApproved) {
    /**
     * Validates distinct endpoints before the stopped-write final archive exists. / 在停写最终归档产生前校验不同端点。
     *
     * @param migrationId migration id / 迁移标识
     * @param applicationId managed application identifier / 受管应用标识
     * @param sourceServerId source server id / 源码服务器标识
     * @param targetServerId target server id / 目标服务器标识
     * @param estimatedBytes estimated bytes / 估计字节
     * @param stopWindowApproved stop window approved / 停止窗口已批准
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public OfflineMigrationRequest {
        migrationId = identifier(migrationId, "migrationId");
        applicationId = identifier(applicationId, "applicationId");
        sourceServerId = identifier(sourceServerId, "sourceServerId");
        targetServerId = identifier(targetServerId, "targetServerId");
        if (sourceServerId.equals(targetServerId)) {
            throw new IllegalArgumentException("offline migration requires different source and target servers");
        }
        if (estimatedBytes < 1)
            throw new IllegalArgumentException("estimatedBytes must be positive");
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
        value = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
