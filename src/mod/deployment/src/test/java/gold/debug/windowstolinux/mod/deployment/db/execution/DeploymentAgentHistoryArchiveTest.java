package gold.debug.windowstolinux.mod.deployment.db.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseIdentity;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseOrigin;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseStore;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentHistoryArchive;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationReader;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentRecovery;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real H2/CLASS transaction fixtures; no model or SSH peer is started. / 真实H2/CLASS事务夹具，不启动模型或SSH替身。 */
class DeploymentAgentHistoryArchiveTest extends DeploymentAgentArchiveFixture {
    @Test
    void originalRecoveryProducerArchivesSuccessKeepsSharedBusinessAndNeverDispatchesAfterReopen() throws Exception {
        enable();
        var originals = new ArrayList<UUID>();
        var creating = new AtomicReference<UUID>();
        var release = new AtomicReference<DeploymentReleaseIdentity>();
        var foreign = new AtomicReference<UUID>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            for (int attempt = 0; attempt < 2; attempt++)
                run(runtime, task -> {
                    var context = runtime.modContexts().get(OWNER).context();
                    if (foreign.get() == null) {
                        var held = context.tasks().submit(new gold.debug.windowstolinux.appapi.task.model.TaskRequest("foreign-fixture", "Foreign retained history", List.of(), false), other -> {
                            var log = new DeploymentAgentActionLog(context.db());
                            log.create(other, new DeploymentAgentActionLog.Identity("foreign", "c".repeat(64), "d".repeat(64), "MANUAL_REVIEW", true));
                            log.state(other, "SUCCEEDED");
                            return success();
                        });
                        foreign.set(held.id());
                        assertEquals(TaskResult.SUCCEEDED, held.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                    }
                    var id = originalSuccess(runtime, task);
                    var log = new DeploymentAgentActionLog(context.db());
                    assertTrue(log.events(task).stream().anyMatch(e -> e.type().equals("STATE") && e.detail().equals("UNKNOWN")));
                    assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                    assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                    assertTrue(log.events(task).stream().noneMatch(e -> e.type().equals("DELIVERY_RESULT") || e.type().equals(DeploymentAgentActionLog.DELIVERY_RECOVERY_SETTLED)));
                    var record = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
                    var business = new DeploymentReleaseStore(context.db()).list(task, "application").getFirst();
                    if (creating.get() == null) {
                        creating.set(id);
                        release.set(business.identity());
                    } else {
                        assertEquals(creating.get(), business.executionId());
                        assertEquals(release.get(), business.identity());
                        assertTrue(!record.identity().executionId().equals(business.executionId()));
                    }
                    var archive = new DeploymentAgentHistoryArchive(context);
                    assertEquals(TaskResult.SUCCEEDED, archive.settle(task, success()).outcome());
                    assertEquals(TaskResult.SUCCEEDED, archive.settle(task, success()).outcome());
                    assertTrue(log.find(task).isEmpty());
                    assertTrue(new DeploymentExecutionStore(context.db()).findForTask(task).isEmpty());
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_event WHERE task_id='" + task.id() + "'"));
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_task WHERE task_id='" + foreign.get() + "'"));
                    assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(task.id(), task).isPresent());
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                    originals.add(task.id());
                    return success();
                });
            assertEquals(3, scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE state='ACTIVE'"));
            valid(runtime);
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var strict = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (Set.of("linux", "ai", "file").contains(method.getName()))
                        throw new AssertionError("Receipt reuse cannot dispatch " + method.getName());
                    return invoke(method, context, args);
                });
                for (UUID original : originals) {
                    assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(strict).archiveRecoveredHistory(task, original).outcome());
                    try (var connection = context.db().openConnection(task)) {
                        var receipt = DeploymentOperationReceipts.read(connection, original).orElseThrow();
                        assertEquals(release.get(), receipt.release().orElseThrow());
                        DeploymentOperationReceipts.validateBusiness(connection, receipt);
                    }
                    assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(original, task).isPresent());
                }
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_task WHERE task_id='" + foreign.get() + "'"));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void originalDeliveryLostCommitAcknowledgementReusesTheCommittedReceiptWithoutRepeatingRecovery() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                originalSuccess(runtime, task);
                assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                var uncertain = assertThrows(ApiException.class, () -> new DeploymentAgentHistoryArchive(withDatabase(context, fault(context.db(), "lost_ack"))).settle(task, success()));
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", uncertain.problem().eventCode());
                assertTrue(new DeploymentAgentActionLog(context.db()).find(task).isEmpty());
                assertTrue(new DeploymentExecutionStore(context.db()).findForTask(task).isEmpty());
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"proof", "receipt", "events", "task", "execution", "before_commit"})
    void originalDeliveryProofAndAllCleanupWritesRollBackTogether(String point) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                originalSuccess(runtime, task);
                assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                var log = new DeploymentAgentActionLog(context.db());
                var events = log.events(task);
                var formal = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
                assertThrows(Exception.class, () -> new DeploymentAgentHistoryArchive(withDatabase(context, fault(context.db(), point))).settle(task, success()));
                assertEquals(events, log.events(task));
                assertEquals(formal, new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"command", "prepare", "capability", "inspect", "multi_delivery", "later_same_action", "bad_proof", "unknown_state", "failed_state", "busy", "source", "configuration", "layout", "revision", "seal", "invalidated", "missing_component", "foreign_candidate", "review_chain"})
    void originalDeliveryRecoveryRejectsUnresolvedOrChangedEvidenceWithoutDeleting(String kind) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                originalSuccess(runtime, task);
                assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                var log = new DeploymentAgentActionLog(context.db());
                var original = log.events(task);
                var delivery = original.stream().filter(e -> e.type().equals("DELIVERY_EXECUTING")).findFirst().orElseThrow();
                gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint held = null;
                switch (kind) {
                    case "command", "prepare", "capability" ->
                        log.event(task, kind.toUpperCase(Locale.ROOT) + "_EXECUTING", "pending", "original");
                    case "inspect" -> log.event(task, "INSPECT_REQUESTED", "original", "pending");
                    case "multi_delivery", "later_same_action" ->
                        log.event(task, "DELIVERY_EXECUTING", kind.equals("later_same_action") ? delivery.action() : "f".repeat(64), "1");
                    case "bad_proof" ->
                        log.event(task, DeploymentAgentActionLog.DELIVERY_RECOVERY_SETTLED, delivery.action(), "{}");
                    case "unknown_state", "failed_state" ->
                        log.state(task, kind.equals("unknown_state") ? "UNKNOWN" : "FAILED");
                    case "busy" ->
                        held = context.tasks().beginCheckpoint(task, "fixture.held", "{\"formatVersion\":1}");
                    case "invalidated" -> log.event(task, "ARTIFACT_INVALIDATED", "web", "original");
                    case "review_chain" ->
                        log.event(task, "RECOVERY_REVIEW_REBOUND", "b".repeat(64), "broken predecessor");
                    default -> {
                        String type = switch (kind) {
                            case "source" -> "CURRENT_SOURCE";
                            case "configuration" -> "WORKSPACE_QUOTA";
                            case "layout" -> "REMOTE_LAYOUT";
                            case "revision" -> "DELIVERY_EXECUTING";
                            case "seal", "missing_component" -> "SEALED_ARTIFACT";
                            case "foreign_candidate" -> "CANDIDATE";
                            default -> throw new AssertionError(kind);
                        };
                        var selected = original.stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow();
                        String detail = kind.equals("revision") ? "3" : kind.equals("configuration")
                            ? selected.detail().replace("b".repeat(64), "e".repeat(64)) : kind.equals("layout")
                            ? selected.detail().replace("\"layoutSha256\":\"", "\"layoutSha256\":\"f") : kind.equals("foreign_candidate")
                            ? "foreign-" + "f".repeat(16) : "9".repeat(64);
                        try (var connection = context.db().openConnection(task); var update = connection.prepareStatement(
                            kind.equals("missing_component") ? "DELETE FROM deployment_agent_event WHERE event_id=? AND task_id=?"
                                : "UPDATE deployment_agent_event SET event_detail=? WHERE event_id=? AND task_id=?")) {
                            int index = 1;
                            if (!kind.equals("missing_component")) update.setString(index++, detail);
                            update.setLong(index++, selected.id());
                            update.setString(index, task.id().toString());
                            assertEquals(1, update.executeUpdate());
                        }
                    }
                }
                var modified = log.events(task);
                try {
                    if (held == null)
                        assertThrows(Exception.class, () -> new DeploymentAgentHistoryArchive(context).settle(task, success()), kind);
                    else
                        assertEquals(TaskResult.UNKNOWN, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                    assertEquals(modified, log.events(task));
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_execution"));
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                } finally {
                    if (held != null) held.close();
                    restoreEvents(context, task, original);
                    log.state(task, "SUCCEEDED");
                }
                assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "uncommitted", "unclean", "foreign_task", "template_release", "whole_release", "invalidated_before_delivery"})
    void originalDeliveryProofRequiresTheExactFinishedSuccessfulFormalTemplate(String kind) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                originalSuccess(runtime, task);
                assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                var log = new DeploymentAgentActionLog(context.db());
                var store = new DeploymentExecutionStore(context.db());
                var formal = store.findForTask(task).orElseThrow();
                var template = store.publicationTemplate(formal).orElseThrow();
                var identity = kind.equals("foreign_task") ? new DeploymentExecutionIdentity(formal.identity().executionId(), UUID.randomUUID(), Optional.empty(), "application", new ServerId("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, "a".repeat(64)) : formal.identity();
                var invalid = new DeploymentExecutionRecord(identity, kind.equals("unclean") || kind.equals("unknown") ? "EXECUTING" : formal.phase(),
                    Optional.of(kind.equals("unknown") ? TaskResult.UNKNOWN : TaskResult.SUCCEEDED), formal.requestJson(), formal.recoveryJson(), formal.resultJson(),
                    !kind.equals("uncommitted") && !kind.equals("unknown"), !kind.equals("unclean") && !kind.equals("unknown"), formal.createdAt(), formal.updatedAt());
                if (kind.equals("template_release")) {
                    var changed = template.units().stream().map(u -> new DeploymentUnitCommit(u.unit(), u.runtime(), new DeploymentCurrentRelease(u.unit().id(), "f".repeat(64), u.release().publishedAt()), u.configuration(), u.secrets())).toList();
                    template = new DeploymentPublishProposal(template.graph(), changed);
                }
                if (kind.equals("whole_release")) {
                    var graph = template.graph();
                    template = new DeploymentPublishProposal(new DeploymentApplicationGraph(graph.applicationId(), graph.healthComponentId(),
                        new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Process(9, 1), graph.components()), template.units());
                    var request = (com.fasterxml.jackson.databind.node.ObjectNode) DeploymentJsonDocument.parse(formal.requestJson(), 144 * 1_048_576);
                    request.set("publicationTemplate", DeploymentJsonDocument.parse(gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishTemplateCodec.encodeTemplate(template),
                        gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishTemplateCodec.LIMIT));
                    invalid = new DeploymentExecutionRecord(identity, formal.phase(), formal.outcome(), request.toString(), formal.recoveryJson(), formal.resultJson(),
                        formal.businessCommitted(), formal.cleanupComplete(), formal.createdAt(), formal.updatedAt());
                }
                var events = log.events(task);
                if (kind.equals("invalidated_before_delivery")) {
                    var changed = new ArrayList<DeploymentAgentActionLog.Event>();
                    boolean inserted = false;
                    for (var event : events) {
                        changed.add(new DeploymentAgentActionLog.Event(event.id() * 10, event.type(), event.action(), event.detail()));
                        if (!inserted && event.type().equals("SEALED_ARTIFACT")) {
                            changed.add(new DeploymentAgentActionLog.Event(event.id() * 10 + 1, "ARTIFACT_INVALIDATED", event.action(), "command"));
                            inserted = true;
                        }
                    }
                    events = changed;
                }
                var selected = template;
                var selectedFormal = invalid;
                var selectedEvents = events;
                assertThrows(Exception.class, () -> DeploymentAgentActionLog.originalDeliveryProof(task.id(), log.find(task).orElseThrow(), selectedFormal, selected, selectedEvents));
                assertEquals(formal, store.findForTask(task).orElseThrow());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                new DeploymentAgentHistoryArchive(context).settle(task, success());
                return success();
            });
        }
    }

    private UUID originalSuccess(PlatformRuntime runtime, TaskContext task) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var log = new DeploymentAgentActionLog(context.db());
        log.create(task, agentIdentity(), Optional.of("a".repeat(64)));
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.record(log, task, new ServerId("server"), layout);
        String archive = "c".repeat(64), source = "d".repeat(64), current = "e".repeat(64), artifact = "f".repeat(64), plan = "1".repeat(64);
        log.event(task, "SOURCE", archive, source);
        log.event(task, "TARGET_PROFILE", "", "a".repeat(64));
        log.event(task, "PLAN_APPLICATION", "", "application");
        log.event(task, "PLAN", "", plan);
        var parts = graph("application").components().stream().map(p -> new DeploymentApplicationGraph.Part(p.componentId(),
            DeploymentUnitIdentity.forManaged("application-" + p.componentId(), new ServerId("server"), p.unit().ownershipManifestSha256()), p.runtime(), p.dependencies(), p.reviewedRuntime(), p.dataPaths(),
            new DeploymentResourceBindings(p.resources().fileBindings(), Optional.of(List.of())))).toList();
        var graph = new DeploymentApplicationGraph("application", "web", graph("application").healthCheck(), parts);
        var commits = publications(graph, 1, List.of()).stream().map(u -> {
            var p = graph.components().stream().filter(v -> v.unit().equals(u.unit())).findFirst().orElseThrow();
            String digest = DeploymentAgentInputs.hash("agent-release:" + artifact + ":" + current + ":" + u.configuration().sha256() + ":" + p.reviewedRuntime() + ":" + p.resources());
            return new DeploymentUnitCommit(u.unit(), u.runtime(), new DeploymentCurrentRelease(u.unit().id(), digest, u.release().publishedAt()), u.configuration(), u.secrets());
        }).toList();
        for (var part : parts) {
            String id = part.componentId();
            var workspace = new DeploymentRemoteWorkspace(part.unit().id(), archive, layout);
            String binding = gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.binding(task.id(), id, source, plan, 1073741824L);
            var quota = DeploymentJsonDocument.object().put("task", task.id().toString()).put("server", "server").put("source", source).put("configuration", "b".repeat(64))
                .put("layout", layout.digest()).put("plan", plan).put("workspaceBytes", 1073741824L).put("reserveBytes", 1073741824L).put("mode", "AUTO");
            log.event(task, "WORKSPACE_QUOTA", id, quota.toString());
            log.event(task, "PREPARE_EXECUTING", binding, id);
            quota.remove("mode");
            quota.put("prepareAction", binding).put("candidate", workspace.candidateId());
            log.event(task, "CANDIDATE_QUOTA", id, quota.toString());
            log.event(task, "PREPARE_RESULT", binding, current);
            log.event(task, "CANDIDATE", id, workspace.candidateId());
            log.event(task, "CURRENT_SOURCE", id, current);
            String seal = DeploymentAgentInputs.hash(id + ":" + current + ":seal");
            log.event(task, "SEAL_EXECUTING", seal, current);
            log.event(task, "SEAL_RESULT", seal, artifact);
            log.event(task, "SEALED_ARTIFACT", id, artifact);
        }
        log.event(task, "DELIVERY_EXECUTING", "2".repeat(64), "1");
        for (var part : parts) log.event(task, "SEALED_TOOLS", part.componentId(), "0".repeat(64) + ":false");
        var proposal = new DeploymentPublishResult(graph, commits);
        var id = UUID.randomUUID();
        var store = new DeploymentExecutionStore(context.db());
        var identity = new DeploymentExecutionIdentity(id, task.id(), Optional.empty(), "application", new ServerId("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, "a".repeat(64));
        var intents = DeploymentOriginalPublishCommit.references(new ServerId("server"), proposal, Map.of());
        store.begin(task, identity, "deployment-agent", intents, Optional.empty(), OBSERVED);
        var refs = new ArrayList<UUID>();
        for (var intent : intents) {
            var ref = context.servers().reserve(new ServerId("server"), intent.businessKey(), task);
            refs.add(ref.id());
            store.recordReference(task, id, intent, ref.id(), OBSERVED.plusSeconds(1));
        }
        store.startEffects(task, id, OBSERVED.plusSeconds(2));
        store.recordPublicationTemplate(task, id, new DeploymentPublishProposal(graph, commits), OBSERVED.plusSeconds(3));
        log.event(task, "AI_PUBLICATION", "application", "MODEL_GENERATED_COMMANDS");
        log.event(task, "STAGE_EXECUTING", "web", "original");
        log.event(task, "STAGE_RESULT", "web", "staged");
        log.event(task, "AI_PUBLICATION_VERIFIED", "application", "HEALTHY");
        log.event(task, "AI_PUBLICATION_FINISHED", "application", "MODEL_ACCEPTED_VERIFIED_RESULT");
        store.commitApplication(task, id, graph, commits, OBSERVED.plusSeconds(4));
        for (UUID ref : refs) context.servers().resolve(ref, ResourceRefResult.REFERENCED, task);
        log.event(task, "AI_PUBLICATION_CLEANED", "application", "ABSENT");
        log.state(task, "UNKNOWN");
        return id;
    }

    @SuppressWarnings("unchecked")
    private OperationResult<Void> finishOriginal(ModContext context, TaskContext task) throws Exception {
        var recovery = new DeploymentAgentRecovery(context, new DeploymentAgentActionLog(context.db()));
        var method = DeploymentAgentRecovery.class.getDeclaredMethod("finishRecovered", TaskContext.class, DeploymentExecutionRecord.class);
        method.setAccessible(true);
        try {
            var result = (OperationResult<Void>) method.invoke(recovery, task, new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow());
            if (result.outcome() == TaskResult.UNKNOWN) {
                var store = new DeploymentExecutionStore(context.db());
                var formal = store.findForTask(task).orElseThrow();
                var log = new DeploymentAgentActionLog(context.db());
                try {
                    DeploymentAgentActionLog.originalDeliveryProof(task.id(), log.find(task).orElseThrow(), formal, store.publicationTemplate(formal).orElseThrow(), log.events(task));
                } catch (Exception failure) {
                    throw new SQLException("Actual recovery finish remained " + result.problem().map(Problem::eventCode).orElse("UNKNOWN"), failure);
                }
                throw new SQLException("Actual recovery finish remained " + result.problem().map(Problem::eventCode).orElse("UNKNOWN"));
            }
            return result;
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            if (failed.getCause() instanceof Error error) throw error;
            throw failed;
        }
    }

    private void restoreEvents(ModContext context, TaskContext task, List<DeploymentAgentActionLog.Event> original) throws Exception {
        try (var connection = context.db().openConnection(task)) {
            try (var delete = connection.prepareStatement("DELETE FROM deployment_agent_event WHERE task_id=?")) {
                delete.setString(1, task.id().toString());
                delete.executeUpdate();
            }
            for (var event : original)
                try (var insert = connection.prepareStatement("INSERT INTO deployment_agent_event(event_id,task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?,?)")) {
                    insert.setLong(1, event.id());
                    insert.setString(2, task.id().toString());
                    insert.setString(3, event.action());
                    insert.setString(4, event.type());
                    insert.setString(5, event.detail());
                    insert.setLong(6, System.currentTimeMillis());
                    assertEquals(1, insert.executeUpdate());
                }
        }
    }

    @Test
    void platformRecoverySuccessKeepsOriginalCompensatedFailureAndHistoricalCleanupIsIdempotent() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var original = context.tasks().submit(new gold.debug.windowstolinux.appapi.task.model.TaskRequest(
                gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.TASK_TYPE, "Recovered history", List.of("server"), true), task -> {
                var journal = new DeploymentAgentActionLog(context.db());
                journal.create(task, agentIdentity());
                var id = UUID.randomUUID();
                var identity = new DeploymentExecutionIdentity(id, task.id(), Optional.empty(), "application", new ServerId("server"),
                    DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, "a".repeat(64));
                var store = new DeploymentExecutionStore(context.db());
                store.begin(task, identity, "deployment-agent", List.of(intent()), Optional.empty(), OBSERVED);
                var ref = context.servers().reserve(new ServerId("server"), intent().businessKey(), task);
                store.recordReference(task, id, intent(), ref.id(), OBSERVED.plusSeconds(1));
                store.startEffects(task, id, OBSERVED.plusSeconds(2));
                var reason = new Problem(ProblemCode.IO_FAILURE, "ORIGINAL_DEPLOYMENT_FAILED", "Verified compensation", Map.of(), Optional.empty());
                store.recordUncommittedOutcome(task, id, TaskResult.FAILED, reason, OBSERVED.plusSeconds(3));
                context.servers().resolve(ref.id(), ResourceRefResult.NOT_REFERENCED, task);
                store.finishCleanup(task, id, OBSERVED.plusSeconds(4));
                journal.event(task, "AI_PUBLICATION_CLEANED", "application", "ABSENT");
                journal.state(task, "FAILED");
                return success(); // Platform certifies recovery closure rather than App publication.
            });
            assertEquals(TaskResult.SUCCEEDED, original.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            run(runtime, current -> {
                var archive = new DeploymentAgentHistoryArchive(context);
                var result = archive.archiveRecoveredHistory(current, original.id());
                assertEquals(TaskResult.FAILED, result.outcome());
                assertEquals("ORIGINAL_DEPLOYMENT_FAILED", result.problem().orElseThrow().eventCode());
                assertEquals(result, archive.archiveRecoveredHistory(current, original.id()));
                assertEquals(0, count(runtime, current, "SELECT COUNT(*) FROM managed_application"));
                assertEquals(0, count(runtime, current, "SELECT COUNT(*) FROM deployment_execution"));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void noExecutionRequiresActualRecoverySettlementProof() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentAgentActionLog(context.db());
                journal.create(task, agentIdentity());
                journal.state(task, "FAILED");
                var failed = new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
                    "DEPLOYMENT_AGENT_PREFORMAL_RECONCILED", "Constructed uncompensated failure", Map.of(), Optional.empty())));
                assertThrows(SQLException.class, () -> new DeploymentAgentHistoryArchive(context).settle(task, failed));
                assertTrue(journal.find(task).isPresent());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                return success();
            });
        }
    }

    @Test
    void restartAfterLocalCommitBeforePlatformEndUsesOnlyReceiptAndNoOriginalDispatch() throws Exception {
        enable();
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp", System.getProperty("java.class.path"), getClass().getName(), "--settled-crash", root.toString())
            .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            }
        }
        UUID original = UUID.fromString(Files.readString(root.resolve("settled-original")));
        try (var runtime = open()) {
            var pages = new gold.debug.windowstolinux.app.ui.mod.ModPageRegistry(new gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageHost() {
                public void show(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key, gold.debug.windowstolinux.appapi.ui.page.PageSpec spec, javax.swing.JComponent component) {
                    throw new AssertionError("No UI expected");
                }

                public void focus(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key) {
                }

                public void remove(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key) {
                }

                public void environment(gold.debug.windowstolinux.appapi.ui.page.UiEnvironment environment) {
                }
            }, new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertTrue(report.running().isEmpty());
            assertEquals(List.of("MOD_RECOVERY_READY"), report.problems().stream().map(Problem::eventCode).toList());
            var ended = runtime.tasks().reconcile(original).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED, ended.state());
            assertEquals(Optional.of(TaskResult.SUCCEEDED), ended.outcome());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + original + "'"));
        }
    }

    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("--settled-crash")) throw new IllegalArgumentException();
        try {
            var fixture = new DeploymentAgentHistoryArchiveTest();
            fixture.root = Path.of(args[1]);
            var runtime = fixture.open();
            fixture.start(runtime, true);
            fixture.server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var task = context.tasks().submit(new gold.debug.windowstolinux.appapi.task.model.TaskRequest(
                gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.TASK_TYPE, "Settlement crash gap", List.of("server"), true), original -> {
                fixture.preparedSuccess(runtime, original);
                new DeploymentAgentHistoryArchive(context).settle(original, fixture.success());
                Files.writeString(fixture.root.resolve("settled-original"), original.id().toString());
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                    "CONSTRUCTED_AFTER_SETTLEMENT_CRASH", "Platform ending interrupted after Mod commit", Map.of(), Optional.empty())));
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (task.snapshot().state() != gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN
                && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN, task.snapshot().state());
            assertTrue(Files.isRegularFile(fixture.root.resolve("settled-original")));
            assertEquals(task.id().toString(), Files.readString(fixture.root.resolve("settled-original")));
            assertTrue(!task.completion().toCompletableFuture().isDone(), "The platform original remains unfinished at the crash");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    @Test
    void successRemovesWorkingRowsAndKeepsBusinessAndSharedCreatingOriginAcrossReopen() throws Exception {
        enable();
        var tasks = new ArrayList<UUID>();
        var origin = new AtomicReference<UUID>();
        var release = new AtomicReference<DeploymentReleaseIdentity>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            for (int index = 0; index < 2; index++)
                run(runtime, task -> {
                    var id = preparedSuccess(runtime, task);
                    tasks.add(task.id());
                    var context = runtime.modContexts().get(OWNER).context();
                    var store = new DeploymentExecutionStore(context.db());
                    var history = new DeploymentReleaseStore(context.db()).list(task, "application").getFirst();
                    if (origin.get() == null) {
                        origin.set(id);
                        release.set(history.identity());
                    } else {
                        assertEquals(origin.get(), history.executionId());
                        assertEquals(release.get(), history.identity());
                    }
                    var archive = new DeploymentAgentHistoryArchive(context);
                    assertEquals(TaskResult.SUCCEEDED, archive.settle(task, success()).outcome());
                    assertEquals(TaskResult.SUCCEEDED, archive.settle(task, success()).outcome());
                    assertTrue(store.findForTask(task).isEmpty());
                    assertTrue(new DeploymentAgentActionLog(context.db()).find(task).isEmpty());
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_event"));
                    verify(runtime, task, graph("application"), publications(graph("application"), 1, List.of()));
                    assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(task.id(), task).isPresent());
                    return success();
                });
            assertEquals(3, scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE state='ACTIVE'"));
            valid(runtime);
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                for (UUID original : tasks) {
                    try (var connection = context.db().openConnection(task)) {
                        var receipt = DeploymentOperationReceipts.read(connection, original).orElseThrow();
                        assertEquals(release.get(), receipt.release().orElseThrow());
                        DeploymentOperationReceipts.validateBusiness(connection, receipt);
                        assertEquals(origin.get(), DeploymentReleaseOrigin.read(connection,
                            DeploymentReleaseStore.read(connection, "application", release.get().id()).orElseThrow()).executionId());
                    }
                    assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(original, task).isPresent());
                }
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"receipt", "events", "task", "execution", "before_commit"})
    void anyWriteFailureKeepsOriginalWorkingEvidenceAndRollsBackTheReceipt(String point) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                preparedSuccess(runtime, task);
                var context = runtime.modContexts().get(OWNER).context();
                var original = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
                var events = new DeploymentAgentActionLog(context.db()).events(task);
                var fault = new DeploymentAgentHistoryArchive(withDatabase(context, fault(context.db(), point)));
                assertThrows(Exception.class, () -> fault.settle(task, success()));
                assertEquals(original, new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow());
                assertEquals(events, new DeploymentAgentActionLog(context.db()).events(task));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                new DeploymentAgentHistoryArchive(context).settle(task, success());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void lostCommitAcknowledgementReadsReceiptWithoutReconstructingOrRepeatingWork() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                preparedSuccess(runtime, task);
                var context = runtime.modContexts().get(OWNER).context();
                var failure = assertThrows(ApiException.class, () -> new DeploymentAgentHistoryArchive(
                    withDatabase(context, commitFault(context.db()))).settle(task, success()));
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_execution"));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_task"));
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(context).retained(task).orElseThrow().outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void preformalCompensationCanSettleFailureWithoutCreatingAnApplication() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentAgentActionLog(context.db());
                journal.create(task, agentIdentity());
                journal.event(task, "AI_RECOVERY_SETTLED", task.id().toString(), "FAILED:COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED");
                journal.state(task, "FAILED");
                var failed = new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                    ProblemCode.IO_FAILURE, "DEPLOYMENT_AGENT_PREFORMAL_RECONCILED", "Verified no remaining effects", Map.of(), Optional.empty())));
                assertEquals(TaskResult.FAILED, new DeploymentAgentHistoryArchive(context).settle(task, failed).outcome());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM managed_application"));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                try (var connection = context.db().openConnection(task)) {
                    var receipt = DeploymentOperationReceipts.read(connection, task.id()).orElseThrow();
                    assertEquals(TaskResult.FAILED, receipt.outcome());
                    assertTrue(receipt.release().isEmpty());
                }
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "FAILED", "PENDING", "BUSY"})
    void unresolvedStateEffectOrExplicitResourceCannotBeRemoved(String state) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                preparedSuccess(runtime, task);
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentAgentActionLog(context.db());
                if (Set.of("UNKNOWN", "FAILED").contains(state)) journal.state(task, state);
                if (state.equals("PENDING")) journal.event(task, "COMMAND_EXECUTING", "pending-command", "original");
                gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint held = state.equals("BUSY")
                    ? context.tasks().beginCheckpoint(task, "fixture.held", "{\"formatVersion\":1}") : null;
                try {
                    if (held == null)
                        assertThrows(SQLException.class, () -> new DeploymentAgentHistoryArchive(context).settle(task, success()));
                    else
                        assertEquals(TaskResult.UNKNOWN, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_execution"));
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_task"));
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                } finally {
                    if (held != null) held.close();
                }
                if (state.equals("PENDING")) journal.event(task, "COMMAND_RESULT", "pending-command", "verified");
                journal.state(task, "SUCCEEDED");
                new DeploymentAgentHistoryArchive(context).settle(task, success());
                return success();
            });
        }
    }

    private static ModContext withDatabase(ModContext original, ModDatabase database) {
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> method.getName().equals("db") ? database : invoke(method, original, args));
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    private static ModDatabase fault(ModDatabase original, String point) {
        return new ModDatabase() {
            public gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity identity() throws ApiException {
                return original.identity();
            }

            public Connection openConnection(TaskContext task) throws SQLException, ApiException {
                var connection = original.openConnection(task);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit") && point.equals("lost_ack")) {
                        invoke(method, connection, args);
                        throw new SQLException("Committed original settlement acknowledgement lost");
                    }
                    if (method.getName().equals("commit") && point.equals("before_commit"))
                        throw new SQLException("Pre-commit fixture failure");
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        boolean fail = point.equals("proof") && sql.startsWith("INSERT INTO deployment_agent_event")
                            || point.equals("receipt") && sql.startsWith("INSERT INTO deployment_operation_receipt")
                            || point.equals("events") && sql.startsWith("DELETE FROM deployment_agent_event")
                            || point.equals("task") && sql.startsWith("DELETE FROM deployment_agent_task")
                            || point.equals("execution") && sql.startsWith("DELETE FROM deployment_execution");
                        if (fail) throw new SQLException("Cleanup statement fixture failure");
                    }
                    return invoke(method, connection, args);
                });
            }
        };
    }
}
