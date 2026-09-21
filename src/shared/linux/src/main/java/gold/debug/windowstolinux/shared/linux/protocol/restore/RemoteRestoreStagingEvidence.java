package gold.debug.windowstolinux.shared.linux.protocol.restore;

import java.util.List;
import java.util.Objects;

/**
 * Verified isolated remote staging evidence. / 已验证的隔离远端暂存证据。
 *
 * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
 * @param remoteRoot remote root / 远端根目录
 * @param candidateToken candidate token / 候选令牌
 * @param stagedBytes staged bytes / 已暂存字节
 * @param isolated isolated / 隔离
 * @param integrityVerified integrity verified / 完整性已验证
 * @param existingReleaseUntouched existing release untouched / 既有发布Untouched
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
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
    /**
     * Validates bounded evidence and managed paths. / 校验有界证据和受管路径。
     *
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param remoteRoot remote root / 远端根目录
     * @param candidateToken candidate token / 候选令牌
     * @param stagedBytes staged bytes / 已暂存字节
     * @param isolated isolated / 隔离
     * @param integrityVerified integrity verified / 完整性已验证
     * @param existingReleaseUntouched existing release untouched / 既有发布Untouched
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
