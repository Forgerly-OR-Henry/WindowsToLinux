package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import static gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentCollectionEvidence.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApplicationReader.ApplicationFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentApplicationGraph;

/** Phases under one borrowed collection session and the original durable handoff. / 同一借用采集会话及原持久交接下的阶段。 */
final class DeploymentCollectionWork {
    /** Original authorized phase journal. / 原已授权阶段账本。 */
    private final DeploymentHandoffLedger ledger;

    /** Complete original release and component graph. / 完整原发布与组件图。 */
    private final ApplicationFacts data;

    /** Caller-owned authentic root task. / 调用方所属真实根任务。 */
    private final TaskContext task;

    /** Complete reviewed artifact selection. / 完整已审阅产物选择。 */
    private final List<DeploymentCollectionPlan.ArtifactPlan> plans;

    /** Only artifacts whose producer was actually attempted. / 仅包含实际尝试过生产者的产物。 */
    private final List<DeploymentCollectionPlan.ArtifactPlan> attemptedPlans;

    /** Borrowed exact helper command channel. / 借用的精确helper命令频道。 */
    private final DeploymentHandoffChannel channel;

    /** Borrowed original runtime channel. / 借用的原运行时频道。 */
    private final DeploymentPublishChannel publication;

    /** Original immutable remote producer operation. / 原不可变远端生产者操作。 */
    private final String operation;

    /** Latest confirmed revision of the same journal row. / 同一账本行的最新确证修订。 */
    private DeploymentHandoffLedger.Entry entry;

    /** Private attempt and completion evidence persisted before effects. / 副作用前持久保存的私有尝试及完成依据。 */
    private final ObjectNode facts;

    /** Per-component original states and owned marker evidence. / 每组件原状态与所属标记依据。 */
    private ObjectNode units;

    /** Borrows one session while preserving original attempted-artifact identity. / 借用同一会话并保留原尝试产物身份。
     * @param ledger original phase journal / 原阶段账本
     * @param data complete original release / 完整原发布
     * @param task authentic original root task / 真实原根任务
     * @param entry original authorized row / 原已授权行
     * @param plans complete reviewed artifact selection / 完整已审阅产物选择
     * @param channel exact borrowed helper channel / 精确借用helper频道
     * @param publication borrowed original runtime channel / 借用原运行时频道 */
    DeploymentCollectionWork(DeploymentHandoffLedger ledger, ApplicationFacts data, TaskContext task,
                             DeploymentHandoffLedger.Entry entry, List<DeploymentCollectionPlan.ArtifactPlan> plans,
                             DeploymentHandoffChannel channel, DeploymentPublishChannel publication) {
        this.ledger = ledger;
        this.data = data;
        this.task = task;
        this.entry = entry;
        this.plans = List.copyOf(plans);
        this.channel = channel;
        this.publication = publication;
        facts = ledger.recovery(entry);
        units = facts.withObject("units");
        operation = facts.path("operation").asText();
        if (!operation.matches("backup-[0-9a-f]{32}"))
            throw new IllegalArgumentException("Original collection operation differs");
        var attempted = new HashSet<String>();
        facts.path("attemptedArtifacts").forEach(value -> attempted.add(value.asText()));
        attemptedPlans = plans.stream().filter(plan -> attempted.contains(planKey(plan))).toList();
        if (attemptedPlans.size() != attempted.size())
            throw new IllegalArgumentException("Original attempted bindings differ");
    }

    /** Records verified original component states before maintenance effects. / 维护副作用前记录已核实原组件状态。
     * @param quiescence whether selected bindings require stopped writers / 所选绑定是否要求停止写入者
     * @return known initial observations or their failure / 已知初始观察或其失败
     * @throws ApiException if authority or original persistence is unavailable / 权限或原持久化不可用时 */
    OperationResult<Void> begin(boolean quiescence) throws ApiException {
        var verified = current(channel, data);
        if (verified.outcome() != TaskResult.SUCCEEDED)
            return copy(verified);
        units = facts.putObject("units");
        for (var component : data.graph().components()) {
            var observed = publication.observe(component.unit(), component.reviewedRuntime());
            if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty())
                return copy(observed);
            String state = observed.value().orElseThrow().state().name();
            if (quiescence && state.equals("ERROR"))
                return failed("DEPLOYMENT_COLLECTION_FAILED_COMPONENT_CANNOT_QUIESCE");
            if (quiescence && state.equals("RUNNING") && (!component.runtime().workload().supportsLifecycle()
                || !component.runtime().workload().equals(component.reviewedRuntime().workload())))
                return failed("DEPLOYMENT_COLLECTION_LIFECYCLE_DECLARATION_UNREVIEWED");
            units.putObject(component.componentId()).put("before", state).put("maintenanceAttempted", false)
                .put("maintenanceAcquired", false).put("stopAttempted", false).put("stopped", false)
                .put("startAttempted", false).put("restored", false);
        }
        facts.put("quiescence", quiescence);

        facts.put("changed", true);
        save(DeploymentHandoffState.MAINTENANCE);
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Acquires only this operation's markers after exact current-release checks. / 精确当前发布核对后仅获取本操作标记。
     * @throws ApiException if identity, marker or original evidence is unconfirmed / 身份、标记或原依据未确证时 */
    void acquireMaintenance() throws ApiException {
        for (var component : data.graph().components()) {
            if (current(channel, data).outcome() != TaskResult.SUCCEEDED)
                throw fail("DEPLOYMENT_COLLECTION_CURRENT_CHANGED");
            var unit = units.withObject(component.componentId());
            unit.put("maintenanceAttempted", true);
            save(DeploymentHandoffState.MAINTENANCE);
            var marker = channel.execute(
                "application-maintenance", List.of("begin", component.unit().id(),
                    component.unit().ownershipManifestSha256(), operation, unitRelease(data, component)),
                new byte[0], Duration.ofMinutes(2), 1024);
            if (!DeploymentHandoffChannel.receipt(marker, "MAINTENANCE=begin\n"))
                throw fail("DEPLOYMENT_COLLECTION_MAINTENANCE_UNKNOWN");
            unit.put("maintenanceAcquired", true);
            save(DeploymentHandoffState.MAINTENANCE);
        }
    }

    /** Stops only originally running components required for the reviewed collection. / 仅停止已审阅采集需要的原运行组件。
     * @throws ApiException if original identity or stop acknowledgement is unconfirmed / 原身份或停止确认未确证时 */
    void quiesce() throws ApiException {
        if (facts.path("quiescence").asBoolean())
            for (var component : order(data.graph()).reversed()) {
                var unit = units.withObject(component.componentId());
                if (!unit.path("before").asText().equals("RUNNING"))
                    continue;
                if (current(channel, data).outcome() != TaskResult.SUCCEEDED)
                    throw fail("DEPLOYMENT_COLLECTION_CURRENT_CHANGED");
                unit.put("stopAttempted", true);
                save(DeploymentHandoffState.MAINTENANCE);
                var stopped = publication.lifecycle(component.unit(), component.reviewedRuntime(), "stop",
                    unitRelease(data, component));
                if (stopped.outcome() != TaskResult.SUCCEEDED)
                    throw fail("DEPLOYMENT_COLLECTION_STOP_UNKNOWN");
                unit.put("stopped", true);
                save(DeploymentHandoffState.MAINTENANCE);
            }
    }

    /** Dispatches each original producer once and accepts only sealed full receipts. / 一次发送每项原生产者并仅接受封印完整回执。
     * @return complete original remote artifacts / 完整原远端产物
     * @throws ApiException if cancellation or uncertain producer evidence requires recovery / 取消或未知生产者依据要求恢复时 */
    DeploymentCollection export() throws ApiException {
        var collected = facts.putArray("artifacts");
        for (var plan : plans) {
            if (task.cancelToken().requested())
                throw fail("DEPLOYMENT_COLLECTION_CANCELLED_IN_MAINTENANCE");
            if (current(channel, data).outcome() != TaskResult.SUCCEEDED)
                throw fail("DEPLOYMENT_COLLECTION_CURRENT_CHANGED");
            var attempts = facts.withArray("attemptedArtifacts");
            attempts.add(plan.componentId() + ":" + plan.kind() + ":" + plan.resource());
            save(DeploymentHandoffState.MAINTENANCE);
            var remote = channel.execute(plan.kind().equals("database") ? "handoff-database-export" : "backup-create",
                plan.createArguments(operation, data.snapshot().application().id()), new byte[0],
                Duration.ofHours(2), 4096);
            if (remote.outcome() != TaskResult.SUCCEEDED || remote.value().isEmpty())
                throw fail("DEPLOYMENT_COLLECTION_ARTIFACT_UNKNOWN");
            var artifact = parseArtifact(remote, plan, operation, data.snapshot().application().id());
            if (artifact.isEmpty())
                throw fail("DEPLOYMENT_COLLECTION_RECEIPT_UNKNOWN");
            var value = artifact.orElseThrow();
            collected.addObject().put("id", value.id()).put("bindingId", value.bindingId())
                .put("remotePath", value.remotePath()).put("byteCount", value.byteCount())
                .put("sha256", value.sha256());
            save(DeploymentHandoffState.MAINTENANCE);
        }
        save(DeploymentHandoffState.COLLECTED);
        return new DeploymentCollection(entry.publicValue(), artifacts(entry.recovery()));
    }

    /** Fences producers before restoring source writes and removing original materials. / 恢复源写入及移除原材料前关闭生产者准入。
     * @return confirmed original source recovery and cleanup / 已确证原源恢复与清理
     * @throws ApiException if any original recovery phase remains unconfirmed / 任一原恢复阶段仍未确证时 */
    DeploymentHandoff finish() throws ApiException {
        closeProducers();
        var statuses = producerStatuses();
        restoreSource();
        verifySource();
        discardDatabases(statuses);
        discardArchives();
        releaseMaintenance();
        facts.put("sourceRestored", true);
        save(DeploymentHandoffState.FINISHED);
        return entry.publicValue();
    }

    /** Confirms a durable original-operation fence before permitting source restart. / 允许源重启前确证持久原操作关闭标记。
     * @throws ApiException if a writer or original fence remains unconfirmed / 写入者或原关闭标记未确证时 */
    private void closeProducers() throws ApiException {
        // Confirm that every actually dispatched producer has stopped before
        // restoring source writes or removing original operation material.
        if (current(channel, data).outcome() != TaskResult.SUCCEEDED)
            throw fail("DEPLOYMENT_COLLECTION_RECOVERY_CURRENT_CHANGED");
        boolean closePreviouslyAttempted = facts.path("closeAttempted").asBoolean();
        if (!closePreviouslyAttempted) {
            facts.put("closeAttempted", true);
            save(DeploymentHandoffState.COMPENSATING);
        }
        if (!DeploymentHandoffChannel.receipt(
            channel.execute(closePreviouslyAttempted ? "handoff-operation-close-status" : "handoff-operation-close",
                List.of(operation, data.snapshot().application().id(), task.id().toString(),
                    data.snapshot().revisionSha256()),
                new byte[0], Duration.ofMinutes(2), 1024),
            "HANDOFF_OPERATION_CLOSED=1\n"))
            throw fail("DEPLOYMENT_COLLECTION_PRODUCER_FENCE_UNCONFIRMED");
        facts.put("closed", true);
        save(DeploymentHandoffState.COMPENSATING);
    }

    /** Reads only attempted idle artifacts after the confirmed producer fence. / 生产者关闭确证后仅读取曾尝试的空闲产物。
     * @return exact original artifact digests or incomplete markers / 精确原产物摘要或未完整标记
     * @throws ApiException if original artifact status is unconfirmed / 原产物状态未确证时 */
    private Map<String, String> producerStatuses() throws ApiException {
        var statuses = new HashMap<String, String>();
        for (var plan : attemptedPlans) {
            var status = channel.execute(
                plan.kind().equals("database") ? "handoff-database-status" : "handoff-backup-status",
                plan.statusArguments(operation, data.snapshot().application().id()), new byte[0],
                Duration.ofMinutes(10), 4096);
            String prefix = plan.kind().equals("database") ? "HANDOFF_DATABASE" : "HANDOFF_BACKUP";
            if (DeploymentHandoffChannel.receipt(status, prefix + "=1\nPRESENT=0\n")) {
                statuses.put(plan.bindingId(), "absent");
            } else if (DeploymentHandoffChannel.receipt(status, prefix + "=1\nPRESENT=1\nCOMPLETE=0\n")) {
                statuses.put(plan.bindingId(), "incomplete");
            } else {
                var sealed = parseStatus(status, plan, operation, data.snapshot().application().id());
                if (sealed.isEmpty())
                    throw fail("DEPLOYMENT_COLLECTION_PRODUCER_UNCONFIRMED");
                statuses.put(plan.bindingId(), sealed.orElseThrow().sha256());
            }
        }
        return Map.copyOf(statuses);
    }

    /** Restarts only this operation's stopped original writers, preserving unknown start attempts. / 仅重启本操作停止的原写入者并保留未知启动尝试。
     * @throws ApiException if original identity or start evidence is unconfirmed / 原身份或启动依据未确证时 */
    private void restoreSource() throws ApiException {
        for (var component : order(data.graph())) {
            var saved = units.path(component.componentId());
            if (!saved.path("stopAttempted").asBoolean(false))
                continue;
            if (current(channel, data).outcome() != TaskResult.SUCCEEDED)
                throw fail("DEPLOYMENT_COLLECTION_RECOVERY_CURRENT_CHANGED");
            var observed = publication.observe(component.unit(), component.reviewedRuntime());
            if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty())
                throw fail("DEPLOYMENT_COLLECTION_RESTORE_OBSERVATION_UNKNOWN");
            String now = observed.value().orElseThrow().state().name();
            if (now.equals("RUNNING"))
                continue;
            if (!saved.path("before").asText().equals("RUNNING") || !now.equals("STOPPED"))
                throw fail("DEPLOYMENT_COLLECTION_RESTORE_STATE_UNKNOWN");
            if (!component.runtime().workload().supportsLifecycle()
                || !component.runtime().workload().equals(component.reviewedRuntime().workload()))
                throw fail("DEPLOYMENT_COLLECTION_RESTORE_DECLARATION_UNREVIEWED");
            if (saved.path("startAttempted").asBoolean(false))
                throw fail("DEPLOYMENT_COLLECTION_START_ATTEMPT_UNKNOWN");
            var held = marker(channel, component, operation);
            if (held.isEmpty() || !held.orElseThrow())
                throw fail("DEPLOYMENT_COLLECTION_MAINTENANCE_LOST");
            var mutable = facts.withObject("units").withObject(component.componentId());
            mutable.put("startAttempted", true);
            save(DeploymentHandoffState.COMPENSATING);
            var started = publication.lifecycle(component.unit(), component.reviewedRuntime(), "start",
                unitRelease(data, component));
            if (started.outcome() != TaskResult.SUCCEEDED)
                throw fail("DEPLOYMENT_COLLECTION_RESTORE_UNKNOWN");
            mutable.put("restored", true);
            save(DeploymentHandoffState.COMPENSATING);
        }
    }

    /** Removes only idle original database artifacts with exact digest evidence. / 仅以精确摘要依据移除空闲原数据库产物。
     * @param statuses confirmed original producer statuses / 已确证原生产者状态
     * @throws ApiException if original discard or its receipt is unconfirmed / 原丢弃或其回执未确证时 */
    private void discardDatabases(Map<String, String> statuses) throws ApiException {
        for (var plan : attemptedPlans)
            if (plan.kind().equals("database")) {
                String expected = statuses.get(plan.bindingId());
                var cleanup = facts.withObject("cleanup").withObject(plan.bindingId());
                if (cleanup.path("done").asBoolean(false))
                    continue;
                if (expected.equals("absent")) {
                    cleanup.put("done", true);
                    save(DeploymentHandoffState.COMPENSATING);
                    continue;
                }
                if (!cleanup.path("attempted").asBoolean(false)) {
                    cleanup.put("attempted", true);
                    save(DeploymentHandoffState.COMPENSATING);
                    var arguments = new ArrayList<>(
                        plan.createArguments(operation, data.snapshot().application().id()));
                    arguments.add(expected);
                    var removed = channel.execute("handoff-database-discard", arguments, new byte[0],
                        Duration.ofMinutes(10), 1024);
                    if (!DeploymentHandoffChannel.receipt(removed, "HANDOFF_DATABASE_DISCARDED=1\n"))
                        throw fail("DEPLOYMENT_COLLECTION_DATABASE_CLEANUP_UNKNOWN");
                } else {
                    var status = channel.execute("handoff-database-status",
                        plan.statusArguments(operation, data.snapshot().application().id()), new byte[0],
                        Duration.ofMinutes(10), 1024);
                    if (!DeploymentHandoffChannel.receipt(status, "HANDOFF_DATABASE=1\nPRESENT=0\n"))
                        throw fail("DEPLOYMENT_COLLECTION_DATABASE_CLEANUP_UNKNOWN");
                }
                cleanup.put("done", true);
                save(DeploymentHandoffState.COMPENSATING);
            }
    }

    /** Discards original archive material once and only observes after a lost reply. / 一次丢弃原归档材料，丢失回复后仅观察。
     * @throws ApiException if original archive cleanup is unconfirmed / 原归档清理未确证时 */
    private void discardArchives() throws ApiException {
        var cleanup = facts.withObject("cleanup").withObject("archives");
        if (!cleanup.path("done").asBoolean(false)) {
            if (!cleanup.path("attempted").asBoolean(false)) {
                cleanup.put("attempted", true);
                save(DeploymentHandoffState.COMPENSATING);
                var removed = channel.execute("backup-discard", List.of(operation), new byte[0], Duration.ofMinutes(10),
                    1024);
                if (!DeploymentHandoffChannel.receipt(removed, "DISCARDED=1\n"))
                    throw fail("DEPLOYMENT_COLLECTION_ARCHIVE_CLEANUP_UNKNOWN");
            } else
                for (var plan : attemptedPlans)
                    if (!plan.kind().equals("database")) {
                        var status = channel.execute("handoff-backup-status",
                            plan.statusArguments(operation, data.snapshot().application().id()), new byte[0],
                            Duration.ofMinutes(10), 1024);
                        if (!DeploymentHandoffChannel.receipt(status, "HANDOFF_BACKUP=1\nPRESENT=0\n"))
                            throw fail("DEPLOYMENT_COLLECTION_ARCHIVE_CLEANUP_UNKNOWN");
                    }
            cleanup.put("done", true);
            save(DeploymentHandoffState.COMPENSATING);
        }
    }

    /** Releases only this operation's owned markers after source verification. / 来源核实后仅释放本操作所属标记。
     * @throws ApiException if original marker release is unconfirmed / 原标记释放未确证时 */
    private void releaseMaintenance() throws ApiException {
        for (var component : data.graph().components()) {
            if (!units.path(component.componentId()).path("maintenanceAttempted").asBoolean(false))
                continue;
            var marker = marker(channel, component, operation);
            if (marker.isEmpty())
                throw fail("DEPLOYMENT_COLLECTION_MAINTENANCE_STATUS_UNKNOWN");
            if (!marker.orElseThrow())
                continue;
            var ended = channel.execute(
                "application-maintenance", List.of("end", component.unit().id(),
                    component.unit().ownershipManifestSha256(), operation, unitRelease(data, component)),
                new byte[0], Duration.ofMinutes(2), 1024);
            if (!DeploymentHandoffChannel.receipt(ended, "MAINTENANCE=end\n"))
                throw fail("DEPLOYMENT_COLLECTION_MAINTENANCE_END_UNKNOWN");
        }
    }

    /** Verifies original current digests, before-states and applicable health policies. / 核实原当前摘要、变更前状态及适用健康策略。
     * @throws ApiException if original source restoration is unconfirmed / 原来源恢复未确证时 */
    private void verifySource() throws ApiException {
        if (current(channel, data).outcome() != TaskResult.SUCCEEDED)
            throw fail("DEPLOYMENT_COLLECTION_RECOVERY_CURRENT_CHANGED");
        boolean allRunning = true;
        for (var component : data.graph().components()) {
            var observed = publication.observe(component.unit(), component.reviewedRuntime());
            String expected = units.path(component.componentId()).path("before").asText();
            if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty()
                || !observed.value().orElseThrow().state().name().equals(expected))
                throw fail("DEPLOYMENT_COLLECTION_SOURCE_RESTORE_UNVERIFIED");
            if (expected.equals("RUNNING")) {
                if (publication.healthCheck(component.unit(), component.reviewedRuntime().healthCheck())
                    .outcome() != TaskResult.SUCCEEDED)
                    throw fail("DEPLOYMENT_COLLECTION_SOURCE_HEALTH_UNVERIFIED");
            } else
                allRunning = false;
        }
        if (allRunning) {
            var health = data.graph().components().stream()
                .filter(value -> value.componentId().equals(data.graph().healthComponentId())).findFirst()
                .orElseThrow();
            if (publication.healthCheck(health.unit(), data.graph().healthCheck()).outcome() != TaskResult.SUCCEEDED)
                throw fail("DEPLOYMENT_COLLECTION_SOURCE_HEALTH_UNVERIFIED");
        }
    }

    /** Saves evidence with the exact previous phase and reloads the original row. / 以精确前阶段保存依据并重读原行。
     * @param phase confirmed next phase / 已确证下一阶段
     * @throws ApiException if the original compare-and-set is unavailable / 原比较更新不可用时 */
    private void save(DeploymentHandoffState phase) throws ApiException {
        ledger.advance(entry, entry.publicValue().state(), phase, facts, Optional.empty(), task);
        entry = ledger.find(entry.publicValue().id(), task)
            .orElseThrow(() -> problem(ProblemCode.UNKNOWN, "DEPLOYMENT_COLLECTION_JOURNAL_ABSENT"));
    }

    /** Persists uncertainty before returning the original recovery blocker. / 返回原恢复阻碍前持久保存未知。
     * @param event original uncertainty event / 原未知事件
     * @return classified original uncertainty / 已分类原未知
     * @throws ApiException if original blocker persistence is unavailable / 原阻碍持久化不可用时 */
    private ApiException fail(String event) throws ApiException {
        facts.putArray("blockers").add(event);
        save(DeploymentHandoffState.UNKNOWN);
        return problem(ProblemCode.UNKNOWN, event);
    }
}
