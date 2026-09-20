package gold.debug.windowstolinux.shared.deploy.contract.result.deployment;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A typed, secret-free deployment trace entry with an optional structured failure. / 可携带结构化失败的类型化无秘密部署跟踪条目。 */
public record DeploymentEvent(
        DeploymentTraceEvent step,
        boolean succeeded,
        LocalizedMessage message,
        String evidence,
        Optional<FailureDescriptor> failure
) {
    /** Validates one immutable trace event. / 校验一项不可变跟踪事件。 */
    public DeploymentEvent {
        step = Objects.requireNonNull(step, "step");
        message = Objects.requireNonNull(message, "message");
        evidence = Objects.requireNonNull(evidence, "evidence");
        failure = Objects.requireNonNull(failure, "failure");
        if (succeeded && failure.isPresent()) {
            throw new IllegalArgumentException("a successful deployment event cannot carry a failure");
        }
    }

    /** Creates a typed controlled step result. / 创建类型化受控步骤结果。 */
    public static DeploymentEvent result(DeploymentTraceEvent step, boolean succeeded, String evidence) {
        return new DeploymentEvent(step, succeeded,
                LocalizedMessage.of(succeeded ? "deployment.event.succeeded" : "deployment.event.failed",
                        succeeded ? Map.of("step", step.code()) : Map.of("step", step.code(), "detail", evidence)),
                evidence, Optional.empty());
    }

    /** Creates a typed failed step carrying its shared failure descriptor. / 创建携带共用失败描述的类型化失败步骤。 */
    public static DeploymentEvent failed(DeploymentTraceEvent step, FailureDescriptor failure) {
        Objects.requireNonNull(failure, "failure");
        return new DeploymentEvent(step, false, failure.userMessage(), failure.diagnostic(), Optional.of(failure));
    }

    /** Returns this event with a carried failure rebound to the enclosing operation. / 返回将携带失败绑定到外层操作的事件。 */
    public DeploymentEvent withOperationIdentity(gold.debug.windowstolinux.shared.model.failure.OperationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return failure.map(value -> failed(step, value.withOperationIdentity(identity))).orElse(this);
    }
}
