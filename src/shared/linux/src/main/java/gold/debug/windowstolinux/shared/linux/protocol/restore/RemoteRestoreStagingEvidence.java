package gold.debug.windowstolinux.shared.linux.protocol.restore;

import java.util.List;
import java.util.Objects;

/** Verified isolated remote staging evidence. / 已验证的隔离远端暂存证据。 */
public record RemoteRestoreStagingEvidence(
        String candidateId,
        String remoteRoot,
        String candidateToken,
        long stagedBytes,
        boolean isolated,
        boolean integrityVerified,
        boolean existingReleaseUntouched,
        List<String> evidence
) {
    /** Validates bounded evidence and managed paths. / 校验有界证据和受管路径。 */
    public RemoteRestoreStagingEvidence {
        candidateId = Objects.requireNonNull(candidateId, "candidateId").trim();
        if (!candidateId.matches("[a-z0-9][a-z0-9-]{0,79}")) {
            throw new IllegalArgumentException("candidateId is invalid");
        }
        remoteRoot = Objects.requireNonNull(remoteRoot, "remoteRoot").trim();
        String expectedRoot = "/var/lib/windowstolinux/work/" + candidateId + "/mutable/restore";
        if (!remoteRoot.equals(expectedRoot)) throw new IllegalArgumentException("remoteRoot is outside the candidate");
        candidateToken = Objects.requireNonNull(candidateToken, "candidateToken").trim();
        if (!candidateToken.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("candidateToken is invalid");
        if (stagedBytes < 0) throw new IllegalArgumentException("stagedBytes must not be negative");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 64 || evidence.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("staging evidence is invalid");
        }
    }
}
