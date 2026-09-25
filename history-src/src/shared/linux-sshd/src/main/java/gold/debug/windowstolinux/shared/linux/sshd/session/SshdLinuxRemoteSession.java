package gold.debug.windowstolinux.shared.linux.sshd.session;

import java.util.List;

import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.build.RemoteBuildEnvironment;
import gold.debug.windowstolinux.shared.linux.build.RemoteBuildPort;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteRuntimeConfiguration;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteSecretPayload;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingEvidence;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingRequest;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.sshd.backup.SshdBackupArtifactPort;
import gold.debug.windowstolinux.shared.linux.sshd.backup.SshdDatabaseOperationPort;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdPlatformCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.distro.ManagedEnvironmentExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.CandidateWorkspaceExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input.DeploymentInputProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.release.ContainerReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.release.DeploymentReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.restore.SshdRestoreActivationPort;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ManagedRuntimeProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.transfer.SshdRestoreTransport;
import gold.debug.windowstolinux.shared.linux.sshd.execution.transfer.SshdSourceTransport;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.ContainerRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.ManagedRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.ManagedRuntimeKindProbe;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdHealthProbe;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdLifecycleExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdOwnershipObserver;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.transfer.SourceUploadResult;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;

/**
 * Unified session facade delegating each typed capability to its implementation package.
 *
 *  <p>将各项类型化能力委派给其实现包的统一会话门面。
 */
public final class SshdLinuxRemoteSession implements DeploymentRemoteSession {
    /** Authenticated runtime protocol. / 已认证运行协议。 */
    private final DeploymentReleaseProtocolExecutor deploymentProtocol;

    /** Authenticated runtime protocol. / 已认证运行协议。 */
    private final ContainerReleaseProtocolExecutor containerProtocol;

    /** Authenticated runtime protocol. / 已认证运行协议。 */
    private final ContainerRuntimeExecutor containerRuntime;
    /** Returns neutral platform and injected standard environment preparation. / 返回通用平台及注入的标准环境准备能力。
     * @return authenticated environment capability / 已认证环境能力
     */
    @Override
    public gold.debug.windowstolinux.shared.linux.distro.LinuxEnvironmentPreparer environment() {
        return environment;
    }
    /** Task source and sandbox port. / 任务源码及沙箱端口。 */
    private final gold.debug.windowstolinux.shared.linux.workspace.RemoteProjectPort projects;
    /** Returns the authenticated project port. / 返回已认证项目端口。
     * @return protected project operations / 受保护项目操作
     */
    @Override
    public gold.debug.windowstolinux.shared.linux.workspace.RemoteProjectPort projects() {
        return projects;
    }
    /** Authenticated command executor. / 已认证命令执行器。 */
    private final SshCommandExecutor commands;

    /**
     * Client.
     * <p>客户端。
     */
    private final SshClient client;

    /**
     * Session used for the current scoped operation.
     * <p>当前限定作用域操作使用的会话。
     */
    private final ClientSession session;

    /**
     * Account name used by the reviewed connection.
     * <p>已审阅连接使用的账户名。
     */
    private final String username;

    /**
     * Observed target tools and runtime capabilities.
     * <p>目标工具及运行能力观测。
     */
    private final SshdCapabilityCollector capabilities;

    /**
     * Deployment capabilities.
     * <p>部署能力。
     */
    private final SshdPlatformCapabilityCollector deploymentCapabilities;

    /**
     * Bound managed environment executor collaborator for environment.
     * <p>处理环境的受管环境执行器协作对象。
     */
    private final ManagedEnvironmentExecutor environment;

    /**
     * Bound gold debug windowstolinux shared linux distro selinux environment preparer collaborator for selinux.
     * <p>处理selinux 对应的输入或状态的golddebugwindowstolinux共享Linux发行版Selinux环境准备器协作对象。
     */
    private final gold.debug.windowstolinux.shared.linux.distro.SelinuxEnvironmentPreparer selinux;

    /**
     * Transfer.
     * <p>传输。
     */
    private final SshdSourceTransport transfer;

    /**
     * Restore transfer.
     * <p>恢复传输。
     */
    private final SshdRestoreTransport restoreTransfer;

    /**
     * Bound deployment build executor collaborator for deployment build.
     * <p>处理部署构建的部署构建执行器协作对象。
     */
    private final RemoteBuildPort deploymentBuild;

    /**
     * Bound candidate workspace executor collaborator for candidates.
     * <p>处理候选集合的候选工作区执行器协作对象。
     */
    private final CandidateWorkspaceExecutor candidates;

    /**
     * Bound managed runtime protocol executor collaborator for runtimes.
     * <p>处理运行时集合的受管运行时协议执行器协作对象。
     */
    private final ManagedRuntimeProtocolExecutor runtimes;

    /**
     * Bound deployment input protocol executor collaborator for deployment inputs.
     * <p>处理部署输入集合的部署输入协议执行器协作对象。
     */
    private final DeploymentInputProtocolExecutor deploymentInputs;

    /**
     * Systemd health.
     * <p>systemd健康。
     */
    private final SystemdHealthProbe systemdHealth;

    /**
     * Bound managed runtime executor collaborator for managed runtime.
     * <p>处理受管运行时的受管运行时执行器协作对象。
     */
    private final ManagedRuntimeExecutor managedRuntime;
    /**
     * Returns native DB operations for this already authenticated and trusted connection. / 返回当前已认证且可信连接的原生数据库操作能力。
     *
     * @return native DB operations for this already authenticated and trusted connection / 当前已认证且可信连接的原生数据库操作能力
     */
    @Override
    public gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort nativeDatabases() {
        return nativeDatabases;
    }
    /**
     * Databases.
     * <p>数据库集合。
     */
    private final SshdDatabaseOperationPort databases;

    /**
     * Native databases.
     * <p>原生数据库集合。
     */
    private final gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort nativeDatabases;

    /**
     * Backup artifacts.
     * <p>备份制品集合。
     */
    private final SshdBackupArtifactPort backupArtifacts;

    /**
     * Restore activation.
     * <p>恢复激活。
     */
    private final SshdRestoreActivationPort restoreActivation;

    /**
     * External applications.
     * <p>外部应用集合。
     */
    private final gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort externalApplications;
    /**
     * Returns bounded application discovery on this trusted session. / 返回当前可信会话上的有界应用发现能力。
     *
     * @return bounded application discovery on this trusted session / 当前可信会话上的有界应用发现能力
     */
    @Override
    public gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort externalApplications() {
        return externalApplications;
    }

    /**
     * Creates an instance of this type. / 创建此类型的实例。
     *
     * @param client client / 客户端
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param hostFingerprint host fingerprint / 主机指纹
     */
    public SshdLinuxRemoteSession(SshClient client, ClientSession session, SshEndpoint endpoint,
            String hostFingerprint) {
        this(client, session, endpoint, hostFingerprint, null, null);
    }

    /** Binds optional deployment policy to an authenticated transport. / 向已认证传输绑定可选部署策略。
     * @param client authenticated session input / 已认证会话输入
     * @param session authenticated session input / 已认证会话输入
     * @param endpoint authenticated session input / 已认证会话输入
     * @param hostFingerprint authenticated session input / 已认证会话输入
     * @param builds optional standard build strategy / 可选标准构建策略
     * @param preparation optional standard environment policy / 可选标准环境策略
     */
    public SshdLinuxRemoteSession(SshClient client, ClientSession session, SshEndpoint endpoint, String hostFingerprint,
            RemoteBuildPort.Factory builds,
            gold.debug.windowstolinux.shared.linux.distro.EnvironmentPreparationPlan preparation) {
        this.client = client;
        this.session = session;
        this.username = endpoint.username();
        this.commands = new SshCommandExecutor(session, endpoint);
        this.projects = new gold.debug.windowstolinux.shared.linux.sshd.workspace.SshdRemoteProjectPort(commands);
        this.externalApplications = new gold.debug.windowstolinux.shared.linux.sshd.runtime.SshdExternalApplicationPort(
                commands);
        this.selinux = new gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.SelinuxPreparationExecutor(commands,
                endpoint.serverId());
        this.databases = new SshdDatabaseOperationPort(commands);
        this.nativeDatabases = new gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.database.SshdNativeDatabasePort(
                commands);
        this.backupArtifacts = new SshdBackupArtifactPort(commands);
        this.restoreActivation = new SshdRestoreActivationPort(commands);
        this.capabilities = new SshdCapabilityCollector(commands, hostFingerprint);
        this.deploymentCapabilities = new SshdPlatformCapabilityCollector(commands, hostFingerprint);
        this.environment = new ManagedEnvironmentExecutor(commands, capabilities, deploymentCapabilities,
                endpoint.serverId(), endpoint.username(), preparation);
        this.candidates = new CandidateWorkspaceExecutor(commands);
        this.runtimes = new ManagedRuntimeProtocolExecutor(commands);
        this.deploymentProtocol = new DeploymentReleaseProtocolExecutor(commands);
        this.containerProtocol = new ContainerReleaseProtocolExecutor(commands);
        this.deploymentInputs = new DeploymentInputProtocolExecutor(commands);
        this.transfer = new SshdSourceTransport(session, commands, candidates);
        this.restoreTransfer = new SshdRestoreTransport(session, candidates);
        this.deploymentBuild = builds == null ? null : builds.create(commands, endpoint.username());
        this.systemdHealth = new SystemdHealthProbe(commands);
        SystemdOwnershipObserver systemdObservation = new SystemdOwnershipObserver(commands, endpoint.username());
        SystemdLifecycleExecutor systemdLifecycle = new SystemdLifecycleExecutor(commands, runtimes, systemdObservation,
                systemdHealth, endpoint.username());
        this.containerRuntime = new ContainerRuntimeExecutor(commands);
        this.managedRuntime = new ManagedRuntimeExecutor(new ManagedRuntimeKindProbe(runtimes), deploymentProtocol,
                containerProtocol, systemdObservation, systemdLifecycle, systemdHealth, containerRuntime);
    }

    /**
     * Collects observed target tools and runtime capabilities.
     * <p>采集目标工具及运行能力观测。
     *
     * @return constructed or resolved server capability facts / 构造或解析得到的服务器能力事实
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public ServerCapabilityFacts collectCapabilities() throws LinuxOperationException {
        return capabilities.collect();
    }

    /**
     * Executes a minimal read-only proof on the authenticated connection. / 在已认证连接上执行最小只读证明。
     *
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public void verifyConnection() throws LinuxOperationException {
        commands.verifyConnection();
    }

    /**
     * Collects deployment capabilities.
     * <p>采集部署能力。
     *
     * @return constructed or resolved linux capability facts / 构造或解析得到的Linux能力事实
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public LinuxCapabilityFacts collectDeploymentCapabilities() throws LinuxOperationException {
        return deploymentCapabilities.collectDeploymentCapabilities();
    }

    /**
     * Returns fixed system preparation for this trusted session. / 返回当前可信会话的固定系统准备能力。
     *
     * @return fixed system preparation for this trusted session / 当前可信会话的固定系统准备能力
     */
    @Override
    public gold.debug.windowstolinux.shared.linux.distro.SelinuxEnvironmentPreparer selinuxPreparation() {
        return selinux;
    }

    /**
     * Uploads source identity or content read by the operation.
     * <p>上传操作读取的源身份或内容。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param maxWorkspaceBytes max workspace bytes / 最大工作区字节
     * @return constructed or resolved source upload result / 构造或解析得到的源码上传结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public SourceUploadResult uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace,
            long maxWorkspaceBytes) throws LinuxOperationException {
        return transfer.upload(archive, workspace, maxWorkspaceBytes);
    }

    /**
     * Stages deployment inputs.
     * <p>暂存部署输入集合。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @return constructed or resolved remote deployment inputs / 构造或解析得到的远端部署输入集合
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public RemoteDeploymentInputs stageDeploymentInputs(ManagedApplication application,
            RemoteRuntimeConfiguration configuration, List<RemoteSecretPayload> secrets)
            throws LinuxOperationException {
        return deploymentInputs.stage(application, configuration, secrets);
    }

    /**
     * Cleans up candidate.
     * <p>清理候选。
     *
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public RemoteStepResult cleanupCandidate(RemoteWorkspace workspace) throws LinuxOperationException {
        return candidates.cleanup(workspace);
    }

    /** Queries the exact candidate's task marker. / 查询精确候选项的任务标记。
     * @param candidate exact task and candidate / 精确任务及候选项
     * @return observed facts / 观测事实
     * @throws LinuxOperationException if observation fails / 观测失败时
     */
    @Override
    public java.util.Map<String, String> inspectTaskCandidate(
            gold.debug.windowstolinux.shared.linux.transfer.RemoteTaskCandidate candidate)
            throws LinuxOperationException {
        return candidates.inspectTask(candidate.workspace(), candidate.taskId());
    }

    /** Stops and cleans only a task-owned candidate. / 仅停止并清理任务所属候选项。
     * @param candidate exact task and candidate / 精确任务及候选项
     * @return cleanup result / 清理结果
     * @throws LinuxOperationException if execution fails / 执行失败时
     */
    @Override
    public RemoteStepResult cleanupTaskCandidate(
            gold.debug.windowstolinux.shared.linux.transfer.RemoteTaskCandidate candidate)
            throws LinuxOperationException {
        return candidates.cleanupTask(candidate.workspace(), candidate.taskId());
    }

    /**
     * Stages restore files.
     * <p>暂存恢复文件集合。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved remote restore staging evidence / 构造或解析得到的远端恢复暂存证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public RemoteRestoreStagingEvidence stageRestoreFiles(RemoteRestoreStagingRequest request)
            throws LinuxOperationException {
        return restoreTransfer.stageRestoreFiles(request);
    }

    /**
     * Discards restore files.
     * <p>清理恢复文件集合。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public RemoteStepResult discardRestoreFiles(RemoteRestoreStagingRequest request) throws LinuxOperationException {
        return restoreTransfer.discardRestoreFiles(request);
    }

    /**
     * Checks health.
     * <p>检查健康。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return constructed or resolved health check result / 构造或解析得到的健康检查结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck)
            throws LinuxOperationException {
        return systemdHealth.check(application, healthCheck);
    }

    /**
     * Applies the managed runtime's retention policy to recent successful releases.
     * <p>对近期成功发布应用受管运行时的保留策略。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application)
            throws LinuxOperationException {
        return runtimes.retain(application);
    }

    /**
     * Observes lifecycle observation.
     * <p>观测生命周期观测。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException {
        return managedRuntime.observe(application);
    }

    /**
     * Executes lifecycle.
     * <p>执行生命周期。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action,
            HealthCheck healthCheck) throws LinuxOperationException {
        return managedRuntime.execute(application, action, healthCheck);
    }

    /**
     * Captures the current deployment through the container or native snapshot protocol selected by the runtime.
     * <p>通过运行规格选定的容器或原生快照协议捕获当前部署。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved release snapshot / 构造或解析得到的发布快照
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public ReleaseSnapshot snapshotDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerProtocol.snapshot(application, container);
        }
        return deploymentProtocol.snapshot(application, runtime);
    }

    /**
     * Publishes deployment.
     * <p>发布部署。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param buildResult build result / 构建结果
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param contentPublication content publication / 内容发布
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public RemoteStepResult publishDeployment(ManagedApplication application, DeploymentProjectFacts facts,
            RemoteWorkspace workspace, DeploymentBuildResult buildResult, String releaseIdentity,
            DeploymentRuntimeSpecification runtime, RemoteDeploymentInputs inputs,
            ManagedContentPublication contentPublication, ReleaseSnapshot snapshot) throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerProtocol.publish(application, workspace, buildResult, releaseIdentity, container, inputs,
                    contentPublication, snapshot);
        }
        return deploymentProtocol.publish(application, facts, workspace, buildResult, releaseIdentity, runtime, inputs,
                contentPublication, snapshot);
    }

    /**
     * Restores the recorded deployment snapshot through the selected runtime-specific rollback protocol.
     * <p>通过选定运行环境专用回滚协议恢复已记录部署快照。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @param buildResult build result / 构建结果
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public RemoteStepResult rollbackDeployment(ManagedApplication application, ReleaseSnapshot snapshot,
            DeploymentBuildResult buildResult, String releaseIdentity, DeploymentRuntimeSpecification runtime,
            RemoteDeploymentInputs inputs) throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerProtocol.rollback(application, snapshot, buildResult, releaseIdentity, container, inputs);
        }
        return deploymentProtocol.rollback(application, snapshot, buildResult, releaseIdentity, runtime, inputs);
    }

    /**
     * Checks deployment health.
     * <p>检查部署健康。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return constructed or resolved health check result / 构造或解析得到的健康检查结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public HealthCheckResult checkDeploymentHealth(ManagedApplication application,
            DeploymentRuntimeSpecification runtime, HealthCheck healthCheck) throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerRuntime.checkHealth(application, container, healthCheck);
        }
        return systemdHealth.check(application, healthCheck);
    }

    /**
     * Observes deployment.
     * <p>观测部署。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public LifecycleObservation observeDeployment(ManagedApplication application,
            DeploymentRuntimeSpecification runtime) throws LinuxOperationException {
        return managedRuntime.observe(application);
    }

    /**
     * Executes deployment lifecycle.
     * <p>执行部署生命周期。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public LifecycleObservation executeDeploymentLifecycle(ManagedApplication application,
            DeploymentRuntimeSpecification runtime, LifecycleAction action) throws LinuxOperationException {
        return managedRuntime.execute(application, runtime, action);
    }

    /**
     * Returns the connection's database operations. / 返回当前连接的数据库操作能力。
     *
     * @return the connection's database operations / 当前连接的数据库操作能力
     */
    @Override
    public RemoteDatabasePort databaseOperations() {
        return databases;
    }

    /**
     * Returns the connection's backup artifacts. / 返回当前连接的备份制品能力。
     *
     * @return the connection's backup artifacts / 当前连接的备份制品能力
     */
    @Override
    public RemoteBackupArtifactPort backupArtifacts() {
        return backupArtifacts;
    }

    /**
     * Returns the connection's restore activation. / 返回当前连接的恢复激活能力。
     *
     * @return the connection's restore activation / 当前连接的恢复激活能力
     */
    @Override
    public RemoteRestoreActivationPort restoreActivation() {
        return restoreActivation;
    }

    /**
     * Closes this resource. / 关闭此资源。
     */
    @Override
    public void close() {
        try {
            SshSessionLifecycleExecutor.closeQuietly(session);
        } finally {
            SshSessionLifecycleExecutor.closeQuietly(client);
        }
    }

    /** Requires an explicitly assembled standard strategy. / 要求显式装配标准策略。
     * @return current strategy / 当前策略
     */
    public RemoteBuildPort standardBuild() {
        if (deploymentBuild == null)
            throw new IllegalStateException("standard build strategy is not configured");
        return deploymentBuild;
    }
}
