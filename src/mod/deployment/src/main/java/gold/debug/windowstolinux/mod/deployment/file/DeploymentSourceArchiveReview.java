package gold.debug.windowstolinux.mod.deployment.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Bounded exact text available to the existing independent review of one captured build archive. */
public final class DeploymentSourceArchiveReview {
    private static final int MAX_MEMBERS = 64;
    private static final int MAX_TOTAL_BYTES = 64 * 1024;

    private DeploymentSourceArchiveReview() { }

    public record Member(String path, String sha256, int bytes, String text) { }
    public record Omitted(String path, String reason) { }
    public record Evidence(String archiveSha256, String sourceManifestSha256, String relativeRoot,
                           int memberCount, String coverage, List<Member> members, List<Omitted> omitted) {
        public Evidence {
            members = List.copyOf(members);
            omitted = List.copyOf(omitted);
        }
    }

    /** Reads the same component selection from the original live snapshot, without opening a new local source. */
    public static Evidence capture(DeploymentSourceMaterial material, DeploymentSourceArchive archive, TaskContext task)
        throws IOException, ApiException {
        DeploymentSourceArchiver.cancelled(task);
        if (!archive.originSnapshotId().equals(material.snapshot().id()))
            throw new IOException("Build archive review differs from its original source snapshot");
        String relativeRoot = archive.relativeRoot();
        int prefix = relativeRoot.isEmpty() ? 0 : relativeRoot.length() + 1;
        var names = DeploymentSourceTar.memberNames(material, relativeRoot);
        if (names.size() > MAX_MEMBERS || archive.uncompressedByteCount() > MAX_TOTAL_BYTES)
            return evidence(archive, names.size(), "OUTSIDE_INLINE_REVIEW_BOUNDS", List.of(), List.of());
        var root = material.snapshot().directory();
        var members = new ArrayList<Member>();
        var omitted = new ArrayList<Omitted>();
        long verifiedBytes = 0;
        for (String name : names) {
            DeploymentSourceArchiver.cancelled(task);
            String entry = name.substring(prefix);
            var file = root.resolve(name).normalize();
            if (entry.isEmpty() || !file.startsWith(root) || !root.relativize(file).toString().replace('\\', '/').equals(name)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file))
                throw new IOException("Original archive member is unavailable");
            long size = Files.size(file);
            verifiedBytes += size;
            if (verifiedBytes > archive.uncompressedByteCount())
                throw new IOException("Original archive source byte count changed");
            if (DeploymentSourcePolicy.file(name, Set.of())) {
                omitted.add(new Omitted(entry, "SOURCE_POLICY"));
                continue;
            }
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = stream.readNBytes((int) size + 1);
                try {
                    if (bytes.length != size)
                        throw new IOException("Original archive member byte count changed");
                    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                    if (text.indexOf('\0') >= 0)
                        omitted.add(new Omitted(entry, "NON_TEXT"));
                    else if (text.lines().anyMatch(DeploymentSourceBrowser::protectedText))
                        omitted.add(new Omitted(entry, "PROTECTED_CONTENT"));
                    else
                        members.add(new Member(entry, sha256(bytes), bytes.length, text));
                } catch (CharacterCodingException binary) {
                    omitted.add(new Omitted(entry, "NON_TEXT"));
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        }
        if (verifiedBytes != archive.uncompressedByteCount())
            throw new IOException("Original archive source byte count changed");
        if (!archive.sourceManifestSha256().equals(material.snapshot().sha256()))
            throw new IOException("Build archive review differs from its original source snapshot");
        archive.path();
        DeploymentSourceArchiver.cancelled(task);
        return evidence(archive, names.size(), omitted.isEmpty() ? "COMPLETE" : "PARTIAL", members, omitted);
    }

    private static Evidence evidence(DeploymentSourceArchive archive, int count, String coverage,
                                     List<Member> members, List<Omitted> omitted) {
        return new Evidence(archive.sha256(), archive.sourceManifestSha256(), archive.relativeRoot(),
            count, coverage, members, omitted);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
