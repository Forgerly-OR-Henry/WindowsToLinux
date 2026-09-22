package gold.debug.windowstolinux.shared.standard.deploy.execution.transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentTransactionState;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;

/**
 * Holds the mutable state of one component inside a reviewed transaction. / 持有一次经审阅事务中单个组件的可变状态。
 */
final class MultiComponentTransactionContext {
    /**
     * Component.
     * <p>组件。
     */
    final ReviewedComponentDeployment component;

    /**
     * Ordered progress or transaction events.
     * <p>有序进度或事务事件。
     */
    List<DeploymentEvent> events = new ArrayList<>();

    /**
     * Current lifecycle or workflow state.
     * <p>当前生命周期或工作流状态。
     */
    ComponentTransactionState state = ComponentTransactionState.PRECONDITION_REJECTED;

    /**
     * Platform-owned work area with enforced path boundaries.
     * <p>具有路径边界约束的平台工作区。
     */
    RemoteWorkspace workspace;

    /**
     * Build.
     * <p>构建。
     */
    DeploymentBuildResult build;

    /**
     * Reviewed non-secret deployment input fields.
     * <p>已审阅的非秘密部署输入字段。
     */
    DeploymentInputManifest inputs;

    /**
     * Digest identifying the exact published release.
     * <p>标识精确已发布版本的摘要。
     */
    String releaseIdentity;

    /**
     * Stopped.
     * <p>已停止。
     */
    boolean stopped;

    /**
     * Published.
     * <p>已发布。
     */
    boolean published;

    /**
     * Observation.
     * <p>观测。
     */
    LifecycleObservation observation;

    /**
     * Binds the supplied dependencies and state for multi component transaction context.
     * <p>为多组件事务上下文绑定传入的依赖及状态。
     *
     * @param component component / 组件
     */
    MultiComponentTransactionContext(ReviewedComponentDeployment component) {
        this.component = component;
    }

    /**
     * Appends one deployment trace result to this component's ordered event list.
     * <p>向当前组件的有序事件列表追加一个部署跟踪结果。
     *
     * @param step step / 步骤
     * @param succeeded whether the build and artifact verification succeeded / 构建和产物验证是否成功
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    void event(DeploymentTraceEvent step, boolean succeeded, String evidence) {
        events.add(DeploymentEvent.result(step, succeeded, evidence));
    }

    /**
     * Records the classified failure in the ordered transaction events.
     * <p>在有序事务事件中记录分类失败。
     *
     * @param step step / 步骤
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    void failure(DeploymentTraceEvent step, gold.debug.windowstolinux.shared.model.failure.FailureDescriptor failure) {
        events.add(DeploymentEvent.failed(step, failure));
    }

    /**
     * Builds component deployment result from the supplied component result inputs.
     * <p>根据所提供组件结果输入构建组件部署结果。
     *
     * @return component deployment result from the supplied component result inputs / 根据所提供组件结果输入构建组件部署结果
     */
    ComponentDeploymentResult componentResult() {
        return new ComponentDeploymentResult(component.componentId(), state, events, Optional.ofNullable(observation));
    }

    /**
     * Builds multi component deployment result from the supplied result inputs.
     * <p>根据所提供结果输入构建多组件部署结果。
     *
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param applicationEvents application events / 应用事件集合
     * @param contexts contexts / 上下文集合
     * @param identity identity / 身份
     * @return multi component deployment result from the supplied result inputs / 根据所提供结果输入构建多组件部署结果
     */
    static MultiComponentDeploymentResult result(DeploymentStatus status, List<DeploymentEvent> applicationEvents,
            Map<String, MultiComponentTransactionContext> contexts, Optional<String> identity) {
        List<ComponentDeploymentResult> results = contexts.values().stream()
                .map(MultiComponentTransactionContext::componentResult).toList();
        return new MultiComponentDeploymentResult(status, applicationEvents, results, identity);
    }
}
