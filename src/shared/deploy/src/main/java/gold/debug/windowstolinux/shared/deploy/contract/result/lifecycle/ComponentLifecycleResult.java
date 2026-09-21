package gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle;

import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Objects;
import java.util.Optional;

/**
 * Live component outcome from one application lifecycle request. / 一次应用生命周期请求中的组件实时结果。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param actionAttempted action attempted / 动作已尝试
 * @param accepted accepted / 已接受
 * @param message localized explanation / 本地化说明
 * @param observation observation / 观测
 */
public record ComponentLifecycleResult(
        String componentId,
        boolean actionAttempted,
        boolean accepted,
        LocalizedMessage message,
        Optional<LifecycleObservation> observation
) {
    /**
     * Validates bounded component evidence. / 验证有界的组件证据。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param actionAttempted action attempted / 动作已尝试
     * @param accepted accepted / 已接受
     * @param message localized explanation / 本地化说明
     * @param observation observation / 观测
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ComponentLifecycleResult {
        componentId = Objects.requireNonNull(componentId, "componentId");
        message = Objects.requireNonNull(message, "message");
        observation = Objects.requireNonNull(observation, "observation");
        if (accepted && observation.isEmpty()) {
            throw new IllegalArgumentException("accepted component lifecycle results require a live observation");
        }
    }
}
