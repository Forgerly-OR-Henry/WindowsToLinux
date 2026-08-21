package gold.debug.windowstolinux.shared.backup.restore;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.RestoreCandidateRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidation;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Immutable restore plan binding validated local content to one isolated target candidate. / 将已验证本地内容绑定到单个隔离目标候选的不可变恢复计划。 */
public record BackupRestorePlan(
        BackupArchiveValidation validation,
        BackupRestoreCandidate candidate,
        Path localCandidateParent,
        String candidateId,
        RestoreMaterialKind materialKind,
        RestoreTargetProfile target,
        Optional<DatabaseRestoreRequest> databaseRestore
) {
    /** Validates archive, candidate, database and application identity binding. / 校验归档、候选、数据库和应用身份绑定。 */
    public BackupRestorePlan {
        validation = Objects.requireNonNull(validation, "validation");
        candidate = Objects.requireNonNull(candidate, "candidate");
        localCandidateParent = Objects.requireNonNull(localCandidateParent, "localCandidateParent")
                .toAbsolutePath().normalize();
        if (!candidate.root().getParent().equals(localCandidateParent)) {
            throw new IllegalArgumentException("restore candidate is outside its controlled parent");
        }
        if (!candidate.manifest().equals(validation.manifest())
                || candidate.extractedBytes() != validation.verifiedBytes()) {
            throw new IllegalArgumentException("restore candidate differs from archive validation evidence");
        }
        String applicationId = validation.manifest().applicationId();
        candidateId = Objects.requireNonNull(candidateId, "candidateId").trim();
        String expectedCandidateId = applicationId + "-" + validation.archiveSha256().substring(0, 16);
        if (!candidateId.equals(expectedCandidateId)) {
            throw new IllegalArgumentException("candidateId is not bound to the backup digest");
        }
        if (!candidate.root().getFileName().toString().equals(candidateId)) {
            throw new IllegalArgumentException("candidate root differs from candidateId");
        }
        materialKind = Objects.requireNonNull(materialKind, "materialKind");
        target = Objects.requireNonNull(target, "target");
        databaseRestore = Objects.requireNonNull(databaseRestore, "databaseRestore");
        BackupDatabaseType databaseType = validation.manifest().inventory().database().type();
        if (databaseType == BackupDatabaseType.NONE != databaseRestore.isEmpty()) {
            throw new IllegalArgumentException("database restore presence differs from the manifest");
        }
        if (databaseRestore.isPresent()) {
            DatabaseRestoreRequest request = databaseRestore.orElseThrow();
            if (!request.applicationId().equals(applicationId) || !request.candidateId().equals(candidateId)
                    || !request.artifact().database().equals(validation.manifest().inventory().database())) {
                throw new IllegalArgumentException("database restore is not bound to the backup evidence");
            }
        }
    }

    /** Projects the validated plan onto the narrow platform/deploy seam. / 将已验证计划投影为平台及部署窄接缝。 */
    public RestoreCandidateRequest candidateRequest() {
        return new RestoreCandidateRequest(validation.manifest(), candidate.root(), validation.archiveSha256(),
                validation.verifiedBytes(), candidateId, target.serverId(),
                materialKind == RestoreMaterialKind.SOURCE_REBUILD);
    }
}
