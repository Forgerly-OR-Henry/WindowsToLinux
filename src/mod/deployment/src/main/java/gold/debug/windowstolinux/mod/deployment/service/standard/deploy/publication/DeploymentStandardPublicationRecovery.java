package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRemoteRecovery;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentWorkload;

/** Reconciles one original remote publication using retained facts and read-only helper observations. / 仅使用留存事实及只读helper观测核对一项原远端发布。 */
public final class DeploymentStandardPublicationRecovery implements DeploymentRemoteRecovery {
    /** Original Mod capabilities; no path, source selection or replacement task is reconstructed. / 原Mod能力；不重建路径、来源选择或替代任务。 */
    private final ModContext context;

    /** Binds the same activation used for the reference recovery callback. / 绑定引用恢复回调所用的同一启用。
     * @param context actual activation / 实际启用 */
    public DeploymentStandardPublicationRecovery(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Observes original owner, release, snapshot and cleanup without replaying a build or publish. / 不重放构建或发布，仅观察原归属、发布、快照及收尾。
     * @param task authentic original recovery task / 真实原恢复任务
     * @param record retained execution / 留存执行
     * @return absent for reference-only work, otherwise verified or uncertain remote result / 纯引用任务为空，否则返回已核实或未决远端结果 */
    @Override
    public Optional<OperationResult<DeploymentPublicationProposal>> reconcile(TaskContext task,
            DeploymentExecutionRecord record) {
        try {
            var graph = DeploymentGraphPublicationJournal.reopen(context, task);
            if (graph.isPresent())
                return Optional.of(new DeploymentMultiComponentPublicationRecovery(context).reconcile(task, record,
                        graph.orElseThrow()));
        } catch (ApiException | IllegalArgumentException unavailable) {
            return Optional.of(unknown("DEPLOYMENT_GRAPH_JOURNAL_UNAVAILABLE"));
        }
        final DeploymentPublicationCleanupJournal journal;
        try {
            var original = DeploymentPublicationCleanupJournal.reopen(context, task);
            if (original.isEmpty())
                return Optional.empty();
            journal = original.orElseThrow();
        } catch (ApiException | IllegalArgumentException unavailable) {
            return Optional.of(unknown("DEPLOYMENT_REMOTE_JOURNAL_UNAVAILABLE"));
        }
        OperationResult<DeploymentPublicationProposal> proven = null;
        try {
            if (!DeploymentPublicationAttemptRecovery.child(context, task, record, journal)
                    || !DeploymentAssistedReviewRecovery.child(context, task, record, journal))
                return Optional.of(unknown("DEPLOYMENT_ASSISTED_REVIEW_RECOVERY_UNCONFIRMED"));
            var facts = DeploymentPublicationRecoveryFacts.read(journal.facts(), record);
            var proposal = new DeploymentExecutionRepository(context.db()).publicationTemplate(record);
            if (journal.released()) {
                if (record.outcome().filter(outcome -> outcome != Outcome.UNKNOWN).isEmpty()
                        && facts.verifiedOutcome().isEmpty())
                    return Optional.of(unknown("DEPLOYMENT_RELEASED_WITH_UNKNOWN_BUSINESS"));
                if (!context.linux().pendingTransfers(task).isEmpty())
                    return Optional.of(unknown("DEPLOYMENT_ORIGINAL_TRANSFER_PENDING"));
                context.source().closeTaskResources(task);
                return Optional.of(record.outcome().filter(outcome -> outcome != Outcome.UNKNOWN).isPresent()
                        ? known(record, proposal, facts)
                        : verified(facts, proposal));
            }
            if (journal.facts().path("phase").asText().equals("REMOTE_CLEANUP_VERIFIED")
                    || journal.facts().path("phase").asText().equals("SESSION_CLOSED")
                    || journal.facts().path("phase").asText().equals("CLEANUP_VERIFIED")) {
                if (record.outcome().filter(outcome -> outcome != Outcome.UNKNOWN).isEmpty()
                        && facts.verifiedOutcome().isEmpty() || !context.linux().pendingTransfers(task).isEmpty())
                    return Optional.of(unknown("DEPLOYMENT_CLEANUP_WITHOUT_KNOWN_BUSINESS"));
                context.source().closeTaskResources(task);
                if (!journal.sessionClosed() || !journal.release())
                    return Optional.of(unknown("DEPLOYMENT_RECOVERY_CHECKPOINT_UNCONFIRMED"));
                return Optional.of(record.outcome().filter(outcome -> outcome != Outcome.UNKNOWN).isPresent()
                        ? known(record, proposal, facts)
                        : verified(facts, proposal));
            }
            if (!context.linux().pendingTransfers(task).isEmpty())
                return Optional.of(unknown("DEPLOYMENT_ORIGINAL_TRANSFER_PENDING"));
            OperationResult<DeploymentPublicationProposal> result;
            try (RemoteSession session = context.linux().connect(facts.unit().server(), task)) {
                var channel = new DeploymentPublicationChannel(session, context.interaction(), facts.unit().server(),
                        task, facts.plan());
                var observed = facts.operation().isEmpty() ? channel.originalStatus(facts.unit(), facts.candidate(), facts.release(), facts.backend(), facts.engine()) : channel.originalStatus(facts.unit(), facts.candidate(), facts.release(), facts.backend(), facts.engine(), facts.operation().orElseThrow());
                if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty())
                    return Optional.of(unknown("DEPLOYMENT_ORIGINAL_STATUS_UNCONFIRMED"));
                var status = observed.value().orElseThrow();
                result = business(record, proposal, facts, status, channel, journal);
                if (result.outcome() == Outcome.UNKNOWN)
                    return Optional.of(result);
                if (facts.verifiedOutcome().isEmpty()) {
                    boolean persisted = journal.verifiedBusiness(result.outcome(), result.problem());
                    if (!persisted)
                        return Optional.of(unknown("DEPLOYMENT_VERIFIED_BUSINESS_UNCONFIRMED"));
                }
                proven = result;
                if (!finishRemote(journal, facts, status, channel, result.outcome() == Outcome.SUCCEEDED))
                    return Optional.of(result);
            }
            if (!journal.sessionClosed())
                return Optional.of(result);
            if (!context.linux().pendingTransfers(task).isEmpty())
                return Optional.of(result);
            try {
                context.source().closeTaskResources(task);
            } catch (ApiException unavailable) {
                return Optional.of(result);
            }
            if (!journal.release())
                return Optional.of(result);
            return Optional.of(result);
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            return Optional.of(proven == null ? unknown("DEPLOYMENT_REMOTE_RECOVERY_UNCONFIRMED") : proven);
        }
    }

    /** Preserves a previously formal known result without inventing a second commit. / 保留原正式已知结果，不补造第二次提交。
     * @param record retained execution / 留存执行
     * @param proposal original proposal / 原提案
     * @param facts original remote facts / 原远端事实
     * @return original known business result / 原已知业务结果
     * @throws SQLException if original result cannot be read / 原结果不可读取时 */
    private OperationResult<DeploymentPublicationProposal> known(DeploymentExecutionRecord record,
            Optional<DeploymentPublicationProposal> proposal, DeploymentPublicationRecoveryFacts facts)
            throws SQLException {
        if (record.outcome().orElse(null) == Outcome.SUCCEEDED)
            return record.businessCommitted() && proposal.isPresent() && facts.release().isPresent()
                    ? new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty())
                    : unknown("DEPLOYMENT_COMMITTED_PROPOSAL_MISSING");
        if (record.outcome().orElse(null) == Outcome.FAILED)
            return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                    new DeploymentExecutionRepository(context.db()).problem(record));
        return unknown("DEPLOYMENT_REMOTE_RESULT_NOT_KNOWN");
    }

    /** Restores a durably verified result without repeating the remote proof or business write. / 从已持久核实结果恢复，不重复远端证明或业务写入。
     * @param facts retained independent result / 留存独立结果
     * @param proposal original full proposed commit / 原完整拟提交内容
     * @return verified original result / 已核实原结果 */
    private static OperationResult<DeploymentPublicationProposal> verified(DeploymentPublicationRecoveryFacts facts,
            Optional<DeploymentPublicationProposal> proposal) {
        if (facts.verifiedOutcome().orElse(null) == Outcome.SUCCEEDED)
            return proposal.isPresent() && facts.release().isPresent()
                    && proposal.orElseThrow().units().getFirst().release().sha256()
                            .equals(facts.release().orElseThrow())
                                    ? new OperationResult<>(Outcome.SUCCEEDED, proposal, Optional.empty())
                                    : unknown("DEPLOYMENT_VERIFIED_PROPOSAL_MISSING");
        if (facts.verifiedOutcome().orElse(null) == Outcome.FAILED)
            return facts.verifiedProblem().isPresent()
                    ? new OperationResult<>(Outcome.FAILED, Optional.empty(), facts.verifiedProblem())
                    : unknown("DEPLOYMENT_VERIFIED_FAILURE_MISSING");
        return unknown("DEPLOYMENT_VERIFIED_RESULT_MISSING");
    }

    /** Proves only a state that follows the original durable phase and exact read-only owner facts. / 仅证明与原持久阶段和精确只读归属事实相符的状态。
     * @param record original execution / 原执行
     * @param proposal pre-publish business template / 发布前业务模板
     * @param facts original remote intent / 原远端意图
     * @param status current complete remote observation / 当前完整远端观测
     * @param channel original reviewed channel / 原已审阅通道
     * @param journal original durable checkpoint / 原持久检查点
     * @return known business result or uncertainty / 已知业务结果或未知
     * @throws ApiException if a read-only check is unavailable / 只读核对不可用时
     * @throws SQLException if original business evidence cannot be read / 原业务依据不可读取时 */
    private OperationResult<DeploymentPublicationProposal> business(DeploymentExecutionRecord record,
            Optional<DeploymentPublicationProposal> proposal, DeploymentPublicationRecoveryFacts facts,
            DeploymentOriginalPublicationStatus status, DeploymentPublicationChannel channel,
            DeploymentPublicationCleanupJournal journal) throws ApiException, SQLException {
        if (facts.verifiedOutcome().isPresent()) {
            var result = verified(facts, proposal);
            if (result.outcome() == Outcome.SUCCEEDED && !status.current().equals(facts.release())
                    || result.outcome() == Outcome.FAILED && facts.release().isPresent()
                            && !restored(facts, status, channel))
                return unknown("DEPLOYMENT_ORIGINAL_BUSINESS_STATE_CHANGED");
            return result;
        }
        if (status.buildActive())
            return unknown("DEPLOYMENT_ORIGINAL_BUILD_STILL_ACTIVE");
        if (record.outcome().filter(outcome -> outcome != Outcome.UNKNOWN).isPresent()) {
            var result = known(record, proposal, facts);
            if (result.outcome() == Outcome.UNKNOWN)
                return result;
            if (result.outcome() == Outcome.SUCCEEDED && !status.current().equals(facts.release())
                    || result.outcome() == Outcome.FAILED && !restored(facts, status, channel))
                return unknown("DEPLOYMENT_ORIGINAL_BUSINESS_STATE_CHANGED");
            return result;
        }
        if (facts.release().isEmpty()) {
            if (status.maintenance())
                return unknown("DEPLOYMENT_PREPUBLISH_MAINTENANCE_CONFLICT");
            if (status.snapshot()) {
                if (facts.snapshot().isPresent() && !snapshotMatches(facts, status))
                    return unknown("DEPLOYMENT_PREPUBLISH_SNAPSHOT_CONFLICT");
                if (!status.current().equals(status.previousDigest()))
                    return unknown("DEPLOYMENT_PREPUBLISH_CURRENT_CHANGED");
                if (facts.snapshot().isEmpty()) {
                    var recovered = new DeploymentRemoteReleaseSnapshot(
                            Optional.of(facts.operation().orElse(record.identity().taskId()).toString()), status.previousRunning().orElseThrow(),
                            status.previousDigest(), status.previousKind());
                    if (!journal.snapshot(recovered))
                        return unknown("DEPLOYMENT_PREPUBLISH_SNAPSHOT_RECORD_UNCONFIRMED");
                }
                if (facts.phase().equals("SNAPSHOT_DISCARD_MAY_HAVE_RUN"))
                    return unknown("DEPLOYMENT_PREPUBLISH_SNAPSHOT_DISCARD_UNCONFIRMED");
                if (!journal.mark("SNAPSHOT_DISCARD_MAY_HAVE_RUN"))
                    return unknown("DEPLOYMENT_PREPUBLISH_SNAPSHOT_DISCARD_BOUNDARY_UNCONFIRMED");
                var discarded = cleanupCall(journal, facts.phase(), () -> facts.operation().isEmpty() ? channel.discardSnapshot(facts.unit()) : channel.discardSnapshot(facts.unit(), facts.operation().orElseThrow()));
                if (notDispatched(journal, facts.phase(), discarded))
                    return unknown("DEPLOYMENT_PREPUBLISH_SNAPSHOT_NOT_DISPATCHED");
                if (discarded.outcome() != Outcome.SUCCEEDED)
                    return unknown("DEPLOYMENT_PREPUBLISH_SNAPSHOT_UNCONFIRMED");
                var after = facts.operation().isEmpty() ? channel.originalStatus(facts.unit(), facts.candidate(), Optional.empty(), facts.backend(), facts.engine()) : channel.originalStatus(facts.unit(), facts.candidate(), Optional.empty(), facts.backend(), facts.engine(), facts.operation().orElseThrow());
                if (after.outcome() != Outcome.SUCCEEDED || after.value().orElseThrow().snapshot())
                    return unknown("DEPLOYMENT_PREPUBLISH_SNAPSHOT_RETAINED");
                if (!journal.mark("SNAPSHOT_DISCARDED"))
                    return unknown("DEPLOYMENT_PREPUBLISH_SNAPSHOT_DISCARD_UNCONFIRMED");
            } else if (facts.snapshot().filter(saved -> saved.token().isPresent()).isPresent()
                    && !facts.phase().equals("SNAPSHOT_DISCARD_MAY_HAVE_RUN")
                    && !facts.phase().equals("SNAPSHOT_DISCARDED"))
                return unknown("DEPLOYMENT_PREPUBLISH_SNAPSHOT_MISSING");
            return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNAVAILABLE, "DEPLOYMENT_PUBLICATION_NOT_STARTED",
                            "Original publication ended before dispatch", Map.of(), Optional.empty())));
        }
        if (proposal.isEmpty()
                || !proposal.orElseThrow().units().getFirst().release().sha256().equals(facts.release().orElseThrow())
                || facts.snapshot().isEmpty())
            return unknown("DEPLOYMENT_ORIGINAL_PROPOSAL_MISSING");
        if (facts.phase().startsWith("ROLLBACK_") && restored(facts, status, channel))
            return new OperationResult<>(Outcome.FAILED, Optional.empty(), facts.failure());
        if (facts.phase().startsWith("ROLLBACK_"))
            return unknown("DEPLOYMENT_ROLLBACK_UNCONFIRMED");
        if (!status.current().equals(facts.release())
                || facts.snapshot().orElseThrow().token().isPresent() && !snapshotMatches(facts, status))
            return unknown("DEPLOYMENT_PUBLISHED_OWNER_UNCONFIRMED");
        var reviewedRuntime = proposal.orElseThrow().graph().components().getFirst().reviewedRuntime();
        var observed = channel.observe(facts.unit(), reviewedRuntime);
        if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty())
            return unknown("DEPLOYMENT_PUBLISHED_RUNTIME_UNCONFIRMED");
        var expected = reviewedRuntime.workload().mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND
                ? DeploymentRemoteRuntimeObservation.StateType.INSTALLED
                : DeploymentRemoteRuntimeObservation.StateType.RUNNING;
        if (observed.value().orElseThrow().state() != expected)
            return unknown("DEPLOYMENT_PUBLISHED_RUNTIME_CHANGED");
        var health = channel.healthCheck(facts.unit(), reviewedRuntime.healthCheck());
        if (health.outcome() != Outcome.SUCCEEDED)
            return unknown("DEPLOYMENT_PUBLISHED_HEALTH_UNCONFIRMED");
        return new OperationResult<>(Outcome.SUCCEEDED, proposal, Optional.empty());
    }

    /** Requires exact old content and its observed state after a claimed rollback. / 声称回滚后要求精确旧内容及实际状态。
     * @param facts original snapshot facts / 原快照事实
     * @param status complete remote status / 完整远端状态
     * @param channel original reviewed channel / 原审阅通道
     * @return true only for a confirmed original restoration
     *         / 仅原恢复获确认时为真
     * @throws ApiException if observation cannot be made / 无法观察时 */
    private boolean restored(DeploymentPublicationRecoveryFacts facts, DeploymentOriginalPublicationStatus status,
            DeploymentPublicationChannel channel) throws ApiException {
        var original = facts.snapshot();
        if (original.isEmpty() || status.snapshot() || status.buildActive())
            return false;
        var saved = original.orElseThrow();
        if (saved.token().isEmpty())
            return status.current().isEmpty() && !status.unitPresent()
                    && status.containerPresent().filter(Boolean::booleanValue).isEmpty();
        if (!status.current().equals(saved.previousDigest()) || !status.currentKind().equals(saved.previousKind()))
            return false;
        var observed = channel.observeRestored(facts.unit());
        if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty())
            return false;
        var state = observed.value().orElseThrow().state();
        return state != DeploymentRemoteRuntimeObservation.StateType.ERROR
                && saved.previousRunning() == (state == DeploymentRemoteRuntimeObservation.StateType.RUNNING);
    }

    /** Checks the exact task-token snapshot before trusting its old-release claim. / 信任旧发布声明前核对精确任务令牌快照。
     * @param facts original saved snapshot / 原已存快照
     * @param status remote observation / 远端观察
     * @return whether all old identities match / 全部旧身份是否匹配 */
    private static boolean snapshotMatches(DeploymentPublicationRecoveryFacts facts,
            DeploymentOriginalPublicationStatus status) {
        return facts.snapshot().isPresent() && status.snapshot()
                && status.previousDigest().equals(facts.snapshot().orElseThrow().previousDigest())
                && status.previousKind().equals(facts.snapshot().orElseThrow().previousKind())
                && status.previousRunning().equals(Optional.of(facts.snapshot().orElseThrow().previousRunning()));
    }

    /** Finishes only original confirmed cleanup steps in their historical order. / 仅按原历史顺序完成原已确认收尾步骤。
     * @param journal original CAS checkpoint / 原CAS检查点
     * @param facts original identity / 原身份
     * @param status initial read-only observation / 初次只读观察
     * @param channel original reviewed channel / 原审阅通道
     * @param successful whether new publication is proven / 新发布是否已证明
     * @return whether every remote effect and acknowledgement was confirmed / 全部远端效果与确认是否已核实
     * @throws ApiException if host admission fails / 宿主准入失败时 */
    private boolean finishRemote(DeploymentPublicationCleanupJournal journal, DeploymentPublicationRecoveryFacts facts,
            DeploymentOriginalPublicationStatus status, DeploymentPublicationChannel channel, boolean successful)
            throws ApiException {
        if (journal.facts().path("phase").asText().equals("REMOTE_CLEANUP_VERIFIED")
                || journal.facts().path("phase").asText().equals("SESSION_CLOSED")
                || journal.facts().path("phase").asText().equals("CLEANUP_VERIFIED"))
            return true;
        var current = status;
        var phase = journal.facts().path("phase").asText();
        if (successful && !current.current().equals(facts.release()))
            return false;
        if (!successful && facts.release().isPresent() && !restored(facts, current, channel)
                || !successful && facts.release().isEmpty() && current.snapshot())
            return false;
        if (current.maintenance()) {
            if (facts.release().isEmpty() || phase.equals("MAINTENANCE_END_MAY_HAVE_RUN")
                    || cleanupOrder(phase) >= cleanupOrder("MAINTENANCE_ENDED")
                    || !journal.mark("MAINTENANCE_END_MAY_HAVE_RUN"))
                return false;
            var ended = cleanupCall(journal, phase,
                    () -> channel.endMaintenance(facts.unit(), facts.release().orElseThrow()));
            if (notDispatched(journal, phase, ended))
                return false;
            current = reread(channel, facts);
            if (current == null || current.maintenance())
                return false;
        }
        if (cleanupOrder(phase) <= cleanupOrder("MAINTENANCE_END_MAY_HAVE_RUN") && !journal.mark("MAINTENANCE_ENDED"))
            return false;
        phase = journal.facts().path("phase").asText();
        if (successful) {
            if (current.retainedCount() > 3) {
                if (phase.equals("RETAIN_MAY_HAVE_RUN") || cleanupOrder(phase) >= cleanupOrder("RETAIN_VERIFIED")
                        || !journal.mark("RETAIN_MAY_HAVE_RUN"))
                    return false;
                var retained = cleanupCall(journal, phase, () -> channel.retain(facts.unit()));
                if (notDispatched(journal, phase, retained))
                    return false;
                current = reread(channel, facts);
                if (current == null || current.retainedCount() > 3)
                    return false;
            }
            if (cleanupOrder(phase) <= cleanupOrder("RETAIN_MAY_HAVE_RUN") && !journal.mark("RETAIN_VERIFIED"))
                return false;
            phase = journal.facts().path("phase").asText();
        }
        if (current.candidate()) {
            if (phase.equals("CANDIDATE_CLEANUP_MAY_HAVE_RUN")
                    || cleanupOrder(phase) >= cleanupOrder("CANDIDATE_CLEANED")
                    || !journal.mark("CANDIDATE_CLEANUP_MAY_HAVE_RUN"))
                return false;
            var cleaned = cleanupCall(journal, phase, () -> channel.cleanupCandidate(facts.unit(), facts.candidate()));
            if (notDispatched(journal, phase, cleaned))
                return false;
            current = reread(channel, facts);
            if (current == null || current.candidate())
                return false;
        }
        return !current.maintenance() && !current.buildActive()
                && (cleanupOrder(phase) >= cleanupOrder("CANDIDATE_CLEANED") || journal.mark("CANDIDATE_CLEANED"))
                && journal.remoteClean();
    }

    /** Orders only durable remote cleanup phases so a later uncertain step cannot be erased. / 仅排序远端持久收尾阶段，防止擦掉较晚的未确认步骤。
     * @param phase original journal phase / 原账本阶段
     * @return ordered cleanup position / 收尾序位 */
    private static int cleanupOrder(String phase) {
        return switch (phase) {
            case "MAINTENANCE_END_MAY_HAVE_RUN" -> 1;
            case "MAINTENANCE_ENDED" -> 2;
            case "RETAIN_MAY_HAVE_RUN" -> 3;
            case "RETAIN_VERIFIED" -> 4;
            case "CANDIDATE_CLEANUP_MAY_HAVE_RUN" -> 5;
            case "CANDIDATE_CLEANED" -> 6;
            case "REMOTE_CLEANUP_VERIFIED", "SESSION_CLOSED", "CLEANUP_VERIFIED" -> 7;
            default -> 0;
        };
    }

    /** One original fixed command that can report a definite pre-dispatch rejection. / 一项可报告明确发送前拒绝的原固定命令。 */
    @FunctionalInterface
    private interface CleanupCommand {
        /** Sends at most once. / 最多发送一次。
         * @return observed command result / 已观测命令结果
         * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
        OperationResult<?> run() throws ApiException;
    }

    /** Restores the prior durable phase only when host admission proves no command was sent. / 仅在宿主准入证明未发送命令时恢复先前持久阶段。
     * @param journal original checkpoint / 原检查点
     * @param previous prior durable phase / 先前持久阶段
     * @param command reviewed original command / 已审核的原命令
     * @return command observation / 命令观测
     * @throws ApiException if admission failed before dispatch / 发送前准入失败时 */
    private static OperationResult<?> cleanupCall(DeploymentPublicationCleanupJournal journal, String previous,
            CleanupCommand command) throws ApiException {
        try {
            return command.run();
        } catch (ApiException rejected) {
            if (rejected.problem().code() == ProblemCode.ACCESS_DENIED
                    || rejected.problem().code() == ProblemCode.INVALID_INPUT)
                journal.mark(previous);
            throw rejected;
        }
    }

    /** Identifies only a definite empty pre-dispatch result; remote failures with a process receipt stay uncertain. / 仅识别明确无回执的发送前结果；带进程回执的远端失败仍不明。
     * @param journal original checkpoint / 原检查点
     * @param previous prior phase / 原阶段
     * @param result observed command / 已观察命令
     * @return true only when dispatch was definitely refused / 仅明确拒绝发送时为真 */
    private static boolean notDispatched(DeploymentPublicationCleanupJournal journal, String previous,
            OperationResult<?> result) {
        if ((result.outcome() == Outcome.FAILED || result.outcome() == Outcome.CANCELLED) && result.value().isEmpty()) {
            journal.mark(previous);
            return true;
        }
        return false;
    }

    /** Rechecks after a single authorised cleanup without resending an uncertain command. / 单次获授权收尾后重新只读核对，不重发不明命令。
     * @param channel original channel / 原通道
     * @param facts original identity / 原身份
     * @return current complete status or null when uncertain / 当前完整状态；未知时为空
     * @throws ApiException if host admission fails / 宿主准入失败时 */
    private static DeploymentOriginalPublicationStatus reread(DeploymentPublicationChannel channel,
            DeploymentPublicationRecoveryFacts facts) throws ApiException {
        var read = facts.operation().isEmpty() ? channel.originalStatus(facts.unit(), facts.candidate(), facts.release(), facts.backend(), facts.engine()) : channel.originalStatus(facts.unit(), facts.candidate(), facts.release(), facts.backend(), facts.engine(), facts.operation().orElseThrow());
        return read.outcome() == Outcome.SUCCEEDED ? read.value().orElse(null) : null;
    }

    /** Reports a bounded original-task uncertainty. / 报告有界原任务未知。
     * @param event stable diagnostic / 稳定诊断
     * @return unknown without a fabricated publication / 不补造发布的未知结果 */
    private static OperationResult<DeploymentPublicationProposal> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), newProblem(event));
    }

    /** Creates only safe bounded metadata for a recovered result. / 仅为恢复结果创建安全有界元数据。
     * @param event stable diagnostic / 稳定诊断
     * @return public problem / 公开问题 */
    private static Optional<Problem> newProblem(String event) {
        return Optional.of(new Problem(ProblemCode.UNKNOWN, event, "Original publication evidence remains unconfirmed",
                Map.of(), Optional.empty()));
    }
}
