package gold.debug.windowstolinux.shared.model.lifecycle;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.time.Instant;
import java.util.Objects;

/**
 * A verified remote state observed for a managed application.
 *
 * <p>为受管应用观测到的已验证远端状态。
 *
 * @param application the {@code application} value / {@code application} 值
 * @param runtimeState the {@code runtimeState} value / {@code runtimeState} 值
 * @param autostartState the {@code autostartState} value / {@code autostartState} 值
 * @param ownershipVerified the {@code ownershipVerified} value / {@code ownershipVerified} 值
 * @param observedAt the {@code observedAt} value / {@code observedAt} 值
 * @param evidence the {@code evidence} value / {@code evidence} 值
 */
public record LifecycleObservation(
        ManagedApplication application,
        RuntimeState runtimeState,
        AutostartState autostartState,
        boolean ownershipVerified,
        Instant observedAt,
        String evidence
) {
    /**
     * Creates a {@code LifecycleObservation} instance.
     *
     * <p>创建 {@code LifecycleObservation} 实例。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param runtimeState the {@code runtimeState} value / {@code runtimeState} 值
     * @param autostartState the {@code autostartState} value / {@code autostartState} 值
     * @param ownershipVerified the {@code ownershipVerified} value / {@code ownershipVerified} 值
     * @param observedAt the {@code observedAt} value / {@code observedAt} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
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
