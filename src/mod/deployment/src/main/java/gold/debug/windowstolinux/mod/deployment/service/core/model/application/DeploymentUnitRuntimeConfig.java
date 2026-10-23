package gold.debug.windowstolinux.mod.deployment.service.core.model.application;

import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentAccessUrl;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentIdentityMode;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload;

/**
 * The last successfully deployed runtime contract for one managed application. It is mutable across successful deployments, unlike the application ownership identity itself.
 *
 *  <p>某个受管应用最后一次成功部署的运行时契约。与应用资源归属身份本身不同，它会随成功部署而变化。
 *
 * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
 * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
 * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
 * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
 */
public record DeploymentUnitRuntimeConfig(DeploymentHealthCheck healthCheck,
                                          Optional<DeploymentAccessUrl> userAccessUrl,
                                          DeploymentIdentityMode identityPolicy,
                                          gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload workload) {
    /**
     * Validates and binds the inputs required by managed application runtime configuration.
     * <p>校验并绑定受管应用运行时配置所需输入。
     *
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentUnitRuntimeConfig {
        identityPolicy = Objects.requireNonNull(identityPolicy, "identityPolicy");
        workload = Objects.requireNonNull(workload, "workload");
        healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        userAccessUrl = Objects.requireNonNull(userAccessUrl, "userAccessUrl");
    }
}
