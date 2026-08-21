package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.windows.workspace.WindowsBackupArchiveAttempt;
import gold.debug.windowstolinux.app.windows.workspace.WindowsBackupArchiveWorkspace;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchivePolicy;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidation;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidator;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.format.BackupArchiveContent;
import gold.debug.windowstolinux.shared.backup.format.BackupArchiveWriter;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/** Creates, independently re-reads, and atomically publishes one already-materialized local backup. / 创建、独立回读并原子发布一个已完成取材的本地备份。 */
public final class BackupArchiveCreationUseCase {
    private static final long PUBLICATION_OVERHEAD_BYTES = 1024L * 1024L;
    private final BackupArchivePolicy policy;
    private final BackupArchiveWriter writer;
    private final BackupArchiveValidator validator;
    private final WindowsBackupArchiveWorkspace workspace;

    /** Creates the local archive publication boundary with conservative product defaults. / 使用保守产品默认值创建本地归档发布边界。 */
    public BackupArchiveCreationUseCase() {
        this(BackupArchivePolicy.defaults(), new WindowsBackupArchiveWorkspace());
    }

    BackupArchiveCreationUseCase(BackupArchivePolicy policy, WindowsBackupArchiveWorkspace workspace) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.writer = new BackupArchiveWriter(policy);
        this.validator = new BackupArchiveValidator(policy);
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    /** Publishes only after temporary and final-path validation both return identical evidence. / 仅在临时与最终路径校验返回完全相同证据后发布成功。 */
    public CreatedBackupArchive create(
            BackupManifest manifest,
            List<BackupArchiveContent> contents,
            Path destination
    ) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(contents, "contents");
        WindowsBackupArchiveAttempt attempt = workspace.createAttempt(destination, requiredCapacity(manifest));
        BackupArchiveValidation staged = null;
        boolean published = false;
        try {
            try (OutputStream output = Files.newOutputStream(attempt.temporary(),
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write(manifest, contents, output);
            }
            staged = validator.validate(attempt.temporary());
            requireExpectedManifest(manifest, staged);
            workspace.publish(attempt);
            published = true;
            BackupArchiveValidation completed = validator.validate(attempt.destination());
            requireSameEvidence(staged, completed);
            return new CreatedBackupArchive(attempt.destination(), BackupArchiveInspection.from(completed));
        } catch (IOException | RuntimeException exception) {
            cleanup(attempt, staged, published, exception);
            throw exception;
        }
    }

    private long requiredCapacity(BackupManifest manifest) throws BackupException {
        try {
            long members = manifest.members().stream().mapToLong(BackupMember::size)
                    .reduce(0L, Math::addExact);
            long entryOverhead = Math.multiplyExact((long) manifest.members().size() + 1L, 2048L);
            return Math.addExact(PUBLICATION_OVERHEAD_BYTES,
                    Math.addExact(policy.maximumManifestBytes(), Math.addExact(members, entryOverhead)));
        } catch (ArithmeticException exception) {
            throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED,
                    "backup publication capacity requirement overflowed", exception);
        }
    }

    private static void requireExpectedManifest(BackupManifest expected, BackupArchiveValidation actual)
            throws BackupException {
        if (!expected.equals(actual.manifest())) {
            throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                    "the staged archive manifest differs from the requested manifest");
        }
    }

    private static void requireSameEvidence(BackupArchiveValidation staged, BackupArchiveValidation completed)
            throws BackupException {
        if (!staged.equals(completed)) {
            throw BackupException.create(BackupFailureType.ARCHIVE_CHANGED,
                    "the backup archive changed during final-path publication validation");
        }
    }

    private void cleanup(
            WindowsBackupArchiveAttempt attempt,
            BackupArchiveValidation staged,
            boolean published,
            Exception original
    ) {
        try {
            if (published && staged != null) workspace.discardPublished(attempt, staged.archiveSha256());
            else workspace.discardTemporary(attempt);
        } catch (WindowsWorkspaceException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }
}
