package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

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

import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentPublicationChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApplicationReader.ApplicationFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentApplicationGraph;

/** Coordinates a complete original-task source collection with retained maintenance evidence. / 以留存维护依据编排完整原任务来源采集。 */
public final class DeploymentCollectionCoordinator {
    /** Actual provider source and review capabilities. / 实际提供方来源与审核能力。 */
    private final ModContext context;

    /** Complete immutable current-release reader. / 完整不可变当前发布读取器。 */
    private final DeploymentApplicationReader reader;

    /** Original caller-authorized durable handoff journal. / 原调用方授权的持久交接账本。 */
    private final DeploymentHandoffLedger ledger;

    /** Exact complete artifact selection before effects. / 副作用前的精确完整产物选择。 */
    private final DeploymentCollectionPlan planner = new DeploymentCollectionPlan();

    /** Binds original deployment capabilities. / 绑定原部署能力。
     * @param context active deployment Mod / 活动部署Mod */
    public DeploymentCollectionCoordinator(ModContext context) {
        this.context = java.util.Objects.requireNonNull(context);
        this.reader = new DeploymentApplicationReader(context.db());
        this.ledger = new DeploymentHandoffLedger(context);
    }

    /** Reviews the complete release and selected real bindings before creating a handoff. / 创建交接前审阅完整发布及所选真实绑定。
     * @param request selected source / 所选来源
     * @param task original caller task / 原调用任务
     * @return reviewed durable handoff / 已审阅持久交接
     * @throws ApiException if release or selected binding is not supported / 发布或绑定不受支持时 */
    public OperationResult<DeploymentHandoff> review(DeploymentCollectionRequest request, TaskContext task)
        throws ApiException {
        var data = reader.find(request.application(), task);
        if (!data.snapshot().revisionSha256().equals(request.expectedRevisionSha256()))
            return failed("DEPLOYMENT_COLLECTION_REVISION_CHANGED");
        planner.build(data, request.bindingIds());
        return success(ledger.reviewCollection(request.application(), request.expectedRevisionSha256(),
            request.bindingIds(), task));
    }

    /** Collects release, image and selected data only once under original maintenance. / 仅在原维护下采集一次发布、镜像与所选数据。
     * @param id original handoff / 原交接
     * @param task original caller task / 原调用任务
     * @return complete sealed remote artifacts / 完整封印的远端产物
     * @throws ApiException if access or durable evidence cannot be checked / 访问或持久依据无法核对时 */
    public OperationResult<DeploymentCollection> collect(UUID id, TaskContext task) throws ApiException {
        var entry = required(id, task);
        if (!entry.request().path("kind").asText().equals("COLLECTION"))
            return failed("DEPLOYMENT_COLLECTION_KIND_CHANGED");
        if (entry.publicValue().state() == DeploymentHandoffState.COLLECTED)
            return success(new DeploymentCollection(entry.publicValue(), artifacts(entry.recovery())));
        if (entry.publicValue().state() != DeploymentHandoffState.REVIEWED)
            return unknown("DEPLOYMENT_COLLECTION_RECONCILE_REQUIRED");
        var data = current(entry, task);
        var selected = new ArrayList<String>();
        for (var value : entry.request().path("bindings"))
            selected.add(value.asText());
        var plans = planner.build(data, selected);
        boolean quiescence = selected.stream().anyMatch(idValue -> data.snapshot().bindings().stream()
            .anyMatch(binding -> binding.id().equals(idValue) && binding.needsQuiescence()));
        String operation = entry.recovery().path("operation").asText();
        if (!operation.matches("backup-[0-9a-f]{32}"))
            return unknown("DEPLOYMENT_COLLECTION_TOKEN_INVALID");
        try (var session = context.linux().connect(data.snapshot().application().server(), task)) {
            var helper = new DeploymentManagedHelper(session, context.interaction(),
                data.snapshot().application().server(), task, data.snapshot().revisionSha256()).probe();
            if (helper.outcome() != Outcome.SUCCEEDED)
                return copy(helper);
            var publication = new DeploymentPublicationChannel(session, context.interaction(),
                data.snapshot().application().server(), task, data.snapshot().revisionSha256());
            var channel = new DeploymentHandoffChannel(session, context.interaction(),
                data.snapshot().application().server(), task, data.snapshot().revisionSha256());
            var work = new DeploymentCollectionWork(ledger, data, task, entry, plans, channel, publication);
            var begun = work.begin(quiescence);
            if (begun.outcome() != Outcome.SUCCEEDED)
                return copy(begun);
            work.acquireMaintenance();
            work.quiesce();
            return success(work.export());
        } catch (ApiException | RuntimeException failure) {
            var persisted = required(id, task);
            if (persisted.publicValue().state() == DeploymentHandoffState.REVIEWED)
                throw failure;
            retainFailure(persisted, task);
            return unknown("DEPLOYMENT_COLLECTION_CALL_UNCONFIRMED");
        }
    }

    /** Restores only units stopped by this handoff and releases only owned markers. / 仅恢复本交接停止的单元并释放其所属标记。
     * @param id original handoff / 原交接
     * @param task original caller task / 原调用任务
     * @return durable cleanup / 持久清理
     * @throws ApiException if authorization or evidence cannot be confirmed / 授权或依据无法确认时 */
    public OperationResult<DeploymentHandoff> finish(UUID id, TaskContext task) throws ApiException {
        var entry = required(id, task);
        if (!entry.request().path("kind").asText().equals("COLLECTION"))
            return failed("DEPLOYMENT_COLLECTION_KIND_CHANGED");
        if (entry.publicValue().state() == DeploymentHandoffState.FINISHED)
            return success(entry.publicValue());
        if (!Set.of(DeploymentHandoffState.MAINTENANCE, DeploymentHandoffState.COLLECTED,
                DeploymentHandoffState.UNKNOWN, DeploymentHandoffState.COMPENSATING)
            .contains(entry.publicValue().state()))
            return failed("DEPLOYMENT_COLLECTION_PHASE_INVALID");
        try {
            var data = current(entry, task);
            var selected = new ArrayList<String>();
            entry.request().path("bindings").forEach(value -> selected.add(value.asText()));
            var plans = planner.build(data, selected);
            if (!entry.recovery().path("units").isObject())
                return unknown("DEPLOYMENT_COLLECTION_BEFORE_MISSING");
            try (var session = context.linux().connect(data.snapshot().application().server(), task)) {
                if (new DeploymentManagedHelper(session, context.interaction(), data.snapshot().application().server(),
                    task, data.snapshot().revisionSha256()).probe().outcome() != Outcome.SUCCEEDED)
                    return uncertain(entry, ledger.recovery(entry), task, "DEPLOYMENT_COLLECTION_HELPER_UNCONFIRMED");
                var publication = new DeploymentPublicationChannel(session, context.interaction(),
                    data.snapshot().application().server(), task, data.snapshot().revisionSha256());
                var channel = new DeploymentHandoffChannel(session, context.interaction(),
                    data.snapshot().application().server(), task, data.snapshot().revisionSha256());
                return success(
                    new DeploymentCollectionWork(ledger, data, task, entry, plans, channel, publication).finish());
            }
        } catch (ApiException | RuntimeException failure) {
            retainFailure(required(id, task), task);
            return unknown("DEPLOYMENT_COLLECTION_FINISH_UNCONFIRMED");
        }
    }

    /** Reads a previously uncertain collection without replaying artifact creation. / 不重放产物创建地读取先前未知采集。
     * @param id original handoff / 原交接
     * @param task original caller task / 原调用任务
     * @return same durable phase or confirmed artifacts / 同一持久阶段或已确认产物
     * @throws ApiException if access fails / 访问失败时 */
    public OperationResult<DeploymentHandoff> reconcile(UUID id, TaskContext task) throws ApiException {
        var entry = required(id, task);
        if (!entry.request().path("kind").asText().equals("COLLECTION"))
            return failed("DEPLOYMENT_COLLECTION_KIND_CHANGED");
        if (entry.publicValue().state() != DeploymentHandoffState.UNKNOWN)
            return success(entry.publicValue());
        try {
            var data = current(entry, task);
            var selected = new ArrayList<String>();
            for (var value : entry.request().path("bindings"))
                selected.add(value.asText());
            var plans = planner.build(data, selected);
            String operation = entry.recovery().path("operation").asText();
            if (!operation.matches("backup-[0-9a-f]{32}"))
                return unknown("DEPLOYMENT_COLLECTION_TOKEN_INVALID");
            var attempts = new HashSet<String>();
            for (var value : entry.recovery().path("attemptedArtifacts"))
                attempts.add(value.asText());
            ObjectNode facts = ledger.recovery(entry);
            var confirmed = facts.putArray("artifacts");
            try (var session = context.linux().connect(data.snapshot().application().server(), task)) {
                var helper = new DeploymentManagedHelper(session, context.interaction(),
                    data.snapshot().application().server(), task, data.snapshot().revisionSha256()).probe();
                if (helper.outcome() != Outcome.SUCCEEDED)
                    return unknown("DEPLOYMENT_COLLECTION_HELPER_UNCONFIRMED");
                var channel = new DeploymentHandoffChannel(session, context.interaction(),
                    data.snapshot().application().server(), task, data.snapshot().revisionSha256());
                var publication = new DeploymentPublicationChannel(session, context.interaction(),
                    data.snapshot().application().server(), task, data.snapshot().revisionSha256());
                var verifiedCurrent = DeploymentCollectionEvidence.current(channel, data);
                if (verifiedCurrent.outcome() != Outcome.SUCCEEDED)
                    return unknown("DEPLOYMENT_COLLECTION_CURRENT_UNCONFIRMED");
                for (var component : data.graph().components()) {
                    var saved = entry.recovery().path("units").path(component.componentId());
                    if (saved.isMissingNode())
                        return unknown("DEPLOYMENT_COLLECTION_BEFORE_MISSING");
                    if (saved.path("maintenanceAttempted").asBoolean(false)) {
                        var marker = marker(channel, component, operation);
                        if (marker.isEmpty() || !marker.orElseThrow())
                            return unknown("DEPLOYMENT_COLLECTION_MAINTENANCE_UNCONFIRMED");
                    }
                    if (saved.path("stopAttempted").asBoolean(false)) {
                        var observed = publication.observe(component.unit(), component.reviewedRuntime());
                        if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty()
                            || !observed.value().orElseThrow().state().name().equals("STOPPED"))
                            return unknown("DEPLOYMENT_COLLECTION_QUIESCENCE_UNCONFIRMED");
                    }
                }
                for (var plan : plans) {
                    String key = plan.componentId() + ":" + plan.kind() + ":" + plan.resource();
                    if (!attempts.contains(key))
                        return unknown("DEPLOYMENT_COLLECTION_ARTIFACT_NOT_ATTEMPTED");
                    var remote = channel.execute(
                        plan.kind().equals("database") ? "handoff-database-status" : "handoff-backup-status",
                        plan.statusArguments(operation, data.snapshot().application().id()), new byte[0],
                        Duration.ofMinutes(10), 4096);
                    var value = parseStatus(remote, plan, operation, data.snapshot().application().id());
                    if (value.isEmpty())
                        return unknown("DEPLOYMENT_COLLECTION_ARTIFACT_UNCONFIRMED");
                    var artifact = value.orElseThrow();
                    confirmed.addObject().put("id", artifact.id()).put("bindingId", artifact.bindingId())
                        .put("remotePath", artifact.remotePath()).put("byteCount", artifact.byteCount())
                        .put("sha256", artifact.sha256());
                }
            }
            entry = advance(entry, DeploymentHandoffState.COLLECTED, facts.put("reconciledReadOnly", true), task);
            return success(entry.publicValue());
        } catch (ApiException | RuntimeException failure) {
            retainFailure(required(id, task), task);
            return unknown("DEPLOYMENT_COLLECTION_RECONCILE_UNCONFIRMED");
        }
    }

    /** Verifies the exact active release before any source step. / 任一来源步骤前核实精确活动发布。
     * @param entry authorized original handoff / 已授权原交接
     * @param task authentic original task / 真实原任务
     * @return complete current original release / 完整当前原发布
     * @throws ApiException if source identity or revision differs / 来源身份或修订不符时 */
    private ApplicationFacts current(DeploymentHandoffLedger.Entry entry, TaskContext task) throws ApiException {
        var data = reader.find(entry.publicValue().application(), task);
        if (!data.snapshot().revisionSha256().equals(entry.publicValue().revisionSha256()))
            throw problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_COLLECTION_REVISION_CHANGED");
        return data;
    }

    /** Reads one original-task handoff. / 读取一项原任务交接。
     * @param id original handoff ID / 原交接ID
     * @param task authentic original task / 真实原任务
     * @return authorized original row / 已授权原行
     * @throws ApiException if original identity or access is unavailable / 原身份或访问不可用时 */
    private DeploymentHandoffLedger.Entry required(UUID id, TaskContext task) throws ApiException {
        return ledger.find(id, task)
            .orElseThrow(() -> problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_COLLECTION_HANDOFF_ABSENT"));
    }

    /** Advances and reloads a durable step. / 推进并重读一项持久步骤。
     * @param entry authorized original row / 已授权原行
     * @param next confirmed next phase / 已确证下一阶段
     * @param facts original private recovery evidence / 原私有恢复依据
     * @param task authentic original task / 真实原任务
     * @return reloaded original row / 重读的原行
     * @throws ApiException if original persistence is unconfirmed / 原持久化未确证时 */
    private DeploymentHandoffLedger.Entry advance(DeploymentHandoffLedger.Entry entry, DeploymentHandoffState next,
                                                  ObjectNode facts, TaskContext task) throws ApiException {
        ledger.advance(entry, entry.publicValue().state(), next, facts, Optional.empty(), task);
        return required(entry.publicValue().id(), task);
    }

    /** Retains an uncertain operation instead of repeating it. / 保留未知操作而不重复发送。
     * @param <T> payload type / 载荷类型
     * @param entry authorized original row / 已授权原行
     * @param facts original private recovery evidence / 原私有恢复依据
     * @param task authentic original task / 真实原任务
     * @param event original uncertainty event / 原未知事件
     * @return retained unknown result / 留存未知结果
     * @throws ApiException if original persistence is unavailable / 原持久化不可用时 */
    private <T> OperationResult<T> uncertain(DeploymentHandoffLedger.Entry entry, ObjectNode facts, TaskContext task,
                                             String event) throws ApiException {
        if (entry.publicValue().state() != DeploymentHandoffState.UNKNOWN)
            advance(entry, DeploymentHandoffState.UNKNOWN, facts, task);
        return unknown(event);
    }

    /** Retains post-effect call or close failure under the original task. / 在原任务下保留副作用后的调用或关闭失败。
     * @param entry authorized original row / 已授权原行
     * @param task authentic original task / 真实原任务
     * @throws ApiException if original persistence is unavailable / 原持久化不可用时 */
    private void retainFailure(DeploymentHandoffLedger.Entry entry, TaskContext task) throws ApiException {
        var phase = entry.publicValue().state();
        if (phase != DeploymentHandoffState.UNKNOWN && phase != DeploymentHandoffState.FINISHED)
            advance(entry, DeploymentHandoffState.UNKNOWN, ledger.recovery(entry).put("callOutcomeUnconfirmed", true),
                task);
    }

}
