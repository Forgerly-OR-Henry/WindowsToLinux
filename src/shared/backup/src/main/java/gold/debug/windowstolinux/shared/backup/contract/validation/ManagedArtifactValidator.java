package gold.debug.windowstolinux.shared.backup.contract.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.shared.backup.execution.collection.ManagedArtifactEvidence;
import gold.debug.windowstolinux.shared.backup.execution.collection.ManagedArtifactFormatType;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

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

/** Strict independent validator for helper-generated PAX TAR and OCI archive artifacts. / helper 生成 PAX TAR 与 OCI 归档的严格独立校验器。 */
public final class ManagedArtifactValidator {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final BackupArchivePolicy policy;
    private final ObjectMapper json = new ObjectMapper();

    /** Creates a validator with explicit resource bounds. / 使用显式资源边界创建校验器。 */
    public ManagedArtifactValidator(BackupArchivePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Validates one ordinary managed tree archive and rejects every link or special member. / 校验普通受管树归档并拒绝全部链接或特殊成员。 */
    public ManagedArtifactEvidence validatePax(Path path) throws BackupException {
        Path file = regular(path);
        int entries = scanTar(file, null).entries();
        return evidence(file, ManagedArtifactFormatType.PAX_TAR, entries);
    }

    /** Validates one closed, reachable, digest-correct OCI image archive. / 校验封闭、可达且摘要正确的 OCI 镜像归档。 */
    public ManagedArtifactEvidence validateOci(Path path) throws BackupException {
        Path file = regular(path);
        OciScan first = scanTar(file, Set.of("oci-layout", "index.json"));
        JsonNode layout = document(first.documents().get("oci-layout"), "oci-layout");
        if (!layout.isObject() || layout.size() != 1
                || !"1.0.0".equals(layout.path("imageLayoutVersion").asText())) {
            throw invalid("OCI layout is not the exact supported version", null);
        }
        JsonNode index = document(first.documents().get("index.json"), "index.json");
        JsonNode manifests = index.path("manifests");
        if (!index.isObject() || index.path("schemaVersion").asInt(-1) != 2
                || !manifests.isArray() || manifests.size() != 1) {
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
        requireBlob(first.blobs(), configuration); reachable.add(blobPath(configuration.digest()));
        for (JsonNode layer : manifest.path("layers")) {
            Descriptor descriptor = descriptor(layer);
            requireBlob(first.blobs(), descriptor); reachable.add(blobPath(descriptor.digest()));
        }
        if (!reachable.equals(first.blobs().keySet())) {
            throw invalid("OCI archive contains unreachable or missing blobs", null);
        }
        return evidence(file, ManagedArtifactFormatType.OCI_ARCHIVE, first.entries());
    }

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
                if (!archive.canReadEntryData(entry)) throw invalid("unsupported TAR entry encoding", null);
                String name = safePath(entry.getName());
                if (!names.add(name)) throw invalid("duplicate TAR member", null);
                entries++;
                if (entries > policy.maximumMembers()) throw invalid("TAR member count exceeds policy", null);
                if (entry.isDirectory()) continue;
                if (!entry.isFile() || entry.isLink() || entry.isSymbolicLink()) {
                    throw invalid("TAR contains a link or special member", null);
                }
                if (entry.getSize() < 0 || entry.getSize() > policy.maximumMemberBytes()) {
                    throw invalid("TAR member size exceeds policy", null);
                }
                total = Math.addExact(total, entry.getSize());
                if (total > policy.maximumTotalBytes()) throw invalid("TAR total size exceeds policy", null);
                MessageDigest digest = sha256();
                ByteArrayOutputStream capture = documents != null && documents.contains(name)
                        ? new ByteArrayOutputStream() : null;
                long readTotal = 0;
                int read;
                while ((read = archive.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    readTotal = Math.addExact(readTotal, read);
                    if (readTotal > entry.getSize()) throw invalid("TAR member exceeds declared size", null);
                    digest.update(buffer, 0, read);
                    if (capture != null) {
                        if (capture.size() + read > policy.maximumManifestBytes()) {
                            throw invalid("OCI metadata document exceeds policy", null);
                        }
                        capture.write(buffer, 0, read);
                    }
                }
                if (readTotal != entry.getSize()) throw invalid("TAR member is truncated", null);
                String sha = HexFormat.of().formatHex(digest.digest());
                if (name.startsWith("blobs/sha256/")) {
                    String expected = name.substring("blobs/sha256/".length());
                    if (!expected.matches("[0-9a-f]{64}") || !expected.equals(sha)) {
                        throw invalid("OCI blob path differs from its content digest", null);
                    }
                    blobs.put(name, new Blob(entry.getSize(), sha));
                }
                if (capture != null) captured.put(name, capture.toByteArray());
            }
        } catch (BackupException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("managed TAR artifact could not be validated", exception);
        }
        if (entries < 1) throw invalid("managed TAR artifact is empty", null);
        if (documents != null && !captured.keySet().containsAll(documents)) {
            throw invalid("required OCI metadata is missing", null);
        }
        return new OciScan(entries, Map.copyOf(blobs), Map.copyOf(captured));
    }

    private ManagedArtifactEvidence evidence(Path file, ManagedArtifactFormatType format, int entries)
            throws BackupException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = sha256();
            long count = 0;
            byte[] bytes = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(bytes)) >= 0) {
                if (read == 0) continue;
                count = Math.addExact(count, read);
                digest.update(bytes, 0, read);
            }
            return new ManagedArtifactEvidence(file, format, count, HexFormat.of().formatHex(digest.digest()), entries);
        } catch (IOException | ArithmeticException exception) {
            throw invalid("managed artifact digest could not be computed", exception);
        }
    }

    private Path regular(Path path) throws BackupException {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        try {
            long size = Files.size(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                    || size < 1 || size > policy.maximumTotalBytes()) {
                throw invalid("managed artifact is not one bounded regular file", null);
            }
            return path;
        } catch (IOException exception) {
            throw invalid("managed artifact file could not be inspected", exception);
        }
    }

    private String safePath(String raw) throws BackupException {
        String name = Objects.requireNonNull(raw, "TAR path").replace('\\', '/');
        while (name.startsWith("./")) name = name.substring(2);
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.isBlank()) return ".";
        if (name.startsWith("/") || name.length() > policy.maximumPathLength()
                || name.contains("//") || java.util.Arrays.asList(name.split("/")).contains("..")) {
            throw invalid("TAR member path is unsafe", null);
        }
        return name;
    }

    private JsonNode document(byte[] bytes, String name) throws BackupException {
        if (bytes == null) throw invalid(name + " is missing", null);
        try { return json.readTree(bytes); }
        catch (IOException exception) { throw invalid(name + " is not valid JSON", exception); }
    }

    private Descriptor descriptor(JsonNode value) throws BackupException {
        if (!value.isObject() || !value.path("digest").asText().matches("sha256:[0-9a-f]{64}")) {
            throw invalid("OCI descriptor is malformed", null);
        }
        long size = value.path("size").asLong(-1);
        if (size < 0 || size > policy.maximumMemberBytes()) throw invalid("OCI descriptor size is invalid", null);
        return new Descriptor(value.path("digest").asText(), size);
    }

    private void requireBlob(Map<String, Blob> blobs, Descriptor descriptor) throws BackupException {
        Blob blob = blobs.get(blobPath(descriptor.digest()));
        if (blob == null || blob.size() != descriptor.size()) throw invalid("OCI descriptor blob is missing or differs", null);
    }

    private static String blobPath(String digest) { return "blobs/sha256/" + digest.substring("sha256:".length()); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    private static BackupException invalid(String diagnostic, Throwable cause) {
        return BackupException.create(BackupFailureType.INTEGRITY_FAILED, diagnostic, cause);
    }
    private record Blob(long size, String sha256) { }
    private record Descriptor(String digest, long size) { }
    private record OciScan(int entries, Map<String, Blob> blobs, Map<String, byte[]> documents) { }
}
