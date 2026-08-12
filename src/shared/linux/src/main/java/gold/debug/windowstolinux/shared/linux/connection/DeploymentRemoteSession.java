package gold.debug.windowstolinux.shared.linux.connection;

import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;

import java.util.List;

/**
 * Bounded extension to the verified managed service session for all typed deployment single-component project types.
 *
 * <p>已验证受管部署会话的有界扩展，覆盖全部部署单组件项目类型。
 */
public interface DeploymentRemoteSession extends LinuxRemoteSession {
    /** Collects distribution and container facts before one typed deployment. / 在类型化部署前采集发行版和容器事实。 */
    LinuxCapabilities collectDeploymentCapabilities() throws LinuxOperationException;

    /** Builds one analyzed typed deployment candidate. / 构建一个已分析的部署候选版本。 */
    DeploymentBuildResult buildDeployment(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                      RemoteWorkspace workspace, BuildLimits limits, ConfigurationSnapshot configuration)
            throws LinuxOperationException;

    /** Seals reviewed runtime configuration and exact secret revisions outside release trees. / 在发布树之外封存经审阅的运行时配置与精确秘密修订。 */
    DeploymentInputManifest stageDeploymentInputs(ManagedApplication application, ConfigurationSnapshot configuration,
                                                   List<ResolvedSecretRevision> secrets) throws LinuxOperationException;

    /** Captures a rollback snapshot for the declared runtime. / 为声明的运行时捕获回滚快照。 */
    ReleaseSnapshot snapshotDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException;

    /** Publishes one sealed typed deployment release. / 发布一个已封存的部署版本。 */
    RemoteStepResult publishDeployment(ManagedApplication application, RemoteWorkspace workspace, DeploymentBuildResult build,
                                     String releaseIdentity, DeploymentRuntimeSpecification runtime,
                                     DeploymentInputManifest inputs, ReleaseSnapshot snapshot)
            throws LinuxOperationException;

    /** Rolls back one typed deployment release. / 回滚一个部署版本。 */
    RemoteStepResult rollbackDeployment(ManagedApplication application, ReleaseSnapshot snapshot, DeploymentBuildResult build,
                                      String releaseIdentity, DeploymentRuntimeSpecification runtime,
                                      DeploymentInputManifest inputs)
            throws LinuxOperationException;

    /** Checks a typed deployment runtime and process ownership. / 检查类型化部署运行时及进程归属。 */
    HealthCheckResult checkDeploymentHealth(ManagedApplication application, DeploymentRuntimeSpecification runtime,
                                          HealthCheck healthCheck) throws LinuxOperationException;

    /** Observes a typed deployment release. / 观察类型化部署版本。 */
    LifecycleObservation observeDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException;

    /** Executes one verified lifecycle action for the selected runtime. / 为选定运行时执行一个已验证的生命周期动作。 */
    LifecycleObservation executeDeploymentLifecycle(ManagedApplication application, DeploymentRuntimeSpecification runtime,
                                                    LifecycleAction action) throws LinuxOperationException;
}
