package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretDocument;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidation;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreCandidate;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Short-lived validated activation material whose secret revisions are cleared on close. / 关闭时清零秘密修订的短生命周期已验证激活材料。 */
record PreparedBackupActivation(
        BackupArchiveValidation validation,
        BackupRestoreCandidate restoreCandidate,
        PreparedBackupCandidate localCandidate,
        Optional<BackupSecretDocument> secretDocument
) implements AutoCloseable {
    PreparedBackupActivation {
        validation = Objects.requireNonNull(validation, "validation");
        restoreCandidate = Objects.requireNonNull(restoreCandidate, "restoreCandidate");
        localCandidate = Objects.requireNonNull(localCandidate, "localCandidate");
        secretDocument = Objects.requireNonNull(secretDocument, "secretDocument");
        if (secretDocument.isPresent() != !validation.manifest().inventory().secretReferences().isEmpty()) {
            throw new IllegalArgumentException("activation secret presence differs from the manifest");
        }
    }

    List<ResolvedSecretRevision> secrets() {
        return secretDocument.map(BackupSecretDocument::revisions).orElseGet(List::of);
    }

    @Override public void close() { secretDocument.ifPresent(BackupSecretDocument::close); }
}
