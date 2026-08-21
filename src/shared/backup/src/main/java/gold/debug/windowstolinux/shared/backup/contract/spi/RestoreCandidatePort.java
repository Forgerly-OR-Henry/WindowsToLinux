package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Platform/deploy seam for staging, checking, committing and recovering restored files. / 暂存、检查、提交和恢复已还原文件的平台及部署接缝。 */
public interface RestoreCandidatePort {
    /** Stages all non-database content without changing the current release. / 暂存全部非数据库内容且不改变当前发布。 */
    FileEvidence stageFiles(RestoreCandidateRequest request) throws BackupException;

    /** Checks every restored component against the candidate database token. / 结合候选数据库令牌检查每个恢复组件。 */
    HealthEvidence verifyComponents(RestoreCandidateRequest request, FileEvidence files, Optional<String> databaseToken)
            throws BackupException;

    /** Checks the whole-application business health gate. / 检查整应用业务健康门。 */
    HealthEvidence verifyApplication(RestoreCandidateRequest request, FileEvidence files, Optional<String> databaseToken)
            throws BackupException;

    /** Atomically commits the fully verified candidate and retains the previous release. / 原子提交完整已验证候选并保留旧发布。 */
    CommitEvidence commit(RestoreCandidateRequest request, FileEvidence files, Optional<String> databaseToken)
            throws BackupException;

    /** Removes an uncommitted file candidate and verifies the existing release. / 移除未提交文件候选并验证现有发布。 */
    RecoveryEvidence recoverExisting(RestoreCandidateRequest request, Optional<FileEvidence> files)
            throws BackupException;

    /** Isolated staged-file evidence. / 隔离暂存文件证据。 */
    record FileEvidence(
            String candidateId,
            String candidateToken,
            long stagedBytes,
            boolean isolated,
            boolean integrityVerified,
            boolean existingReleaseUntouched,
            List<String> evidence
    ) {
        /** Validates bounded stage evidence. / 校验有界暂存证据。 */
        public FileEvidence {
            candidateId = id(candidateId, "candidateId");
            candidateToken = id(candidateToken, "candidateToken");
            if (stagedBytes < 0) throw new IllegalArgumentException("stagedBytes must not be negative");
            evidence = validatedEvidence(evidence);
        }
    }

    /** Component or whole-application health evidence. / 组件或整应用健康证据。 */
    record HealthEvidence(boolean healthy, List<String> evidence) {
        /** Validates bounded health evidence. / 校验有界健康证据。 */
        public HealthEvidence {
            evidence = validatedEvidence(evidence);
        }
    }

    /** Commit evidence retaining a rollback point. / 保留回滚点的提交证据。 */
    record CommitEvidence(
            boolean committed,
            boolean previousReleaseRetained,
            String activeReleaseToken,
            List<String> evidence
    ) {
        /** Validates bounded commit evidence. / 校验有界提交证据。 */
        public CommitEvidence {
            activeReleaseToken = id(activeReleaseToken, "activeReleaseToken");
            evidence = validatedEvidence(evidence);
        }
    }

    /** Failed-candidate cleanup and existing-release verification evidence. / 失败候选清理及现有发布验证证据。 */
    record RecoveryEvidence(boolean candidateRemoved, boolean existingReleaseVerified, List<String> evidence) {
        /** Validates bounded recovery evidence. / 校验有界恢复证据。 */
        public RecoveryEvidence {
            evidence = validatedEvidence(evidence);
        }
    }

    private static String id(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static List<String> validatedEvidence(List<String> values) {
        Objects.requireNonNull(values, "evidence");
        if (values.isEmpty() || values.size() > 64) throw new IllegalArgumentException("restore evidence is incomplete");
        List<String> result = values.stream().map(value -> {
            String item = Objects.requireNonNull(value, "evidence item").trim();
            if (item.isEmpty() || item.length() > 512 || item.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("restore evidence item is invalid");
            }
            return item;
        }).distinct().toList();
        if (result.size() != values.size()) throw new IllegalArgumentException("restore evidence contains duplicates");
        return result;
    }
}
