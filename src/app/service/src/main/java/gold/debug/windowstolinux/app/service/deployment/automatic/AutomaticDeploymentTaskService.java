package gold.debug.windowstolinux.app.service.deployment.automatic;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import gold.debug.windowstolinux.app.db.persistence.repository.AgentTaskRepository;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.service.contract.*;
import gold.debug.windowstolinux.app.service.contract.definition.*;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.source.SourcePreparationUseCase;
import gold.debug.windowstolinux.shared.ai.client.StructuredAiClient;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedDeploymentAdvisor;

/** App-only mode assembly, frozen model scope and durable task lifetime. / 仅 App 使用的模式装配、冻结模型作用域及持久化任务生命周期。 */
public final class AutomaticDeploymentTaskService {
    /** Existing typed deployment facade. / 既有类型化部署门面。 */
    private final AutomaticDeploymentApplicationFacade service;

    /** Model configuration and credential gateway. / 模型配置及凭据网关。 */
    private final AiUseCaseFacade ai;

    /** Existing source preparation. / 既有源码准备。 */
    private final SourcePreparationUseCase source;

    /** Shared same-server exclusion. / 共享同服务器互斥。 */
    private final ServerOperationLockRegistry locks;

    /** Nonsecret durable journal. / 非秘密持久化日志。 */
    private final AgentTaskRepository records;

    /** In-process controls only; restart never reconstructs execution closures. / 仅进程内控制，重启不重建执行闭包。 */
    private final Map<String, AgentTaskControl> active = new ConcurrentHashMap<>();

    /** Explicit requests to replace a paused task's model snapshot. / 显式替换暂停任务模型快照的请求。 */
    private final Set<String> refreshRequested = ConcurrentHashMap.newKeySet();

    /** Task-owned remote operations. / 任务所属远端操作。 */
    private final DeploymentCandidateToolService remoteTools;

    /** Autonomous engine assembly. / 自主引擎装配。 */
    private final gold.debug.windowstolinux.app.service.deployment.AutonomousDeploymentUseCase autonomous;
    /** Binds App dependencies without performing deployment. / 绑定 App 依赖，不执行部署。
     * @param service typed deployment facade / 类型化部署门面
     * @param ai purpose model gateway / 用途模型网关
     * @param source source preparation / 源码准备
     * @param locks server exclusion / 服务器互斥
     * @param records task journal / 任务日志
     * @param remoteTools task-owned remote operations / 任务所属远端操作
     * @param autonomous autonomous use case / 自主用例
     */
    public AutomaticDeploymentTaskService(AutomaticDeploymentApplicationFacade service, AiUseCaseFacade ai,
            SourcePreparationUseCase source, ServerOperationLockRegistry locks, AgentTaskRepository records,
            DeploymentCandidateToolService remoteTools,
            gold.debug.windowstolinux.app.service.deployment.AutonomousDeploymentUseCase autonomous) {
        this.service = service;
        this.ai = ai;
        this.source = source;
        this.locks = locks;
        this.records = records;
        this.remoteTools = remoteTools;
        this.autonomous = autonomous;
    }

    /** Dispatches a fully checked task; model preflight precedes all effects. / 派发完整检查的任务，模型预检先于全部外部影响。
     * @param request frozen click-time request / 点击时冻结请求
     * @param master task unlock buffer / 任务解锁缓冲区
     * @param suppliedInteraction user inputs and exact reviews / 用户输入及精确审阅
     * @param suppliedFingerprint first-use host identity confirmation / 首次主机身份确认
     * @param progress structured progress / 结构化进度
     * @return actual verified deployment result / 实际已验证部署结果
     * @throws Exception on failed preflight or deployment / 预检或部署失败时
     */
    public AutomaticDeploymentOutcome deploy(AutomaticDeploymentRequest request, char[] master,
            AutomaticDeploymentInteraction suppliedInteraction, Predicate<String> suppliedFingerprint,
            Consumer<LocalizedMessage> progress) throws Exception {
        long started = System.nanoTime();
        var lock = locks.forServer(request.server().id());
        if (!lock.tryLock()) {
            Arrays.fill(master, '\0');
            throw new IllegalStateException("server already has an active operation");
        }
        try (var scope = ai.openDeployment(request.automationMode())) {
            var interaction = tracked(scope, suppliedInteraction);
            Predicate<String> fingerprint = value -> {
                scope.human();
                return suppliedFingerprint.test(value);
            };
            if (!records.unresolved(request.server().id(), endpointBinding(request)).isEmpty()) {
                progress.accept(LocalizedMessage.of("deployment.agent.unresolvedOutcome"));
                throw new IllegalStateException("deployment.agent.unresolvedOutcome");
            }
            if (request.automationMode() == DeploymentAutomationMode.STATIC)
                return new AutomaticDeploymentUseCase(service, source, locks).deploy(request, master, interaction,
                        fingerprint, progress);
            return deployAiTask(request, master, interaction, fingerprint, progress, scope, started);
        } finally {
            Arrays.fill(master, '\0');
            lock.unlock();
        }
    }

    /** Runs the independently reviewed branch inside the frozen model and server scopes. / 在冻结模型及服务器作用域中运行独立审批分支。
     * @param request frozen request / 冻结请求
     * @param master scoped credentials / 限定凭据
     * @param interaction human port / 人工端口
     * @param fingerprint host trust callback / 主机信任回调
     * @param progress visible progress / 可见进度
     * @param scope frozen purpose scope / 冻结用途作用域
     * @param started monotonic start time / 单调起始时间
     * @return verified result / 已验证结果
     * @throws Exception when execution or persistence fails / 执行或持久化失败时
     */
    private AutomaticDeploymentOutcome deployAiTask(AutomaticDeploymentRequest request, char[] master,
            AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint,
            Consumer<LocalizedMessage> progress, DeploymentAiScope scope, long started) throws Exception {
        String id = request.taskId();
        var frozen = new TreeMap<String, String>();
        for (var purpose : AiPurposeType.values())
            frozen.put(purpose.name(), scope.providers(purpose).toString());
        records.create(id, request.server().id(),
                AgentAction.digest(AssistedDeploymentBoundary.target(request.server())), request.automationMode(),
                request.approvalMode(), AgentAction.digest(frozen.toString()),
                request.automationMode() == DeploymentAutomationMode.AGENT
                        ? gold.debug.windowstolinux.shared.agent.execution.protocol.AutonomousAiProtocolClient.SKILL
                        : gold.debug.windowstolinux.shared.standard.deploy.assistance.execution.protocol.AssistedAiProtocolClient.DEPLOYMENT_SKILL,
                gold.debug.windowstolinux.shared.deploy.approval.DeploymentApprovalClient.APPROVAL_SKILL);
        records.event(id, "REMOTE_TARGET", "", endpointBinding(request));
        var control = createControl(request, scope, progress);
        scope.beforeRequest(control::checkpoint);
        if (active.putIfAbsent(id, control) != null)
            throw new IllegalStateException("task already running");
        try (var autonomousModels = request.automationMode() == DeploymentAutomationMode.AGENT
                ? ai.agentModels(master)
                : null;
                var assistedModels = request.automationMode() == DeploymentAutomationMode.ASSISTED
                        ? ai.assistedModels(master)
                        : null) {
            gold.debug.windowstolinux.shared.deploy.approval.DeploymentReviewPort models = autonomousModels != null
                    ? autonomousModels
                    : assistedModels;
            Consumer<Map<String, String>> journal = event -> {
                try {
                    records.event(id, event.get("type"), event.get("action"), event.get("detail"));
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException("cannot persist action intent", failure);
                }
                progress.accept(LocalizedMessage.of("deployment.agent.event", Map.of("type", event.get("type"),
                        "action", event.get("action"), "detail", event.get("detail"))));
            };
            scope.audit(journal);
            var revision = new java.util.concurrent.atomic.AtomicReference<Supplier<String>>(() -> scope.binding());
            var audit = new gold.debug.windowstolinux.shared.deploy.approval.CommandApprovalAudit(
                    request.approvalMode(), models, control,
                    (command, risk) -> interaction.confirm("deployment.agent.commandReview",
                            Map.of("command", command.command(), "script", command.script(), "target", command.target(),
                                    "identity", command.identity(), "directory", command.directory(), "risk",
                                    risk.name(), "binding", command.binding())),
                    command -> {
                        try {
                            if (!command.task().equals(id)
                                    || !command.target().equals(AssistedDeploymentBoundary.target(request.server()))
                                    || !service.findServerProfile(request.server().id())
                                            .filter(request.server()::equals).isPresent())
                                return AgentRiskLevel.FORBIDDEN;
                        } catch (java.sql.SQLException failure) {
                            return AgentRiskLevel.FORBIDDEN;
                        }
                        return command.command().contains("'agent-read'") || command.command().contains("'agent-seal'")
                                || command.command().contains("'agent-run'")
                                        ? AgentRiskLevel.NORMAL
                                        : AgentRiskLevel.HIGH;
                    }, journal, command -> progress.accept(LocalizedMessage.of("deployment.agent.observation",
                            Map.of("tool", "COMMAND", "output", command.command() + "\n" + command.script()))));
            try (var commandScope = new gold.debug.windowstolinux.shared.linux.command.CommandExecutionScope(id,
                    AssistedDeploymentBoundary.target(request.server()),
                    () -> AgentAction.digest(revision.get().get() + "|" + scope.binding()), audit)) {
                if (request.automationMode() == DeploymentAutomationMode.AGENT) {
                    try (var remoteScope = new gold.debug.windowstolinux.shared.linux.transfer.DeploymentRemoteTaskScope(
                            id,
                            workspace -> journal
                                    .accept(Map.of("type", "REMOTE_CANDIDATE", "action", workspace.candidateId(),
                                            "detail", workspace.applicationId() + "|" + workspace.sourceSha256())))) {
                        var result = autonomous.deploy(request, master, interaction, fingerprint, progress,
                                autonomousModels, control, journal, revision::set);
                        control.finish(result.status() == DeploymentStatus.SUCCEEDED
                                ? AgentTaskState.SUCCEEDED
                                : result.status() == DeploymentStatus.MANUAL_RECOVERY_REQUIRED
                                        ? AgentTaskState.UNKNOWN
                                        : AgentTaskState.FAILED);
                        return result;
                    }
                }
                var boundary = new AssistedDeploymentBoundary(id, request.server(), AgentApprovalMode.FULL_CONTROL,
                        assistedModels, control, interaction, journal, () -> {
                            try {
                                return service.findServerProfile(request.server().id())
                                        .map(current -> current.equals(request.server())).orElse(false);
                            } catch (java.sql.SQLException failure) {
                                return false;
                            }
                        });
                try (var remoteScope = new gold.debug.windowstolinux.shared.linux.transfer.DeploymentRemoteTaskScope(id,
                        workspace -> {
                            journal.accept(Map.of("type", "REMOTE_CANDIDATE", "action", workspace.candidateId(),
                                    "detail", workspace.applicationId() + "|" + workspace.sourceSha256()));
                            for (boolean cleanup : List.of(false, true))
                                boundary.register((cleanup ? "cleanup:" : "query:") + workspace.candidateId(),
                                        cleanup ? AgentToolType.CLEAN_CANDIDATE : AgentToolType.CANDIDATE_STATUS,
                                        Map.of("candidate", workspace.candidateId(), "task", id, "scope",
                                                "Exact root-owned task candidate; excludes release, configuration and data directories"),
                                        () -> true, () -> remoteTools.candidate(request.server(), id, workspace,
                                                cleanup, master.clone()));
                        })) {
                    revision.set(boundary::binding);
                    {
                        var advice = new AssistedDeploymentAdvisor(
                                (phase, candidates, evidence) -> ai.assist(phase, candidates, evidence, master.clone()),
                                interaction, progress, assistedModels, boundary.budget());
                        var result = new AutomaticDeploymentUseCase(service, source, locks, advice, boundary).deploy(
                                request, master, new AssistedDeploymentInteraction(interaction, boundary), fingerprint,
                                progress);
                        control.finish(result.status() == DeploymentStatus.SUCCEEDED
                                ? AgentTaskState.SUCCEEDED
                                : result.status() == DeploymentStatus.MANUAL_RECOVERY_REQUIRED
                                        ? AgentTaskState.UNKNOWN
                                        : AgentTaskState.FAILED);
                        return result;
                    }
                }
            }
        } catch (Exception failure) {
            if (control.state() != AgentTaskState.UNKNOWN)
                control.finish(
                        failure instanceof CancellationException ? AgentTaskState.CANCELLED : AgentTaskState.FAILED);
            throw failure;
        } finally {
            active.remove(id);
            refreshRequested.remove(id);
            records.event(id, "METRICS", "",
                    scope.metrics() + ";seconds=" + (System.nanoTime() - started) / 1_000_000_000);
        }
    }

    /** Creates task state persistence and explicit model-refresh hooks. / 创建任务状态持久化及显式模型刷新钩子。
     * @param request frozen task / 冻结任务
     * @param scope model scope / 模型作用域
     * @param progress visible progress / 可见进度
     * @return task control / 任务控制
     */
    private AgentTaskControl createControl(AutomaticDeploymentRequest request, DeploymentAiScope scope,
            Consumer<LocalizedMessage> progress) {
        String id = request.taskId();
        var control = new AgentTaskControl(state -> {
            try {
                records.state(id, state);
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("cannot persist task state", failure);
            }
            progress.accept(LocalizedMessage.of("deployment.agent.state", "state", state.name()));
        });
        control.onResume(() -> {
            if (refreshRequested.remove(id)) {
                try {
                    scope.replace(ai.deploymentSnapshot(request.automationMode()));
                    records.event(id, "CONFIGURATION_REPLACED", "", scope.binding());
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException("cannot replace model snapshot", failure);
                }
            }
        });
        return control;
    }

    /** Changes process-local control without changing frozen task policy. / 改变进程内控制，不改变冻结任务策略。
     * @param id task identifier / 任务标识
     * @param command explicit user action / 显式用户操作
     */
    public void control(String id, AgentTaskCommandAction command) {
        var task = active.get(id);
        if (task == null)
            throw new IllegalStateException("task not active");
        switch (command) {
            case PAUSE -> task.pause();
            case RESUME -> task.resume();
            case CANCEL -> task.cancel();
            case REFRESH_MODELS -> {
                if (task.state() != AgentTaskState.PAUSED)
                    throw new IllegalStateException("model replacement requires paused task");
                try {
                    ai.deploymentSnapshot(DeploymentAutomationMode.AGENT);
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException("invalid model configuration", failure);
                }
                refreshRequested.add(id);
                task.resume();
            }
        }
    }

    /** Reads a running task's safe state and pause reason. / 读取运行任务的安全状态及暂停原因。
     * @param id task identifier / 任务标识
     * @return nonsecret state fields / 非秘密状态字段
     */
    public Map<String, String> state(String id) {
        var task = active.get(id);
        return task == null ? Map.of() : Map.of("state", task.state().name(), "reason", task.reason());
    }

    /** Reads persisted recent task history. / 读取持久化最近任务历史。
     * @return nonsecret task summaries / 非秘密任务摘要
     * @throws java.sql.SQLException on database failure / 数据库失败时
     */
    public List<Map<String, String>> history() throws java.sql.SQLException {
        return records.recent();
    }

    /** Reads one persisted task's event sequence. / 读取一个持久化任务的事件序列。
     * @param id task identifier / 任务标识
     * @return nonsecret chronological events / 非秘密时间顺序事件
     * @throws java.sql.SQLException on database failure / 数据库失败时
     */
    public List<Map<String, String>> events(String id) throws java.sql.SQLException {
        return records.events(id);
    }

    /** Queries recorded candidates without clearing uncertainty or authorizing replay. / 查询已记录候选项，不清除不确定状态或授权重放。
     * @param id recorded task / 已记录任务
     * @param master temporary unlock buffer / 临时解锁缓冲区
     * @return observed facts and remaining uncertainty / 观测事实及剩余不确定性
     * @throws Exception when remote or journal access fails / 远端或日志访问失败时
     */
    public List<Map<String, String>> inspect(String id, char[] master) throws Exception {
        try {
            var task = records.find(id).orElseThrow(() -> new IllegalArgumentException("task missing"));
            var server = service.findServerProfile(task.get("server_id")).orElseThrow();
            if (!AgentAction.digest(AssistedDeploymentBoundary.target(server)).equals(task.get("target_digest")))
                throw new SecurityException("task target changed");
            var lock = locks.forServer(server.id());
            if (!lock.tryLock())
                throw new IllegalStateException("server already has an active operation");
            try {
                var observations = new ArrayList<Map<String, String>>();
                var seen = new HashSet<String>();
                for (var event : records.events(id))
                    if (event.get("event_type").equals("REMOTE_CANDIDATE") && seen.add(event.get("detail"))) {
                        String[] fields = event.get("detail").split("\\|", -1);
                        if (fields.length != 2)
                            throw new IllegalStateException("invalid candidate evidence");
                        var workspace = new gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace(fields[0],
                                fields[1]);
                        var observed = remoteTools.candidate(server, id, workspace, false, master.clone());
                        observations.add(observed.facts());
                        records.event(id, "RECONCILIATION_QUERY", workspace.candidateId(),
                                new TreeMap<>(observed.facts()).toString());
                    }
                observations.add(Map.of("outcome",
                        "UNKNOWN is retained until complete publication, database and environment effects can be verified; candidate absence alone never authorizes replay."));
                return List.copyOf(observations);
            } finally {
                lock.unlock();
            }
        } finally {
            Arrays.fill(master, '\0');
        }
    }

    /** Counts user interactions without recording secrets or answers. / 统计用户交互，不记录秘密或回答。
     * @param scope current task / 当前任务
     * @param delegate UI port / 界面端口
     * @return counting port / 计数端口
     */
    private static AutomaticDeploymentInteraction tracked(DeploymentAiScope scope,
            AutomaticDeploymentInteraction delegate) {
        return new AutomaticDeploymentInteraction() {
            /** Counts an input dialog. / 统计输入对话框。
             * @param fields requested fields / 请求字段
             * @return user answers / 用户回答
             */
            @Override
            public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                scope.human();
                return delegate.requestInputs(fields);
            }

            /** Counts a concrete confirmation. / 统计具体确认。
             * @param key localized question / 本地化问题
             * @param details displayed effects / 展示影响
             * @return decision / 决定
             */
            @Override
            public boolean confirm(String key, Map<String, ?> details) {
                scope.human();
                return delegate.confirm(key, details);
            }

            /** Counts a secret prompt without accessing its content. / 统计秘密提示，不读取内容。
             * @param key localized prompt / 本地化提示
             * @return temporary buffer / 临时缓冲区
             */
            @Override
            public char[] requestSecret(String key) {
                scope.human();
                return delegate.requestSecret(key);
            }

            /** Preserves the explicit database replacement contract. / 保留显式数据库替换契约。
             * @param details reviewed effects / 已审阅影响
             * @return decision / 决定
             */
            @Override
            public boolean confirmDatabaseReplacement(Map<String, ?> details) {
                scope.human();
                return delegate.confirmDatabaseReplacement(details);
            }
        };
    }

    /** Binds the selected endpoint independently of its editable saved-profile identifier. / 独立于可编辑保存资料标识绑定所选端点。
     * @param request frozen task / 冻结任务
     * @return nonsecret endpoint digest / 非秘密端点摘要
     */
    private static String endpointBinding(AutomaticDeploymentRequest request) {
        return AgentAction.digest(request.server().host().toLowerCase(Locale.ROOT) + "|" + request.server().sshPort());
    }
}
