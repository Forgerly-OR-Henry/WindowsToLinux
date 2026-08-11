package gold.debug.windowstolinux.shared.model.deployment;

import java.util.Arrays;

/**
 * Stable deployment trace codes; UI modules map these codes to localized labels.
 *
 * <p>稳定的部署跟踪代码；界面模块将这些代码映射为本地化标签。
 */
public enum DeploymentStep {
    /**
     * Represents the {@code ROOT_BUILD_SESSION} option.
     *
     * <p>表示 {@code ROOT_BUILD_SESSION} 选项。
     */
    ROOT_BUILD_SESSION("root-build-session"),
    /**
     * Represents the {@code TARGET_CAPABILITIES} option.
     *
     * <p>表示 {@code TARGET_CAPABILITIES} 选项。
     */
    TARGET_CAPABILITIES("target-capabilities"),
    /**
     * Represents the {@code SOURCE_WORKSPACE} option.
     *
     * <p>表示 {@code SOURCE_WORKSPACE} 选项。
     */
    SOURCE_WORKSPACE("source-workspace"),
    /**
     * Represents the {@code TARGET_SPACE} option.
     *
     * <p>表示 {@code TARGET_SPACE} 选项。
     */
    TARGET_SPACE("target-space"),
    /**
     * Represents the {@code SOURCE_UPLOAD} option.
     *
     * <p>表示 {@code SOURCE_UPLOAD} 选项。
     */
    SOURCE_UPLOAD("source-upload"),
    /**
     * Represents the {@code REMOTE_BUILD} option.
     *
     * <p>表示 {@code REMOTE_BUILD} 选项。
     */
    REMOTE_BUILD("remote-build"),
    /**
     * Represents the {@code SNAPSHOT} option.
     *
     * <p>表示 {@code SNAPSHOT} 选项。
     */
    SNAPSHOT("snapshot"),
    /**
     * Represents the {@code PUBLISH} option.
     *
     * <p>表示 {@code PUBLISH} 选项。
     */
    PUBLISH("publish"),
    /**
     * Represents the {@code CANDIDATE_HEALTH} option.
     *
     * <p>表示 {@code CANDIDATE_HEALTH} 选项。
     */
    CANDIDATE_HEALTH("candidate-health"),
    /**
     * Represents the {@code FINAL_OBSERVATION} option.
     *
     * <p>表示 {@code FINAL_OBSERVATION} 选项。
     */
    FINAL_OBSERVATION("final-observation"),
    /**
     * Represents the {@code RELEASE_RETENTION} option.
     *
     * <p>表示 {@code RELEASE_RETENTION} 选项。
     */
    RELEASE_RETENTION("release-retention"),
    /**
     * Represents the {@code LINUX_OPERATION} option.
     *
     * <p>表示 {@code LINUX_OPERATION} 选项。
     */
    LINUX_OPERATION("linux-operation"),
    /**
     * Represents the {@code ROLLBACK} option.
     *
     * <p>表示 {@code ROLLBACK} 选项。
     */
    ROLLBACK("rollback"),
    /**
     * Represents the {@code ROLLBACK_HEALTH} option.
     *
     * <p>表示 {@code ROLLBACK_HEALTH} 选项。
     */
    ROLLBACK_HEALTH("rollback-health"),
    /**
     * Represents the {@code ROLLBACK_OBSERVATION} option.
     *
     * <p>表示 {@code ROLLBACK_OBSERVATION} 选项。
     */
    ROLLBACK_OBSERVATION("rollback-observation"),
    /**
     * Represents the {@code RECOVERY_RECONNECT} option.
     *
     * <p>表示 {@code RECOVERY_RECONNECT} 选项。
     */
    RECOVERY_RECONNECT("recovery-reconnect"),
    /**
     * Represents the {@code CANDIDATE_CLEANUP_RECONNECT} option.
     *
     * <p>表示 {@code CANDIDATE_CLEANUP_RECONNECT} 选项。
     */
    CANDIDATE_CLEANUP_RECONNECT("candidate-cleanup-reconnect"),
    /**
     * Represents the {@code CANDIDATE_CLEANUP} option.
     *
     * <p>表示 {@code CANDIDATE_CLEANUP} 选项。
     */
    CANDIDATE_CLEANUP("candidate-cleanup");

    private final String code;

    DeploymentStep(String code) {
        this.code = code;
    }

    /**
     * Performs the {@code code} operation.
     *
     * <p>执行 {@code code} 操作。
     *
     * @return the operation result / 操作结果
     */
    public String code() {
        return code;
    }

    /**
     * Creates a value through {@code fromCode}.
     *
     * <p>通过 {@code fromCode} 创建值。
     *
     * @param code the {@code code} value / {@code code} 值
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     */
    public static DeploymentStep fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported deployment step code: " + code));
    }
}
