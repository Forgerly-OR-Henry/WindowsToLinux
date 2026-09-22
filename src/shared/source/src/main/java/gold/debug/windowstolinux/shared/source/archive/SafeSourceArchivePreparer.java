package gold.debug.windowstolinux.shared.source.archive;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import gold.debug.windowstolinux.shared.source.contract.validation.SourceBoundaryValidator;
import gold.debug.windowstolinux.shared.source.manifest.SourceManifest;
import gold.debug.windowstolinux.shared.source.snapshot.SourceSnapshotAssembler;

/**
 * Creates a deterministic, boundary-checked source-only {@code tar.gz}. / 创建确定且经过边界检查的纯源码 {@code tar.gz}。
 */
public final class SafeSourceArchivePreparer {
    /**
     * Validator.
     * <p>校验器。
     */
    private final SourceBoundaryValidator validator;

    /**
     * Writer.
     * <p>写入器。
     */
    private final SourceSnapshotAssembler writer;

    /**
     * Creates the production source archiver. / 创建生产源码归档器。
     */
    public SafeSourceArchivePreparer() {
        this(new SourceBoundaryValidator());
    }

    /**
     * Validates and binds the inputs required by safe source archive preparer.
     * <p>校验并绑定安全源码归档准备器所需输入。
     *
     * @param validator validator / 校验器
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    SafeSourceArchivePreparer(SourceBoundaryValidator validator) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
        this.writer = new SourceSnapshotAssembler(validator);
    }

    /**
     * Creates one verified source-only archive. / 创建一份已验证的纯源码归档。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @param requestedArchivePath requested archive path / 已请求归档路径
     * @return one verified source-only archive / 一份已验证的纯源码归档
     * @throws SourceArchiveException if the source archive boundary rejects the operation / 源码归档边界拒绝当前操作时
     */
    public SourceArchive archive(Path sourceDirectory, Path requestedArchivePath) throws SourceArchiveException {
        Path temporaryArchive = null;
        SourceArchiveStageType stage = SourceArchiveStageType.VALIDATE_SOURCE;
        try {
            requireNotInterrupted();
            Path sourceRoot = validator.validateSourceDirectory(sourceDirectory);
            stage = SourceArchiveStageType.VALIDATE_DESTINATION;
            Path archivePath = validator.validateDestination(sourceRoot, requestedArchivePath);
            stage = SourceArchiveStageType.COLLECT;
            SourceManifest manifest = validator.collect(sourceRoot);
            requireNotInterrupted();
            stage = SourceArchiveStageType.WRITE;
            Files.createDirectories(archivePath.getParent());
            temporaryArchive = Files.createTempFile(archivePath.getParent(), "windowstolinux-source-", ".tar.gz");
            writer.write(sourceRoot, manifest, temporaryArchive);
            requireNotInterrupted();
            stage = SourceArchiveStageType.MOVE;
            moveIntoPlace(temporaryArchive, archivePath);
            temporaryArchive = null;
            stage = SourceArchiveStageType.HASH;
            return new SourceArchive(archivePath, sha256Of(archivePath), manifest.entries().size(),
                    Files.size(archivePath), manifest.byteCount(), manifest.excludedEntries());
        } catch (IOException | RuntimeException exception) {
            SourceArchiveException structured = map(stage, exception);
            throw cleanupThen(temporaryArchive, structured);
        }
    }

    /**
     * Classifies an archive preparation failure by its stage while retaining the original cause.
     * <p>按阶段分类归档准备失败，并保留原始原因。
     *
     * @param stage stage / 阶段
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return constructed or resolved source archive exception / 构造或解析得到的源码归档异常
     */
    private static SourceArchiveException map(SourceArchiveStageType stage, Exception cause) {
        if (Thread.currentThread().isInterrupted() || cause instanceof java.nio.channels.ClosedByInterruptException) {
            return SourceArchiveException.create(SourceArchiveFailureType.INTERRUPTED,
                    "Source archive preparation was interrupted and its temporary output was cleaned", cause);
        }
        String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
        SourceArchiveFailureType type;
        String diagnostic;
        if (message.contains("symbolic")) {
            type = SourceArchiveFailureType.SYMBOLIC_LINK_REJECTED;
            diagnostic = "A symbolic link crossed the accepted source or destination boundary";
        } else if (message.contains("outside") || message.contains("escapes") || message.contains("traverse")) {
            type = SourceArchiveFailureType.BOUNDARY_ESCAPE;
            diagnostic = "A source or archive entry attempted to escape its controlled boundary";
        } else if (message.contains("path is too long") || message.contains("header value is too long")) {
            type = SourceArchiveFailureType.PATH_TOO_LONG;
            diagnostic = "A source entry cannot be represented in the bounded archive format";
        } else if (message.contains("changed") || message.contains("shrank") || message.contains("grew")) {
            type = SourceArchiveFailureType.ENTRY_CHANGED;
            diagnostic = "A source entry changed while the deterministic archive was being written";
        } else if (stage == SourceArchiveStageType.VALIDATE_DESTINATION) {
            type = SourceArchiveFailureType.DESTINATION_INVALID;
            diagnostic = "The archive destination failed its boundary or filename checks";
        } else if (stage == SourceArchiveStageType.VALIDATE_SOURCE) {
            type = SourceArchiveFailureType.SOURCE_DIRECTORY_INVALID;
            diagnostic = "The selected source must be a readable non-symbolic-link directory";
        } else if (stage == SourceArchiveStageType.COLLECT) {
            type = SourceArchiveFailureType.ENTRY_UNREADABLE;
            diagnostic = "At least one source entry could not be read within the controlled boundary";
        } else if (stage == SourceArchiveStageType.HASH) {
            type = SourceArchiveFailureType.HASH_FAILED;
            diagnostic = "The completed source archive could not be verified with SHA-256";
        } else {
            type = SourceArchiveFailureType.WRITE_FAILED;
            diagnostic = "The deterministic source archive could not be written or published";
        }
        return SourceArchiveException.create(type, diagnostic, cause);
    }

    /**
     * Cleans up then.
     * <p>清理then 对应的输入或状态。
     *
     * @param temporaryArchive temporary archive / 临时归档
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return constructed or resolved source archive exception / 构造或解析得到的源码归档异常
     */
    static SourceArchiveException cleanupThen(Path temporaryArchive, SourceArchiveException failure) {
        if (temporaryArchive == null) {
            return failure;
        }
        try {
            Files.deleteIfExists(temporaryArchive);
            return failure;
        } catch (IOException cleanup) {
            cleanup.addSuppressed(failure);
            return SourceArchiveException.create(SourceArchiveFailureType.CLEANUP_FAILED,
                    "Temporary source archive cleanup could not be verified", cleanup);
        }
    }

    /**
     * Requires not interrupted and rejects inputs outside the declared constraints.
     * <p>要求未已中断并拒绝超出已声明约束的输入。
     *
     * @throws java.nio.channels.ClosedByInterruptException if the closed by interrupt boundary rejects the operation / 已关闭执行者Interrupt边界拒绝当前操作时
     */
    private static void requireNotInterrupted() throws java.nio.channels.ClosedByInterruptException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.nio.channels.ClosedByInterruptException();
        }
    }

    /**
     * Reads the complete local file and returns its hexadecimal SHA-256 digest.
     * <p>读取完整本地文件并返回其十六进制 SHA-256 摘要。
     *
     * @param file file / 文件
     * @return the complete local file and returns its hexadecimal SHA-256 digest / 完整本地文件并返回其十六进制 SHA-256 摘要
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static String sha256Of(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Publishes the completed archive with an atomic replacement when supported, otherwise using ordinary replacement.
     * <p>支持时通过原子替换发布已完成归档，否则使用普通替换。
     *
     * @param temporaryArchive temporary archive / 临时归档
     * @param archivePath archive path / 归档路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void moveIntoPlace(Path temporaryArchive, Path archivePath) throws IOException {
        try {
            Files.move(temporaryArchive, archivePath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporaryArchive, archivePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Creates a SHA-256 accumulator for independent content evidence.
     * <p>创建用于独立内容证据的 SHA-256 累加器。
     *
     * @return new SHA-256 digest accumulator / 新的 SHA-256 摘要累加器
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }

    /**
     * Identifies the source archive preparation stage used for failure reporting.
     * <p>标识源码归档准备阶段以用于失败报告。
     */
    private enum SourceArchiveStageType {
        /**
         * VALIDATE SOURCE classification within source archive stage type.
         * <p>源码归档阶段类型中的校验源码分类。
         */
        VALIDATE_SOURCE,
        /**
         * VALIDATE DESTINATION classification within source archive stage type.
         * <p>源码归档阶段类型中的校验目的地分类。
         */
        VALIDATE_DESTINATION,
        /**
         * COLLECT classification within source archive stage type.
         * <p>源码归档阶段类型中的采集分类。
         */
        COLLECT,
        /**
         * WRITE classification within source archive stage type.
         * <p>源码归档阶段类型中的写入分类。
         */
        WRITE,
        /**
         * MOVE classification within source archive stage type.
         * <p>源码归档阶段类型中的移动分类。
         */
        MOVE,
        /**
         * HASH classification within source archive stage type.
         * <p>源码归档阶段类型中的哈希分类。
         */
        HASH
    }
}
