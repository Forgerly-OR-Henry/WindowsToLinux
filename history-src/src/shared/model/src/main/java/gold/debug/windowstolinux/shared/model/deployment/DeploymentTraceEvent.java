package gold.debug.windowstolinux.shared.model.deployment;

import java.util.Arrays;

/**
 * Stable deployment trace codes; UI modules map these codes to localized labels.
 *
 *  <p>稳定的部署跟踪代码；界面模块将这些代码映射为本地化标签。
 */
public enum DeploymentTraceEvent {
    /**
     * Verifies the managed application identity before any remote operation. / 在任何远端操作前验证受管应用身份。
     */
    MANAGED_IDENTITY("managed-identity"),
    /**
     * Represents the {@code ROOT_BUILD_SESSION} option.
     *
     *  <p>表示 {@code ROOT_BUILD_SESSION} 选项。
     */
    ROOT_BUILD_SESSION("root-build-session"),
    /**
     * Verifies the installed helper protocol before source upload. / 在上传源码前验证已安装的 helper 协议。
     */
    HELPER_PROTOCOL("helper-protocol"),
    /**
     * Verifies every component before an application transaction. / 在应用事务前验证每个组件。
     */
    APPLICATION_PREFLIGHT("application-preflight"),
    /**
     * Records one deterministic parallel-safe component build wave. / 记录一个确定性的可安全并行组件构建波次。
     */
    COMPONENT_BUILD_WAVE("component-build-wave"),
    /**
     * Represents the {@code TARGET_CAPABILITIES} option.
     *
     *  <p>表示 {@code TARGET_CAPABILITIES} 选项。
     */
    TARGET_CAPABILITIES("target-capabilities"),
    /**
     * Represents the {@code TYPED_HOST_COMPATIBILITY} option.
     *
     *  <p>表示 {@code TYPED_HOST_COMPATIBILITY} 选项。
     */
    TYPED_HOST_COMPATIBILITY("typed-host-compatibility"),
    /**
     * Represents the {@code SOURCE_WORKSPACE} option.
     *
     *  <p>表示 {@code SOURCE_WORKSPACE} 选项。
     */
    SOURCE_WORKSPACE("source-workspace"),
    /**
     * Represents the {@code TARGET_SPACE} option.
     *
     *  <p>表示 {@code TARGET_SPACE} 选项。
     */
    TARGET_SPACE("target-space"),
    /**
     * Represents the {@code SOURCE_UPLOAD} option.
     *
     *  <p>表示 {@code SOURCE_UPLOAD} 选项。
     */
    SOURCE_UPLOAD("source-upload"),
    /**
     * Represents the {@code REMOTE_BUILD} option.
     *
     *  <p>表示 {@code REMOTE_BUILD} 选项。
     */
    REMOTE_BUILD("remote-build"),
    /**
     * Verifies that the build result is bound to the reviewed source identity. / 验证构建结果绑定到经审阅源码身份。
     */
    BUILD_PROVENANCE("build-provenance"),
    /**
     * Records one completely built and sealed component candidate. / 记录一个已完整构建并封存的组件候选。
     */
    CANDIDATE_READY("candidate-ready"),
    /**
     * Represents the {@code DEPLOYMENT_INPUTS} option.
     *
     *  <p>表示 {@code DEPLOYMENT_INPUTS} 选项。
     */
    DEPLOYMENT_INPUTS("deployment-inputs"),
    /**
     * Represents the {@code SNAPSHOT} option.
     *
     *  <p>表示 {@code SNAPSHOT} 选项。
     */
    SNAPSHOT("snapshot"),
    /**
     * Records that every affected component snapshot is complete. / 记录所有受影响组件快照均已完成。
     */
    APPLICATION_SNAPSHOT("application-snapshot"),
    /**
     * Stops one old component after all snapshots are complete. / 在全部快照完成后停止一个旧组件。
     */
    STOP_OLD("stop-old"),
    /**
     * Records completion of reverse dependency stop ordering. / 记录逆依赖停止顺序已完成。
     */
    APPLICATION_STOP("application-stop"),
    /**
     * Represents the {@code PUBLISH} option.
     *
     *  <p>表示 {@code PUBLISH} 选项。
     */
    PUBLISH("publish"),
    /**
     * Represents the {@code CANDIDATE_HEALTH} option.
     *
     *  <p>表示 {@code CANDIDATE_HEALTH} 选项。
     */
    CANDIDATE_HEALTH("candidate-health"),
    /**
     * Checks the whole-application business health gate. / 检查整体应用业务健康门。
     */
    APPLICATION_HEALTH("application-health"),
    /**
     * Represents the {@code FINAL_OBSERVATION} option.
     *
     *  <p>表示 {@code FINAL_OBSERVATION} 选项。
     */
    FINAL_OBSERVATION("final-observation"),
    /**
     * Records authoritative observations for every component. / 记录每个组件的权威观测。
     */
    APPLICATION_OBSERVATION("application-observation"),
    /**
     * Represents the {@code RELEASE_RETENTION} option.
     *
     *  <p>表示 {@code RELEASE_RETENTION} 选项。
     */
    RELEASE_RETENTION("release-retention"),
    /**
     * Commits a verified whole-application release identity. / 提交一个已验证的整体应用发布身份。
     */
    APPLICATION_COMMIT("application-commit"),
    /**
     * Represents the {@code LINUX_OPERATION} option.
     *
     *  <p>表示 {@code LINUX_OPERATION} 选项。
     */
    LINUX_OPERATION("linux-operation"),
    /**
     * Records a controlled multi-component Linux failure. / 记录受控的多组件 Linux 失败。
     */
    MULTI_COMPONENT_LINUX_OPERATION("multi-component-linux-operation"),
    /**
     * Represents the {@code ROLLBACK} option.
     *
     *  <p>表示 {@code ROLLBACK} 选项。
     */
    ROLLBACK("rollback"),
    /**
     * Represents the {@code ROLLBACK_HEALTH} option.
     *
     *  <p>表示 {@code ROLLBACK_HEALTH} 选项。
     */
    ROLLBACK_HEALTH("rollback-health"),
    /**
     * Represents the {@code ROLLBACK_OBSERVATION} option.
     *
     *  <p>表示 {@code ROLLBACK_OBSERVATION} 选项。
     */
    ROLLBACK_OBSERVATION("rollback-observation"),
    /**
     * Represents the {@code RECOVERY_RECONNECT} option.
     *
     *  <p>表示 {@code RECOVERY_RECONNECT} 选项。
     */
    RECOVERY_RECONNECT("recovery-reconnect"),
    /**
     * Reconnects and reverifies the host for application recovery. / 为应用恢复重连并重新验证主机。
     */
    APPLICATION_RECOVERY_RECONNECT("application-recovery-reconnect"),
    /**
     * Records a whole-application rollback failure. / 记录整体应用回滚失败。
     */
    APPLICATION_ROLLBACK("application-rollback"),
    /**
     * Represents the {@code CANDIDATE_CLEANUP_RECONNECT} option.
     *
     *  <p>表示 {@code CANDIDATE_CLEANUP_RECONNECT} 选项。
     */
    CANDIDATE_CLEANUP_RECONNECT("candidate-cleanup-reconnect"),
    /**
     * Represents the {@code CANDIDATE_CLEANUP} option.
     *
     *  <p>表示 {@code CANDIDATE_CLEANUP} 选项。
     */
    CANDIDATE_CLEANUP("candidate-cleanup");

    /**
     * Stable machine-readable classification code.
     * <p>稳定的机器可读分类码。
     */
    private final String code;

    /**
     * Binds the supplied dependencies and state for deployment trace event.
     * <p>为部署跟踪事件绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     */
    DeploymentTraceEvent(String code) {
        this.code = code;
    }

    /**
     * Returns stable machine-readable classification code.
     * <p>返回稳定的机器可读分类码。
     *
     * @return the operation result / 操作结果
     */
    public String code() {
        return code;
    }

    /**
     * Creates a value through {@code fromCode}.
     *
     *  <p>通过 {@code fromCode} 创建值。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @return the operation result / 操作结果
     */
    public static DeploymentTraceEvent fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported deployment step code: " + code));
    }
}
