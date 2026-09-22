package gold.debug.windowstolinux.shared.backup.format;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchivePolicy;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupManifestValidator;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifestCodec;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 * Deterministic ZIP writer that verifies every stream against the manifest while writing. / 写入时逐流对照清单校验的确定性 ZIP 写入器。
 */
public final class BackupArchiveWriter {
    /**
     * BUFFER SIZE.
     * <p>缓冲区大小。
     */
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Bound backup archive policy collaborator for explicit validation and resource-bound policy.
     * <p>处理显式校验及资源边界策略的备份归档策略协作对象。
     */
    private final BackupArchivePolicy policy;

    /**
     * Bound backup manifest codec collaborator for codec.
     * <p>处理编解码器的备份清单编解码器协作对象。
     */
    private final BackupManifestCodec codec;

    /**
     * Creates a writer with explicit archive bounds. / 使用显式归档边界创建写入器。
     *
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupArchiveWriter(BackupArchivePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.codec = new BackupManifestCodec();
    }

    /**
     * Writes one complete archive; callers must discard their temporary destination on failure. / 写入完整归档；失败时调用方必须丢弃临时目标。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param contents contents / 内容集合
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void write(BackupManifest manifest, List<BackupArchiveContent> contents, OutputStream destination)
            throws BackupException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(destination, "destination");
        new BackupManifestValidator(policy).validate(manifest);
        Map<String, BackupArchiveContent> indexed = index(contents);
        if (!indexed.keySet().equals(
                manifest.members().stream().map(BackupMember::path).collect(java.util.stream.Collectors.toSet()))) {
            throw BackupException.create(BackupFailureType.MANIFEST_INVALID,
                    "archive streams do not exactly match manifest members");
        }
        try (ZipArchiveOutputStream archive = new ZipArchiveOutputStream(closeShield(destination))) {
            archive.setEncoding("UTF-8");
            archive.setUseLanguageEncodingFlag(true);
            byte[] manifestDocument = codec.write(manifest);
            if (manifestDocument.length > policy.maximumManifestBytes()) {
                throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED, "encoded manifest exceeds policy");
            }
            writeBytes(archive, "manifest.json", manifestDocument);
            for (BackupMember member : manifest.members())
                writeMember(archive, member, indexed.get(member.path()));
            archive.finish();
        } catch (BackupException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw BackupException.create(BackupFailureType.WRITE_FAILED, "backup archive writing failed", exception);
        }
    }

    /**
     * Indexes supplied archive contents by member path and rejects duplicate members.
     * <p>按成员路径索引所提供归档内容，并拒绝重复成员。
     *
     * @param contents contents / 内容集合
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private Map<String, BackupArchiveContent> index(List<BackupArchiveContent> contents) throws BackupException {
        Map<String, BackupArchiveContent> indexed = new HashMap<>();
        for (BackupArchiveContent content : contents) {
            if (indexed.putIfAbsent(content.member().path(), content) != null) {
                throw BackupException.create(BackupFailureType.MANIFEST_INVALID, "duplicate archive content stream");
            }
        }
        return Map.copyOf(indexed);
    }

    /**
     * Writes content buffer processed by the current codec or stream.
     * <p>写入当前编解码器或流处理的内容缓冲区。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param content content / 内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private void writeBytes(ZipArchiveOutputStream archive, String path, byte[] content) throws IOException {
        ZipArchiveEntry entry = regularEntry(path);
        archive.putArchiveEntry(entry);
        archive.write(content);
        archive.closeArchiveEntry();
    }

    /**
     * Streams one declared member into the ZIP while checking exact size, digest and aggregate archive bounds.
     * <p>将一个声明成员流式写入 ZIP，同时检查精确大小、摘要及归档总量边界。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param member member / 成员
     * @param content content / 内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private void writeMember(ZipArchiveOutputStream archive, BackupMember member, BackupArchiveContent content)
            throws IOException, BackupException {
        ZipArchiveEntry entry = regularEntry(member.path());
        archive.putArchiveEntry(entry);
        MessageDigest digest = sha256();
        long count = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = content.stream().open()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0)
                    continue;
                count = Math.addExact(count, read);
                if (count > member.size() || count > policy.maximumMemberBytes()) {
                    throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                            "archive member stream exceeds its declared size");
                }
                digest.update(buffer, 0, read);
                archive.write(buffer, 0, read);
            }
        }
        archive.closeArchiveEntry();
        String actualHash = HexFormat.of().formatHex(digest.digest());
        if (count != member.size() || !actualHash.equals(member.sha256())) {
            throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                    "archive member stream does not match its manifest digest");
        }
    }

    /**
     * Creates a deterministic ZIP file entry with zero timestamp and owner-only read/write mode.
     * <p>创建时间戳为零且仅所有者可读写的确定性 ZIP 文件条目。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return a deterministic ZIP file entry with zero timestamp and owner-only read/write mode / 时间戳为零且仅所有者可读写的确定性 ZIP 文件条目
     */
    private static ZipArchiveEntry regularEntry(String path) {
        ZipArchiveEntry entry = new ZipArchiveEntry(path);
        entry.setTime(0L);
        entry.setUnixMode(UnixStat.FILE_FLAG | 0600);
        return entry;
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
            throw BackupException.create(BackupFailureType.WRITE_FAILED, "SHA-256 is unavailable", exception);
        }
    }

    /**
     * Builds output stream from the supplied close shield inputs.
     * <p>根据所提供关闭防护输入构建输出流。
     *
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @return output stream from the supplied close shield inputs / 根据所提供关闭防护输入构建输出流
     */
    private static OutputStream closeShield(OutputStream destination) {
        return new FilterOutputStream(destination) {
            /**
             * Closes the resources owned by this instance and completes its cleanup boundary.
             * <p>关闭当前实例持有的资源并完成其清理边界。
             *
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }
}
