package gold.debug.windowstolinux.shared.linux.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * Opaque remote rollback reference captured before a deployment changes state.
 *
 *  <p>部署改变状态之前捕获的不透明远端回滚引用。
 *
 * @param hasPreviousRelease has previous release / 具有此前发布
 * @param previousWasRunning previous was running / 此前Was运行中
 * @param rollbackToken rollback token / 回滚令牌
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record ReleaseSnapshot(boolean hasPreviousRelease, boolean previousWasRunning, Optional<String> rollbackToken,
        String evidence) {
    /**
     * Validates and binds the inputs required by release snapshot.
     * <p>校验并绑定发布快照所需输入。
     *
     * @param hasPreviousRelease has previous release / 具有此前发布
     * @param previousWasRunning previous was running / 此前Was运行中
     * @param rollbackToken rollback token / 回滚令牌
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ReleaseSnapshot {
        rollbackToken = Objects.requireNonNull(rollbackToken, "rollbackToken");
        evidence = Objects.requireNonNull(evidence, "evidence");
        if (hasPreviousRelease != rollbackToken.isPresent()) {
            throw new IllegalArgumentException("previous release and rollback token must agree");
        }
        if (!hasPreviousRelease && previousWasRunning) {
            throw new IllegalArgumentException("a first deployment cannot have a previous runtime state");
        }
    }

    /**
     * Builds release snapshot from the supplied first deployment inputs.
     * <p>根据所提供首次部署输入构建发布快照。
     *
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return the operation result / 操作结果
     */
    public static ReleaseSnapshot firstDeployment(String evidence) {
        return new ReleaseSnapshot(false, false, Optional.empty(), evidence);
    }

    /**
     * Returns the contract with the supplied previous release applied.
     * <p>返回应用所提供此前发布后的契约。
     *
     * @param token token / 令牌
     * @param previousWasRunning previous was running / 此前Was运行中
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return the operation result / 操作结果
     */
    public static ReleaseSnapshot withPreviousRelease(String token, boolean previousWasRunning, String evidence) {
        return new ReleaseSnapshot(true, previousWasRunning, Optional.of(token), evidence);
    }
}
