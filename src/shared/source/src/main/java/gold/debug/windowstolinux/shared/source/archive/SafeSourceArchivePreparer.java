package gold.debug.windowstolinux.shared.source.archive;

import gold.debug.windowstolinux.shared.source.manifest.SourceManifest;
import gold.debug.windowstolinux.shared.source.snapshot.SourceSnapshotAssembler;
import gold.debug.windowstolinux.shared.source.contract.validation.SourceBoundaryValidator;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Creates a deterministic, boundary-checked source-only {@code tar.gz}.
 *
 * <p>创建确定且经过边界检查的纯源码 {@code tar.gz}。
 */
public final class SafeSourceArchivePreparer {
    private final SourceBoundaryValidator validator;
    private final SourceSnapshotAssembler writer;

    /**
     * Creates a {@code SafeSourceArchivePreparer} instance.
     *
     * <p>创建 {@code SafeSourceArchivePreparer} 实例。
     */
    public SafeSourceArchivePreparer() {
        this(new SourceBoundaryValidator());
    }

    SafeSourceArchivePreparer(SourceBoundaryValidator validator) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
        this.writer = new SourceSnapshotAssembler(validator);
    }

    /**
     * Performs the {@code archive} operation.
     *
     * <p>执行 {@code archive} 操作。
     *
     * @param sourceDirectory the {@code sourceDirectory} value / {@code sourceDirectory} 值
     * @param requestedArchivePath the {@code requestedArchivePath} value / {@code requestedArchivePath} 值
     * @return the operation result / 操作结果
     * @throws IOException if the operation cannot be completed / 无法完成操作时
     */
    public SourceArchive archive(Path sourceDirectory, Path requestedArchivePath) throws IOException {
        Path sourceRoot = validator.validateSourceDirectory(sourceDirectory);
        Path archivePath = validator.validateDestination(sourceRoot, requestedArchivePath);
        SourceManifest manifest = validator.collect(sourceRoot);
        Files.createDirectories(archivePath.getParent());
        Path temporaryArchive = Files.createTempFile(archivePath.getParent(), "windowstolinux-source-", ".tar.gz");
        try {
            writer.write(sourceRoot, manifest, temporaryArchive);
            moveIntoPlace(temporaryArchive, archivePath);
            return new SourceArchive(archivePath, sha256Of(archivePath), manifest.entries().size(), Files.size(archivePath),
                    manifest.byteCount(), manifest.excludedEntries());
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temporaryArchive);
            throw exception;
        }
    }

    private static String sha256Of(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void moveIntoPlace(Path temporaryArchive, Path archivePath) throws IOException {
        try {
            Files.move(temporaryArchive, archivePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporaryArchive, archivePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }
}
