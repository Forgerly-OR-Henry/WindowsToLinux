package gold.debug.windowstolinux.shared.backup.format;

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

/** Deterministic ZIP writer that verifies every stream against the manifest while writing. / 写入时逐流对照清单校验的确定性 ZIP 写入器。 */
public final class BackupArchiveWriter {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final BackupArchivePolicy policy;
    private final BackupManifestCodec codec;

    /** Creates a writer with explicit archive bounds. / 使用显式归档边界创建写入器。 */
    public BackupArchiveWriter(BackupArchivePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.codec = new BackupManifestCodec();
    }

    /** Writes one complete archive; callers must discard their temporary destination on failure. / 写入完整归档；失败时调用方必须丢弃临时目标。 */
    public void write(BackupManifest manifest, List<BackupArchiveContent> contents, OutputStream destination)
            throws BackupException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(destination, "destination");
        new BackupManifestValidator(policy).validate(manifest);
        Map<String, BackupArchiveContent> indexed = index(contents);
        if (!indexed.keySet().equals(manifest.members().stream().map(BackupMember::path)
                .collect(java.util.stream.Collectors.toSet()))) {
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
            for (BackupMember member : manifest.members()) writeMember(archive, member, indexed.get(member.path()));
            archive.finish();
        } catch (BackupException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw BackupException.create(BackupFailureType.WRITE_FAILED, "backup archive writing failed", exception);
        }
    }

    private Map<String, BackupArchiveContent> index(List<BackupArchiveContent> contents) throws BackupException {
        Map<String, BackupArchiveContent> indexed = new HashMap<>();
        for (BackupArchiveContent content : contents) {
            if (indexed.putIfAbsent(content.member().path(), content) != null) {
                throw BackupException.create(BackupFailureType.MANIFEST_INVALID, "duplicate archive content stream");
            }
        }
        return Map.copyOf(indexed);
    }

    private void writeBytes(ZipArchiveOutputStream archive, String path, byte[] content) throws IOException {
        ZipArchiveEntry entry = regularEntry(path);
        archive.putArchiveEntry(entry);
        archive.write(content);
        archive.closeArchiveEntry();
    }

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
                if (read == 0) continue;
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

    private static ZipArchiveEntry regularEntry(String path) {
        ZipArchiveEntry entry = new ZipArchiveEntry(path);
        entry.setTime(0L);
        entry.setUnixMode(UnixStat.FILE_FLAG | 0600);
        return entry;
    }

    private static MessageDigest sha256() throws BackupException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw BackupException.create(BackupFailureType.WRITE_FAILED, "SHA-256 is unavailable", exception);
        }
    }

    private static OutputStream closeShield(OutputStream destination) {
        return new FilterOutputStream(destination) {
            @Override public void close() throws IOException { flush(); }
        };
    }
}
