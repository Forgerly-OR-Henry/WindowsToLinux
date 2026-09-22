package gold.debug.windowstolinux.shared.backup.restore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidation;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Extracts a validated archive into a new isolated candidate directory. / 将已校验归档提取到新的隔离候选目录。
 */
public final class BackupArchiveExtractor {
    /**
     * BUFFER SIZE.
     * <p>缓冲区大小。
     */
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Rebinds the archive fingerprint, extracts exact members and removes partial output on failure. / 重新绑定归档指纹、提取精确成员，并在失败时移除部分输出。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param candidateRoot candidate root / 候选根目录
     * @param validation validation / 校验
     * @return constructed or resolved backup restore candidate / 构造或解析得到的备份恢复候选
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupRestoreCandidate extract(Path archive, Path candidateRoot, BackupArchiveValidation validation)
            throws BackupException {
        Path normalizedArchive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        Path normalizedRoot = Objects.requireNonNull(candidateRoot, "candidateRoot").toAbsolutePath().normalize();
        Objects.requireNonNull(validation, "validation");
        verifyArchiveFingerprint(normalizedArchive, validation.archiveSha256());
        List<Path> created = new ArrayList<>();
        try {
            createIsolatedRoot(normalizedRoot, created);
            long extracted = extractMembers(normalizedArchive, normalizedRoot, validation, created);
            verifyArchiveFingerprint(normalizedArchive, validation.archiveSha256());
            return new BackupRestoreCandidate(normalizedRoot, validation.manifest(), extracted);
        } catch (BackupException exception) {
            cleanup(created, exception);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            BackupException failure = BackupException.create(BackupFailureType.EXTRACTION_FAILED,
                    "validated archive could not be extracted into the candidate", exception);
            cleanup(created, failure);
            throw failure;
        }
    }

    /**
     * Extracts only validated manifest members under the target root and records created paths for rollback.
     * <p>仅在目标根目录下提取已验证清单成员，并记录已创建路径以供回滚。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param validation validation / 校验
     * @param created created / 已创建
     * @return total extracted uncompressed bytes / 提取的未压缩总字节数
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private long extractMembers(Path archive, Path root, BackupArchiveValidation validation, List<Path> created)
            throws IOException, BackupException {
        long total = 0;
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            for (BackupMember member : validation.manifest().members()) {
                ZipArchiveEntry entry = zip.getEntry(member.path());
                if (entry == null) {
                    throw BackupException.create(BackupFailureType.ARCHIVE_CHANGED,
                            "a validated archive member is no longer present");
                }
                Path target = safeTarget(root, member.path());
                createParents(root, target.getParent(), created);
                writeExact(zip.getInputStream(entry), target, member, created);
                total = Math.addExact(total, member.size());
            }
        }
        return total;
    }

    /**
     * Creates isolated root.
     * <p>创建隔离根目录。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param created created / 已创建
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private void createIsolatedRoot(Path root, List<Path> created) throws IOException, BackupException {
        Path parent = root.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw BackupException.create(BackupFailureType.EXTRACTION_FAILED,
                    "candidate parent must be an existing non-link directory");
        }
        Files.createDirectory(root);
        created.add(root);
    }

    /**
     * Validates and produces safe target for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的安全目标。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param memberPath member path / 成员路径
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private Path safeTarget(Path root, String memberPath) throws BackupException {
        Path target = root.resolve(memberPath).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw BackupException.create(BackupFailureType.MEMBER_REJECTED,
                    "archive member escapes the isolated candidate root");
        }
        return target;
    }

    /**
     * Creates parents.
     * <p>创建父级集合。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param targetParent target parent / 目标父级
     * @param created created / 已创建
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private void createParents(Path root, Path targetParent, List<Path> created) throws IOException, BackupException {
        Path current = root;
        for (Path segment : root.relativize(targetParent)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                    throw BackupException.create(BackupFailureType.MEMBER_REJECTED,
                            "candidate path contains a link or non-directory parent");
                }
            } else {
                Files.createDirectory(current);
                created.add(current);
            }
        }
    }

    /**
     * Writes a validated archive member to its owned destination and checks the exact copied byte count and digest.
     * <p>将已验证归档成员写入自有目的地，并检查精确复制字节数及摘要。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param member member / 成员
     * @param created created / 已创建
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private void writeExact(InputStream input, Path target, BackupMember member, List<Path> created)
            throws IOException, BackupException {
        MessageDigest digest = sha256();
        long count = 0;
        OpenOption[] options = {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS};
        try (input;
                SeekableByteChannel channel = Files.newByteChannel(target, options);
                OutputStream output = Channels.newOutputStream(channel)) {
            created.add(target);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0)
                    continue;
                count = Math.addExact(count, read);
                if (count > member.size()) {
                    throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                            "archive member changed after validation");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (count != member.size() || !actual.equals(member.sha256())) {
            throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                    "extracted member differs from validated evidence");
        }
    }

    /**
     * Verifies archive fingerprint.
     * <p>验证归档指纹。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private void verifyArchiveFingerprint(Path archive, String expected) throws BackupException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(archive)) {
            int read;
            while ((read = input.read(buffer)) >= 0)
                if (read > 0)
                    digest.update(buffer, 0, read);
        } catch (IOException exception) {
            throw BackupException.create(BackupFailureType.ARCHIVE_CHANGED, "validated archive can no longer be read",
                    exception);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
            throw BackupException.create(BackupFailureType.ARCHIVE_CHANGED,
                    "archive fingerprint changed after validation");
        }
    }

    /**
     * Cleans up backup archive extractor.
     * <p>清理备份归档提取器。
     *
     * @param created created / 已创建
     * @param original original / 原始
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private void cleanup(List<Path> created, BackupException original) throws BackupException {
        List<IOException> failures = new ArrayList<>();
        created.stream().distinct().sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                failures.add(exception);
            }
        });
        if (!failures.isEmpty()) {
            BackupException cleanupFailure = BackupException.create(BackupFailureType.CLEANUP_FAILED,
                    "partial restore candidate could not be removed completely", original);
            failures.forEach(cleanupFailure::addSuppressed);
            throw cleanupFailure;
        }
    }

    /**
     * Creates a SHA-256 accumulator for independent content evidence.
     * <p>创建用于独立内容证据的 SHA-256 累加器。
     *
     * @return new SHA-256 digest accumulator / 新的 SHA-256 摘要累加器
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private static MessageDigest sha256() throws BackupException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw BackupException.create(BackupFailureType.INTEGRITY_FAILED, "SHA-256 is unavailable", exception);
        }
    }
}
