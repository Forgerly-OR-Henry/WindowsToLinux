package gold.debug.windowstolinux.shared.model.deployment;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Objects;

/**
 * Structured rejection raised before an unapproved target mutation. / 未经批准的目标修改前抛出的结构化拒绝。
 */
public final class DeploymentApprovalException extends RuntimeException implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Creates one approval rejection. / 创建一个批准拒绝。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     */
    public DeploymentApprovalException(DeploymentApprovalFailureType type, String diagnostic) {
        this(FailureDescriptor.create(type, OperationIdentity.create(), diagnostic));
    }

    /**
     * Validates and binds the inputs required by deployment approval exception.
     * <p>校验并绑定部署Approval异常所需输入。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private DeploymentApprovalException(FailureDescriptor failure) {
        super(Objects.requireNonNull(failure, "failure").diagnostic());
        this.failure = failure;
    }

    /**
     * Returns the structured rejection. / 返回结构化拒绝。
     *
     * @return the structured rejection / 结构化拒绝
     */
    @Override
    public FailureDescriptor failure() {
        return failure;
    }
}
