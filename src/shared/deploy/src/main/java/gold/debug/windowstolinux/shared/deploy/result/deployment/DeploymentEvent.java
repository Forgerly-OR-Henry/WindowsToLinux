package gold.debug.windowstolinux.shared.deploy.result.deployment;


import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;

import java.util.Map;
import java.util.Objects;

/**
 * A short, secret-free trace entry suitable for UI and persisted history.
 *
 * <p>适用于界面和持久化历史的简短、无秘密跟踪条目。
 *
 * @param step the {@code step} value / {@code step} 值
 * @param succeeded the {@code succeeded} value / {@code succeeded} 值
 * @param message the {@code message} value / {@code message} 值
 * @param evidence the {@code evidence} value / {@code evidence} 值
 */
public record DeploymentEvent(String step, boolean succeeded, LocalizedMessage message, String evidence) {
    /**
     * Creates a {@code DeploymentEvent} instance.
     *
     * <p>创建 {@code DeploymentEvent} 实例。
     *
     * @param step the {@code step} value / {@code step} 值
     * @param succeeded the {@code succeeded} value / {@code succeeded} 值
     * @param message the {@code message} value / {@code message} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DeploymentEvent {
        step = Objects.requireNonNull(step, "step");
        message = Objects.requireNonNull(message, "message");
        evidence = Objects.requireNonNull(evidence, "evidence");
        step = DeploymentTraceEvent.fromCode(step).code();
    }

    /**
     * Compatibility constructor for diagnostic-only integrations.
     *
     * <p>用于仅提供诊断信息的集成兼容构造器。
     *
     * @param step the {@code step} value / {@code step} 值
     * @param succeeded the {@code succeeded} value / {@code succeeded} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     */
    public DeploymentEvent(String step, boolean succeeded, String evidence) {
        this(step, succeeded, LocalizedMessage.of(succeeded ? "deployment.event.succeeded" : "deployment.event.failed",
                Map.of("step", step)), evidence);
    }
}
