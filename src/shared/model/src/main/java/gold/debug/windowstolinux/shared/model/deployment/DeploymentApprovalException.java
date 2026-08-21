package gold.debug.windowstolinux.shared.model.deployment;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Objects;

/** Structured rejection raised before an unapproved target mutation. / 未经批准的目标修改前抛出的结构化拒绝。 */
public final class DeploymentApprovalException extends RuntimeException implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates one approval rejection. / 创建一个批准拒绝。 */
    public DeploymentApprovalException(DeploymentApprovalFailureType type, String diagnostic) {
        this(FailureDescriptor.create(type, OperationIdentity.create(), diagnostic));
    }

    private DeploymentApprovalException(FailureDescriptor failure) {
        super(Objects.requireNonNull(failure, "failure").diagnostic());
        this.failure = failure;
    }

    /** Returns the structured rejection. / 返回结构化拒绝。 */
    @Override
    public FailureDescriptor failure() {
        return failure;
    }
}
