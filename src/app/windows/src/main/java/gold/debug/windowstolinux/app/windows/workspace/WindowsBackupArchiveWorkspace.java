package gold.debug.windowstolinux.app.windows.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Owns fail-closed local publication of one user-selected backup archive. / 持有单个用户所选备份归档的失败关闭本地发布边界。
 */
public final class WindowsBackupArchiveWorkspace {
    /**
     * BUFFER SIZE.
     * <p>缓冲区大小。
     */
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Creates a same-directory temporary output without replacing an existing destination. / 创建同目录临时输出且不替换既有目标。
     *
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param minimumFreeBytes minimum free disk space in bytes / 最小磁盘剩余空间，单位为字节
     * @return a same-directory temporary output without replacing an existing destination / 同目录临时输出且不替换既有目标
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public WindowsBackupArchiveAttempt createAttempt(Path destination, long minimumFreeBytes)
            throws WindowsWorkspaceException {
        Path normalized = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null || normalized.getFileName() == null || minimumFreeBytes < 1) {
            throw failure("The backup destination or capacity requirement is invalid", null);
        }
        Path temporary = null;
        Object temporaryFileKey = null;
        try {
            requireWritableParent(parent, minimumFreeBytes);
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("The selected backup destination already exists", null);
            }
            temporary = Files.createTempFile(parent, ".windowstolinux-backup-", ".tmp").toAbsolutePath().normalize();
            BasicFileAttributes attributes = attributes(temporary);
            temporaryFileKey = attributes.fileKey();
            if (!attributes.isRegularFile() || Files.isSymbolicLink(temporary)) {
                throw failure("The backup temporary output is not one regular file", null);
            }
            return new WindowsBackupArchiveAttempt(normalized, temporary, attributes.fileKey());
        } catch (WindowsWorkspaceException exception) {
            deleteCreatedTemporary(temporary, temporaryFileKey, exception);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            WindowsWorkspaceException failure = failure("The backup temporary output could not be created", exception);
            deleteCreatedTemporary(temporary, temporaryFileKey, failure);
            throw failure;
        }
    }

    /**
     * Atomically publishes the exact temporary file while refusing destination replacement. / 原子发布精确临时文件且拒绝替换目标。
     *
     * @param attempt attempt / 尝试
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public Path publish(WindowsBackupArchiveAttempt attempt) throws WindowsWorkspaceException {
        Objects.requireNonNull(attempt, "attempt");
        try {
            requireOwned(attempt.temporary(), attempt.fileKey());
            if (Files.exists(attempt.destination(), LinkOption.NOFOLLOW_LINKS)) {
                throw failure("The selected backup destination appeared before publication", null);
            }
            Files.move(attempt.temporary(), attempt.destination(), StandardCopyOption.ATOMIC_MOVE);
            return attempt.destination();
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (AtomicMoveNotSupportedException exception) {
            throw failure("The selected backup destination does not support atomic publication", exception);
        } catch (IOException | RuntimeException exception) {
            throw failure("The validated backup archive could not be published atomically", exception);
        }
    }

    /**
     * Deletes only the still-owned temporary file for this attempt. / 仅删除本次尝试仍受持有的临时文件。
     *
     * @param attempt attempt / 尝试
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void discardTemporary(WindowsBackupArchiveAttempt attempt) throws WindowsWorkspaceException {
        Objects.requireNonNull(attempt, "attempt");
        if (!Files.exists(attempt.temporary(), LinkOption.NOFOLLOW_LINKS))
            return;
        try {
            requireOwned(attempt.temporary(), attempt.fileKey());
            Files.delete(attempt.temporary());
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure("The failed backup temporary output could not be removed", exception);
        }
    }

    /**
     * Deletes a published file only when its identity and expected digest are still exact. / 仅在已发布文件身份及预期摘要仍精确时删除。
     *
     * @param attempt attempt / 尝试
     * @param expectedSha256 expected sha 256 / 预期SHA256
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void discardPublished(WindowsBackupArchiveAttempt attempt, String expectedSha256)
            throws WindowsWorkspaceException {
        Objects.requireNonNull(attempt, "attempt");
        expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256").trim();
        if (!expectedSha256.matches("[0-9a-f]{64}"))
            throw failure("The expected backup digest is invalid", null);
        if (!Files.exists(attempt.destination(), LinkOption.NOFOLLOW_LINKS))
            return;
        try {
            requireOwned(attempt.destination(), attempt.fileKey());
            if (!expectedSha256.equals(hash(attempt.destination()))) {
                throw failure("The published backup changed externally and was preserved", null);
            }
            Files.delete(attempt.destination());
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure("The invalid published backup could not be removed safely", exception);
        }
    }

    /**
     * Requires writable parent.
     * <p>要求可写父级。
     *
     * @param parent parent / 父级
     * @param minimumFreeBytes minimum free disk space in bytes / 最小磁盘剩余空间，单位为字节
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     */
    private static void requireWritableParent(Path parent, long minimumFreeBytes)
            throws IOException, WindowsWorkspaceException {
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(parent)) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.DIRECTORY_NOT_WRITABLE,
                    "The backup destination parent is not one writable regular directory", null);
        }
        if (Files.getFileStore(parent).getUsableSpace() < minimumFreeBytes) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.CAPACITY_INSUFFICIENT,
                    "The backup destination lacks the declared minimum free capacity", null);
        }
    }

    /**
     * Requires owned.
     * <p>要求已持有。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param expectedFileKey expected file key / 预期文件键
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     */
    private static void requireOwned(Path path, Object expectedFileKey) throws IOException, WindowsWorkspaceException {
        BasicFileAttributes attributes = attributes(path);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(path)
                || expectedFileKey != null && !expectedFileKey.equals(attributes.fileKey())) {
            throw failure("The backup output is no longer the file created by this attempt", null);
        }
    }

    /**
     * Reads filesystem identity and type without following symbolic links.
     * <p>读取文件系统身份及类型，不跟随符号链接。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return filesystem identity and type without following symbolic links / 文件系统身份及类型，不跟随符号链接
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Computes content identity for subsequent integrity comparison.
     * <p>计算供后续完整性比较使用的内容身份。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return content identity for subsequent integrity comparison / 供后续完整性比较使用的内容身份
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     */
    private static String hash(Path path) throws IOException, WindowsWorkspaceException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0)
                if (read > 0)
                    digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw failure("SHA-256 is unavailable for backup cleanup", exception);
        }
    }

    /**
     * Deletes created temporary.
     * <p>删除已创建临时。
     *
     * @param temporary temporary / 临时
     * @param expectedFileKey expected file key / 预期文件键
     * @param original original / 原始
     */
    private static void deleteCreatedTemporary(Path temporary, Object expectedFileKey, Exception original) {
        if (temporary == null)
            return;
        if (expectedFileKey == null) {
            original.addSuppressed(
                    failure("The failed backup temporary output could not be identity-bound and was preserved", null));
            return;
        }
        try {
            if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS))
                return;
            BasicFileAttributes attributes = attributes(temporary);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(temporary)
                    || !expectedFileKey.equals(attributes.fileKey())) {
                original.addSuppressed(
                        failure("The failed backup temporary output changed externally and was preserved", null));
                return;
            }
            Files.delete(temporary);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static WindowsWorkspaceException failure(String diagnostic, Throwable cause) {
        return WindowsWorkspaceException.create(WindowsWorkspaceFailureType.ARCHIVE_FAILED, diagnostic, cause);
    }
}
