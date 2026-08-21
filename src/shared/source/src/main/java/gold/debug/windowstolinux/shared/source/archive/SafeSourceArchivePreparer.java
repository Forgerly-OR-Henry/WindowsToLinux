package gold.debug.windowstolinux.shared.source.archive;

import gold.debug.windowstolinux.shared.source.contract.validation.SourceBoundaryValidator;
import gold.debug.windowstolinux.shared.source.manifest.SourceManifest;
import gold.debug.windowstolinux.shared.source.snapshot.SourceSnapshotAssembler;

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

/** Creates a deterministic, boundary-checked source-only {@code tar.gz}. / 创建确定且经过边界检查的纯源码 {@code tar.gz}。 */
public final class SafeSourceArchivePreparer {
    private final SourceBoundaryValidator validator;
    private final SourceSnapshotAssembler writer;

    /** Creates the production source archiver. / 创建生产源码归档器。 */
    public SafeSourceArchivePreparer() { this(new SourceBoundaryValidator()); }

    SafeSourceArchivePreparer(SourceBoundaryValidator validator) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
        this.writer = new SourceSnapshotAssembler(validator);
    }

    /** Creates one verified source-only archive. / 创建一份已验证的纯源码归档。 */
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
            return new SourceArchive(archivePath, sha256Of(archivePath), manifest.entries().size(), Files.size(archivePath),
                    manifest.byteCount(), manifest.excludedEntries());
        } catch (IOException | RuntimeException exception) {
            SourceArchiveException structured = map(stage, exception);
            throw cleanupThen(temporaryArchive, structured);
        }
    }

    private static SourceArchiveException map(SourceArchiveStageType stage, Exception cause) {
        if (Thread.currentThread().isInterrupted()
                || cause instanceof java.nio.channels.ClosedByInterruptException) {
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

    private static void requireNotInterrupted() throws java.nio.channels.ClosedByInterruptException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.nio.channels.ClosedByInterruptException();
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

    private enum SourceArchiveStageType { VALIDATE_SOURCE, VALIDATE_DESTINATION, COLLECT, WRITE, MOVE, HASH }
}
