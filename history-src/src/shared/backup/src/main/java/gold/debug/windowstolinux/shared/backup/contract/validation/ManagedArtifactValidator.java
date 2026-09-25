package gold.debug.windowstolinux.shared.backup.contract.validation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.shared.backup.contract.validation.ManagedArtifactEvidence;
import gold.debug.windowstolinux.shared.backup.contract.validation.ManagedArtifactFormatType;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Strict independent validator for helper-generated PAX TAR and OCI archive artifacts. / helper 生成 PAX TAR 与 OCI 归档的严格独立校验器。
 */
public final class ManagedArtifactValidator {
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
     * JSON mapper for managed artifact validator.
     * <p>受管制品校验器使用的 JSON 映射器。
     */
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Creates a validator with explicit resource bounds. / 使用显式资源边界创建校验器。
     *
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedArtifactValidator(BackupArchivePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Validates one ordinary managed tree archive and rejects every link or special member. / 校验普通受管树归档并拒绝全部链接或特殊成员。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return constructed or resolved managed artifact evidence / 构造或解析得到的受管制品证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    public ManagedArtifactEvidence validatePax(Path path) throws BackupException {
        Path file = regular(path);
        int entries = scanTar(file, null).entries();
        return evidence(file, ManagedArtifactFormatType.PAX_TAR, entries);
    }

    /**
     * Validates one closed, reachable, digest-correct OCI image archive. / 校验封闭、可达且摘要正确的 OCI 镜像归档。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return constructed or resolved managed artifact evidence / 构造或解析得到的受管制品证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    public ManagedArtifactEvidence validateOci(Path path) throws BackupException {
        Path file = regular(path);
        OciScan first = scanTar(file, Set.of("oci-layout", "index.json"));
        JsonNode layout = document(first.documents().get("oci-layout"), "oci-layout");
        if (!layout.isObject() || layout.size() != 1 || !"1.0.0".equals(layout.path("imageLayoutVersion").asText())) {
            throw invalid("OCI layout is not the exact supported version", null);
        }
        JsonNode index = document(first.documents().get("index.json"), "index.json");
        JsonNode manifests = index.path("manifests");
        if (!index.isObject() || index.path("schemaVersion").asInt(-1) != 2 || !manifests.isArray()
                || manifests.size() != 1) {
            throw invalid("OCI index must describe exactly one schema-v2 image", null);
        }
        Descriptor image = descriptor(manifests.get(0));
        requireBlob(first.blobs(), image);
        String imagePath = blobPath(image.digest());
        OciScan second = scanTar(file, Set.of(imagePath));
        JsonNode manifest = document(second.documents().get(imagePath), "OCI image manifest");
        if (!manifest.isObject() || manifest.path("schemaVersion").asInt(-1) != 2
                || !manifest.path("layers").isArray()) {
            throw invalid("OCI image manifest is malformed", null);
        }
        Set<String> reachable = new LinkedHashSet<>();
        reachable.add(imagePath);
        Descriptor configuration = descriptor(manifest.path("config"));
        requireBlob(first.blobs(), configuration);
        reachable.add(blobPath(configuration.digest()));
        for (JsonNode layer : manifest.path("layers")) {
            Descriptor descriptor = descriptor(layer);
            requireBlob(first.blobs(), descriptor);
            reachable.add(blobPath(descriptor.digest()));
        }
        if (!reachable.equals(first.blobs().keySet())) {
            throw invalid("OCI archive contains unreachable or missing blobs", null);
        }
        return evidence(file, ManagedArtifactFormatType.OCI_ARCHIVE, first.entries());
    }

    /**
     * Scans bounded TAR members, rejects unsafe names and types, hashes blobs and captures only the requested OCI metadata documents.
     * <p>扫描有界 TAR 成员，拒绝不安全名称及类型、计算 blob 摘要，并仅捕获请求的 OCI 元数据文档。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param documents documents / 文档集合
     * @return constructed or resolved oci scan / 构造或解析得到的Oci扫描
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private OciScan scanTar(Path path, Set<String> documents) throws BackupException {
        Set<String> names = new HashSet<>();
        Map<String, Blob> blobs = new HashMap<>();
        Map<String, byte[]> captured = new HashMap<>();
        int entries = 0;
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (TarArchiveInputStream archive = new TarArchiveInputStream(Files.newInputStream(path))) {
            TarArchiveEntry entry;
            while ((entry = archive.getNextTarEntry()) != null) {
                if (!archive.canReadEntryData(entry))
                    throw invalid("unsupported TAR entry encoding", null);
                String name = safePath(entry.getName());
                if (!names.add(name))
                    throw invalid("duplicate TAR member", null);
                entries++;
                if (entries > policy.maximumMembers())
                    throw invalid("TAR member count exceeds policy", null);
                if (entry.isDirectory())
                    continue;
                if (!entry.isFile() || entry.isLink() || entry.isSymbolicLink()) {
                    throw invalid("TAR contains a link or special member", null);
                }
                if (entry.getSize() < 0 || entry.getSize() > policy.maximumMemberBytes()) {
                    throw invalid("TAR member size exceeds policy", null);
                }
                total = Math.addExact(total, entry.getSize());
                if (total > policy.maximumTotalBytes())
                    throw invalid("TAR total size exceeds policy", null);
                MessageDigest digest = sha256();
                ByteArrayOutputStream capture = documents != null && documents.contains(name)
                        ? new ByteArrayOutputStream()
                        : null;
                long readTotal = 0;
                int read;
                while ((read = archive.read(buffer)) >= 0) {
                    if (read == 0)
                        continue;
                    readTotal = Math.addExact(readTotal, read);
                    if (readTotal > entry.getSize())
                        throw invalid("TAR member exceeds declared size", null);
                    digest.update(buffer, 0, read);
                    if (capture != null) {
                        if (capture.size() + read > policy.maximumManifestBytes()) {
                            throw invalid("OCI metadata document exceeds policy", null);
                        }
                        capture.write(buffer, 0, read);
                    }
                }
                if (readTotal != entry.getSize())
                    throw invalid("TAR member is truncated", null);
                String sha = HexFormat.of().formatHex(digest.digest());
                if (name.startsWith("blobs/sha256/")) {
                    String expected = name.substring("blobs/sha256/".length());
                    if (!expected.matches("[0-9a-f]{64}") || !expected.equals(sha)) {
                        throw invalid("OCI blob path differs from its content digest", null);
                    }
                    blobs.put(name, new Blob(entry.getSize(), sha));
                }
                if (capture != null)
                    captured.put(name, capture.toByteArray());
            }
        } catch (BackupException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("managed TAR artifact could not be validated", exception);
        }
        if (entries < 1)
            throw invalid("managed TAR artifact is empty", null);
        if (documents != null && !captured.keySet().containsAll(documents)) {
            throw invalid("required OCI metadata is missing", null);
        }
        return new OciScan(entries, Map.copyOf(blobs), Map.copyOf(captured));
    }

    /**
     * Reads the complete local member to measure its byte count and SHA-256 digest.
     * <p>读取完整本地成员以测量字节数及 SHA-256 摘要。
     *
     * @param file file / 文件
     * @param format format / 格式
     * @param entries the type-checked entries / 经类型检查的条目
     * @return the complete local member to measure its byte count and SHA-256 digest / 完整本地成员以测量字节数及 SHA-256 摘要
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private ManagedArtifactEvidence evidence(Path file, ManagedArtifactFormatType format, int entries)
            throws BackupException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = sha256();
            long count = 0;
            byte[] bytes = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(bytes)) >= 0) {
                if (read == 0)
                    continue;
                count = Math.addExact(count, read);
                digest.update(bytes, 0, read);
            }
            return new ManagedArtifactEvidence(file, format, count, HexFormat.of().formatHex(digest.digest()), entries);
        } catch (IOException | ArithmeticException exception) {
            throw invalid("managed artifact digest could not be computed", exception);
        }
    }

    /**
     * Requires a normalized regular non-symlink artifact with nonzero bounded size.
     * <p>要求制品路径规范化，指向大小非零且有界的常规非符号链接文件。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private Path regular(Path path) throws BackupException {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        try {
            long size = Files.size(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || size < 1
                    || size > policy.maximumTotalBytes()) {
                throw invalid("managed artifact is not one bounded regular file", null);
            }
            return path;
        } catch (IOException exception) {
            throw invalid("managed artifact file could not be inspected", exception);
        }
    }

    /**
     * Validates and produces safe path for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的安全路径。
     *
     * @param raw raw / 原始
     * @return safe path text / 安全路径文本
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private String safePath(String raw) throws BackupException {
        String name = Objects.requireNonNull(raw, "TAR path").replace('\\', '/');
        while (name.startsWith("./"))
            name = name.substring(2);
        if (name.endsWith("/"))
            name = name.substring(0, name.length() - 1);
        if (name.isBlank())
            return ".";
        if (name.startsWith("/") || name.length() > policy.maximumPathLength() || name.contains("//")
                || java.util.Arrays.asList(name.split("/")).contains("..")) {
            throw invalid("TAR member path is unsafe", null);
        }
        return name;
    }

    /**
     * Parses required artifact metadata as JSON and reports invalid or missing content as a backup failure.
     * <p>将必需制品元数据解析为 JSON，并将无效或缺失内容报告为备份失败。
     *
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return required artifact metadata as JSON and reports invalid or missing content as a backup failure / 将必需制品元数据解析为 JSON，并将无效或缺失内容报告为备份失败
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private JsonNode document(byte[] bytes, String name) throws BackupException {
        if (bytes == null)
            throw invalid(name + " is missing", null);
        try {
            return json.readTree(bytes);
        } catch (IOException exception) {
            throw invalid(name + " is not valid JSON", exception);
        }
    }

    /**
     * Builds the structured failure descriptor for descriptor.
     * <p>为描述符构建结构化失败描述。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return the structured failure descriptor for descriptor / 为描述符构建结构化失败描述
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private Descriptor descriptor(JsonNode value) throws BackupException {
        if (!value.isObject() || !value.path("digest").asText().matches("sha256:[0-9a-f]{64}")) {
            throw invalid("OCI descriptor is malformed", null);
        }
        long size = value.path("size").asLong(-1);
        if (size < 0 || size > policy.maximumMemberBytes())
            throw invalid("OCI descriptor size is invalid", null);
        return new Descriptor(value.path("digest").asText(), size);
    }

    /**
     * Requires blob.
     * <p>要求二进制块。
     *
     * @param blobs blobs / 二进制块集合
     * @param descriptor descriptor / 描述符
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private void requireBlob(Map<String, Blob> blobs, Descriptor descriptor) throws BackupException {
        Blob blob = blobs.get(blobPath(descriptor.digest()));
        if (blob == null || blob.size() != descriptor.size())
            throw invalid("OCI descriptor blob is missing or differs", null);
    }

    /**
     * Maps a validated SHA-256 digest to its OCI blob member path.
     * <p>将已校验 SHA-256 摘要映射为 OCI blob 成员路径。
     *
     * @param digest content identity used for independent verification / 独立验证所用的内容身份
     * @return blob path text / 二进制块路径文本
     */
    private static String blobPath(String digest) {
        return "blobs/sha256/" + digest.substring("sha256:".length());
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
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * Creates the owning module's failure for rejected input or evidence.
     * <p>为被拒绝输入或证据创建所属模块的失败。
     *
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return the owning module's failure for rejected input or evidence / 为被拒绝输入或证据创建所属模块的失败
     */
    private static BackupException invalid(String diagnostic, Throwable cause) {
        return BackupException.create(BackupFailureType.INTEGRITY_FAILED, diagnostic, cause);
    }
    /**
     * Records the measured size and digest of one OCI content blob.
     * <p>记录一个 OCI 内容块的实测大小及摘要。
     *
     * @param size size / 大小
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     */
    private record Blob(long size, String sha256) {
    }

    /**
     * Carries an OCI descriptor's media type, size and content digest.
     * <p>携带 OCI 描述符的媒体类型、大小及内容摘要。
     *
     * @param digest content identity used for independent verification / 独立验证所用的内容身份
     * @param size size / 大小
     */
    private record Descriptor(String digest, long size) {
    }

    /**
     * Collects validated OCI descriptors and the archive members they reference.
     * <p>汇总已验证 OCI 描述符及其引用的归档成员。
     *
     * @param entries the type-checked entries / 经类型检查的条目
     * @param blobs blobs / 二进制块集合
     * @param documents documents / 文档集合
     */
    private record OciScan(int entries, Map<String, Blob> blobs, Map<String, byte[]> documents) {
    }
}
