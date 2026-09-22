package gold.debug.windowstolinux.app.service.deployment.single;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentRequest;

/**
 * Secret-free deployment summary rendered by the desktop UI.
 *
 *  <p>由桌面界面呈现的无秘密部署摘要。
 *
 * @param result the complete typed deployment result / 完整的类型化部署结果
 * @param handoff handoff / 交接
 */
public record DeploymentOutcome(DeploymentResult result, Optional<DeploymentHandoff> handoff) {
    /**
     * Validates and binds the inputs required by deployment outcome.
     * <p>校验并绑定部署结果所需输入。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @param handoff handoff / 交接
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentOutcome {
        result = Objects.requireNonNull(result, "result");
        handoff = Objects.requireNonNull(handoff, "handoff");
        if ((result.status() == DeploymentStatus.SUCCEEDED) != handoff.isPresent()) {
            throw new IllegalArgumentException("only a successful deployment may provide a user handoff");
        }
    }

    /**
     * Returns the terminal deployment status. / 返回部署终态。
     *
     * @return the terminal deployment status / 部署终态
     */
    public DeploymentStatus status() {
        return result.status();
    }

    /**
     * Returns the ordered deployment evidence events. / 返回有序部署证据事件。
     *
     * @return the ordered deployment evidence events / 有序部署证据事件
     */
    public List<DeploymentEvent> events() {
        return result.events();
    }

    /**
     * Creates a value through {@code from}.
     *
     *  <p>通过 {@code from} 创建值。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static DeploymentOutcome from(DeploymentResult result, ReviewedDeploymentRequest request,
            ManagedApplication application) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(application, "application");
        Optional<DeploymentHandoff> handoff = result.status() == DeploymentStatus.SUCCEEDED
                ? Optional.of(successHandoff(request, application))
                : Optional.empty();
        return new DeploymentOutcome(result, handoff);
    }

    /**
     * Builds deployment handoff from the supplied success handoff inputs.
     * <p>根据所提供成功交接输入构建部署交接。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return deployment handoff from the supplied success handoff inputs / 根据所提供成功交接输入构建部署交接
     */
    private static DeploymentHandoff successHandoff(ReviewedDeploymentRequest request, ManagedApplication application) {
        return new DeploymentHandoff.ApplicationEntry(gold.debug.windowstolinux.shared.model.managed.ApplicationUsage
                .from(application, request.runtime().workload()));
    }

}
