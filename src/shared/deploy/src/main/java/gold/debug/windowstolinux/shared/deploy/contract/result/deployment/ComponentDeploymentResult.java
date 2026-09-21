package gold.debug.windowstolinux.shared.deploy.contract.result.deployment;

import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Component-scoped deployment evidence and terminal state. / 组件范围的部署证据与终态。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
 * @param events ordered progress or transaction events / 有序进度或事务事件
 * @param observation observation / 观测
 */
public record ComponentDeploymentResult(
        String componentId,
        ComponentTransactionState state,
        List<DeploymentEvent> events,
        Optional<LifecycleObservation> observation
) {
    /**
     * Validates immutable component evidence. / 验证不可变组件证据。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param observation observation / 观测
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ComponentDeploymentResult {
        componentId = Objects.requireNonNull(componentId, "componentId");
        state = Objects.requireNonNull(state, "state");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        observation = Objects.requireNonNull(observation, "observation");
    }
}
