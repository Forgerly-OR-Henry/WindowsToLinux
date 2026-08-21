package gold.debug.windowstolinux.shared.linux.protocol.restore;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Exact local candidate and member inventory for isolated SFTP staging. / 用于隔离 SFTP 暂存的精确本地候选及成员清单。 */
public record RemoteRestoreStagingRequest(
        String applicationId,
        String archiveSha256,
        Path localCandidateRoot,
        long expectedBytes,
        List<RemoteRestoreMember> members
) {
    /** Binds every member and byte to the digest-derived candidate namespace. / 将每个成员和字节绑定到摘要派生的候选命名空间。 */
    public RemoteRestoreStagingRequest {
        applicationId = Objects.requireNonNull(applicationId, "applicationId").trim();
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("applicationId must be a bounded managed identifier");
        }
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256").trim().toLowerCase(Locale.ROOT);
        if (!archiveSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("archiveSha256 must be canonical SHA-256");
        }
        localCandidateRoot = Objects.requireNonNull(localCandidateRoot, "localCandidateRoot")
                .toAbsolutePath().normalize();
        if (!localCandidateRoot.getFileName().toString().equals(applicationId + "-" + archiveSha256.substring(0, 16))) {
            throw new IllegalArgumentException("local candidate root is not bound to the archive digest");
        }
        if (expectedBytes < 0) throw new IllegalArgumentException("expectedBytes must not be negative");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (members.isEmpty() || members.size() > 4096) {
            throw new IllegalArgumentException("restore members must contain one to 4096 values");
        }
        Set<String> paths = new HashSet<>();
        long bytes = 0;
        for (RemoteRestoreMember member : members) {
            if (!paths.add(member.path().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("restore member paths must be case-insensitively unique");
            }
            bytes = Math.addExact(bytes, member.size());
        }
        if (bytes != expectedBytes) {
            throw new IllegalArgumentException("restore member bytes differ from expectedBytes");
        }
    }

    /** Returns the only allowed candidate identifier. / 返回唯一允许的候选标识。 */
    public String candidateId() {
        return applicationId + "-" + archiveSha256.substring(0, 16);
    }
}
