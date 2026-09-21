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

/**
 * Creates, independently re-reads, and atomically publishes one already-materialized local backup. / 创建、独立回读并原子发布一个已完成取材的本地备份。
 */
public final class BackupArchiveCreationUseCase {
    /**
     * PUBLICATION OVERHEAD BYTES.
     * <p>发布OVERHEAD字节。
     */
    private static final long PUBLICATION_OVERHEAD_BYTES = 1024L * 1024L;
    /**
     * Bound backup archive policy collaborator for explicit validation and resource-bound policy.
     * <p>处理显式校验及资源边界策略的备份归档策略协作对象。
     */
    private final BackupArchivePolicy policy;
    /**
     * Writer.
     * <p>写入器。
     */
    private final BackupArchiveWriter writer;
    /**
     * Validator.
     * <p>校验器。
     */
    private final BackupArchiveValidator validator;
    /**
     * Platform-owned work area with enforced path boundaries.
     * <p>具有路径边界约束的平台工作区。
     */
    private final WindowsBackupArchiveWorkspace workspace;

    /**
     * Creates the local archive publication boundary with conservative product defaults. / 使用保守产品默认值创建本地归档发布边界。
     */
    public BackupArchiveCreationUseCase() {
        this(BackupArchivePolicy.defaults(), new WindowsBackupArchiveWorkspace());
    }

    /**
     * Validates and binds the inputs required by backup archive creation use case.
     * <p>校验并绑定备份归档创建用例所需输入。
     *
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    BackupArchiveCreationUseCase(BackupArchivePolicy policy, WindowsBackupArchiveWorkspace workspace) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.writer = new BackupArchiveWriter(policy);
        this.validator = new BackupArchiveValidator(policy);
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    /**
     * Publishes only after temporary and final-path validation both return identical evidence. / 仅在临时与最终路径校验返回完全相同证据后发布成功。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param contents contents / 内容集合
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @return constructed or resolved created backup archive / 构造或解析得到的已创建备份归档
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Validates and produces required capacity for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的必需容量。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @return required capacity as a numeric result / 必需容量的数值结果
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
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

    /**
     * Requires expected manifest.
     * <p>要求预期清单。
     *
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @param actual actual / 实际
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private static void requireExpectedManifest(BackupManifest expected, BackupArchiveValidation actual)
            throws BackupException {
        if (!expected.equals(actual.manifest())) {
            throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                    "the staged archive manifest differs from the requested manifest");
        }
    }

    /**
     * Requires same evidence.
     * <p>要求相同证据。
     *
     * @param staged staged / 已暂存
     * @param completed completed / 已完成
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private static void requireSameEvidence(BackupArchiveValidation staged, BackupArchiveValidation completed)
            throws BackupException {
        if (!staged.equals(completed)) {
            throw BackupException.create(BackupFailureType.ARCHIVE_CHANGED,
                    "the backup archive changed during final-path publication validation");
        }
    }

    /**
     * Cleans up backup archive creation.
     * <p>清理备份归档创建。
     *
     * @param attempt attempt / 尝试
     * @param staged staged / 已暂存
     * @param published published / 已发布
     * @param original original / 原始
     */
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
