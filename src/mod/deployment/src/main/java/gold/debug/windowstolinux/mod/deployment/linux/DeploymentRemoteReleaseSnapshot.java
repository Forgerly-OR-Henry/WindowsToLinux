package gold.debug.windowstolinux.mod.deployment.linux;

import java.util.Objects;
import java.util.Optional;

/** Original helper-owned rollback snapshot, distinct from a local business release row. / 原 helper 所属回滚快照，区别于本机业务发布行。
 * @param token actual previous-release token, or empty for first deployment / 实际旧发布令牌；首次部署为空
 * @param previousRunning whether the previous runtime was active / 旧运行时是否活动
 * @param previousDigest exact prior release bytes identity / 精确旧发布字节身份
 * @param previousKind native, ordinary, or container owner kind / 原生、普通或容器归属类别 */
public record DeploymentRemoteReleaseSnapshot(Optional<String> token, boolean previousRunning,
        Optional<String> previousDigest, String previousKind) {
    /** Validates the exact helper token. / 校验精确 helper 令牌。
     * @param token actual token / 实际令牌
     * @param previousRunning previous activity / 旧运行状态
     * @param previousDigest original digest / 原摘要
     * @param previousKind original kind / 原类别 */
    public DeploymentRemoteReleaseSnapshot {
        token = Objects.requireNonNull(token);
        previousDigest = Objects.requireNonNull(previousDigest);
        previousKind = Objects.requireNonNull(previousKind);
        if (token.isPresent() && !token.orElseThrow().matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid original remote snapshot token");
        if (token.isEmpty() != previousDigest.isEmpty()
                || token.isEmpty() && (previousRunning || !previousKind.equals("none"))
                || token.isPresent() && (!previousDigest.orElseThrow().matches("[0-9a-f]{64}")
                        || !java.util.Set.of("ordinary", "deployment", "container").contains(previousKind)))
            throw new IllegalArgumentException("Invalid original remote snapshot identity");
    }
}
