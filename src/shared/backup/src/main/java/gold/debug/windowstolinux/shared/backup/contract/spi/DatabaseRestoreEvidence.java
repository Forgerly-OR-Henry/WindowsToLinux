package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.List;

/**
 * Verified but not yet activated database restore candidate. / 已验证但尚未激活的数据库恢复候选。
 *
 * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
 * @param connectionToken connection token / 连接令牌
 * @param integrityVerified integrity verified / 完整性已验证
 * @param schemaReadable schema readable / 结构可读
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record DatabaseRestoreEvidence(
        String candidateId,
        String connectionToken,
        boolean integrityVerified,
        boolean schemaReadable,
        List<String> evidence
) {
    /**
     * Requires complete candidate verification. / 要求完整候选校验。
     *
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param connectionToken connection token / 连接令牌
     * @param integrityVerified integrity verified / 完整性已验证
     * @param schemaReadable schema readable / 结构可读
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public DatabaseRestoreEvidence {
        candidateId = DatabaseContractRules.identifier(candidateId, "candidateId");
        connectionToken = DatabaseContractRules.identifier(connectionToken, "connectionToken");
        evidence = DatabaseContractRules.evidence(evidence);
        if (!integrityVerified || !schemaReadable) {
            throw new IllegalArgumentException("unverified database candidate cannot be returned as restore evidence");
        }
    }
}
