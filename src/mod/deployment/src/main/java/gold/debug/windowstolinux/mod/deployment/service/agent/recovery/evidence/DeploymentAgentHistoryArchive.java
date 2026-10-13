package gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.check.DeploymentReleaseChecker;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionCodec;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentOperationReceipts;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.db.transaction.DeploymentStorageTransaction;

/** Removes one actually settled Agent operation after preserving its durable receipt. / 保留持久凭据后移除一项实际结清的自主操作。 */
public final class DeploymentAgentHistoryArchive {
    private final ModContext context;

    public DeploymentAgentHistoryArchive(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Reads a prior atomic settlement before any original evidence or remote dispatch. / 任一原依据或远端发送前读取先前原子结清。 */
    public Optional<OperationResult<Void>> retained(TaskContext task) throws Exception {
        DeploymentSchemaIdentity.requireIdentity(context.db().identity());
        Optional<DeploymentOperationReceipts.Receipt> receipt;
        try (var connection = context.db().openConnection(task)) {
            receipt = DeploymentOperationReceipts.read(connection, task.id());
            if (receipt.isPresent()) {
                if (receipt.orElseThrow().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT)
                    throw new SQLException("Original settlement belongs to another execution mode");
                DeploymentOperationReceipts.validateBusiness(connection, receipt.orElseThrow());
            }
        }
        if (receipt.isEmpty()) return Optional.empty();
        if (!context.tasks().resourcesReleased(task))
            return Optional.of(unknown("DEPLOYMENT_AGENT_SETTLED_RESOURCES_RETAINED"));
        return Optional.of(receipt.orElseThrow().result());
    }

    /** Called only after the original recovery or final successful publication proved physical cleanup. / 仅在原恢复或最终成功发布证实物理收尾后调用。 */
    public OperationResult<Void> settle(TaskContext task, OperationResult<Void> observed) throws Exception {
        if (observed.outcome() == TaskResult.UNKNOWN)
            throw new SQLException("Unresolved Agent work cannot be removed");
        var prior = retained(task);
        if (prior.isPresent()) {
            if (prior.orElseThrow().outcome() != observed.outcome())
                throw new SQLException("Original settled result changed");
            return prior.orElseThrow();
        }
        if (!context.tasks().resourcesReleased(task))
            return unknown("DEPLOYMENT_AGENT_SETTLEMENT_RESOURCES_RETAINED");
        return archive(task, task.id(), observed, false);
    }

    /** Cleans an ended original only after the platform returns its owned durable completion proof. / 仅在平台返回所属持久结束证明后清理已结束原任务。 */
    public OperationResult<Void> archiveRecoveredHistory(TaskContext current, UUID selected) throws Exception {
        DeploymentSchemaIdentity.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(current)) {
            var receipt = DeploymentOperationReceipts.read(connection, selected);
            if (receipt.isPresent()) {
                if (receipt.orElseThrow().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT)
                    throw new SQLException("Original history belongs to another mode");
                DeploymentOperationReceipts.validateBusiness(connection, receipt.orElseThrow());
                return receipt.orElseThrow().result();
            }
        }
        var archived = context.tasks().findArchived(selected, current)
            .orElseThrow(() -> new SQLException("Original platform completion proof is unavailable"));
        if (!archived.id().equals(selected)
            || !archived.type().equals(gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.TASK_TYPE)
            || archived.state() != gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED
            || archived.outcome().orElse(null) != TaskResult.SUCCEEDED || !archived.blockers().isEmpty())
            throw new SQLException("Historical cleanup requires a proven successful original closure");
        // The platform certifies recovery and resource closure; the original deployment result remains local.
        OperationResult<Void> original;
        try (var connection = context.db().openConnection(current)) {
            var saved = DeploymentAgentActionLog.find(connection, selected)
                .orElseThrow(() -> new SQLException("Original Agent history is absent"));
            try (var query = connection.prepareStatement("SELECT * FROM deployment_execution WHERE platform_task_id=?")) {
                query.setObject(1, selected);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        var execution = DeploymentExecutionStore.row(rows);
                        var outcome = execution.outcome().filter(value -> value != TaskResult.UNKNOWN)
                            .orElseThrow(() -> new SQLException("Original deployment result is unresolved"));
                        var formal = DeploymentJsonDocument.parse(execution.resultJson().orElseThrow());
                        Optional<Problem> problem = formal.path("problemCode").isNull() ? Optional.empty() : Optional.of(new Problem(
                            ProblemCode.valueOf(DeploymentJsonDocument.string(formal, "problemCode")),
                            DeploymentJsonDocument.string(formal, "eventCode"), "Original deployment settlement", Map.of(), Optional.empty()));
                        original = new OperationResult<>(outcome, Optional.empty(), problem);
                    } else {
                        TaskResult outcome;
                        try {
                            outcome = TaskResult.valueOf(saved.state());
                        } catch (IllegalArgumentException invalid) {
                            throw new SQLException("Original deployment result is unresolved", invalid);
                        }
                        if (!Set.of(TaskResult.FAILED, TaskResult.CANCELLED).contains(outcome))
                            throw new SQLException("Original pre-publication result is unresolved");
                        original = new OperationResult<>(outcome, Optional.empty(), Optional.of(new Problem(
                            outcome == TaskResult.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
                            "DEPLOYMENT_AGENT_PREFORMAL_RECONCILED", "Original compensation settled", Map.of(), Optional.empty())));
                    }
                }
            }
        }
        return archive(current, selected, original, true);
    }

    private OperationResult<Void> archive(TaskContext task, UUID selected, OperationResult<Void> observed, boolean historical) throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<DeploymentOperationReceipts.Receipt>();
        if (!historical && !context.tasks().resourcesReleased(task))
            throw new SQLException("Original resources changed before opening the archive connection");
        var connection = context.db().openConnection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            try (var lock = connection.prepareStatement("SELECT task_id FROM deployment_agent_task WHERE task_id=? FOR UPDATE")) {
                lock.setString(1, selected.toString());
                try (var rows = lock.executeQuery()) {
                    if (!rows.next()) {
                        var settled = DeploymentOperationReceipts.read(connection, selected)
                            .orElseThrow(() -> new SQLException("Original Agent work is absent without a settlement receipt"));
                        if (settled.mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT || settled.outcome() != observed.outcome())
                            throw new SQLException("Original settlement identity or result changed");
                        DeploymentOperationReceipts.validateBusiness(connection, settled);
                        result.set(settled);
                        return;
                    }
                }
            }
            try (var lock = connection.prepareStatement("SELECT event_id FROM deployment_agent_event WHERE task_id=? ORDER BY event_id FOR UPDATE")) {
                lock.setString(1, selected.toString());
                try (var rows = lock.executeQuery()) {
                    while (rows.next()) { /* Own every original event through commit. */ }
                }
            }
            var saved = DeploymentAgentActionLog.find(connection, selected).orElseThrow();
            var events = DeploymentAgentActionLog.events(connection, selected);
            if (!saved.state().equals(observed.outcome().name())
                || gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentHistoryReader.pending(events).isPresent())
                throw new SQLException("Original Agent evidence remains unresolved");
            boolean recovered = events.stream().anyMatch(event -> event.type().equals("AI_RECOVERY_SETTLED")
                && event.action().equals(selected.toString())
                && event.detail().equals(observed.outcome().name() + ":COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED"));
            DeploymentExecutionRecord execution = null;
            try (var query = connection.prepareStatement("SELECT * FROM deployment_execution WHERE platform_task_id=? FOR UPDATE")) {
                query.setObject(1, selected);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) execution = DeploymentExecutionStore.row(rows);
                    if (rows.next()) throw new SQLException("Original formal operation is not unique");
                }
            }
            boolean deliveryProof = events.stream().anyMatch(event -> event.type().equals(DeploymentAgentActionLog.DELIVERY_RECOVERY_SETTLED));
            if (deliveryProof || !DeploymentAgentActionLog.effectsSettled(events)) {
                if (!recovered || execution == null || observed.outcome() != TaskResult.SUCCEEDED)
                    throw new SQLException("Original delivery recovery is unconfirmed");
                var template = new DeploymentExecutionStore(context.db()).publicationTemplate(execution).orElseThrow();
                var proof = DeploymentAgentActionLog.originalDeliveryProof(selected, saved, execution, template, events).orElseThrow();
                DeploymentReleaseChecker.linked(connection, execution);
                if (!deliveryProof) DeploymentAgentActionLog.appendDeliveryProof(connection, selected, proof);
                events = DeploymentAgentActionLog.events(connection, selected);
            }
            if (!DeploymentAgentActionLog.effectsSettled(events))
                throw new SQLException("Original Agent effects remain unresolved");
            var evidence = DeploymentJsonDocument.object().put("formatVersion", 1).put("task", selected.toString())
                .put("target", saved.identity().targetDigest()).put("configuration", saved.identity().configurationDigest());
            var history = evidence.putArray("events");
            for (var event : events)
                history.addObject().put("id", event.id()).put("type", event.type())
                    .put("action", event.action()).put("detail", event.detail());
            UUID operation = selected;
            Optional<String> application = Optional.empty();
            Optional<gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseIdentity> release = Optional.empty();
            Optional<ProblemCode> problem = observed.problem().map(Problem::code);
            String eventCode = observed.problem().map(Problem::eventCode).orElse("DEPLOYMENT_AGENT_SETTLED");
            if (execution != null) {
                if (!execution.identity().taskId().equals(selected) || execution.identity().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT
                    || !execution.identity().server().id().equals(saved.identity().server()) || !execution.phase().equals("FINISHED")
                    || !execution.cleanupComplete() || !execution.outcome().equals(Optional.of(observed.outcome()))
                    || !DeploymentExecutionCodec.supported(execution))
                    throw new SQLException("Original formal operation is not actually settled");
                operation = execution.identity().executionId();
                evidence.put("request", digest(execution.requestJson())).put("recovery", digest(execution.recoveryJson()));
                var formal = DeploymentJsonDocument.parse(execution.resultJson().orElseThrow());
                eventCode = DeploymentJsonDocument.string(formal, "eventCode");
                problem = formal.path("problemCode").isNull() ? Optional.empty() : Optional.of(ProblemCode.valueOf(DeploymentJsonDocument.string(formal, "problemCode")));
                String publishedApplication = execution.identity().applicationId();
                if (events.stream().noneMatch(e -> e.type().equals("AI_PUBLICATION_CLEANED")
                    && e.action().equals(publishedApplication) && e.detail().equals("ABSENT")))
                    throw new SQLException("Original candidate cleanup is unconfirmed");
                if (observed.outcome() == TaskResult.SUCCEEDED) {
                    if (!execution.businessCommitted())
                        throw new SQLException("Original publication or candidate cleanup is unconfirmed");
                    DeploymentReleaseChecker.linked(connection, execution);
                    application = Optional.of(execution.identity().applicationId());
                    release = DeploymentExecutionCodec.release(execution);
                } else {
                    if (execution.businessCommitted())
                        throw new SQLException("Compensation cannot remove a committed success");
                    if (!recovered && !historical)
                        throw new SQLException("Original compensation recovery is unconfirmed");
                }
            } else if (observed.outcome() == TaskResult.SUCCEEDED || !recovered)
                throw new SQLException("Original pre-publication settlement proof is absent");
            var receipt = new DeploymentOperationReceipts.Receipt(operation, selected, new ServerId(saved.identity().server()),
                DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, observed.outcome(), application, release,
                problem, eventCode, digest(evidence.toString()), Instant.now());
            DeploymentOperationReceipts.store(connection, receipt);
            delete(connection, "DELETE FROM deployment_agent_event WHERE task_id=?", selected.toString(), events.size());
            delete(connection, "DELETE FROM deployment_agent_task WHERE task_id=?", selected.toString(), 1);
            if (execution != null)
                delete(connection, "DELETE FROM deployment_execution WHERE execution_id=? AND platform_task_id=?", operation, selected, 1);
            result.set(receipt);
        });
        return result.get().result();
    }

    private static void delete(Connection connection, String sql, Object id, int expected) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            if (statement.executeUpdate() != expected)
                throw new SQLException("Exact original cleanup was not confirmed");
        }
    }

    private static void delete(Connection connection, String sql, Object operation, Object task, int expected) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, operation);
            statement.setObject(2, task);
            if (statement.executeUpdate() != expected)
                throw new SQLException("Exact original operation removal was not confirmed");
        }
    }

    private static String digest(String value) throws SQLException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new SQLException("Evidence digest is unavailable", unavailable);
        }
    }

    private static OperationResult<Void> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Original Agent settlement remains unconfirmed", Map.of(), Optional.empty())));
    }
}
