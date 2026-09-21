package gold.debug.windowstolinux.shared.backup.contract.validation;

import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifestCodec;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import gold.debug.windowstolinux.shared.backup.manifest.BackupProvenance;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField;
import org.apache.commons.compress.archivers.zip.X000A_NTFS;
import org.apache.commons.compress.archivers.zip.X5455_ExtendedTimestamp;
import org.apache.commons.compress.archivers.zip.Zip64ExtendedInformationExtraField;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipExtraField;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;

/**
 * Validates an untrusted backup without extracting any member. / 在不提取任何成员的情况下校验不受信备份。
 */
public final class BackupArchiveValidator {
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
     * Signature trust.
     * <p>签名信任。
     */
    private final BackupSignatureTrust signatureTrust;
    /**
     * Bound backup manifest codec collaborator for codec.
     * <p>处理编解码器的备份清单编解码器协作对象。
     */
    private final BackupManifestCodec codec = new BackupManifestCodec();

    /**
     * Creates an integrity-only validator. / 创建仅校验完整性的校验器。
     *
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     */
    public BackupArchiveValidator(BackupArchivePolicy policy) {
        this(policy, null);
    }

    /**
     * Creates a validator that can also verify optional provenance. / 创建还可校验可选来源签名的校验器。
     *
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     * @param signatureTrust signature trust / 签名信任
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupArchiveValidator(BackupArchivePolicy policy, BackupSignatureTrust signatureTrust) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.signatureTrust = signatureTrust;
    }

    /**
     * Verifies structure, bounds, exact member hashes and optional signature. / 校验结构、边界、精确成员摘要及可选签名。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @return constructed or resolved backup archive validation / 构造或解析得到的备份归档校验
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupArchiveValidation validate(Path archive) throws BackupException {
        Path normalized = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        ensureArchiveFile(normalized);
        String initialArchiveHash = hashFile(normalized);
        ValidationState state;
        try (ZipFile zip = ZipFile.builder().setPath(normalized).get()) {
            state = validateOpenArchive(zip);
        } catch (BackupException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw BackupException.create(BackupFailureType.ARCHIVE_INVALID,
                    "backup ZIP structure could not be read safely", exception);
        }
        String finalArchiveHash = hashFile(normalized);
        if (!initialArchiveHash.equals(finalArchiveHash)) {
            throw BackupException.create(BackupFailureType.ARCHIVE_CHANGED,
                    "backup archive changed while it was being validated");
        }
        return new BackupArchiveValidation(finalArchiveHash, state.manifest(),
                state.verifiedBytes(), state.provenanceStatus());
    }

    /**
     * Requires a regular non-symlink archive file within the configured size bound.
     * <p>要求归档为配置大小边界内的常规非符号链接文件。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private void ensureArchiveFile(Path archive) throws BackupException {
        try {
            if (!Files.isRegularFile(archive) || Files.isSymbolicLink(archive)) {
                throw BackupException.create(BackupFailureType.ARCHIVE_INVALID,
                        "backup archive must be one regular non-link file");
            }
            long maximumArchiveBytes = Math.addExact(policy.maximumTotalBytes(),
                    Math.addExact(policy.maximumManifestBytes(), Math.multiplyExact(policy.maximumMembers(), 2048L)));
            if (Files.size(archive) > maximumArchiveBytes) {
                throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED, "compressed archive exceeds policy");
            }
        } catch (BackupException exception) {
            throw exception;
        } catch (IOException | ArithmeticException exception) {
            throw BackupException.create(BackupFailureType.ARCHIVE_INVALID,
                    "backup archive metadata could not be validated", exception);
        }
    }

    /**
     * Validates the open archive's manifest, exact member set, sizes and digests before issuing independent validation evidence.
     * <p>在生成独立验证证据前，校验已打开归档的清单、精确成员集合、大小及摘要。
     *
     * @param zip zip / ZIP 归档
     * @return constructed or resolved validation state / 构造或解析得到的校验状态
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private ValidationState validateOpenArchive(ZipFile zip) throws BackupException, IOException {
        List<ZipArchiveEntry> entries = Collections.list(zip.getEntries());
        if (entries.isEmpty() || entries.size() > policy.maximumMembers() + 1) {
            throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED, "archive member count violates policy");
        }
        Map<String, ZipArchiveEntry> indexed = indexEntries(entries);
        ZipArchiveEntry manifestEntry = indexed.get("manifest.json");
        if (manifestEntry == null) {
            throw BackupException.create(BackupFailureType.MANIFEST_INVALID, "manifest.json is missing");
        }
        byte[] manifestDocument = readBounded(zip.getInputStream(manifestEntry), policy.maximumManifestBytes(),
                BackupFailureType.MANIFEST_INVALID, "manifest.json exceeds its bound");
        BackupManifest manifest = decodeManifest(manifestDocument);
        new BackupManifestValidator(policy).validate(manifest);
        Map<String, BackupMember> expected = new HashMap<>();
        manifest.members().forEach(member -> expected.put(member.path(), member));
        Set<String> actualPaths = new HashSet<>(indexed.keySet());
        actualPaths.remove("manifest.json");
        if (!actualPaths.equals(expected.keySet())) {
            throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                    "archive entries do not exactly match the manifest");
        }
        long verifiedBytes = 0;
        for (BackupMember member : manifest.members()) {
            ZipArchiveEntry entry = indexed.get(member.path());
            validateDeclaredMember(entry, member);
            String actualHash = hashEntry(zip.getInputStream(entry), member.size());
            if (!actualHash.equals(member.sha256())) {
                throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                        "archive member digest differs from the manifest");
            }
            verifiedBytes = Math.addExact(verifiedBytes, member.size());
        }
        return new ValidationState(manifest, verifiedBytes, verifyProvenance(manifest));
    }

    /**
     * Validates ZIP entry shapes and indexes paths while rejecting duplicate or case-colliding names.
     * <p>校验 ZIP 条目形态并索引路径，同时拒绝重复名称及大小写冲突。
     *
     * @param entries the type-checked entries / 经类型检查的条目
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private Map<String, ZipArchiveEntry> indexEntries(List<ZipArchiveEntry> entries) throws BackupException {
        Map<String, ZipArchiveEntry> indexed = new HashMap<>();
        Set<String> caseInsensitive = new HashSet<>();
        for (ZipArchiveEntry entry : entries) {
            validateEntryShape(entry);
            String name = entry.getName();
            if (!caseInsensitive.add(name.toLowerCase(Locale.ROOT)) || indexed.putIfAbsent(name, entry) != null) {
                throw BackupException.create(BackupFailureType.MEMBER_REJECTED,
                        "duplicate or case-colliding archive member path");
            }
        }
        return Map.copyOf(indexed);
    }

    /**
     * Rejects unsafe ZIP names, links, unsupported methods and oversized or invalid entry metadata.
     * <p>拒绝不安全 ZIP 名称、链接、不支持的压缩方式，以及超大或无效条目元数据。
     *
     * @param entry entry / 条目
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private void validateEntryShape(ZipArchiveEntry entry) throws BackupException {
        String name = entry.getName();
        if (!"manifest.json".equals(name)) ArchivePathRules.validate(name, policy.maximumPathLength());
        if (name == null || name.length() > policy.maximumPathLength() || entry.isDirectory()) {
            throw BackupException.create(BackupFailureType.MEMBER_REJECTED, "directories and invalid paths are rejected");
        }
        if (entry.getGeneralPurposeBit().usesEncryption()) {
            throw BackupException.create(BackupFailureType.MEMBER_REJECTED, "ZIP-level encryption is not supported");
        }
        if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
            throw BackupException.create(BackupFailureType.MEMBER_REJECTED, "unsupported ZIP compression method");
        }
        long size = entry.getSize();
        long compressed = entry.getCompressedSize();
        if (size < 0 || compressed < 0 || size > policy.maximumMemberBytes()) {
            throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED, "archive member size violates policy");
        }
        double ratio = size == 0 ? 1.0d : (double) size / Math.max(1L, compressed);
        if (ratio > policy.maximumCompressionRatio()) {
            throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED, "archive compression ratio violates policy");
        }
        int mode = entry.getUnixMode();
        int fileType = mode & UnixStat.FILE_TYPE_FLAG;
        if (entry.isUnixSymlink() || mode != 0 && fileType != UnixStat.FILE_FLAG) {
            throw BackupException.create(BackupFailureType.MEMBER_REJECTED,
                    "links, devices and non-regular archive members are rejected");
        }
        for (ZipExtraField field : entry.getExtraFields()) {
            if (!(field instanceof Zip64ExtendedInformationExtraField)
                    && !(field instanceof X5455_ExtendedTimestamp)
                    && !(field instanceof X000A_NTFS)
                    && !(field instanceof UnicodePathExtraField)) {
                throw BackupException.create(BackupFailureType.MEMBER_REJECTED,
                        "unrecognized link-capable ZIP metadata is rejected: " + field.getClass().getSimpleName());
            }
        }
    }

    /**
     * Validates declared member.
     * <p>校验已声明成员。
     *
     * @param entry entry / 条目
     * @param member member / 成员
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private void validateDeclaredMember(ZipArchiveEntry entry, BackupMember member) throws BackupException {
        if (entry.getSize() != member.size()) {
            throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                    "archive member size differs from the manifest");
        }
    }

    /**
     * Decodes validated ownership or backup inventory document.
     * <p>解码已验证归属或备份资源清单文档。
     *
     * @param document document / 文档
     * @return validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private BackupManifest decodeManifest(byte[] document) throws BackupException {
        try {
            return codec.read(document);
        } catch (IOException | RuntimeException exception) {
            throw BackupException.create(BackupFailureType.MANIFEST_INVALID,
                    "manifest.json is malformed or incompatible", exception);
        }
    }

    /**
     * Consumes and closes an entry stream, checking its exact byte count while computing SHA-256.
     * <p>消费并关闭条目流，在计算 SHA-256 的同时检查精确字节数。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param expectedSize expected size / 预期大小
     * @return hash entry text / 哈希条目文本
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private String hashEntry(InputStream input, long expectedSize) throws BackupException {
        MessageDigest digest = sha256();
        long count = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                count = Math.addExact(count, read);
                if (count > expectedSize) {
                    throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                            "archive member expands beyond its declared size");
                }
                digest.update(buffer, 0, read);
            }
        } catch (BackupException exception) {
            throw exception;
        } catch (IOException | ArithmeticException exception) {
            throw BackupException.create(BackupFailureType.ARCHIVE_INVALID, "archive member could not be read", exception);
        }
        if (count != expectedSize) {
            throw BackupException.create(BackupFailureType.INTEGRITY_FAILED,
                    "archive member ended before its declared size");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Verifies provenance.
     * <p>验证来源证据。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @return constructed or resolved backup provenance status / 构造或解析得到的备份来源证据状态
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private BackupProvenanceStatus verifyProvenance(BackupManifest manifest) throws BackupException {
        BackupProvenance provenance = manifest.provenance();
        if (!provenance.signed()) return BackupProvenanceStatus.NOT_PRESENT;
        if (signatureTrust == null) return BackupProvenanceStatus.NOT_VERIFIED;
        try {
            PublicKey key = Objects.requireNonNull(signatureTrust.resolve(provenance.keyId()), "trusted key");
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(codec.signaturePayload(manifest));
            if (!verifier.verify(Base64.getDecoder().decode(provenance.signature()))) {
                throw BackupException.create(BackupFailureType.PROVENANCE_FAILED,
                        "backup signature verification failed");
            }
            return BackupProvenanceStatus.VERIFIED;
        } catch (BackupException exception) {
            throw exception;
        } catch (GeneralSecurityException | IOException | RuntimeException exception) {
            throw BackupException.create(BackupFailureType.PROVENANCE_FAILED,
                    "backup signing key or signature is not trusted", exception);
        }
    }

    /**
     * Opens the local archive and computes its complete SHA-256 content digest.
     * <p>打开本地归档并计算完整 SHA-256 内容摘要。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return hash file text / 哈希文件文本
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private String hashFile(Path path) throws BackupException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw BackupException.create(BackupFailureType.ARCHIVE_INVALID,
                    "backup archive could not be hashed", exception);
        }
    }

    /**
     * Reads bounded.
     * <p>读取有界。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param maximumBytes maximum bytes / 最大字节
     * @param failureType failure type / 失败类型
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return bounded / 有界
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private static byte[] readBounded(InputStream input, int maximumBytes, BackupFailureType failureType,
                                      String diagnostic) throws BackupException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 16 * 1024))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total = Math.addExact(total, read);
                if (total > maximumBytes) throw BackupException.create(failureType, diagnostic);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (BackupException exception) {
            throw exception;
        } catch (IOException | ArithmeticException exception) {
            throw BackupException.create(failureType, diagnostic, exception);
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

    /**
     * Accumulates archive member evidence while enforcing manifest and size constraints.
     * <p>在执行清单和大小约束时累计归档成员证据。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param verifiedBytes verified bytes / 已验证字节
     * @param provenanceStatus provenance status / 来源证据状态
     */
    private record ValidationState(
            BackupManifest manifest, long verifiedBytes, BackupProvenanceStatus provenanceStatus) {
    }
}
