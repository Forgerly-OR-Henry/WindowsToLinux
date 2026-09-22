package gold.debug.windowstolinux.app.windows.update;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Pinned release trust root and local update policy. / 固定发布信任根及本地更新策略。
 *
 * @param currentVersion current version / 当前版本
 * @param currentArchitecture current architecture / 当前架构
 * @param trustedKeyId trusted key id / 已信任键标识
 * @param trustedPublicKey trusted public key / 已信任公共键
 * @param revokedReleaseIds revoked release ids / 已撤销发布标识集合
 * @param verificationTime verification time / 验证时间
 * @param emergencyRollbackApproved emergency rollback approved / 紧急回滚已批准
 */
public record DesktopUpdateTrustPolicy(DesktopReleaseVersion currentVersion,
        DesktopArchitectureType currentArchitecture, String trustedKeyId, PublicKey trustedPublicKey,
        Set<String> revokedReleaseIds, Instant verificationTime, boolean emergencyRollbackApproved) {
    /**
     * Validates a pinned Ed25519 key and immutable revocation set. / 校验固定 Ed25519 密钥及不可变撤销集合。
     *
     * @param currentVersion current version / 当前版本
     * @param currentArchitecture current architecture / 当前架构
     * @param trustedKeyId trusted key id / 已信任键标识
     * @param trustedPublicKey trusted public key / 已信任公共键
     * @param revokedReleaseIds revoked release ids / 已撤销发布标识集合
     * @param verificationTime verification time / 验证时间
     * @param emergencyRollbackApproved emergency rollback approved / 紧急回滚已批准
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUpdateTrustPolicy {
        currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        currentArchitecture = Objects.requireNonNull(currentArchitecture, "currentArchitecture");
        trustedKeyId = identifier(trustedKeyId, "trustedKeyId");
        trustedPublicKey = Objects.requireNonNull(trustedPublicKey, "trustedPublicKey");
        if (!trustedPublicKey.getAlgorithm().equalsIgnoreCase("EdDSA")
                && !trustedPublicKey.getAlgorithm().equalsIgnoreCase("Ed25519")) {
            throw new IllegalArgumentException("desktop update trust root must be Ed25519");
        }
        revokedReleaseIds = Set.copyOf(Objects.requireNonNull(revokedReleaseIds, "revokedReleaseIds"));
        revokedReleaseIds.forEach(value -> identifier(value, "revokedReleaseId"));
        verificationTime = Objects.requireNonNull(verificationTime, "verificationTime");
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
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
