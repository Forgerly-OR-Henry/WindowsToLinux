package gold.debug.windowstolinux.shared.linux.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * Opaque remote rollback reference captured before a deployment changes state.
 *
 * <p>部署改变状态之前捕获的不透明远端回滚引用。
 *
 * @param hasPreviousRelease the {@code hasPreviousRelease} value / {@code hasPreviousRelease} 值
 * @param previousWasRunning the {@code previousWasRunning} value / {@code previousWasRunning} 值
 * @param rollbackToken the {@code rollbackToken} value / {@code rollbackToken} 值
 * @param evidence the {@code evidence} value / {@code evidence} 值
 */
public record ReleaseSnapshot(boolean hasPreviousRelease, boolean previousWasRunning, Optional<String> rollbackToken, String evidence) {
    /**
     * Creates a {@code ReleaseSnapshot} instance.
     *
     * <p>创建 {@code ReleaseSnapshot} 实例。
     *
     * @param hasPreviousRelease the {@code hasPreviousRelease} value / {@code hasPreviousRelease} 值
     * @param previousWasRunning the {@code previousWasRunning} value / {@code previousWasRunning} 值
     * @param rollbackToken the {@code rollbackToken} value / {@code rollbackToken} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
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
     * Performs the {@code firstDeployment} operation.
     *
     * <p>执行 {@code firstDeployment} 操作。
     *
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @return the operation result / 操作结果
     */
    public static ReleaseSnapshot firstDeployment(String evidence) {
        return new ReleaseSnapshot(false, false, Optional.empty(), evidence);
    }

    /**
     * Performs the {@code withPreviousRelease} operation.
     *
     * <p>执行 {@code withPreviousRelease} 操作。
     *
     * @param token the {@code token} value / {@code token} 值
     * @param previousWasRunning the {@code previousWasRunning} value / {@code previousWasRunning} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @return the operation result / 操作结果
     */
    public static ReleaseSnapshot withPreviousRelease(String token, boolean previousWasRunning, String evidence) {
        return new ReleaseSnapshot(true, previousWasRunning, Optional.of(token), evidence);
    }
}
