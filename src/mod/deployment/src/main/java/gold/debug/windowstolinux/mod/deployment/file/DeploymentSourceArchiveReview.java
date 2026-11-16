package gold.debug.windowstolinux.mod.deployment.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Bounded exact text available to the existing independent review of one captured build archive. */
public final class DeploymentSourceArchiveReview {
    private static final int MAX_MEMBERS = 64;
    private static final int MAX_INLINE_BYTES = 64 * 1024;
    private static final int MAX_PAGED_MEMBER_BYTES = 1024 * 1024;
    private static final int MAX_READ_BYTES = MAX_INLINE_BYTES + MAX_PAGED_MEMBER_BYTES;
    private static final int MAX_PAGED_LINE_CHARACTERS = 16 * 1024;
    private static final String VENDORED_JSON_HEADER = "vendor/nlohmann/json.hpp";
    // The upstream release publishes this digest for the single-header distribution.
    // https://github.com/nlohmann/json/releases/tag/v3.12.0
    private static final String NLOHMANN_JSON_3_12_0_SHA256 =
        "aaf127c04cb31c406e5b04a63f1ae89369fccde6d8fa7cdda1ed4f32dfc5de63";

    private DeploymentSourceArchiveReview() { }

    public record Member(String path, String sha256, int bytes, int characters,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String text) {
        public Member(String path, String sha256, int bytes, String text) {
            this(path, sha256, bytes, text.length(), text);
        }
    }
    public record Omitted(String path, long bytes, String reason) { }
    public record Evidence(String archiveSha256, String sourceManifestSha256, String relativeRoot,
                           int memberCount, String coverage, int uninspectedMembers, boolean requiredUnavailable,
                           List<Member> members, List<Omitted> omitted, @JsonIgnore Map<String, String> pagedBodies) {
        public Evidence(String archiveSha256, String sourceManifestSha256, String relativeRoot,
                        int memberCount, String coverage, List<Member> members, List<Omitted> omitted) {
            this(archiveSha256, sourceManifestSha256, relativeRoot, memberCount, coverage, 0,
                "OUTSIDE_INLINE_REVIEW_BOUNDS".equals(coverage), members, omitted, Map.of());
        }

        public Evidence {
            members = List.copyOf(members);
            omitted = List.copyOf(omitted);
            pagedBodies = Map.copyOf(pagedBodies);
        }
    }

    private record Original(String entry, String snapshotName, Path file, BasicFileAttributes attributes) {
        long bytes() { return attributes.size(); }
    }

    /** Verifies the original platform-owned archive and source handles without recollecting review text. */
    public static void verifyBinding(DeploymentSourceMaterial material, DeploymentSourceArchive archive, TaskContext task)
        throws IOException, ApiException {
        DeploymentSourceArchiver.cancelled(task);
        try {
            if (!archive.originSnapshotId().equals(material.snapshot().id())
                || !archive.sourceManifestSha256().equals(material.snapshot().sha256()))
                throw new IOException("Build archive review differs from its original source snapshot");
            var file = archive.path();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file)
                || Files.size(file) != archive.byteCount())
                throw new IOException("Original review archive is unavailable");
        } catch (IllegalStateException unavailable) {
            throw new IOException("Original build archive or source snapshot is unavailable", unavailable);
        }
    }

    /** Reads bounded members from the original verified source snapshot, never expanding the compressed archive. */
    public static Evidence capture(DeploymentSourceMaterial material, DeploymentSourceArchive archive, TaskContext task)
        throws IOException, ApiException {
        verifyBinding(material, archive, task);
        String relativeRoot = archive.relativeRoot();
        int prefix = relativeRoot.isEmpty() ? 0 : relativeRoot.length() + 1;
        var names = DeploymentSourceTar.memberNames(material, relativeRoot);
        var originals = new ArrayList<Original>();
        long verifiedBytes = 0;
        var root = material.snapshot().directory();
        for (String name : names.subList(0, Math.min(MAX_MEMBERS, names.size()))) {
            DeploymentSourceArchiver.cancelled(task);
            String entry = name.substring(prefix);
            var file = root.resolve(name).normalize();
            if (!DeploymentSourceArchive.validRelativeRoot(entry) || entry.isEmpty() || !file.startsWith(root)
                || !root.relativize(file).toString().replace('\\', '/').equals(name) || !file.toRealPath().equals(file))
                throw new IOException("Original archive member is unavailable");
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isOther() || attributes.isSymbolicLink())
                throw new IOException("Original archive member is unavailable");
            if (attributes.size() > archive.uncompressedByteCount() - verifiedBytes)
                throw new IOException("Original archive source byte count changed");
            verifiedBytes += attributes.size();
            originals.add(new Original(entry, name, file, attributes));
        }
        int uninspected = names.size() - originals.size();
        if (uninspected == 0 && verifiedBytes != archive.uncompressedByteCount())
            throw new IOException("Original archive source byte count changed");

        var members = new TreeMap<String, Member>();
        var omitted = new TreeMap<String, Omitted>();
        var pagedBodies = new TreeMap<String, String>();
        int inlineRemaining = MAX_INLINE_BYTES;
        int readRemaining = MAX_READ_BYTES;
        boolean requiredUnavailable = uninspected > 0;
        // Prioritize small complete members even when a large member sorts before the project's own files.
        originals.sort(Comparator.comparingLong(Original::bytes));
        for (var original : originals) {
            DeploymentSourceArchiver.cancelled(task);
            String reason = "SOURCE_POLICY";
            if (!DeploymentSourcePolicy.file(original.snapshotName(), Set.of())) {
                long bytes = original.bytes();
                reason = "REVIEW_BYTE_LIMIT";
                if (bytes <= Math.min(readRemaining, MAX_PAGED_MEMBER_BYTES)) {
                    readRemaining -= (int) bytes;
                    boolean inline = bytes <= inlineRemaining;
                    reason = collect(original, inline, members, pagedBodies);
                    if (reason == null) {
                        if (inline) inlineRemaining -= (int) bytes;
                        continue;
                    }
                }
                // Previous inline reviews could explicitly omit protected or non-text members. A large
                // unavailable member must also stop approval when its raw source cannot be supplied.
                requiredUnavailable |= bytes > MAX_INLINE_BYTES || reason.startsWith("REVIEW_");
            }
            omitted.put(original.entry(), new Omitted(original.entry(), original.bytes(), reason));
        }
        verifyBinding(material, archive, task);
        String coverage = omitted.isEmpty() && uninspected == 0 ? "COMPLETE" : "PARTIAL";
        return new Evidence(archive.sha256(), archive.sourceManifestSha256(), relativeRoot, names.size(), coverage,
            uninspected, requiredUnavailable, List.copyOf(members.values()), List.copyOf(omitted.values()), pagedBodies);
    }

    private static String collect(Original original, boolean inline, Map<String, Member> members,
                                  Map<String, String> pagedBodies) throws IOException {
        byte[] bytes;
        try (var stream = Files.newInputStream(original.file(), LinkOption.NOFOLLOW_LINKS)) {
            bytes = stream.readNBytes((int) original.bytes() + 1);
        }
        try {
            var before = original.attributes();
            var after = Files.readAttributes(original.file(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (bytes.length != before.size() || !after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !original.file().toRealPath().equals(original.file()))
                throw new IOException("Original archive member changed during review");
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) return "NON_TEXT";
            if (!inline && text.lines().anyMatch(line -> line.length() > MAX_PAGED_LINE_CHARACTERS))
                return "REVIEW_LINE_LIMIT";
            String digest = sha256(bytes);
            if (text.lines().anyMatch(DeploymentSourceBrowser::protectedText)
                && !publishedJsonHeader(original.entry(), digest)) return "PROTECTED_CONTENT";
            if (!inline) pagedBodies.put(original.entry(), text);
            members.put(original.entry(), new Member(original.entry(), digest, bytes.length, text.length(),
                inline ? text : null));
            return null;
        } catch (CharacterCodingException binary) {
            return "NON_TEXT";
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static boolean publishedJsonHeader(String entry, String digest) {
        return (entry.equals(VENDORED_JSON_HEADER) || entry.endsWith("/" + VENDORED_JSON_HEADER))
            && digest.equals(NLOHMANN_JSON_3_12_0_SHA256);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
