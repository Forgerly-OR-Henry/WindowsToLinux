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
 *  <p>已验证受管部署会话的有界扩展，覆盖全部部署单组件项目类型。
 */
public interface DeploymentRemoteSession extends LinuxRemoteSession, RemoteRestoreFilePort {
    /**
     * Optional discovery capability bound to the authenticated session. / 绑定已认证会话的可选应用发现能力。
     *
     * @return constructed or resolved external application port / 构造或解析得到的外部应用端口
     * @throws UnsupportedOperationException if the requested capability is not implemented by this adapter / 当前适配器未实现所请求能力时
     */
    default gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort externalApplications() {
        throw new UnsupportedOperationException("external application discovery is unavailable on this transport");
    }
    /**
     * Existing database capability bound to this connection. / 绑定当前连接的既有数据库能力。
     *
     * @return constructed or resolved remote database port / 构造或解析得到的远端数据库端口
     */
    RemoteDatabasePort databaseOperations();

    /**
     * Existing artifact capability bound to this connection. / 绑定当前连接的既有备份制品能力。
     *
     * @return constructed or resolved remote backup artifact port / 构造或解析得到的远端备份制品端口
     */
    RemoteBackupArtifactPort backupArtifacts();

    /**
     * Existing activation capability bound to this connection. / 绑定当前连接的既有恢复激活能力。
     *
     * @return constructed or resolved remote restore activation port / 构造或解析得到的远端恢复激活端口
     */
    RemoteRestoreActivationPort restoreActivation();

    /**
     * Native provisioning is supplied by transports that implement the DB protocol. / 原生配置能力由实现数据库协议的传输层提供。
     *
     * @return constructed or resolved native database port / 构造或解析得到的原生数据库端口
     * @throws UnsupportedOperationException if the requested capability is not implemented by this adapter / 当前适配器未实现所请求能力时
     */
    default gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort nativeDatabases() {
        throw new UnsupportedOperationException("native DB provisioning is unavailable on this transport");
    }
    /**
     * Collects distribution and container facts before one typed deployment. / 在类型化部署前采集发行版和容器事实。
     *
     * @return constructed or resolved linux capability facts / 构造或解析得到的Linux能力事实
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    LinuxCapabilityFacts collectDeploymentCapabilities() throws LinuxOperationException;

    /**
     * Prepares reviewed project tools, then returns exact bindings and a fresh host probe. / 准备经审阅的项目工具，随后返回精确绑定和最新主机探测结果。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return constructed or resolved toolchain preparation result / 构造或解析得到的工具链准备结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    default gold.debug.windowstolinux.shared.linux.build.ToolchainPreparationResult prepareToolchains(
            DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime, BuildLimitConfiguration limits)
            throws LinuxOperationException {
        return new gold.debug.windowstolinux.shared.linux.build.ToolchainPreparationResult(
                new gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet("legacy", List.of()),
                collectDeploymentCapabilities());
    }

    /**
     * Builds one analyzed typed deployment candidate. / 构建一个已分析的部署候选版本。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @return one analyzed typed deployment candidate / 一个已分析的部署候选版本
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    DeploymentBuildResult buildDeployment(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                      RemoteWorkspace workspace, BuildLimitConfiguration limits, RemoteBuildEnvironment configuration)
            throws LinuxOperationException;

    /**
     * Seals reviewed runtime configuration and exact secret revisions outside release trees. / 在发布树之外封存经审阅的运行时配置与精确秘密修订。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @return constructed or resolved remote deployment inputs / 构造或解析得到的远端部署输入集合
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RemoteDeploymentInputs stageDeploymentInputs(ManagedApplication application, RemoteRuntimeConfiguration configuration,
                                                   List<RemoteSecretPayload> secrets) throws LinuxOperationException;

    /**
     * Captures a rollback snapshot for the declared runtime. / 为声明的运行时捕获回滚快照。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved release snapshot / 构造或解析得到的发布快照
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    ReleaseSnapshot snapshotDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException;

    /**
     * Publishes one sealed typed deployment release. / 发布一个已封存的部署版本。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param build build / 构建
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param contentPublication content publication / 内容发布
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RemoteStepResult publishDeployment(ManagedApplication application, DeploymentProjectFacts facts,
                                     RemoteWorkspace workspace, DeploymentBuildResult build,
                                     String releaseIdentity, DeploymentRuntimeSpecification runtime,
                                     RemoteDeploymentInputs inputs, ManagedContentPublication contentPublication,
                                     ReleaseSnapshot snapshot)
            throws LinuxOperationException;

    /**
     * Rolls back one typed deployment release. / 回滚一个部署版本。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @param build build / 构建
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RemoteStepResult rollbackDeployment(ManagedApplication application, ReleaseSnapshot snapshot, DeploymentBuildResult build,
                                      String releaseIdentity, DeploymentRuntimeSpecification runtime,
                                      RemoteDeploymentInputs inputs)
            throws LinuxOperationException;

    /**
     * Checks a typed deployment runtime and process ownership. / 检查类型化部署运行时及进程归属。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return constructed or resolved health check result / 构造或解析得到的健康检查结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    HealthCheckResult checkDeploymentHealth(ManagedApplication application, DeploymentRuntimeSpecification runtime,
                                          HealthCheck healthCheck) throws LinuxOperationException;

    /**
     * Observes a typed deployment release. / 观察类型化部署版本。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    LifecycleObservation observeDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException;

    /**
     * Executes one verified lifecycle action for the selected runtime. / 为选定运行时执行一个已验证的生命周期动作。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    LifecycleObservation executeDeploymentLifecycle(ManagedApplication application, DeploymentRuntimeSpecification runtime,
                                                    LifecycleAction action) throws LinuxOperationException;

    /**
     * Retains only the bounded number of recent successful releases. / 仅保留有界数量的最近成功发布。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application) throws LinuxOperationException;
}
