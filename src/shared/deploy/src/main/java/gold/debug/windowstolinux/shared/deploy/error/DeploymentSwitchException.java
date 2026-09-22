package gold.debug.windowstolinux.shared.deploy.error;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * Structured internal stop/publish/health/observation switch failure. / 结构化内部停止、发布、健康或观测切换失败。
 */
public final class DeploymentSwitchException extends RuntimeException implements FailureCarrier {
    /**
     * Step.
     * <p>步骤。
     */
    private final DeploymentTraceEvent step;

    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Creates one switch failure. / 创建一次切换失败。
     *
     * @param step step / 步骤
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentSwitchException(DeploymentTraceEvent step, FailureDescriptor failure) {
        super(Objects.requireNonNull(failure, "failure").diagnostic());
        this.step = Objects.requireNonNull(step, "step");
        this.failure = failure;
    }

    /**
     * Creates a typed switch failure. / 创建类型化切换失败。
     *
     * @param step step / 步骤
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a typed switch failure / 类型化切换失败
     */
    public static DeploymentSwitchException create(DeploymentTraceEvent step, DeploymentExecutionFailureType type,
            String diagnostic) {
        return new DeploymentSwitchException(step,
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic));
    }

    /**
     * Returns step.
     * <p>返回步骤。
     *
     * @return step / 步骤
     */
    public DeploymentTraceEvent step() {
        return step;
    }

    /**
     * Returns structured failure occurrence retained for safe reporting.
     * <p>返回保留用于安全报告的结构化失败实例。
     *
     * @return structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    @Override
    public FailureDescriptor failure() {
        return failure;
    }
}
