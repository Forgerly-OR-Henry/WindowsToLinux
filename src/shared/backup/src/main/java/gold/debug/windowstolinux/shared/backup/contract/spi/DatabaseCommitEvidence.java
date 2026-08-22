package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.List;

/** Activated database candidate with a retained rollback point. / 已激活且保留回滚点的数据库候选。 */
public record DatabaseCommitEvidence(
        String candidateId,
        boolean committed,
        boolean previousDatabaseRetained,
        List<String> evidence
) {
    /** Requires a complete committed identity and bounded evidence. / 要求完整提交身份及有界证据。 */
    public DatabaseCommitEvidence {
        candidateId = DatabaseContractRules.identifier(candidateId, "candidateId");
        evidence = DatabaseContractRules.evidence(evidence);
        if (!committed || !previousDatabaseRetained) {
            throw new IllegalArgumentException("database commit evidence is incomplete");
        }
    }
}
