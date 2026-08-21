package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.List;

/** Verified but not yet activated database restore candidate. / 已验证但尚未激活的数据库恢复候选。 */
public record DatabaseRestoreEvidence(
        String candidateId,
        String connectionToken,
        boolean integrityVerified,
        boolean schemaReadable,
        List<String> evidence
) {
    /** Requires complete candidate verification. / 要求完整候选校验。 */
    public DatabaseRestoreEvidence {
        candidateId = DatabaseContractRules.identifier(candidateId, "candidateId");
        connectionToken = DatabaseContractRules.identifier(connectionToken, "connectionToken");
        evidence = DatabaseContractRules.evidence(evidence);
        if (!integrityVerified || !schemaReadable) {
            throw new IllegalArgumentException("unverified database candidate cannot be returned as restore evidence");
        }
    }
}
