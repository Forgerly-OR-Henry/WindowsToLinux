package gold.debug.windowstolinux.shared.deploy.error;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Objects;

/** Structured internal stop/publish/health/observation switch failure. / 结构化内部停止、发布、健康或观测切换失败。 */
public final class DeploymentSwitchException extends RuntimeException implements FailureCarrier {
    private final DeploymentTraceEvent step;
    private final FailureDescriptor failure;

    /** Creates one switch failure. / 创建一次切换失败。 */
    public DeploymentSwitchException(DeploymentTraceEvent step, FailureDescriptor failure) {
        super(Objects.requireNonNull(failure, "failure").diagnostic());
        this.step = Objects.requireNonNull(step, "step");
        this.failure = failure;
    }

    /** Creates a typed switch failure. / 创建类型化切换失败。 */
    public static DeploymentSwitchException create(
            DeploymentTraceEvent step, DeploymentExecutionFailureType type, String diagnostic) {
        return new DeploymentSwitchException(step,
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic));
    }

    public DeploymentTraceEvent step() { return step; }
    @Override public FailureDescriptor failure() { return failure; }
}
