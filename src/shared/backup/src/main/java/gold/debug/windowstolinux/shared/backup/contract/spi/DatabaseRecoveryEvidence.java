package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.List;

/** Database rollback or uncommitted-candidate cleanup evidence. / 数据库回滚或未提交候选清理证据。 */
public record DatabaseRecoveryEvidence(
        String candidateId,
        boolean recovered,
        boolean previousDatabaseVerified,
        boolean candidateRemoved,
        List<String> evidence
) {
    /** Requires verified recovery and candidate absence. / 要求已验证恢复及候选不存在。 */
    public DatabaseRecoveryEvidence {
        candidateId = DatabaseContractRules.identifier(candidateId, "candidateId");
        evidence = DatabaseContractRules.evidence(evidence);
        if (!recovered || !previousDatabaseVerified || !candidateRemoved) {
            throw new IllegalArgumentException("database recovery evidence is incomplete");
        }
    }
}
