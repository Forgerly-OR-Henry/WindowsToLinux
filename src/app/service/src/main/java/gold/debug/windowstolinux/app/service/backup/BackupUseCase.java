package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.windows.workspace.WindowsRestoreAttempt;
import gold.debug.windowstolinux.app.windows.workspace.WindowsRestoreWorkspace;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchivePolicy;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidation;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidator;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.restore.BackupArchiveExtractor;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreCandidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Local inspection and isolated extraction use case that performs no remote mutation. / 不执行远端修改的本地检查与隔离提取用例。 */
public final class BackupUseCase {
    private final BackupArchiveValidator validator;
    private final BackupArchiveExtractor extractor;
    private final WindowsRestoreWorkspace workspace;

    /** Creates the desktop backup use case over the platform-owned workspace. / 基于平台持有工作区创建桌面备份用例。 */
    public BackupUseCase(Path workDirectory) {
        this(new BackupArchiveValidator(BackupArchivePolicy.defaults()), new BackupArchiveExtractor(),
                new WindowsRestoreWorkspace(workDirectory));
    }

    BackupUseCase(
            BackupArchiveValidator validator,
            BackupArchiveExtractor extractor,
            WindowsRestoreWorkspace workspace
    ) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    /** Fully validates one selected archive without extracting or connecting. / 完整校验一个已选归档且不提取、不连接。 */
    public BackupArchiveInspection inspect(Path archive) throws BackupException {
        return BackupArchiveInspection.from(validator.validate(archive));
    }

    /** Revalidates and extracts one new local candidate without activating it. / 重新校验并提取一个新的本地候选且不激活。 */
    public PreparedBackupCandidate prepare(Path archive) throws IOException {
        BackupArchiveValidation validation = validator.validate(archive);
        WindowsRestoreAttempt attempt = workspace.createAttempt(
                validation.manifest().applicationId(), validation.archiveSha256());
        try {
            BackupRestoreCandidate candidate = extractor.extract(archive, attempt.candidateRoot(), validation);
            return new PreparedBackupCandidate(BackupArchiveInspection.from(validation),
                    candidate.root(), candidate.extractedBytes());
        } catch (BackupException | RuntimeException exception) {
            cleanup(attempt, exception);
            throw exception;
        }
    }

    private void cleanup(WindowsRestoreAttempt attempt, Exception original) {
        try {
            workspace.discardAttempt(attempt);
        } catch (WindowsWorkspaceException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }
}
