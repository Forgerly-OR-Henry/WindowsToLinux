package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.List;

/**
 * Activated database candidate with a retained rollback point. / 已激活且保留回滚点的数据库候选。
 *
 * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
 * @param committed committed / 已提交
 * @param previousDatabaseRetained previous database retained / 此前数据库已保留
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record DatabaseCommitEvidence(String candidateId, boolean committed, boolean previousDatabaseRetained,
        List<String> evidence) {
    /**
     * Requires a complete committed identity and bounded evidence. / 要求完整提交身份及有界证据。
     *
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param committed committed / 已提交
     * @param previousDatabaseRetained previous database retained / 此前数据库已保留
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public DatabaseCommitEvidence {
        candidateId = DatabaseContractRules.identifier(candidateId, "candidateId");
        evidence = DatabaseContractRules.evidence(evidence);
        if (!committed || !previousDatabaseRetained) {
            throw new IllegalArgumentException("database commit evidence is incomplete");
        }
    }
}
