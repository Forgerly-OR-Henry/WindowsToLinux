package gold.debug.windowstolinux.app.service.deployment;

import java.time.Instant;
import java.util.*;
import java.util.function.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.*;
import gold.debug.windowstolinux.app.service.ai.DeploymentAgentModelAdapter;
import gold.debug.windowstolinux.app.service.contract.definition.AutomaticDeploymentOutcome;
import gold.debug.windowstolinux.app.service.contract.definition.AutomaticDeploymentRequest;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.app.service.source.SourcePreparationUseCase;
import gold.debug.windowstolinux.shared.agent.approval.SourcePatchApproval;
import gold.debug.windowstolinux.shared.agent.execution.AutonomousDeploymentSession;
import gold.debug.windowstolinux.shared.agent.tool.AgentDeliveryPort;
import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.deploy.delivery.ManagedDelivery;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.DeploymentInputMapper;
import gold.debug.windowstolinux.shared.deploy.publication.*;
import gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.transfer.*;
import gold.debug.windowstolinux.shared.linux.workspace.RemoteProjectPort;
import gold.debug.windowstolinux.shared.model.agent.AgentAction;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.managed.*;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.source.browse.SourceBrowser;

/** App-owned autonomous deployment assembly and atomic inventory recording. / App 持有的自主部署装配及原子清单记录。 */
public final class AutonomousDeploymentUseCase {
    /** Desktop persistence boundary. / 桌面持久化边界。 */
    private final DesktopPersistence persistence;

    /** Credential and authenticated identity boundary. / 凭据及已认证身份边界。 */
    private final ServerUseCaseFacade servers;

    /** Mechanical remote gateway. / 机械远端网关。 */
    private final DeploymentLinuxGateway gateway;

    /** Snapshot and archive provider; recognition is never called here. / 快照及归档提供者，此处不调用识别。 */
    private final SourcePreparationUseCase source;

    /** Binds application services. / 绑定应用服务。
     * @param persistence inventory repository / 清单仓库
     * @param servers trusted servers / 可信服务器
     * @param gateway remote transport / 远端传输
     * @param source snapshot provider / 快照提供者
     */
    public AutonomousDeploymentUseCase(DesktopPersistence persistence, ServerUseCaseFacade servers,
            DeploymentLinuxGateway gateway, SourcePreparationUseCase source) {
        this.persistence = persistence;
        this.servers = servers;
        this.gateway = gateway;
        this.source = source;
    }

    /** Runs the model loop against a frozen snapshot and actual managed delivery. / 针对冻结快照及实际受管交付运行模型循环。
     * @param request frozen task / 冻结任务
     * @param master temporary credential buffer / 临时凭据缓冲区
     * @param interaction user input and exact approval / 用户输入及精确审核
     * @param fingerprint authenticated host confirmation / 已认证主机确认
     * @param progress visible observations / 可见观察
     * @param models isolated purpose model adapter / 用途隔离模型适配器
     * @param control active task control / 活动任务控制
     * @param journal nonsecret persistent journal / 非秘密持久化日志
     * @param bindRevision supplies current execution evidence to the command boundary / 向命令边界提供当前执行证据
     * @return verified outcome / 已验证结果
     * @throws Exception on unavailable, rejected or unknown execution / 执行不可用、被拒绝或未知时
     */
    public AutomaticDeploymentOutcome deploy(AutomaticDeploymentRequest request, char[] master,
            AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint,
            Consumer<LocalizedMessage> progress, DeploymentAgentModelAdapter models, AgentTaskControl control,
            Consumer<Map<String, String>> journal, Consumer<Supplier<String>> bindRevision) throws Exception {
        if (!request.server().username().equals("root"))
            throw new SecurityException(
                    "managed helper requires root management; project commands use an isolated non-root identity");
        var snapshot = request.directory().isPresent()
                ? source.snapshot(request.directory().orElseThrow())
                : source.snapshot(source.snapshotGit(request.git().orElseThrow()));
        try (snapshot) {
            var browser = new SourceBrowser(snapshot.directory());
            String applicationId = request.overrides().getOrDefault("applicationId",
                    snapshot.directory().getFileName().toString().toLowerCase(Locale.ROOT));
            if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,30}")) {
                applicationId = interaction
                        .requestInputs(
                                List.of(new DeploymentInputField("applicationId", "deployment.input.applicationId",
                                        "deployment.input.applicationId.help", "", List.of())))
                        .orElseThrow(java.util.concurrent.CancellationException::new).get("applicationId");
                if (applicationId == null || !applicationId.matches("[a-z0-9][a-z0-9-]{0,30}"))
                    throw new IllegalArgumentException("invalid managed application identifier");
            }
            var archive = source.archiveNeutral(snapshot.directory(), applicationId);
            String target = request.server().id() + "|" + request.server().username() + "@" + request.server().host()
                    + ":" + request.server().sshPort();
            var patches = new SourcePatchApproval(request.taskId(), target, request.approvalMode(), models,
                    (key, details) -> interaction.confirm(key, details), journal, control,
                    () -> gold.debug.windowstolinux.app.service.ai.DeploymentAiScope.current().orElseThrow().binding());
            try (var store = servers.secrets().open(request.server().credentialMode(), master);
                    var remote = gateway.connect(request.server().endpoint(),
                            servers.loadPassword(request.server(), store),
                            servers.hostKeyVerifier(request.server(), fingerprint))) {
                var delivery = new Delivery(applicationId, request, archive, remote, progress, journal);
                var engine = new AutonomousDeploymentSession(request.taskId(), request.overrides(), browser, models,
                        delivery, patches, control, question -> {
                            progress.accept(LocalizedMessage.of("deployment.agent.question", "question", question));
                            return interaction
                                    .requestInputs(List.of(new DeploymentInputField("agentAnswer",
                                            "deployment.agent.answer", "deployment.agent.answer.help", "", List.of())))
                                    .map(values -> values.get("agentAnswer"));
                        }, journal,
                        event -> progress.accept(LocalizedMessage.of("deployment.agent.observation", event)));
                bindRevision.accept(engine::revision);
                var result = engine.run();
                return new AutomaticDeploymentOutcome(applicationId, result.status(),
                        result.status() == DeploymentStatus.SUCCEEDED ? delivery.handoffs : Map.of());
            }
        }
    }

    /** Credentials stay in the App; this adapter exposes only managed operations. / 凭据留在 App，此适配器仅暴露受管操作。 */
    private final class Delivery implements AgentDeliveryPort {
        /** Logical application identity. / 逻辑应用身份。 */
        private final String applicationId;

        /** Frozen user task. / 冻结用户任务。 */
        private final AutomaticDeploymentRequest request;

        /** Local frozen archive. / 本地冻结归档。 */
        private final gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor archive;

        /** Authenticated session. / 已认证会话。 */
        private final DeploymentRemoteSession remote;

        /** Visible progress callback. / 可见进度回调。 */
        private final Consumer<LocalizedMessage> progress;

        /** Durable nonsecret journal. / 持久化非秘密日志。 */
        private final Consumer<Map<String, String>> journal;

        /** Exact managed ownership identities. / 精确受管归属身份。 */
        private final Map<String, ManagedApplication> applications = new LinkedHashMap<>();

        /** Verified entry points. / 已验证入口。 */
        private final Map<String, DeploymentHandoff> handoffs = new LinkedHashMap<>();

        /** Whether platform preparation has completed. / 平台准备是否已完成。 */
        private boolean prepared;

        /** Captures task-owned services and immutable inputs. / 捕获任务所属服务及不可变输入。
         * @param applicationId logical application / 逻辑应用
         * @param request user task / 用户任务
         * @param archive frozen archive / 冻结归档
         * @param remote authenticated session / 已认证会话
         * @param progress UI observations / 界面观察
         * @param journal durable events / 持久化事件
         */
        private Delivery(String applicationId, AutomaticDeploymentRequest request,
                gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor archive,
                DeploymentRemoteSession remote, Consumer<LocalizedMessage> progress,
                Consumer<Map<String, String>> journal) {
            this.applicationId = applicationId;
            this.request = request;
            this.archive = archive;
            this.remote = remote;
            this.progress = progress;
            this.journal = journal;
        }

        /** Reads actual server facts. / 读取实际服务器事实。
         * @return observed facts / 观察事实
         * @throws Exception when the server cannot be observed / 无法观察服务器时
         */
        @Override
        public Map<String, String> inspect() throws Exception {
            var facts = remote.collectDeploymentCapabilities();
            return Map.of("facts", facts.toString());
        }

        /** Creates one protected source workspace. / 创建一个受保护源码工作区。
         * @param component source-backed declaration / 源码支撑的声明
         * @return source workspace and revision / 源码工作区及修订
         * @throws Exception when preparation cannot be verified / 无法验证准备时
         */
        @Override
        public Prepared prepare(ManagedDelivery.Component component) throws Exception {
            if (!prepared) {
                remote.prepareManagedPlatform(new EnvironmentSetupApproval(request.server().id(), true, Instant.now()));
                prepared = true;
            }
            var server = servers.findTrusted(request.server().id())
                    .orElseThrow(() -> new SecurityException("authenticated server identity missing"));
            var application = ManagedApplicationIdentityResolver.resolve(persistence.managedApplications(),
                    applicationId + "-" + component.id(), server);
            var workspace = new RemoteWorkspace(application.id(), archive.contentSha256());
            var receipt = remote.uploadSource(archive, workspace,
                    BuildLimitConfiguration.defaultNonRoot().maxWorkspaceBytes());
            if (!receipt.contentSha256().equals(archive.contentSha256()) || receipt.byteCount() != archive.byteCount())
                throw new SecurityException("uploaded source identity mismatch");
            String backend = component.runtime() instanceof DeploymentRuntimeSpecification.Container container
                    ? container.engine().name().toLowerCase(Locale.ROOT)
                    : "ordinary";
            String revision = remote.projects().open(request.taskId(), workspace, backend);
            applications.put(component.id(), application);
            journal.accept(Map.of("type", "REMOTE_CANDIDATE", "action", workspace.candidateId(), "detail",
                    workspace.applicationId() + "|" + workspace.sourceSha256()));
            return new Prepared(workspace, revision);
        }

        /** Returns the protected project interface. / 返回受保护项目接口。
         * @return scoped project port / 限定项目端口
         */
        @Override
        public RemoteProjectPort projects() {
            return remote.projects();
        }

        /** Publishes and records the verified graph atomically. / 发布并原子记录已验证图。
         * @param delivery complete declared graph / 完整声明图
         * @param candidates task candidates / 任务候选
         * @param revisions source revisions / 源码修订
         * @param artifacts sealed artifact digests / 封存制品摘要
         * @return actual publication outcome / 实际发布结果
         * @throws Exception when persistence or preparation fails / 持久化或准备失败时
         */
        @Override
        public Result deliver(ManagedDelivery delivery, Map<String, Prepared> candidates, Map<String, String> revisions,
                Map<String, String> artifacts) throws Exception {
            var publications = new ArrayList<PreparedPublication>();
            var records = new ArrayList<SuccessfulManagedDeployment>();
            var components = new ArrayList<ManagedApplicationGraph.Component>();
            for (var component : delivery.components()) {
                var application = applications.get(component.id());
                var candidate = candidates.get(component.id());
                if (application == null || candidate == null || !artifacts.get(component.id()).equals(
                        remote.projects().seal(request.taskId(), candidate.workspace(), revisions.get(component.id()))))
                    throw new SecurityException("source or artifact changed before publication");
                var configuration = ConfigurationSnapshot.create(application.id(),
                        Math.max(1, Instant.now().toEpochMilli()), "autonomous-v2", Instant.now(),
                        component.configuration());
                String release = AgentAction.digest(artifacts.get(component.id()) + "|" + revisions.get(component.id())
                        + "|" + component.runtime() + "|" + configuration.sha256() + "|" + component.resources());
                var staged = DeploymentInputMapper.stage(remote, application, configuration, List.of());
                publications.add(new PreparedPublication(component.id(), component.dependencies(), application,
                        candidate.workspace(),
                        DeploymentBuildResult.succeeded(archive.contentSha256(),
                                "artifact=" + artifacts.get(component.id()) + ";source="
                                        + revisions.get(component.id())),
                        release, component.runtime(), Optional.empty(), DeploymentInputMapper.manifest(staged),
                        new ManagedContentPublication(applicationId, component.id(), DeploymentInputMapper
                                .storage(application.id(), component.resources(), component.runtime()))));
                var runtime = new ManagedApplicationRuntimeConfiguration(component.runtime().healthCheck(),
                        Optional.empty(), component.runtime().identityPolicy(), component.runtime().workload());
                records.add(new SuccessfulManagedDeployment(application, runtime,
                        new CurrentRelease(application.id(), release, Instant.now()), configuration, List.of()));
                components.add(new ManagedApplicationGraph.Component(component.id(), application, runtime,
                        component.dependencies(), Optional.of(component.runtime()),
                        Optional.of(component.resources().fileBindings().stream()
                                .map(gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding::dataPath)
                                .toList()),
                        Optional.of(component.resources())));
            }
            var result = new ManagedPublicationTransaction().publish(remote, publications,
                    Optional.of(delivery.applicationHealth()),
                    (id, event) -> progress.accept(LocalizedMessage.of("deployment.agent.observation",
                            Map.of("tool", id + ":" + event.step(), "output", event.evidence()))));
            if (result.status() != DeploymentStatus.SUCCEEDED)
                return new Result(result.status(), Set.of(), result.evidence());
            try {
                persistence.managedApplicationGraphs().recordSuccessfulApplication(
                        new ManagedApplicationGraph(applicationId, delivery.applicationHealth().componentId(),
                                Optional.of(delivery.applicationHealth().healthCheck()), components),
                        records);
                for (var component : delivery.components())
                    handoffs.put(component.id(), new DeploymentHandoff.ApplicationEntry(
                            ApplicationUsage.from(applications.get(component.id()), component.runtime().workload())));
            } catch (Exception failure) {
                journal.accept(Map.of("type", "INVENTORY_UNKNOWN", "action", applicationId, "detail",
                        failure.getClass().getSimpleName()));
                return new Result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, Set.of(),
                        "remote deployment healthy; local inventory persistence failed");
            }
            boolean clean = true;
            for (var component : delivery.components())
                try {
                    clean &= remote.retainRecentSuccessfulReleases(applications.get(component.id())).succeeded();
                    clean &= remote.cleanupCandidate(candidates.get(component.id()).workspace()).succeeded();
                } catch (Exception failure) {
                    clean = false;
                    journal.accept(Map.of("type", "CLEANUP_PENDING", "action", component.id(), "detail",
                            failure.getClass().getSimpleName()));
                }
            return new Result(DeploymentStatus.SUCCEEDED, result.observations().keySet(),
                    result.evidence() + (clean ? "" : "; cleanup pending"));
        }
    }
}
