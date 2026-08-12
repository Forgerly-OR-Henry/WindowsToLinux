package gold.debug.windowstolinux.shared.linux.connection;

import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * Bounded extension to the verified managed service session for all typed deployment single-component project types.
 *
 * <p>已验证受管部署会话的有界扩展，覆盖全部部署单组件项目类型。
 */
public interface DeploymentRemoteSession extends LinuxRemoteSession {
    /** Builds one analyzed typed deployment candidate. / 构建一个已分析的部署候选版本。 */
    DeploymentBuildResult buildDeployment(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                      RemoteWorkspace workspace, BuildLimits limits) throws LinuxOperationException;

    /** Captures a rollback snapshot for the declared runtime. / 为声明的运行时捕获回滚快照。 */
    ReleaseSnapshot snapshotDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException;

    /** Publishes one sealed typed deployment release. / 发布一个已封存的部署版本。 */
    RemoteStepResult publishDeployment(ManagedApplication application, RemoteWorkspace workspace, DeploymentBuildResult build,
                                     String releaseIdentity, DeploymentRuntimeSpecification runtime, ReleaseSnapshot snapshot)
            throws LinuxOperationException;

    /** Rolls back one typed deployment release. / 回滚一个部署版本。 */
    RemoteStepResult rollbackDeployment(ManagedApplication application, ReleaseSnapshot snapshot, DeploymentBuildResult build,
                                      String releaseIdentity, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException;

    /** Checks a typed typed deployment runtime and process ownership. / 检查类型化部署运行时及进程归属。 */
    HealthCheckResult checkDeploymentHealth(ManagedApplication application, DeploymentRuntimeSpecification runtime,
                                          HealthCheck healthCheck) throws LinuxOperationException;

    /** Observes a typed typed deployment release. / 观察类型化部署版本。 */
    LifecycleObservation observeDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException;
}
