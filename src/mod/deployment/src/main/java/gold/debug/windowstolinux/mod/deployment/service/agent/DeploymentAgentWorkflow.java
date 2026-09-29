package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.appapi.ai.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentExecutionRepository;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublicationCommit;
import gold.debug.windowstolinux.mod.deployment.source.*;
import gold.debug.windowstolinux.mod.deployment.source.git.DeploymentGitRequest;

/** Owns one independent agent task and reconciles only its original durable effects. / 持有一项独立自主任务并仅核对其原持久效果。 */
public final class DeploymentAgentWorkflow implements TaskRecovery {
    /** Dedicated original task type, separate from standard publication. / 与标准发布分离的专用原任务类型。 */
    public static final String TASK_TYPE = "deployment-agent";

    /** Original platform. / 原平台。 */
    private final ModContext context;

    /** Dedicated original task identity and action evidence ledger. / 专有原任务身份及动作依据账本。 */
    private final DeploymentAgentJournal journal;

    /** User-reviewed bounded source and action policy. / 用户审核过的有界来源及动作策略。
     * @param requirements nonsecret task requirements / 不含秘密的任务要求
     * @param approvalMode manual, automatic or full control / 人工、自动或全控
     * @param sourceBytes source capture limit / 来源捕获上限
     * @param sourceEntries discovery limit / 发现上限
     * @param archiveBytes archive limit / 归档上限 */
    public record Request(Map<String, String> requirements, String approvalMode, long sourceBytes, int sourceEntries,
            long archiveBytes) {
        /** Freezes the exact user choice. / 冻结精确用户选择。
         * @param requirements bounded constraints / 有界约束
         * @param approvalMode chosen policy / 所选策略
         * @param sourceBytes source budget / 来源额度
         * @param sourceEntries discovery budget / 发现额度
         * @param archiveBytes archive budget / 归档额度 */
        public Request {
            requirements = Map.copyOf(requirements);
            if (requirements.size() > 64 || requirements.toString().length() > 8000
                    || !Set.of("MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL").contains(approvalMode) || sourceBytes < 1
                    || sourceBytes > 2L * 1024 * 1024 * 1024 || sourceEntries < 1 || sourceEntries > 100_000
                    || archiveBytes < 1 || archiveBytes > 2L * 1024 * 1024 * 1024)
                throw new IllegalArgumentException("Invalid agent task request");
        }
    }

    /** Binds an active Mod and its dedicated original-task journal. / 绑定活动 Mod 与专有原任务账本。
     * @param context original activation / 原启用 */
    public DeploymentAgentWorkflow(ModContext context) {
        this.context = Objects.requireNonNull(context);
        this.journal = new DeploymentAgentJournal(context.db());
    }

    /** Starts one local user-selected source in its own original task. / 在专有原任务中启动一份用户所选本地来源。
     * @param server selected server / 所选服务器
     * @param request bounded user policy / 有界用户策略
     * @return observable original task / 可观察原任务
     * @throws ApiException on failed task admission / 任务准入失败时 */
    public TaskHandle<DeploymentAgentDeliveryPort.Published> submitLocal(ServerRef server, Request request)
            throws ApiException {
        Objects.requireNonNull(request);
        return context.tasks().submit(new TaskRequest(TASK_TYPE, "Autonomous deployment", List.of(server.id()), true),
                task -> {
                    final DeploymentSourceMaterial material;
                    try {
                        var selection = context.source().selectDirectory("Autonomous deployment source", task);
                        material = new DeploymentSourcePreparer(context.source()).prepare(selection,
                                request.sourceBytes(), request.sourceEntries(), task);
                    } catch (ApiException failure) {
                        return finishKnown(failed(task, false, failure), task);
                    }
                    DeploymentSourceArchive archive = null;
                    OperationResult<DeploymentAgentDeliveryPort.Published> result = null;
                    try {
                        archive = new DeploymentSourceArchiver(context.source()).prepare(material,
                                request.archiveBytes(), task);
                        result = execute(server, request, material, archive, Optional.empty(), task);
                    } catch (ApiException failure) {
                        result = failed(task, false, failure);
                    } finally {
                        if (result != null && result.outcome() != Outcome.UNKNOWN) {
                            try {
                                if (archive != null)
                                    archive.close();
                            } finally {
                                material.close();
                            }
                        }
                    }
                    return finishKnown(result, task);
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
    public TaskHandle<DeploymentAgentDeliveryPort.Published> submitGit(ServerRef server, Request request,
            DeploymentGitRequest git, Path executable, String projectName) throws ApiException {
        Objects.requireNonNull(git);
        return context.tasks()
                .submit(new TaskRequest(TASK_TYPE, "Autonomous Git deployment", List.of(server.id()), true), task -> {
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
                        if (review.outcome() != Outcome.SUCCEEDED) {
                            result = new OperationResult<>(review.outcome(), Optional.empty(), review.problem());
                            return result;
                        }
                        DeploymentProjectRecord project = pinned.save();
                        publicationStarted = true;
                        result = execute(server, request, selected.source(), selected.archive(), Optional.of(project),
                                task);
                    } finally {
                        if (!publicationStarted || result != null && result.outcome() != Outcome.UNKNOWN) {
                            try {
                                pinned.close();
                            } finally {
                                pinned.material().source().close();
                            }
                        }
                    }
                    return finishKnown(result, task);
                });
    }

    /** Runs the complete agent against one frozen source and original authenticated target. / 在一份冻结来源及原认证目标上运行完整自主任务。
     * @param server selected target / 所选目标
     * @param request user policy / 用户策略
     * @param material frozen source / 冻结来源
     * @param archive frozen archive / 冻结归档
     * @param project reviewed pinned source project, absent for local source / 经审阅固定来源项目，本地来源时为空
     * @param task original task / 原任务
     * @return observed publication / 已观察发布 */
    private OperationResult<DeploymentAgentDeliveryPort.Published> execute(ServerRef server, Request request,
            DeploymentSourceMaterial material, DeploymentSourceArchive archive,
            Optional<DeploymentProjectRecord> project, TaskContext task) {
        boolean created = false;
        try {
            var source = new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256());
            String configuration = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
            if (!request.approvalMode().equals("MANUAL_REVIEW")
                    && !configuration.equals(context.ai().configurationRevision(AiPurpose.APPROVAL, task)))
                throw new SecurityException("Agent approval route differs from the frozen configuration");
            try (var remote = context.linux().connect(server, task)) {
                String target = targetDigest(server, remote);
                journal.create(task, new DeploymentAgentJournal.Identity(server.id(), target, configuration,
                        request.approvalMode()));
                created = true;
                journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
                var current = (java.util.function.Supplier<String>) () -> {
                    try {
                        return context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                    } catch (ApiException failure) {
                        throw new IllegalStateException("Agent configuration cannot be verified", failure);
                    }
                };
                try (var tools = new DeploymentAgentRemoteTools(context, remote, context.interaction(), server, task,
                        archive, configuration, current, journal, project)) {
                    var session = new DeploymentAgentSession(context, task, server, request.requirements(), source,
                            tools, journal, request.approvalMode(), configuration, current);
                    var result = session.run();
                    if (result.outcome() == Outcome.FAILED)
                        journal.state(task, "FAILED");
                    return result;
                }
            }
        } catch (Exception failure) {
            return failed(task, created, failure);
        }
    }

    /** Closes a known formal result only after the original remote and source scopes end. / 仅在原远端和来源作用域结束后结清已知正式结果。
     * @param result original agent result / 原自主结果
     * @param task original task / 原任务
     * @return same known result or retained uncertainty / 相同已知结果或保留未知 */
    private OperationResult<DeploymentAgentDeliveryPort.Published> finishKnown(
            OperationResult<DeploymentAgentDeliveryPort.Published> result, TaskContext task) {
        if (result.outcome() == Outcome.UNKNOWN)
            return result;
        try {
            context.source().closeTaskResources(task);
            var executions = new DeploymentExecutionRepository(context.db());
            var formal = executions.findForTask(task);
            if (formal.isEmpty())
                return result;
            var finished = new DeploymentOriginalPublicationCommit(context).finishKnown(task, formal.orElseThrow());
            if (finished.outcome() == Outcome.UNKNOWN)
                return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), finished.problem());
            return result;
        } catch (Exception uncertain) {
            return unknownPublished();
        }
    }

    /** Retains a failed original cleanup without inventing a known result. / 原收尾失败时保留未知而不虚构已知结果。
     * @return classified unknown publication / 分类未知发布 */
    private static OperationResult<DeploymentAgentDeliveryPort.Published> unknownPublished() {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
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
        Outcome outcome = failure instanceof IllegalArgumentException || failure instanceof SecurityException
                || failure instanceof IllegalStateException || failure instanceof java.io.IOException
                        ? Outcome.FAILED
                        : Outcome.UNKNOWN;
        Optional<Problem> original = Optional.empty();
        if (failure instanceof DeploymentAiJson.CapabilityOutcomeException classified) {
            outcome = classified.outcome();
            original = classified.problem();
        } else if (failure instanceof CancellationException) {
            outcome = Outcome.CANCELLED;
        } else if (failure instanceof ApiException api) {
            original = Optional.of(api.problem());
            outcome = api.problem().code() == ProblemCode.CANCELLED
                    ? Outcome.CANCELLED
                    : api.problem().code() == ProblemCode.UNKNOWN ? Outcome.UNKNOWN : Outcome.FAILED;
        }
        try {
            if (created) {
                var events = journal.events(task);
                if (!DeploymentAgentJournal.effectsSettled(events)
                        || events.stream().anyMatch(event -> event.type().equals("CANDIDATE"))
                                && events.stream().noneMatch(event -> event.type().equals("DELIVERY_RESULT")))
                    outcome = Outcome.UNKNOWN;
                journal.state(task, switch (outcome) {
                    case SUCCEEDED -> "SUCCEEDED";
                    case FAILED -> "FAILED";
                    case CANCELLED -> "CANCELLED";
                    case UNKNOWN -> "UNKNOWN";
                });
            }
        } catch (Exception uncertainLedger) {
            outcome = Outcome.UNKNOWN;
        }
        Problem problem = original.orElse(null);
        if (outcome == Outcome.UNKNOWN && problem != null && problem.code() != ProblemCode.UNKNOWN)
            problem = null;
        if (problem == null)
            problem = new Problem(
                    outcome == Outcome.CANCELLED
                            ? ProblemCode.CANCELLED
                            : outcome == Outcome.FAILED ? ProblemCode.IO_FAILURE : ProblemCode.UNKNOWN,
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
        var saved = journal.find(originalTask);
        if (saved.isEmpty())
            return unknown();
        return new DeploymentAgentRecovery(context, journal).reconcile(originalTask, saved.orElseThrow());
    }

    /** Builds one bounded known or unknown recovery result. / 构造一项有界已知或未知恢复结果。
     * @param outcome classified result / 分类结果
     * @param code public problem code / 公开问题代码
     * @return no-payload result / 无载荷结果 */
    private static OperationResult<Void> result(Outcome outcome, ProblemCode code) {
        return new OperationResult<>(outcome, Optional.empty(),
                Optional.of(new Problem(code, "DEPLOYMENT_AGENT_RECONCILED",
                        "Original agent task result was reconciled", Map.of(), Optional.empty())));
    }

    /** Retains uncertainty without replay. / 不重放地保留不确定性。
     * @return unknown result / 未知结果 */
    private static OperationResult<Void> unknown() {
        return result(Outcome.UNKNOWN, ProblemCode.UNKNOWN);
    }

    /** Hashes exact nonsecret target identity. / 散列精确不含秘密目标身份。
     * @param value profile and authenticated host fingerprint / 资料与认证主机指纹
     * @return lowercase SHA-256 / 小写 SHA-256 */
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Computes the exact original authenticated target binding. / 计算精确原认证目标绑定。
     * @param server original profile / 原资料
     * @param session authenticated SSH / 已认证 SSH
     * @return nonsecret target digest / 不含秘密目标摘要 */
    static String targetDigest(ServerRef server, RemoteSession session) {
        return hash(server.id() + ":" + session.facts().fingerprint());
    }
}
