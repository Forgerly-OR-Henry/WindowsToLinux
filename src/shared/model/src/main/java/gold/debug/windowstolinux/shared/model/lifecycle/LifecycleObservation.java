package gold.debug.windowstolinux.shared.model.lifecycle;

import java.time.Instant;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

/**
 * A verified remote state observed for a managed application.
 *
 *  <p>为受管应用观测到的已验证远端状态。
 *
 * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
 * @param runtimeState runtime state / 运行时状态
 * @param autostartState autostart state / 自动启动状态
 * @param ownershipVerified ownership verified / 归属已验证
 * @param observedAt observed at / 已观测时刻
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record LifecycleObservation(ManagedApplication application, RuntimeState runtimeState,
        AutostartState autostartState, boolean ownershipVerified, Instant observedAt, String evidence) {
    /**
     * Validates and binds the inputs required by lifecycle observation.
     * <p>校验并绑定生命周期观测所需输入。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtimeState runtime state / 运行时状态
     * @param autostartState autostart state / 自动启动状态
     * @param ownershipVerified ownership verified / 归属已验证
     * @param observedAt observed at / 已观测时刻
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public LifecycleObservation {
        application = Objects.requireNonNull(application, "application");
        runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
        autostartState = Objects.requireNonNull(autostartState, "autostartState");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        evidence = Objects.requireNonNull(evidence, "evidence");
        if (!ownershipVerified && runtimeState == RuntimeState.RUNNING) {
            throw new IllegalArgumentException("an unverified resource cannot be reported as managed running");
        }
    }
}
