package gold.debug.windowstolinux.shared.model.managed;

import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode;

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
public record ManagedApplicationRuntimeConfiguration(HealthCheck healthCheck, Optional<UserAccessUrl> userAccessUrl,
        RuntimeIdentityMode identityPolicy,
        gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload workload) {
    /**
     * Initializes managed application runtime configuration through its shared constructor contract.
     * <p>通过共享构造契约初始化受管应用运行时配置。
     *
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     */
    public ManagedApplicationRuntimeConfiguration(HealthCheck healthCheck, Optional<UserAccessUrl> userAccessUrl,
            RuntimeIdentityMode identityPolicy) {
        this(healthCheck, userAccessUrl, identityPolicy,
                gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.unspecified());
    }

    /**
     * Reads historical health-only runtime state without inventing an identity policy. / 读取历史健康运行状态，不推断缺失的身份策略。
     *
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     */
    public ManagedApplicationRuntimeConfiguration(HealthCheck healthCheck, Optional<UserAccessUrl> userAccessUrl) {
        this(healthCheck, userAccessUrl, RuntimeIdentityMode.LEGACY_UNSPECIFIED);
    }

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
    public ManagedApplicationRuntimeConfiguration {
        identityPolicy = Objects.requireNonNull(identityPolicy, "identityPolicy");
        workload = Objects.requireNonNull(workload, "workload");
        healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        userAccessUrl = Objects.requireNonNull(userAccessUrl, "userAccessUrl");
    }
}
