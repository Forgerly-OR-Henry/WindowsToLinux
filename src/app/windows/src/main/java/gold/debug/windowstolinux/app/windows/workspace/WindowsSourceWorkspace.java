package gold.debug.windowstolinux.app.windows.workspace;

import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.source.archive.SafeSourceArchiver;
import gold.debug.windowstolinux.shared.source.archive.SourceArchive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Windows desktop entry point for preparing a platform-neutral source archive.
 *
 * <p>准备平台无关源码归档的 Windows 桌面入口。
 */
public final class WindowsSourceWorkspace {
    private final SafeSourceArchiver archiver;
    private final Path archiveDirectory;

    /**
     * Creates a {@code WindowsSourceWorkspace} instance.
     *
     * <p>创建 {@code WindowsSourceWorkspace} 实例。
     *
     * @param workDirectory the {@code workDirectory} value / {@code workDirectory} 值
     */
    public WindowsSourceWorkspace(Path workDirectory) {
        this(new SafeSourceArchiver(), workDirectory);
    }

    WindowsSourceWorkspace(SafeSourceArchiver archiver, Path workDirectory) {
        this.archiver = Objects.requireNonNull(archiver, "archiver");
        this.archiveDirectory = Objects.requireNonNull(workDirectory, "workDirectory")
                .toAbsolutePath().normalize().resolve("archives");
    }

    /**
     * Performs the {@code prepare} operation.
     *
     * <p>执行 {@code prepare} 操作。
     *
     * @param sourceDirectory the {@code sourceDirectory} value / {@code sourceDirectory} 值
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @return the operation result / 操作结果
     * @throws IOException if the operation cannot be completed / 无法完成操作时
     */
    public PreparedSourceArchive prepare(Path sourceDirectory, String applicationId) throws IOException {
        Files.createDirectories(archiveDirectory);
        SourceArchive archive = archiver.archive(sourceDirectory,
                archiveDirectory.resolve(applicationId + "-" + UUID.randomUUID() + ".tar.gz"));
        return new PreparedSourceArchive(new SourceArchiveDescriptor(
                archive.archivePath(), archive.contentSha256(), archive.byteCount(), archive.uncompressedByteCount()
        ), archive.excludedEntries());
    }
}
