package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;

import java.util.List;
import java.util.Objects;

/** Platform seam for an explicitly offline, manually switched migration. / 显式离线且人工切流迁移的平台接缝。 */
public interface OfflineMigrationPort {
    /** Verifies target ownership, capacity, ports and restore compatibility before transfer. / 在传输前验证目标归属、容量、端口及恢复兼容性。 */
    TargetPreflightEvidence preflightTarget(OfflineMigrationRequest request) throws BackupException;

    /** Copies the first immutable baseline while the source remains active. / 在源端仍运行时复制首个不可变基线。 */
    SyncEvidence initialSync(OfflineMigrationRequest request) throws BackupException;

    /** Stops source writes and returns a verifiable recovery token. / 停止源端写入并返回可验证恢复令牌。 */
    SourceQuiesceEvidence stopSourceWrites(OfflineMigrationRequest request) throws BackupException;

    /** Copies the final delta only after source writes are proven stopped. / 仅在源端写入已确认停止后复制最终增量。 */
    SyncEvidence finalSync(
            OfflineMigrationRequest request, SyncEvidence initial, SourceQuiesceEvidence quiesced)
            throws BackupException;

    /** Restores and verifies the complete target candidate without switching external traffic. / 恢复并验证完整目标候选且不切换外部流量。 */
    TargetCandidateEvidence restoreAndVerifyTarget(
            OfflineMigrationRequest request, SyncEvidence finalSync) throws BackupException;

    /** Removes only the owned uncommitted target candidate. / 仅移除有归属的未提交目标候选。 */
    RecoveryEvidence discardTargetCandidate(OfflineMigrationRequest request) throws BackupException;

    /** Restores and verifies the source after a failed stopped-write migration. / 在停写迁移失败后恢复并验证源端。 */
    RecoveryEvidence recoverSource(
            OfflineMigrationRequest request, SourceQuiesceEvidence quiesced) throws BackupException;

    /** Target preflight evidence. / 目标前置检查证据。 */
    record TargetPreflightEvidence(
            boolean ownershipVerified,
            boolean portsAvailable,
            boolean restoreCompatible,
            long availableBytes,
            List<String> evidence
    ) {
        /** Validates bounded evidence and capacity. / 校验有界证据及容量。 */
        public TargetPreflightEvidence {
            if (availableBytes < 0) throw new IllegalArgumentException("availableBytes must not be negative");
            evidence = checkedEvidence(evidence);
        }
    }

    /** One digest-bound synchronization result. / 单个摘要绑定的同步结果。 */
    record SyncEvidence(
            long byteCount,
            String contentSha256,
            boolean digestVerified,
            boolean sourceWritesStopped,
            List<String> evidence
    ) {
        /** Validates synchronization identity and evidence. / 校验同步身份及证据。 */
        public SyncEvidence {
            if (byteCount < 1) throw new IllegalArgumentException("byteCount must be positive");
            contentSha256 = digest(contentSha256, "contentSha256");
            evidence = checkedEvidence(evidence);
        }
    }

    /** Source stopped-write and recovery-token evidence. / 源端停写及恢复令牌证据。 */
    record SourceQuiesceEvidence(
            boolean writesStopped,
            boolean noActiveWriters,
            String recoveryToken,
            List<String> evidence
    ) {
        /** Requires a bounded recovery token. / 要求有界恢复令牌。 */
        public SourceQuiesceEvidence {
            recoveryToken = identifier(recoveryToken, "recoveryToken");
            evidence = checkedEvidence(evidence);
        }
    }

    /** Fully restored target candidate evidence before manual traffic switching. / 人工切流前完整恢复的目标候选证据。 */
    record TargetCandidateEvidence(
            String candidateId,
            boolean componentsHealthy,
            boolean applicationHealthy,
            boolean externalTrafficUnchanged,
            List<String> evidence
    ) {
        /** Validates target candidate evidence. / 校验目标候选证据。 */
        public TargetCandidateEvidence {
            candidateId = identifier(candidateId, "candidateId");
            evidence = checkedEvidence(evidence);
        }
    }

    /** One cleanup or source recovery result. / 单次清理或源端恢复结果。 */
    record RecoveryEvidence(boolean completed, boolean verified, List<String> evidence) {
        /** Validates recovery evidence. / 校验恢复证据。 */
        public RecoveryEvidence {
            evidence = checkedEvidence(evidence);
        }
    }

    private static String digest(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static List<String> checkedEvidence(List<String> values) {
        Objects.requireNonNull(values, "evidence");
        if (values.isEmpty() || values.size() > 64) {
            throw new IllegalArgumentException("migration evidence is incomplete");
        }
        List<String> result = values.stream().map(value -> {
            String item = Objects.requireNonNull(value, "evidence item").trim();
            if (item.isEmpty() || item.length() > 512 || item.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("migration evidence item is invalid");
            }
            return item;
        }).distinct().toList();
        if (result.size() != values.size()) throw new IllegalArgumentException("migration evidence has duplicates");
        return result;
    }
}
