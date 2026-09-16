package gold.debug.windowstolinux.shared.linux.session;

import gold.debug.windowstolinux.shared.linux.protocol.RemoteRuntimeConfiguration;
import gold.debug.windowstolinux.shared.linux.build.RemoteBuildEnvironment;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteSecretPayload;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;
import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreFilePort;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;

import java.util.List;

/**
 * Bounded extension to the verified managed service session for all typed deployment single-component project types.
 *
 * <p>已验证受管部署会话的有界扩展，覆盖全部部署单组件项目类型。
 */
public interface DeploymentRemoteSession extends LinuxRemoteSession, RemoteRestoreFilePort {
    /** Optional discovery capability bound to the authenticated session. / 绑定已认证会话的可选应用发现能力。 */
    default gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort externalApplications() {
        throw new UnsupportedOperationException("external application discovery is unavailable on this transport");
    }
    /** Existing database capability bound to this connection. / 绑定当前连接的既有数据库能力。 */
    RemoteDatabasePort databaseOperations();

    /** Existing artifact capability bound to this connection. / 绑定当前连接的既有备份制品能力。 */
    RemoteBackupArtifactPort backupArtifacts();

    /** Existing activation capability bound to this connection. / 绑定当前连接的既有恢复激活能力。 */
    RemoteRestoreActivationPort restoreActivation();

    /** Native provisioning is supplied by transports that implement the DB protocol. / 原生配置能力由实现数据库协议的传输层提供。 */
    default gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort nativeDatabases() {
        throw new UnsupportedOperationException("native DB provisioning is unavailable on this transport");
    }
    /** Collects distribution and container facts before one typed deployment. / 在类型化部署前采集发行版和容器事实。 */
    LinuxCapabilityFacts collectDeploymentCapabilities() throws LinuxOperationException;

    /** Prepares reviewed project tools, then returns exact bindings and a fresh host probe. / 准备经审阅的项目工具，随后返回精确绑定和最新主机探测结果。 */
    default gold.debug.windowstolinux.shared.linux.build.ToolchainPreparationResult prepareToolchains(
            DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime, BuildLimitConfiguration limits)
            throws LinuxOperationException {
        return new gold.debug.windowstolinux.shared.linux.build.ToolchainPreparationResult(
                new gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet("legacy", List.of()),
                collectDeploymentCapabilities());
    }

    /** Builds one analyzed typed deployment candidate. / 构建一个已分析的部署候选版本。 */
    DeploymentBuildResult buildDeployment(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                      RemoteWorkspace workspace, BuildLimitConfiguration limits, RemoteBuildEnvironment configuration)
            throws LinuxOperationException;

    /** Seals reviewed runtime configuration and exact secret revisions outside release trees. / 在发布树之外封存经审阅的运行时配置与精确秘密修订。 */
    RemoteDeploymentInputs stageDeploymentInputs(ManagedApplication application, RemoteRuntimeConfiguration configuration,
                                                   List<RemoteSecretPayload> secrets) throws LinuxOperationException;

    /** Captures a rollback snapshot for the declared runtime. / 为声明的运行时捕获回滚快照。 */
    ReleaseSnapshot snapshotDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException;

    /** Publishes one sealed typed deployment release. / 发布一个已封存的部署版本。 */
    RemoteStepResult publishDeployment(ManagedApplication application, DeploymentProjectFacts facts,
                                     RemoteWorkspace workspace, DeploymentBuildResult build,
                                     String releaseIdentity, DeploymentRuntimeSpecification runtime,
                                     RemoteDeploymentInputs inputs, ManagedContentPublication contentPublication,
                                     ReleaseSnapshot snapshot)
            throws LinuxOperationException;

    /** Rolls back one typed deployment release. / 回滚一个部署版本。 */
    RemoteStepResult rollbackDeployment(ManagedApplication application, ReleaseSnapshot snapshot, DeploymentBuildResult build,
                                      String releaseIdentity, DeploymentRuntimeSpecification runtime,
                                      RemoteDeploymentInputs inputs)
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

    /** Retains only the bounded number of recent successful releases. / 仅保留有界数量的最近成功发布。 */
    RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application) throws LinuxOperationException;
}
