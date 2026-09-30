package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentOriginalPublicationStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentPublication;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;

/** Reconciles only an agent's retained original proposal and remote effects. / 仅核对自主任务留存的原提案与远端影响。 */
public final class DeploymentAgentRecovery {
    /** Actual original owner capabilities. / 实际原所有者能力。 */
    private final ModContext context;

    /** Dedicated original task identity and action evidence ledger. / 专有原任务身份及动作依据账本。 */
    private final DeploymentAgentJournal journal;

    /** Original formal execution evidence repository. / 原正式执行依据仓库。 */
    private final DeploymentExecutionRepository executions;

    /** Binds original persistence and platform capabilities. / 绑定原持久层与平台能力。
     * @param context active Mod / 活动 Mod
     * @param journal agent-only original ledger / 自主专有原账本 */
    public DeploymentAgentRecovery(ModContext context, DeploymentAgentJournal journal) {
        this.context = Objects.requireNonNull(context);
        this.journal = Objects.requireNonNull(journal);
        this.executions = new DeploymentExecutionRepository(context.db());
    }

    /** Reads original remote status and completes only a proven result. / 读取原远端状态并仅完成已证实结果。
     * @param task original recovery task / 原恢复任务
     * @param saved frozen agent identity / 冻结自主身份
     * @return known result or retained uncertainty / 已知结果或保留未知
     * @throws Exception if original evidence cannot be accessed / 原依据无法访问时 */
    public OperationResult<Void> reconcile(TaskContext task, DeploymentAgentJournal.Snapshot saved) throws Exception {
        var events = journal.events(task);
        var retained = executions.findForTask(task);
        if (retained.isEmpty())
            return beforePublication(task, saved, events);
        var record = retained.orElseThrow();
        if (record.identity().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT
                || !record.identity().server().id().equals(saved.identity().server())
                || !record.identity().taskId().equals(task.id()))
            return unknown("DEPLOYMENT_AGENT_IDENTITY_CHANGED");
        var template = executions.publicationTemplate(record);
        if (template.isEmpty())
            return unknown("DEPLOYMENT_AGENT_PROPOSAL_MISSING");
        var original = template.orElseThrow();
        var proposal = new DeploymentPublication(original.graph(), original.units());
        var candidates = candidates(proposal, events);
        if (candidates.isEmpty())
            return unknown("DEPLOYMENT_AGENT_CANDIDATE_EVIDENCE_MISSING");
        var server = record.identity().server();
        try (RemoteSession session = context.linux().connect(server, task)) {
            if (!saved.identity().targetDigest().equals(DeploymentAgentWorkflow.targetDigest(server, session)))
                return unknown("DEPLOYMENT_AGENT_TARGET_CHANGED");
            if (new gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper(session,
                    context.interaction(), server, task, saved.identity().configurationDigest()).probe()
                    .outcome() != Outcome.SUCCEEDED)
                return unknown("DEPLOYMENT_AGENT_HELPER_CHANGED");
            var publisher = new DeploymentGraphPublisher(context, session, server, task,
                    (type, action, detail) -> journal.event(task, type, action, detail));
            var observed = publisher.observeOriginal(proposal, candidates.orElseThrow());
            if (observed.outcome() != Outcome.SUCCEEDED)
                return unknown("DEPLOYMENT_AGENT_REMOTE_STATUS_UNCONFIRMED");
            Outcome proven = classify(record, proposal, candidates.orElseThrow(), events, publisher,
                    observed.value().orElseThrow());
            if (proven == Outcome.UNKNOWN)
                return unknown("DEPLOYMENT_AGENT_REMOTE_RESULT_UNCONFIRMED");
            if (record.outcome().isPresent() && record.outcome().orElseThrow() != Outcome.UNKNOWN
                    && record.outcome().orElseThrow() != proven)
                return unknown("DEPLOYMENT_AGENT_FORMAL_REMOTE_CONFLICT");
            if (record.outcome().isEmpty() || record.outcome().orElseThrow() == Outcome.UNKNOWN) {
                if (proven == Outcome.SUCCEEDED)
                    executions.commitApplication(task, record.identity().executionId(), proposal.graph(),
                            proposal.units(), Instant.now());
                else
                    executions.recordUncommittedOutcome(task, record.identity().executionId(), Outcome.FAILED,
                            problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_AGENT_ORIGINAL_ROLLBACK_CONFIRMED"),
                            Instant.now());
            }
            if (publisher.cleanup(proposal, candidates.orElseThrow(), proven == Outcome.SUCCEEDED)
                    .outcome() != Outcome.SUCCEEDED)
                return unknown("DEPLOYMENT_AGENT_REMOTE_CLEANUP_UNCONFIRMED");
        }
        var latest = executions.findForTask(task).orElseThrow();
        var finished = new DeploymentOriginalPublicationCommit(context).finishKnown(task, latest);
        if (finished.outcome() != Outcome.UNKNOWN)
            journal.state(task, finished.outcome().name());
        return finished;
    }

    /** Keeps a pre-publication candidate for exact later inspection. / 保留发布前候选以供后续精确检查。
     * @param task original task / 原任务
     * @param saved original row / 原记录
     * @param events chronological evidence / 按序依据
     * @return known no-effect result or uncertainty / 已知无影响结果或未知
     * @throws Exception if exact original evidence cannot be read or released / 无法读取或释放精确原依据时 */
    private OperationResult<Void> beforePublication(TaskContext task, DeploymentAgentJournal.Snapshot saved,
            List<DeploymentAgentJournal.Event> events) throws Exception {
        if (saved.state().equals("SUCCEEDED"))
            return unknown("DEPLOYMENT_AGENT_FORMAL_SUCCESS_MISSING");
        var workspaces = originalWorkspaces(events);
        if (workspaces.isEmpty())
            return unknown("DEPLOYMENT_AGENT_PREFORMAL_IDENTITY_MISSING");
        if (!workspaces.orElseThrow().isEmpty()) {
            var server = new ServerRef(saved.identity().server());
            try (RemoteSession session = context.linux().connect(server, task)) {
                if (!saved.identity().targetDigest().equals(DeploymentAgentWorkflow.targetDigest(server, session)))
                    return unknown("DEPLOYMENT_AGENT_TARGET_CHANGED");
                if (new gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper(session,
                        context.interaction(), server, task, saved.identity().configurationDigest()).probe()
                        .outcome() != Outcome.SUCCEEDED)
                    return unknown("DEPLOYMENT_AGENT_HELPER_CHANGED");
                var channel = new DeploymentAgentProjectChannel(session, context.interaction(), server, task,
                        saved.identity().configurationDigest(), () -> {
                            try {
                                return context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                            } catch (ApiException unavailable) {
                                throw new IllegalStateException("Original AI configuration unavailable", unavailable);
                            }
                        });
                for (var workspace : workspaces.orElseThrow()) {
                    var status = channel.candidateStatus(workspace);
                    if (status.buildActive())
                        return unknown("DEPLOYMENT_AGENT_ORIGINAL_BUILD_ACTIVE");
                    if (status.present()) {
                        journal.event(task, "CANDIDATE_CLEANUP_EXECUTING", workspace.candidateId(), "original");
                        channel.cleanupOriginalCandidate(workspace);
                        journal.event(task, "CANDIDATE_CLEANUP_RESULT", workspace.candidateId(), "absent");
                    }
                }
            } catch (Exception unavailable) {
                return unknown("DEPLOYMENT_AGENT_CANDIDATE_CLEANUP_UNCONFIRMED");
            }
        }
        context.source().closeTaskResources(task);
        if (!context.tasks().resourcesReleased(task))
            return unknown("DEPLOYMENT_AGENT_SOURCE_RETAINED");
        Outcome outcome = saved.state().equals("CANCELLED") ? Outcome.CANCELLED : Outcome.FAILED;
        journal.state(task, outcome.name());
        return new OperationResult<>(outcome, Optional.empty(),
                Optional.of(problem(outcome == Outcome.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
                        "DEPLOYMENT_AGENT_PREFORMAL_RECONCILED")));
    }

    /** Reconstructs only task-bound candidate identities from original archived bytes and plan evidence. / 仅从原归档字节与计划依据重建任务绑定候选身份。
     * @param events original ordered evidence / 原有序依据
     * @return all possibly prepared original candidates or missing identity / 所有可能准备的原候选或身份缺失 */
    static Optional<List<DeploymentRemoteWorkspace>> originalWorkspaces(
            List<DeploymentAgentJournal.Event> events) {
        String archive = null;
        String application = null;
        var components = new LinkedHashSet<String>();
        var observed = new HashMap<String, String>();
        for (var event : events) {
            switch (event.type()) {
                case "SOURCE" -> archive = event.action();
                case "PLAN_APPLICATION" -> application = event.detail();
                case "PREPARE_EXECUTING" -> components.add(event.detail());
                case "CANDIDATE" -> {
                    components.add(event.action());
                    observed.put(event.action(), event.detail());
                }
                default -> {
                }
            }
        }
        if (components.isEmpty())
            return Optional.of(List.of());
        if (archive == null || application == null)
            return Optional.empty();
        var workspaces = new ArrayList<DeploymentRemoteWorkspace>();
        try {
            for (String component : components) {
                String id = DeploymentManagedUnitResolver.managedId(application, component);
                var workspace = new DeploymentRemoteWorkspace(id, archive);
                if (observed.containsKey(component) && !observed.get(component).equals(workspace.candidateId()))
                    return Optional.empty();
                workspaces.add(workspace);
            }
            return Optional.of(List.copyOf(workspaces));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** Rebuilds only complete original candidate facts, never a new candidate. / 仅重建完整原候选事实，绝不新建候选。
     * @param proposal saved formal graph / 已存正式图
     * @param events original events / 原事件
     * @return complete candidate set or absence / 完整候选集合或缺失 */
    private static Optional<Map<String, DeploymentGraphPublisher.Candidate>> candidates(DeploymentPublication proposal,
            List<DeploymentAgentJournal.Event> events) {
        var ids = new HashMap<String, String>();
        var sources = new HashMap<String, String>();
        var artifacts = new HashMap<String, String>();
        for (var event : events) {
            switch (event.type()) {
                case "CANDIDATE" -> ids.put(event.action(), event.detail());
                case "CURRENT_SOURCE" -> sources.put(event.action(), event.detail());
                case "SEALED_ARTIFACT" -> artifacts.put(event.action(), event.detail());
                case "ARTIFACT_INVALIDATED" -> artifacts.remove(event.action());
                default -> {
                }
            }
        }
        var result = new LinkedHashMap<String, DeploymentGraphPublisher.Candidate>();
        try {
            for (var component : proposal.graph().components()) {
                String id = component.componentId();
                result.put(id, new DeploymentGraphPublisher.Candidate(ids.get(id), artifacts.get(id), sources.get(id),
                        "0".repeat(64), false, Map.of()));
            }
            return Optional.of(Map.copyOf(result));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** Classifies exact current releases, known rollback, and health. / 按精确当前发布、已知回滚及健康状况分类。
     * @param record original formal execution / 原正式执行
     * @param proposal saved graph / 已存图
     * @param candidates original candidates / 原候选
     * @param events retained effect evidence / 留存影响依据
     * @param publisher original read-only publisher / 原只读发布器
     * @param statuses actual remote statuses / 真实远端状态
     * @return proven success, failure or uncertainty / 已证实成功、失败或未知 */
    private static Outcome classify(DeploymentExecutionRecord record, DeploymentPublication proposal,
            Map<String, DeploymentGraphPublisher.Candidate> candidates, List<DeploymentAgentJournal.Event> events,
            DeploymentGraphPublisher publisher, Map<String, DeploymentOriginalPublicationStatus> statuses) {
        if (publisher.verifyPublished(proposal, candidates).outcome() == Outcome.SUCCEEDED)
            return Outcome.SUCCEEDED;
        if (record.businessCommitted())
            return Outcome.UNKNOWN;
        boolean rolledBack = events.stream()
                .anyMatch(event -> event.type().equals("GRAPH_ROLLBACK_RESULT") && event.detail().equals("RESTORED"));
        if (!rolledBack && record.outcome().filter(value -> value == Outcome.FAILED).isEmpty())
            return Outcome.UNKNOWN;
        var running = new HashMap<String, Boolean>();
        for (var component : proposal.graph().components()) {
            String id = component.componentId();
            var status = statuses.get(id);
            var old = previous(events, id);
            if (old.isEmpty() || status == null || !status.current().equals(old.orElseThrow()) || status.buildActive()
                    || status.maintenance())
                return Outcome.UNKNOWN;
            if (old.orElseThrow().isPresent()) {
                var activity = previousRunning(events, id);
                if (activity.isEmpty())
                    return Outcome.UNKNOWN;
                running.put(id, activity.orElseThrow());
            }
        }
        return publisher.verifyRestoredState(proposal, running).outcome() == Outcome.SUCCEEDED
                ? Outcome.FAILED
                : Outcome.UNKNOWN;
    }

    /** Reads exact old digest from a completed original snapshot. / 从已完成原快照读取精确旧摘要。
     * @param events original events / 原事件
     * @param component exact component / 精确组件
     * @return observed optional old release or missing evidence / 观察到的可空旧发布或依据缺失 */
    private static Optional<Optional<String>> previous(List<DeploymentAgentJournal.Event> events, String component) {
        for (int index = events.size() - 1; index >= 0; index--) {
            var event = events.get(index);
            if (!event.type().equals("SNAPSHOT_RESULT") || !event.action().equals(component))
                continue;
            var parts = event.detail().split(":", -1);
            if (parts.length != 4)
                return Optional.empty();
            if (parts[0].equals("none") && parts[2].equals("none"))
                return Optional.of(Optional.empty());
            if (parts[0].matches("[0-9a-f-]{36}") && parts[2].matches("[0-9a-f]{64}"))
                return Optional.of(Optional.of(parts[2]));
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Reads the old runtime activity from the same exact snapshot event. / 从同一精确快照事件读取旧运行状态。
     * @param events original chronological events / 原按序事件
     * @param component exact component / 精确组件
     * @return old activity or missing evidence / 旧活动状态或依据缺失 */
    private static Optional<Boolean> previousRunning(List<DeploymentAgentJournal.Event> events, String component) {
        for (int index = events.size() - 1; index >= 0; index--) {
            var event = events.get(index);
            if (event.type().equals("SNAPSHOT_RESULT") && event.action().equals(component)) {
                var parts = event.detail().split(":", -1);
                if (parts.length != 4)
                    return Optional.empty();
                if (parts[1].equals("1"))
                    return Optional.of(true);
                if (parts[1].equals("0"))
                    return Optional.of(false);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Builds a bounded public recovery problem. / 构造有界公开恢复问题。
     * @param code category / 类别
     * @param event stable event / 稳定事件
     * @return original-task diagnostic / 原任务诊断 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Original agent task requires review", Map.of(), Optional.empty());
    }

    /** Preserves unknown without any replay. / 不重放任何动作地保留未知。
     * @param event stable event / 稳定事件
     * @return unknown result / 未知结果 */
    private static OperationResult<Void> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }
}
