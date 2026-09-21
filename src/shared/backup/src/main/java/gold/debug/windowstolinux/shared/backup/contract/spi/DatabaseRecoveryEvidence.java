package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.List;

/**
 * Database rollback or uncommitted-candidate cleanup evidence. / 数据库回滚或未提交候选清理证据。
 *
 * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
 * @param recovered recovered / 已恢复
 * @param previousDatabaseVerified previous database verified / 此前数据库已验证
 * @param candidateRemoved candidate removed / 候选已移除
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record DatabaseRecoveryEvidence(
        String candidateId,
        boolean recovered,
        boolean previousDatabaseVerified,
        boolean candidateRemoved,
        List<String> evidence
) {
    /**
     * Requires verified recovery and candidate absence. / 要求已验证恢复及候选不存在。
     *
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param recovered recovered / 已恢复
     * @param previousDatabaseVerified previous database verified / 此前数据库已验证
     * @param candidateRemoved candidate removed / 候选已移除
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public DatabaseRecoveryEvidence {
        candidateId = DatabaseContractRules.identifier(candidateId, "candidateId");
        evidence = DatabaseContractRules.evidence(evidence);
        if (!recovered || !previousDatabaseVerified || !candidateRemoved) {
            throw new IllegalArgumentException("database recovery evidence is incomplete");
        }
    }
}
