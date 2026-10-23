package gold.debug.windowstolinux.mod.deployment.service.agent;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiver;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceSetup;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRequest;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.control.DeploymentAgentControl;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentHistoryReader;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentRecovery;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentGitSourceFlow;

/** Owns one independent agent task and reconciles only its original durable effects. / 持有一项独立自主任务并仅核对其原持久效果。 */
public final class DeploymentAgentWorkflow implements TaskRecovery {
    /** Dedicated original task type, separate from standard publication. / 与标准发布分离的专用原任务类型。 */
    public static final String TASK_TYPE = "deployment-agent";

    /** Original platform. / 原平台。 */
    private final ModContext context;

    private final gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReviewSource reviewSource;

    /** Dedicated original task identity and action evidence ledger. / 专有原任务身份及动作依据账本。 */
    private final DeploymentAgentActionLog journal;

    private final gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy isolationPolicy;

    private volatile DeploymentAgentRemoteTools.IsolationAdmission isolatedAdmission;

    /** Actual admission facts retained by this Workflow instance, never an approval or grant. */
    public record IsolationAdmission(
        gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.PlannedScope scope,
        gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.Receipt receipt) {
    }

    public Optional<IsolationAdmission> isolationAdmission() {
        var saved = isolatedAdmission;
        return saved == null ? Optional.empty() : Optional.of(new IsolationAdmission(saved.scope(), saved.receipt()));
    }

    public boolean protectedIsolationUnchanged(
        gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.Receipt post) {
        var saved = isolatedAdmission;
        if (saved == null) throw new SecurityException("No actual isolation admission exists");
        return saved.gate().protectedUnchanged(post);
    }

    private synchronized void retainIsolationAdmission(DeploymentAgentRemoteTools.IsolationAdmission admission) {
        if (isolatedAdmission != null && !isolatedAdmission.scope().task().equals(admission.scope().task()))
            throw new SecurityException("Isolation admission cannot cross tasks");
        isolatedAdmission = Objects.requireNonNull(admission);
    }

    /** Only live original workers can accept cooperative controls. / 仅活动原工作线程能够接受协作控制。 */
    private final java.util.concurrent.ConcurrentMap<UUID, DeploymentAgentControl> controls = new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.concurrent.ConcurrentMap<UUID, DeploymentAgentGoal> goals = new java.util.concurrent.ConcurrentHashMap<>();

    public Optional<DeploymentAgentGoal> goal(UUID root) {
        return Optional.ofNullable(goals.get(root));
    }

    public void requestApprovalPolicyChange(UUID root, String mode, long expectedRevision) throws ApiException {
        var goal = goals.get(root);
        var current = goal == null ? root : goal.currentHandle().map(TaskHandle::id).orElse(root);
        gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.request(context, current, mode, expectedRevision);
    }

    public gold.debug.windowstolinux.mod.deployment.service.core.DeploymentApprovalPolicyState approvalPolicyState(UUID root) {
        var goal = goals.get(root);
        var current = goal == null ? root : goal.currentHandle().map(TaskHandle::id).orElse(root);
        var state = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.state(context, current);
        if (state.editable() || state.problem().isPresent() || goal == null) return state;
        var confirmed = goal.approvalPolicy();
        return new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentApprovalPolicyState(confirmed.mode(), confirmed.revision(),
            Optional.empty(), Optional.empty(), false);
    }

    /** User-reviewed bounded source and action policy. / 用户审核过的有界来源及动作策略。
     * @param requirements nonsecret task requirements / 不含秘密的任务要求
     * @param approvalMode manual, automatic or full control / 人工、自动或全控
     * @param rememberAuthorization captured question repetition / 已抓取问题重复规则
     * @param sourceBytes source capture limit / 来源捕获上限
     * @param sourceEntries discovery limit / 发现上限
     * @param archiveBytes archive limit / 归档上限 */

    /** Binds an active Mod and its dedicated original-task journal. / 绑定活动 Mod 与专有原任务账本。
     * @param context original activation / 原启用 */
    public DeploymentAgentWorkflow(ModContext context) {
        this(context, null, null);
    }

    DeploymentAgentWorkflow(ModContext context,
                            gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReviewSource reviewSource) {
        this(context, reviewSource, null);
    }

    /** Trusted caller-only isolation policy; never parsed from model requirements. */
    public DeploymentAgentWorkflow(ModContext context,
                                   gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy isolationPolicy) {
        this(context, null, Objects.requireNonNull(isolationPolicy));
    }

    private DeploymentAgentWorkflow(ModContext context,
                                    gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReviewSource reviewSource,
                                    gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy isolationPolicy) {
        this.context = Objects.requireNonNull(context);
        this.isolationPolicy = isolationPolicy;
        this.reviewSource = reviewSource;
        this.journal = new DeploymentAgentActionLog(context.db());
    }

    /** Requests a control on an actually live worker of this activation. / 请求控制此启用代次的实际活动工作线程。
     * @param taskId exact original identity / 精确原身份
     * @param command explicit user request / 显式用户请求 */
    public void control(UUID taskId, DeploymentAgentControl.CommandAction command) {
        var control = controls.get(Objects.requireNonNull(taskId));
        if (control == null)
            throw new IllegalStateException("Original agent is not active");
        control.request(command);
    }

    /** Reads process-local control without fabricating SDK task states. / 读取进程内部控制，不伪造SDK任务状态。
     * @param taskId original identity / 原身份
     * @return actual live control state or absence / 实际活动控制状态或不存在 */
    public Optional<DeploymentAgentControl.State> controlState(UUID taskId) {
        return Optional.ofNullable(controls.get(taskId)).map(DeploymentAgentControl::state);
    }

    /** Starts one local user-selected source in its own original task. / 在专有原任务中启动一份用户所选本地来源。
     * @param server selected server / 所选服务器
     * @param request bounded user policy / 有界用户策略
     * @return observable original task / 可观察原任务
     * @throws ApiException on failed task admission / 任务准入失败时 */
    public TaskHandle<DeploymentAgentDeliveryPort.Published> submitLocal(ServerId server, DeploymentAgentInputs.Request request)
        throws ApiException {
        return submitLocal(server, request, Optional.empty());
    }

    /** Reauthorizes preselected position metadata in a fresh original autonomous task. / 在新的原自主任务重新授权预选位置元数据。
     * @param server explicit original target / 明确原目标
     * @param request frozen bounded policy / 冻结有界策略
     * @param location completed picker position metadata, never a file grant / 已完成选择器位置元数据，不是文件授权
     * @return original root handle / 原根句柄
     * @throws ApiException if original admission fails / 原准入失败时 */
    public TaskHandle<DeploymentAgentDeliveryPort.Published> submitLocal(ServerId server, DeploymentAgentInputs.Request request,
                                                                         gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation location)
        throws ApiException {
        return submitLocal(server, request, Optional.of(Objects.requireNonNull(location)));
    }

    /** Captures one route and keeps all source analysis, archive and execution on its original task. / 抓取一条路径，将全部来源分析、归档及执行保留在其原任务。
     * @param server exact target / 精确目标
     * @param request original policy / 原策略
     * @param location explicit metadata or original in-task picker / 明确元数据或原任务内选择器
     * @return original accepted task / 原已接纳任务
     * @throws ApiException if original admission fails / 原准入失败时 */
    private TaskHandle<DeploymentAgentDeliveryPort.Published> submitLocal(ServerId server, DeploymentAgentInputs.Request request,
                                                                          Optional<gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation> location)
        throws ApiException {
        Objects.requireNonNull(request);
        var goal = new DeploymentAgentGoal(context, server, request, this::submitSuccessor);
        var handle = context.tasks().submit(new TaskRequest(TASK_TYPE, "Autonomous deployment", List.of(server.id()), true), task -> {
            try (var worker = goal.rootWorker(task).open()) {
                goals.put(task.id(), goal);
                var result = runLocal(server, request, location, task);
                worker.finished(result);
                return result;
            }
        });
        goals.put(handle.id(), goal);
        goal.attach(handle);
        return handle;
    }

    private OperationResult<DeploymentAgentDeliveryPort.Published> runLocal(ServerId server, DeploymentAgentInputs.Request request,
                                                                            Optional<gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation> location,
                                                                            TaskContext task) throws Exception {
        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(), task,
            new DeploymentRemoteLayout(gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().paths()));
        var saved = saveSelection(request, task);
        if (saved.isPresent())
            return finishKnown(saved.orElseThrow(), task);
        final String configuration;
        try {
            configuration = admittedConfiguration(server, request, task);
        } catch (ApiException failure) {
            return finishKnown(failed(task, false, failure), task);
        }
        final DeploymentSourceMaterial material;
        try {
            var selection = location.isPresent()
                ? context.file().sources().selectDirectory(java.nio.file.Path.of(location.orElseThrow().location()), task)
                : context.file().sources().selectDirectory("Autonomous deployment source", task);
            if (task.cancelToken().requested()) throw new ApiException(new Problem(ProblemCode.CANCELLED,
                "DEPLOYMENT_AGENT_SOURCE_CANCELLED", "Original source selection cancelled", Map.of(), Optional.empty()));
            material = new DeploymentSourceSetup(context.file()).prepare(selection,
                request.sourceBytes(), request.sourceEntries(), task);
        } catch (ApiException failure) {
            return finishKnown(failed(task, false, failure), task);
        }
        DeploymentSourceArchive archive = null;
        OperationResult<DeploymentAgentDeliveryPort.Published> result = null;
        try {
            archive = new DeploymentSourceArchiver(context.file()).prepare(material,
                request.archiveBytes(), task);
            result = execute(server, request, material, archive, Optional.empty(), configuration, task);
        } catch (ApiException failure) {
            result = failed(task, false, failure);
        } finally {
            if (result != null && result.outcome() != TaskResult.UNKNOWN) {
                try {
                    if (archive != null)
                        archive.close();
                } finally {
                    material.close();
                }
            }
        }
        return finishKnown(result, task);
    }

    private TaskHandle<DeploymentAgentDeliveryPort.Published> submitSuccessor(DeploymentAgentGoal.Pending next) throws Exception {
        var intent = next.ticket.intent();
        var goal = goals.get(intent.root());
        if (goal == null)
            throw new IllegalStateException("Original online goal is absent; no capsule recovery or resubmission is allowed");
        return context.tasks().submit(new TaskRequest(TASK_TYPE, "Autonomous deployment successor", List.of(intent.server()), true), task -> {
            try (var worker = goal.claimWorker(task, next).open()) {
                var server = new ServerId(intent.server());
                var request = intent.request();
                var layout = DeploymentRemoteLayout.decode(intent.layout());
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(), task, layout);
                if (!intent.configuration().equals(admittedConfiguration(server, request, task))
                    || !intent.targetProfile().equals(DeploymentAgentInputs.targetProfile(context.servers().get(server, task)))
                    || !intent.physicalTarget().equals(DeploymentAgentInputs.physicalTarget(context.servers().get(server, task))))
                    throw new IllegalStateException("Original source, target or model authorization changed before successor preparation");
                var material = next.source.rebind(context.file(), task);
                DeploymentSourceArchive archive = null;
                OperationResult<DeploymentAgentDeliveryPort.Published> result = null;
                try {
                    next.source.close();
                    archive = new DeploymentSourceArchiver(context.file()).prepare(material, request.archiveBytes(), task);
                    result = execute(server, request, material, archive, Optional.empty(), intent.configuration(), task);
                } finally {
                    if (result != null && result.outcome() != TaskResult.UNKNOWN) {
                        try {
                            if (archive != null) archive.close();
                        } finally {
                            material.close();
                        }
                    }
                }
                result = finishKnown(result, task);
                worker.finished(result);
                return result;
            } catch (Exception uncertain) {
                goal.releasePrivate(next);
                try {
                    journal.blockSuccessor(task, intent.predecessor());
                } catch (Exception unavailable) {
                    uncertain.addSuppressed(unavailable);
                }
                return failed(task, journal.find(task).isPresent(), uncertain);
            }
        });
    }

    /** Starts a reviewed pinned Git source under the same original agent task. / 在同一自主原任务下启动经审核固定 Git 来源。
     * @param server selected server / 所选服务器
     * @param request bounded user policy / 有界用户策略
     * @param git credential-free Git request / 不含凭据 Git 请求
     * @param executable explicit native Git executable / 明确原生 Git 可执行文件
     * @param projectName reviewed project name / 经审核项目名称
     * @return observable original task / 可观察原任务
     * @throws ApiException on failed task admission / 任务准入失败时 */
    public TaskHandle<DeploymentAgentDeliveryPort.Published> submitGit(ServerId server, DeploymentAgentInputs.Request request,
                                                                       DeploymentGitRequest git, Path executable, String projectName) throws ApiException {
        Objects.requireNonNull(git);
        var goal = new DeploymentAgentGoal(context, server, request, this::submitSuccessor);
        var handle = context.tasks().submit(new TaskRequest(TASK_TYPE, "Autonomous Git deployment", List.of(server.id()), true), task -> {
            try (var worker = goal.rootWorker(task).open()) {
                goals.put(task.id(), goal);
                var result = runGit(server, request, git, executable, projectName, task);
                worker.finished(result);
                return result;
            }
        });
        goals.put(handle.id(), goal);
        goal.attach(handle);
        return handle;
    }

    private OperationResult<DeploymentAgentDeliveryPort.Published> runGit(ServerId server, DeploymentAgentInputs.Request request,
                                                                          DeploymentGitRequest git, Path executable, String projectName, TaskContext task) throws Exception {
        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(), task,
            new DeploymentRemoteLayout(gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().paths()));
        var saved = saveSelection(request, task);
        if (saved.isPresent())
            return finishKnown(saved.orElseThrow(), task);
        final String configuration;
        try {
            configuration = admittedConfiguration(server, request, task);
        } catch (ApiException failure) {
            return finishKnown(failed(task, false, failure), task);
        }
        var pinned = new DeploymentGitSourceFlow(context).prepareForPublication(git, executable,
            projectName, request.sourceBytes(), request.sourceEntries(), task);
        OperationResult<DeploymentAgentDeliveryPort.Published> result = null;
        boolean publicationStarted = false;
        try {
            var selected = pinned.material();
            String facts = "remote=" + selected.remote().location() + "\nreference=" + pinned.reference()
                + "\ncommit=" + selected.commit() + "\nsource=" + selected.source().snapshot().sha256();
            var intent = new ActionIntent("deployment.agent.git-source", List.of(server.id()), projectName,
                selected.source().snapshot().sha256(), facts,
                "Review exact pinned Git source before autonomous deployment");
            var review = context.interaction().approve(intent, task);
            if (review.outcome() != TaskResult.SUCCEEDED) {
                result = new OperationResult<>(review.outcome(), Optional.empty(), review.problem());
                return result;
            }
            DeploymentProjectRecord project = pinned.save();
            publicationStarted = true;
            result = execute(server, request, selected.source(), selected.archive(), Optional.of(project),
                configuration, task);
        } finally {
            if (!publicationStarted || result != null && result.outcome() != TaskResult.UNKNOWN) {
                try {
                    pinned.close();
                } finally {
                    pinned.material().source().close();
                }
            }
        }
        return finishKnown(result, task);
    }

    /** Runs the complete agent against one frozen source and original authenticated target. / 在一份冻结来源及原认证目标上运行完整自主任务。
     * @param server selected target / 所选目标
     * @param request user policy / 用户策略
     * @param material frozen source / 冻结来源
     * @param archive frozen archive / 冻结归档
     * @param project reviewed pinned source project, absent for local source / 经审阅固定来源项目，本地来源时为空
     * @param configuration usable model revision admitted before any source selection / 任一来源选取前准入的可用模型修订
     * @param task original task / 原任务
     * @return observed publication / 已观察发布 */
    private OperationResult<DeploymentAgentDeliveryPort.Published> execute(ServerId server, DeploymentAgentInputs.Request request,
                                                                           DeploymentSourceMaterial material, DeploymentSourceArchive archive,
                                                                           Optional<DeploymentProjectRecord> project, String configuration, TaskContext task) {
        boolean created = false;
        var control = new DeploymentAgentControl();
        try {
            requireSkills();
            var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(), task);
            var source = new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256());
            var successor = DeploymentAgentGoal.currentWorker().flatMap(DeploymentAgentGoal.Worker::predecessor);
            DeploymentAgentGoal.currentWorker().ifPresent(worker -> worker.bind(material, layout));
            if (!configuration.equals(admittedConfiguration(server, request, task)))
                throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_CONFIGURATION_CHANGED",
                    "Original agent configuration changed before execution", Map.of("task", task.id().toString()),
                    Optional.of(task.id())));
            var policy = DeploymentAgentGoal.currentWorker().map(DeploymentAgentGoal.Worker::approvalPolicy)
                .orElseGet(() -> new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.Confirmed(request.approvalMode(), 0));
            try (var workflow = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.open(
                context, task, server, configuration, policy.mode(), request.rememberAuthorization(),
                "source=" + material.snapshot().sha256() + "\narchive=" + archive.sha256() + "\nrequirements=" + new TreeMap<>(request.requirements()), policy.revision());
                 var remote = context.linux().connect(server, task)) {
                String target = DeploymentAgentInputs.targetDigest(server, remote);
                journal.create(task,
                    new DeploymentAgentActionLog.Identity(server.id(), target, configuration, request.approvalMode(), request.rememberAuthorization()),
                    Optional.of(DeploymentAgentInputs.physicalTarget(context.servers().get(server, task))), successor);
                created = true;
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.controller(task).orElseThrow()
                    .observe("AGENT", confirmed -> {
                        DeploymentAgentGoal.currentWorker().ifPresent(worker -> worker.approvalPolicy(confirmed));
                        try {
                            journal.event(task, "APPROVAL_POLICY_CONFIRMED", Long.toString(confirmed.revision()), confirmed.mode());
                        } catch (Exception unavailable) {
                            throw new IllegalStateException("Original policy confirmation history could not be retained", unavailable);
                        }
                    });
                DeploymentAgentLayouts.record(journal, task, server, layout);
                if (controls.putIfAbsent(task.id(), control) != null)
                    throw new IllegalStateException("Original agent worker already exists");
                journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
                journal.event(task, "TARGET_PROFILE", "", DeploymentAgentInputs.targetProfile(context.servers().get(server, task)));
                var current = (java.util.function.Supplier<String>) () -> {
                    try {
                        return context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                    } catch (ApiException failure) {
                        throw new IllegalStateException("Agent configuration cannot be verified", failure);
                    }
                };
                var review = reviewSource == null ? null : Objects.requireNonNull(reviewSource.bind(context, task, server,
                    material.snapshot().sha256(), configuration));
                try (var tools = new DeploymentAgentRemoteTools(context, remote, context.interaction(), server, task,
                    archive, material, configuration, current, journal, project, request.approvalMode(), request.rememberAuthorization(), layout, review, isolationPolicy, this::retainIsolationAdmission)) {
                    var session = new DeploymentAgentSession(context, task, server, request.requirements(), source,
                        tools, journal, request.approvalMode(), request.rememberAuthorization(), configuration, current, control, review);
                    var result = session.run();
                    if (result.outcome() == TaskResult.FAILED)
                        journal.state(task, "FAILED");
                    return result;
                }
            }
        } catch (Exception failure) {
            return failed(task, created, failure);
        } finally {
            controls.remove(task.id(), control);
            control.finish();
        }
    }

    /** Closes a known formal result only after the original remote and source scopes end. / 仅在原远端和来源作用域结束后结清已知正式结果。
     * @param result original agent result / 原自主结果
     * @param task original task / 原任务
     * @return same known result or retained uncertainty / 相同已知结果或保留未知 */
    private OperationResult<DeploymentAgentDeliveryPort.Published> finishKnown(
        OperationResult<DeploymentAgentDeliveryPort.Published> result, TaskContext task) {
        if (result.outcome() == TaskResult.UNKNOWN)
            return result;
        try {
            gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.releaseKnown(context, task);
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.releaseKnown(context, task);
            context.file().closeTaskResources(task);
            var executions = new DeploymentExecutionStore(context.db());
            var formal = executions.findForTask(task);
            if (formal.isEmpty())
                return result;
            var finished = new DeploymentOriginalPublishCommit(context).finishKnown(task, formal.orElseThrow());
            if (finished.outcome() == TaskResult.UNKNOWN)
                return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), finished.problem());
            if (result.outcome() == TaskResult.SUCCEEDED) {
                var archived = new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentHistoryArchive(context)
                    .settle(task, finished);
                if (archived.outcome() == TaskResult.UNKNOWN)
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), archived.problem());
            }
            return result;
        } catch (Exception uncertain) {
            return unknownPublished();
        }
    }

    /** Retains a failed original cleanup without inventing a known result. / 原收尾失败时保留未知而不虚构已知结果。
     * @return classified unknown publication / 分类未知发布 */
    private static OperationResult<DeploymentAgentDeliveryPort.Published> unknownPublished() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_FINAL_CLEANUP_UNCONFIRMED",
                "Original cleanup requires reconciliation", Map.of(), Optional.empty())));
    }

    /** Classifies original failures without replaying possibly started work. / 在不重放可能已启动工作的前提下分类原失败。
     * @param task original task / 原任务
     * @param created whether own ledger exists / 专有账本是否存在
     * @param failure actual failure / 实际失败
     * @return retained classified result / 留存分类结果 */
    private OperationResult<DeploymentAgentDeliveryPort.Published> failed(TaskContext task, boolean created,
                                                                          Exception failure) {
        gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentFailure(context, task, failure);
        TaskResult outcome = failure instanceof IllegalArgumentException || failure instanceof SecurityException
            || failure instanceof IllegalStateException || failure instanceof java.io.IOException
            ? TaskResult.FAILED
            : TaskResult.UNKNOWN;
        Optional<Problem> original = Optional.empty();
        if (failure instanceof DeploymentAiJson.CapabilityOutcomeException classified) {
            outcome = classified.outcome();
            original = classified.problem();
        } else if (failure instanceof CancellationException) {
            outcome = TaskResult.CANCELLED;
        } else if (failure instanceof ApiException api) {
            original = Optional.of(api.problem());
            outcome = api.problem().code() == ProblemCode.CANCELLED
                ? TaskResult.CANCELLED
                : api.problem().code() == ProblemCode.UNKNOWN ? TaskResult.UNKNOWN : TaskResult.FAILED;
        }
        try {
            if (created) {
                var events = journal.events(task);
                if (gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.unconfirmed(context, task)
                    || !DeploymentAgentActionLog.effectsSettled(events)
                    || events.stream().anyMatch(event -> event.type().equals("CANDIDATE"))
                    && events.stream().noneMatch(event -> event.type().equals("DELIVERY_RESULT")))
                    outcome = TaskResult.UNKNOWN;
                journal.state(task, switch (outcome) {
                    case SUCCEEDED -> "SUCCEEDED";
                    case FAILED -> "FAILED";
                    case CANCELLED -> "CANCELLED";
                    case UNKNOWN -> "UNKNOWN";
                });
            }
        } catch (Exception uncertainLedger) {
            outcome = TaskResult.UNKNOWN;
        }
        Problem problem = original.orElse(null);
        if (outcome == TaskResult.UNKNOWN && problem != null && problem.code() != ProblemCode.UNKNOWN)
            problem = null;
        if (problem == null)
            problem = new Problem(
                outcome == TaskResult.CANCELLED
                    ? ProblemCode.CANCELLED
                    : outcome == TaskResult.FAILED ? ProblemCode.IO_FAILURE : ProblemCode.UNKNOWN,
                "DEPLOYMENT_AGENT_STOPPED", "Autonomous deployment requires original-task review", Map.of(),
                Optional.empty());
        return new OperationResult<>(outcome, Optional.empty(), Optional.of(problem));
    }

    /** Reconciles the dedicated original ledger and released resources without a new model or tool call. / 不调用新模型或工具，仅核对专有原账本及已释放资源。
     * @param previous retained task / 留存任务
     * @param originalTask original recovery context / 原恢复上下文
     * @return known terminal or retained unknown / 已知终态或留存未知
     * @throws Exception if original evidence cannot be read / 无法读取原依据时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext originalTask) throws Exception {
        if (!TASK_TYPE.equals(previous.type()) || !previous.id().equals(originalTask.id()))
            return unknown();
        gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.original(context, originalTask, "MANUAL_REVIEW");
        gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.releaseKnown(context, originalTask);
        context.interaction().closeWorkflowResources(originalTask);
        var historyArchive = new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentHistoryArchive(context);
        var retained = historyArchive.retained(originalTask);
        if (retained.isPresent()) return retained.orElseThrow();
        var saved = journal.find(originalTask);
        if (saved.isEmpty())
            return unknown();
        var inspection = DeploymentAgentHistoryReader.pending(journal.events(originalTask));
        if (inspection.isPresent()) {
            var history = new DeploymentAgentHistoryReader(context);
            String facts;
            try {
                facts = history.inspect(originalTask, history.detail(originalTask, originalTask.id()), true).toString();
            } catch (Exception unconfirmed) {
                facts = "UNCONFIRMED:" + unconfirmed.getClass().getSimpleName();
            }
            journal.event(originalTask, "INSPECT_RESULT", Long.toString(inspection.orElseThrow().id()), facts);
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_INSPECTED_UNKNOWN",
                    "Inspection preserves UNKNOWN; recovery requires another explicit original task request",
                    Map.of(), Optional.empty())));
        }
        try {
            var recovered = new DeploymentAgentRecovery(context, journal).reconcile(originalTask, saved.orElseThrow());
            if (recovered.outcome() != TaskResult.UNKNOWN)
                recovered = historyArchive.settle(originalTask, recovered);
            new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(context)
                .effectResult(originalTask.id(), recovered);
            return recovered;
        } catch (Exception failure) {
            gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentFailure(context, originalTask, failure);
            throw failure;
        }
    }

    /** Builds one bounded known or unknown recovery result. / 构造一项有界已知或未知恢复结果。
     * @param outcome classified result / 分类结果
     * @param code public problem code / 公开问题代码
     * @return no-payload result / 无载荷结果 */
    private static OperationResult<Void> result(TaskResult outcome, ProblemCode code) {
        return new OperationResult<>(outcome, Optional.empty(),
            Optional.of(new Problem(code, "DEPLOYMENT_AGENT_RECONCILED",
                "Original agent task result was reconciled", Map.of(), Optional.empty())));
    }

    /** Retains uncertainty without replay. / 不重放地保留不确定性。
     * @return unknown result / 未知结果 */
    private static OperationResult<Void> unknown() {
        return result(TaskResult.UNKNOWN, ProblemCode.UNKNOWN);
    }

    /** Hashes exact nonsecret target identity. / 散列精确不含秘密目标身份。
     * @param value profile and authenticated host fingerprint / 资料与认证主机指纹
     * @return lowercase SHA-256 / 小写 SHA-256 */

    /** Computes the exact original authenticated target binding. / 计算精确原认证目标绑定。
     * @param server original profile / 原资料
     * @param session authenticated SSH / 已认证 SSH
     * @return nonsecret target digest / 不含秘密目标摘要 */

    /** Binds the exact nonsecret original endpoint and account for later readonly inspection. / 绑定精确非秘密原端点及账户以供后续只读检查。
     * @param server actually authorized original profile / 实际授权原资料
     * @return exact endpoint and account digest / 精确端点及账户摘要 */

    /** Binds the exact canonical endpoint across saved-profile aliases without inferring DNS equivalence. / 跨保存资料别名绑定精确规范端点，不推断DNS等价。
     * @param server actually authorized selected profile / 实际授权所选资料
     * @return non-secret canonical endpoint digest / 非秘密规范端点摘要 */

    /** Saves the actual admitted policy before selecting a source or sending any effect. / 选取来源或发送副作用前保存实际准入策略。
     * @param request original selected policy / 原所选策略
     * @param task authentic original task / 真实原任务
     * @return classified save failure or no failure / 分类保存失败或无失败 */
    private Optional<OperationResult<DeploymentAgentDeliveryPort.Published>> saveSelection(DeploymentAgentInputs.Request request,
                                                                                           TaskContext task) {
        try {
            new gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore(context.db()).save(task,
                new gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore.Selection("AGENT",
                    request.approvalMode(), request.rememberAuthorization()));
            return Optional.empty();
        } catch (ApiException unavailable) {
            return Optional.of(failed(task, false, unavailable));
        } catch (java.sql.SQLException unavailable) {
            return Optional.of(new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_PREFERENCE_SAVE_FAILED",
                    "Deployment preferences could not be saved", Map.of("task", task.id().toString()),
                    Optional.of(task.id())))));
        }
    }

    /** Validates bundled instructions before any remote workflow action. / 任何远端流程动作前校验随包说明。 */
    private static void requireSkills() {
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelExecutionContract.class, "host-command.md");
        var resources = gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol.class;
        DeploymentSkillResources.require(resources, "autonomous-deployment.md");
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentPublishRunner.class,
            "autonomous-publication.md", "agent-publish-runner-tools.properties");
        DeploymentSkillResources.requireDescriptions(
            gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentPublishRunner.class,
            "agent-publish-runner-tools.properties", "command", "stage", "publish", "unable", "verify", "finish", "finish_failure", "continue_deployment", "contract", "input");
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReview.class, "action-review.md");
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision.class, "review-response.md");
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval.class, "command-review.md");
        DeploymentSkillResources.require(
            gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.class,
            "finite-command-review.md");
    }

    /** Requires actual target rememberAuthorization and every selected model purpose before source acquisition. / 来源取得前要求实际目标授权及全部所选模型用途。
     * @param server originally selected target / 原所选目标
     * @param request immutable policy / 不可变策略
     * @param task authentic original callback / 真实原回调
     * @return first usable frozen configuration / 首次可用冻结配置
     * @throws ApiException if rememberAuthorization, routing or a selected purpose is unavailable / 授权、路由或所选用途不可用时 */
    private String admittedConfiguration(ServerId server, DeploymentAgentInputs.Request request, TaskContext task) throws ApiException {
        context.servers().get(server, task);
        String configuration = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
        if (!configuration.matches("[0-9a-f]{64}")
            || !configuration.equals(context.ai().configurationRevision(AiPurpose.APPROVAL, task)))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_CONFIGURATION_CHANGED",
                "Original agent model purposes do not share an authorized configuration",
                Map.of("task", task.id().toString()), Optional.of(task.id())));
        return configuration;
    }
}
