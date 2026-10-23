package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup;

import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.linux.cleanup.DeploymentCandidateCompensationProgram;
import gold.debug.windowstolinux.mod.deployment.linux.cleanup.DeploymentCandidateCompensationProgram.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentOriginalPublishStatus;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentCandidateCompensationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHILD = "deployment-publication-unit-00";
    private static final String PLAN = "b".repeat(64), ROOT_PLAN = "c".repeat(64), ARCHIVE = "a".repeat(64), OWNER = "d".repeat(64);

    @Test
    void executesOnlyAfterBoundHumanConfirmationAndKeepsOldUnknownJournalAndKnownFailure() throws Exception {
        var h = new Harness("SUCCESS");
        var child = h.author.get(CHILD).snapshot();
        var root = h.author.get(DeploymentStandardGraphPublishActionLog.KEY).snapshot();
        assertTrue(h.run());
        assertEquals(List.of(Mode.OBSERVE, Mode.EXECUTE), h.sends);
        assertEquals(1, h.prompts);
        assertEquals(child, h.author.get(CHILD).snapshot());
        assertEquals(root, h.author.get(DeploymentStandardGraphPublishActionLog.KEY).snapshot());
        assertEquals(Optional.of(TaskResult.FAILED), h.record.outcome());
        assertFalse(h.record.businessCommitted());
        assertTrue(h.recovery.values().iterator().next().snapshot().released());
        assertEquals("SUCCEEDED", h.retained().path("stage").asText());
        assertTrue(h.retained().path("response").asText().contains("\"receiptSha256\""));
        assertEquals(1, h.saved.size());
        var receipt = h.saved.getFirst();
        assertEquals(DeploymentCandidateCompensation.RECEIPT_EVENT, receipt.eventCode());
        assertEquals(Optional.of(h.taskId), receipt.taskId());
        assertNotEquals(receipt.taskId(), receipt.operationId());
        assertEquals(h.retained().path("binding").path("operationId").asText(), receipt.operationId().orElseThrow().toString());
        assertEquals(h.retained().path("response").asText(), receipt.details().orElseThrow());
        assertTrue(receipt.outcome().isEmpty(), "New-operation success must not replace the original task's FAILED outcome");
        assertTrue(h.run());
        assertEquals(List.of(Mode.OBSERVE, Mode.EXECUTE), h.sends, "Completed same operation must never be sent again");
        assertEquals(1, h.saved.size());
    }

    @ParameterizedTest
    @ValueSource(strings={"NOT_SAVED", "EMERGENCY", "TRUNCATED", "PROBLEM", "REGISTER_THROW", "WRITE_THROW"})
    void failedReceiptPersistenceKeepsDispatchedAndRetriesOnlyReceiptBeforeClosing(String logMode) throws Exception {
        var h = new Harness("SUCCESS");
        h.logMode = logMode;
        assertFalse(h.run());
        assertEquals(List.of(Mode.OBSERVE, Mode.EXECUTE), h.sends);
        assertEquals("DISPATCHED", h.retained().path("stage").asText());
        assertFalse(h.recovery.values().iterator().next().snapshot().released());
        assertTrue(h.saved.isEmpty());
        var operation = h.retained().path("binding").path("operationId").asText();
        h.logMode = "SAVED";
        assertTrue(h.run());
        assertEquals(List.of(Mode.OBSERVE, Mode.EXECUTE, Mode.RECEIPT), h.sends);
        assertEquals(1, h.prompts);
        assertEquals(1, h.saved.size());
        assertEquals(operation, h.saved.getFirst().operationId().orElseThrow().toString());
        assertEquals("receipt", JSON.readTree(h.saved.getFirst().details().orElseThrow()).path("mode").asText());
        assertEquals(Optional.of(TaskResult.FAILED), h.record.outcome());
        assertTrue(h.recovery.values().iterator().next().snapshot().released());
    }

    @Test
    void absentObservationIsSavedSeparatelyAndNeverInventsAnExecutionReceipt() throws Exception {
        var h = new Harness("ABSENT");
        h.logMode = "NOT_SAVED";
        assertFalse(h.run());
        assertEquals("PREPARED", h.retained().path("stage").asText());
        assertFalse(h.recovery.values().iterator().next().snapshot().released());
        h.logMode = "SAVED";
        assertTrue(h.run());
        assertEquals(List.of(Mode.OBSERVE, Mode.OBSERVE), h.sends);
        assertEquals(0, h.prompts);
        assertEquals(1, h.saved.size());
        var observed = h.saved.getFirst();
        assertEquals(DeploymentCandidateCompensation.OBSERVATION_EVENT, observed.eventCode());
        var details = JSON.readTree(observed.details().orElseThrow());
        assertEquals("observe", details.path("mode").asText());
        assertEquals("ABSENT", details.path("status").asText());
        assertEquals("", details.path("receiptSha256").asText());
        assertTrue(observed.outcome().isEmpty());
        assertEquals(Optional.of(TaskResult.FAILED), h.record.outcome());
    }

    @ParameterizedTest
    @ValueSource(strings={"UNKNOWN", "EXECUTE_THROW", "EXIT", "TRUNCATED", "STDERR", "BAD_BINDING", "NOT_DISPATCHED"})
    void uncertainExecutionPermitsOnlySameOperationReceiptReads(String scenario) throws Exception {
        var h = new Harness(scenario);
        assertFalse(h.run());
        UUID operation = DeploymentCandidateCompensationProgram.decodeBinding(h.retained().path("binding")).operationId();
        assertEquals("DISPATCHED", h.retained().path("stage").asText());
        assertFalse(h.recovery.values().iterator().next().snapshot().released());
        h.scenario = "RECEIPT_UNKNOWN";
        assertFalse(h.run());
        h.scenario = "SUCCESS";
        assertTrue(h.run());
        assertEquals(List.of(Mode.OBSERVE, Mode.EXECUTE, Mode.RECEIPT, Mode.RECEIPT), h.sends);
        assertEquals(operation, DeploymentCandidateCompensationProgram.decodeBinding(h.retained().path("binding")).operationId());
        assertEquals(1, h.prompts, "UNKNOWN never re-asks consent or re-executes");
    }

    @ParameterizedTest
    @ValueSource(strings={"DECLINE", "CONFIRM_WRONG", "DENY_OBSERVE", "DENY_EXECUTE", "REFUSED_OBSERVE", "ACTIVE_OBSERVE", "CAS_DISPATCH"})
    void refusalOrMissingDurabilityNeverDispatchesAndNeverFallsBack(String scenario) throws Exception {
        var h = new Harness(scenario);
        assertFalse(h.run());
        assertFalse(h.sends.contains(Mode.EXECUTE));
        int before = h.reviews.size();
        assertFalse(h.run());
        assertEquals(before, h.reviews.size(), "A refused or interrupted explicit action must not create another operation");
        assertEquals(1, h.recovery.size());
        assertEquals("CANDIDATE_CLEANUP_MAY_HAVE_RUN", JSON.readTree(h.author.get(CHILD).snapshot().payload()).path("phase").asText());
    }

    @ParameterizedTest
    @ValueSource(strings={"STATIC", "COMMITTED", "ROOT_PHASE", "ROOT_RESULT", "CHILD_PHASE", "CONTAINER", "RELEASE", "CURRENT", "SNAPSHOT", "BUILD_ACTIVE", "MAINTENANCE", "UNIT", "RETAINED", "CHILD_REBOUND"})
    void rejectsScopeOrLiveStateOutsideTheNarrowOriginalFailure(String mutation) throws Exception {
        var h = new Harness("SUCCESS");
        h.mutate(mutation);
        assertFalse(h.run());
        assertTrue(h.sends.isEmpty());
        assertTrue(h.recovery.isEmpty());
        assertEquals(0, h.prompts);
    }

    @Test
    void immutableCheckpointOrFullCommandChangesAreRejectedBeforeApproval() throws Exception {
        var h = new Harness("UNKNOWN");
        assertFalse(h.run());
        var retained = h.recovery.values().iterator().next();
        ObjectNode original = h.retained();
        var request = new Request(Mode.RECEIPT, DeploymentCandidateCompensationProgram.decodeBinding(original.path("binding")), "", h.record.createdAt().toEpochMilli());
        original.withObject("originalChild").put("archiveSha256", "f".repeat(64));
        retained.snapshot = new TaskCheckpoint.Snapshot(retained.snapshot.revision() + 1, original.toString(), false);
        try (var command = DeploymentCandidateCompensationProgram.command(request)) {
            var intent = DeploymentCandidateCompensationProgram.intent(h.server, request, command);
            assertThrows(IllegalArgumentException.class, () -> DeploymentCandidateCompensation.reviewFacts(h.context, h.task, h.record, intent, command, h.layout));
        }
    }

    @Test
    void soleSuccessorReadsOldReceiptThenUsesDistinctBoundOperationAndPreservesReleasedRefusal() throws Exception {
        var h = new Harness("SUCCESS");
        var old = h.legacyRefusal(); var tombstone = old.snapshot();
        UUID oldOperation = DeploymentCandidateCompensationProgram.decodeBinding(JSON.readTree(tombstone.payload()).path("binding")).operationId();
        assertTrue(h.run());
        assertEquals(tombstone, old.snapshot());
        assertEquals(2, h.recovery.size());
        assertEquals(List.of(Mode.RECEIPT, Mode.OBSERVE, Mode.EXECUTE), h.sends);
        assertEquals(oldOperation, h.sentOperations.getFirst());
        UUID operation = DeploymentCandidateCompensationProgram.decodeBinding(h.retained().path("binding")).operationId();
        assertNotEquals(oldOperation, operation);
        assertNotEquals(h.taskId, operation);
        assertEquals(List.of(oldOperation, operation, operation), h.sentOperations);
        assertEquals(DeploymentCandidateCompensation.successorKey(h.retained().path("binding").path("candidateId").asText()), h.activeKey());
        assertEquals(59, h.activeKey().length());
        assertEquals(2, h.retained().path("formatVersion").intValue());
        assertEquals(DeploymentCandidateCompensationProgram.bodySha256(), h.retained().path("guardSha256").asText());
        assertEquals("EXTERNAL_EXECUTION_SOURCE_ATTESTATION_REQUIRED", h.retained().path("predecessor").path("kind").asText());
        assertEquals(1, h.retained().path("predecessor").path("legacyStateMachineRevision").intValue());
        assertEquals("71f4cea99b53dd037a56279d5b0a746a58bd88cbf9baae6a75d1c72aa98a447c",
            h.retained().path("predecessor").path("expectedLegacyWriterSources").path("compensationSha256").asText());
        assertEquals("receipt-absent", JSON.readTree(h.retained().path("predecessorResponse").asText()).path("reason").asText());
        assertEquals(Optional.of(operation), h.saved.getFirst().operationId());
        assertEquals(Optional.of(TaskResult.FAILED), h.record.outcome());
        assertTrue(h.run());
        assertEquals(3, h.sends.size());
        assertEquals(2, h.recovery.size());
    }

    @ParameterizedTest
    @ValueSource(strings={"PREDECESSOR_RECEIPT_EXISTS", "PREDECESSOR_UNKNOWN", "DENY_PREDECESSOR"})
    void predecessorUncertaintyKeepsOneSuccessorAndNeverObservesOrExecutes(String scenario) throws Exception {
        var h = new Harness(scenario); var old = h.legacyRefusal(); var tombstone = old.snapshot();
        assertFalse(h.run());
        String operation = h.retained().path("binding").path("operationId").asText();
        assertEquals("VERIFY_PREDECESSOR", h.retained().path("stage").asText());
        assertFalse(h.recovery.get(h.activeKey()).snapshot().released());
        assertFalse(h.run());
        assertEquals(operation, h.retained().path("binding").path("operationId").asText());
        assertEquals(tombstone, old.snapshot());
        assertEquals(2, h.recovery.size());
        assertEquals(0, h.prompts);
        assertTrue(h.sends.stream().allMatch(mode -> mode == Mode.RECEIPT));
    }

    @ParameterizedTest
    @ValueSource(strings={"UNRELEASED", "REVISION", "QUESTION", "OBSERVATION", "EVENT", "REASON", "MODE", "EXTRA", "GRAPH"})
    void legacyMigrationRejectsAnythingBeyondTheExactSingleObservationRefusal(String mutation) throws Exception {
        var h = new Harness("SUCCESS"); var old = h.legacyRefusal();
        var value = (ObjectNode)JSON.readTree(old.snapshot.payload());
        var response = (ObjectNode)JSON.readTree(value.path("response").asText());
        switch (mutation) {
            case "QUESTION" -> value.put("questionSha256", "e".repeat(64));
            case "OBSERVATION" -> value.put("observationSha256", "e".repeat(64));
            case "EVENT" -> value.put("event", "UNKNOWN_EVENT");
            case "REASON" -> response.put("reason", "different-refusal");
            case "MODE" -> response.put("mode", "execute");
            case "EXTRA" -> value.put("futureField", true);
            case "GRAPH" -> value.withObject("originalGraph").put("failureEvent", "OTHER_EVENT");
        }
        value.put("response", DeploymentCandidateCompensationProgram.canonical(response));
        old.snapshot = new TaskCheckpoint.Snapshot(mutation.equals("REVISION") ? 3 : 2, value.toString(), !mutation.equals("UNRELEASED"));
        assertFalse(h.run());
        assertEquals(1, h.recovery.size());
        assertTrue(h.reviews.isEmpty());
        assertTrue(h.sends.isEmpty());
    }

    @Test
    void uncertainSuccessorCreationReopensSameSlotAndDeterministicOperation() throws Exception {
        var h = new Harness("BEGIN_SUCCESSOR_UNKNOWN"); h.legacyRefusal();
        assertFalse(h.run());
        String operation = h.retained().path("binding").path("operationId").asText();
        assertTrue(h.sends.isEmpty());
        h.scenario = "SUCCESS";
        assertTrue(h.run());
        assertEquals(operation, h.retained().path("binding").path("operationId").asText());
        assertEquals(2, h.recovery.size());
        assertEquals(List.of(Mode.RECEIPT, Mode.OBSERVE, Mode.EXECUTE), h.sends);
    }

    @ParameterizedTest
    @ValueSource(strings={"TOMBSTONE_BYTES", "GUARD", "EPOCH"})
    void successorRechecksExactPredecessorAndGuardBeforeAnyFurtherReceipt(String mutation) throws Exception {
        var h = new Harness("UNKNOWN"); var old = h.legacyRefusal();
        assertFalse(h.run());
        assertEquals("DISPATCHED", h.retained().path("stage").asText());
        int sent = h.sends.size();
        if (mutation.equals("TOMBSTONE_BYTES")) old.snapshot = new TaskCheckpoint.Snapshot(2, old.snapshot.payload() + " ", true);
        else {
            var retained = h.recovery.get(h.activeKey()); var value = h.retained();
            if (mutation.equals("GUARD")) value.put("guardSha256", "0".repeat(64));
            else value.put("originalExecutionCreatedAtEpochMillis", 1001);
            retained.snapshot = new TaskCheckpoint.Snapshot(retained.snapshot.revision() + 1, value.toString(), false);
        }
        assertFalse(h.run());
        assertEquals(sent, h.sends.size());
        assertEquals(2, h.recovery.size());
    }

    @Test
    void refusedSuccessorIsNeverReplacedOrRevived() throws Exception {
        var h = new Harness("REFUSED_OBSERVE"); var old = h.legacyRefusal(); var tombstone = old.snapshot();
        assertFalse(h.run());
        var successor = h.recovery.get(h.activeKey()).snapshot();
        assertTrue(successor.released());
        assertEquals("REFUSED", h.retained().path("stage").asText());
        assertFalse(h.run());
        assertEquals(successor, h.recovery.get(h.activeKey()).snapshot());
        assertEquals(tombstone, old.snapshot());
        assertEquals(List.of(Mode.RECEIPT, Mode.OBSERVE), h.sends);
        assertEquals(2, h.recovery.size());
    }

    @Test
    void absentExternalSourceConfirmationLeavesSuccessorDeclinedWithoutExecutionOrReplacement() throws Exception {
        var h = new Harness("DECLINE"); var old = h.legacyRefusal(); var tombstone = old.snapshot();
        assertFalse(h.run());
        var successor = h.recovery.get(h.activeKey()).snapshot();
        assertEquals("DECLINED", h.retained().path("stage").asText());
        assertFalse(h.run());
        assertEquals(successor, h.recovery.get(h.activeKey()).snapshot());
        assertEquals(tombstone, old.snapshot());
        assertEquals(List.of(Mode.RECEIPT, Mode.OBSERVE), h.sends);
        assertEquals(1, h.prompts);
        assertEquals(2, h.recovery.size());
    }

    private static final class Harness {
        String scenario;
        String logMode = "SAVED";
        int prompts;
        final UUID taskId = UUID.randomUUID();
        final ServerId server = new ServerId("compensation-original-server");
        final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
        final Map<String, Checkpoint> author = new HashMap<>(), recovery = new HashMap<>();
        final List<Mode> reviews = new ArrayList<>(), sends = new ArrayList<>();
        final List<UUID> sentOperations = new ArrayList<>();
        final List<LogEvent> saved = new ArrayList<>();
        DeploymentOriginalPublishStatus status = status(false, false, false, false, false, false, 0);
        DeploymentExecutionRecord record;
        final TaskContext task = proxy(TaskContext.class, (p,m,a) -> { assertEquals("id", m.getName()); return taskId; });
        final TaskService tasks = proxy(TaskService.class, (p,m,a) -> {
            assertSame(task, a[0]); String key=(String)a[1];
            if(m.getName().equals("findCheckpoint")) return Optional.ofNullable(author.get(key));
            if(m.getName().equals("findRecoveryCheckpoint")) return Optional.ofNullable(recovery.get(key));
            if(m.getName().equals("beginCheckpoint") || m.getName().equals("beginRecoveryCheckpoint")) {
                var target=m.getName().equals("beginCheckpoint")?author:recovery;
                var checkpoint=new Checkpoint((String)a[2]); assertNull(target.putIfAbsent(key,checkpoint));
                if (scenario.equals("BEGIN_SUCCESSOR_UNKNOWN") && key.endsWith("-observation"))
                    throw new ApiException(new Problem(ProblemCode.UNKNOWN,"CHECKPOINT_BEGIN_UNCONFIRMED","Begin unknown",Map.of(),Optional.empty()));
                return checkpoint;
            }
            throw new AssertionError("No other task capability: "+m.getName());
        });
        final DeploymentCommandReview interaction = proxy(DeploymentCommandReview.class, (p,m,a) -> {
            if(m.getName().equals("requestText")) {
                prompts++; assertSame(task,a[3]); assertEquals(DeploymentCandidateCompensation.TITLE,a[0]);
                var binding=DeploymentCandidateCompensationProgram.decodeBinding(retained().path("binding"));
                String question=DeploymentCandidateCompensation.prompt(binding,retained().path("observationSha256").asText(), retained().path("response").asText(),
                    DeploymentCandidateCompensationProgram.canonical(retained().path("predecessor")));
                assertEquals(question,a[1]); assertFalse((Boolean)a[2]);
                return ok(scenario.equals("DECLINE")?"":scenario.equals("CONFIRM_WRONG")?"CONFIRM":DeploymentCandidateCompensation.CONFIRMATION+binding.operationId());
            }
            assertEquals("approveCommand",m.getName(),"New action may never fall back to generic approval");
            var request=DeploymentCandidateCompensationProgram.verify((ActionIntent)a[0],(CommandRequest)a[1],layout);
            DeploymentCandidateCompensation.reviewFacts(this.context,task,record,(ActionIntent)a[0],(CommandRequest)a[1],layout);
            reviews.add(request.mode()); assertSame(task,a[2]);
            if(scenario.equals("DENY_OBSERVE")&&request.mode()==Mode.OBSERVE || scenario.equals("DENY_EXECUTE")&&request.mode()==Mode.EXECUTE
                || scenario.equals("DENY_PREDECESSOR") && retained().path("stage").asText().equals("VERIFY_PREDECESSOR"))
                throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED,"DEPLOYMENT_COMMAND_REVIEW_DENIED","Independent refusal",Map.of(),Optional.empty()));
            return ok(new ApprovalGrant(UUID.randomUUID()));
        });
        final LogService logs = proxy(LogService.class, (p,m,a) -> {
            if (m.getName().equals("register")) {
                assertEquals(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")), a[0]);
                if (logMode.equals("REGISTER_THROW")) throw new ApiException(new Problem(ProblemCode.UNKNOWN,"LOG_UNAVAILABLE","Log unavailable",Map.of(),Optional.empty()));
                return new LogTarget(UUID.randomUUID());
            }
            assertEquals("write", m.getName());
            assertFalse(recovery.get(activeKey()).snapshot().released(), "Durable log write must precede checkpoint release");
            assertEquals(Optional.of(TaskResult.FAILED), record.outcome());
            var event = (LogEvent)a[1];
            assertEquals(Optional.of(taskId), event.taskId());
            assertNotEquals(event.taskId(), event.operationId());
            assertTrue(event.outcome().isEmpty());
            if (logMode.equals("WRITE_THROW")) throw new IllegalStateException("Log unavailable");
            if (logMode.equals("SAVED")) saved.add(event);
            return new LogWriteResult(UUID.randomUUID(), logMode.equals("NOT_SAVED") ? LogStorage.NOT_SAVED
                : logMode.equals("EMERGENCY") ? LogStorage.EMERGENCY_FILE : LogStorage.DATABASE, logMode.equals("TRUNCATED"),
                logMode.equals("PROBLEM") ? Optional.of(new Problem(ProblemCode.UNKNOWN,"LOG_UNAVAILABLE","Log unavailable",Map.of(),Optional.empty())) : Optional.empty());
        });
        final ModContext context = proxy(ModContext.class, (p,m,a) -> switch(m.getName()) {
            case "tasks" -> tasks; case "interaction" -> interaction;
            case "logs" -> logs;
            default -> throw new AssertionError("No extra product capability: "+m.getName());
        });
        final RemoteSession session = proxy(RemoteSession.class, (p,m,a) -> {
            assertEquals("execute",m.getName());
            var command=(CommandRequest)a[0]; var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server,PLAN,command);
            var parsed = request(command); var bound=DeploymentCandidateCompensationProgram.intent(server,parsed,command);
            assertEquals(intent.exactAction(),bound.exactAction());
            sends.add(parsed.mode());
            sentOperations.add(parsed.binding().operationId());
            boolean predecessorReceipt = retained().path("stage").asText().equals("VERIFY_PREDECESSOR");
            assertEquals(predecessorReceipt?"VERIFY_PREDECESSOR":parsed.mode()==Mode.EXECUTE?"DISPATCHED":parsed.mode()==Mode.RECEIPT?"DISPATCHED":"PREPARED",retained().path("stage").asText());
            if(scenario.equals("EXECUTE_THROW")&&parsed.mode()==Mode.EXECUTE) throw new ApiException(new Problem(ProblemCode.UNKNOWN,"SSH_UNCONFIRMED","Unknown original send",Map.of(),Optional.empty()));
            String state=parsed.mode()==Mode.OBSERVE?"OBSERVED":"SUCCEEDED";
            boolean present=parsed.mode()==Mode.OBSERVE, quiet=true;
            if(scenario.equals("ABSENT")&&parsed.mode()==Mode.OBSERVE) {state="ABSENT";present=false;}
            if(scenario.equals("REFUSED_OBSERVE")&&parsed.mode()==Mode.OBSERVE) state="REFUSED";
            if(scenario.equals("ACTIVE_OBSERVE")&&parsed.mode()==Mode.OBSERVE) {state="REFUSED";quiet=false;}
            if(scenario.equals("UNKNOWN")&&parsed.mode()==Mode.EXECUTE || scenario.equals("RECEIPT_UNKNOWN")&&parsed.mode()==Mode.RECEIPT) {state="UNKNOWN";present=true;}
            if(scenario.equals("NOT_DISPATCHED")&&parsed.mode()==Mode.EXECUTE) {state="NOT_DISPATCHED";present=true;}
            var response=JSON.createObjectNode().put("formatVersion",2).put("mode",parsed.mode().wire()).put("taskId",taskId.toString())
                .put("operationId",parsed.binding().operationId().toString()).put("bindingSha256",scenario.equals("BAD_BINDING")&&parsed.mode()==Mode.EXECUTE?"0".repeat(64):parsed.binding().sha256())
                .put("status",state).put("reason","fixture-result").put("candidatePresent",present).put("quiescent",quiet)
                .put("observationSha256","e".repeat(64)).put("receiptSha256",parsed.mode()==Mode.OBSERVE?"":"f".repeat(64));
            response.putObject("systemProcessExclusion");
            if (predecessorReceipt) {
                assertEquals(Mode.RECEIPT, parsed.mode());
                response.put("status", "UNKNOWN").put("reason", scenario.equals("PREDECESSOR_UNKNOWN") ? "receipt-unconfirmed" : "receipt-absent")
                    .put("candidatePresent", false).put("quiescent", false).put("observationSha256", "").put("receiptSha256", "");
                if (scenario.equals("PREDECESSOR_RECEIPT_EXISTS")) response.put("reason", "receipt-started").put("receiptSha256", "f".repeat(64));
            }
            boolean executing=parsed.mode()==Mode.EXECUTE;
            return ok(new CommandResult(Optional.of(executing&&scenario.equals("EXIT")?1:0),response+"\n",executing&&scenario.equals("STDERR")?"unexpected":"",executing&&scenario.equals("TRUNCATED")));
        });

        Harness(String scenario) throws Exception {
            this.scenario=scenario;
            var identity=new DeploymentExecutionIdentity(UUID.randomUUID(),taskId,Optional.empty(),"fixture-app",server,
                DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED,ROOT_PLAN);
            record=new DeploymentExecutionRecord(identity,"EXECUTING",Optional.of(TaskResult.FAILED),"{}","{}",Optional.empty(),false,false,Instant.ofEpochMilli(1000),Instant.ofEpochMilli(1000));
            DeploymentConfigCapture.remoteLayout(tasks,task,layout);
            var root=JSON.createObjectNode().put("version",1).put("task",taskId.toString()).put("server",server.id())
                .put("application","fixture-app").put("planSha256",ROOT_PLAN).put("phase","ALL_PREPARED").put("verifiedOutcome","FAILED")
                .put("failureCode","IO_FAILURE").put("failureEvent","SFTP_NOT_VERIFIED");
            var indexes=root.putArray("components");
            indexes.addObject().put("componentId","backend").put("unitId","fixture-app-backend").put("key",CHILD).put("planSha256",PLAN);
            indexes.addObject().put("componentId","web").put("unitId","fixture-app-web").put("key","deployment-publication-unit-01").put("planSha256","f".repeat(64));
            author.put(DeploymentStandardGraphPublishActionLog.KEY,new Checkpoint(root.toString()));
            var child=JSON.createObjectNode().put("version",3).put("task",taskId.toString()).put("server",server.id()).put("application","fixture-app-backend")
                .put("ownerManifestSha256",OWNER).put("releaseRoot",layout.applicationRoot("fixture-app-backend")).put("archiveSha256",ARCHIVE)
                .put("candidate","fixture-app-backend-"+ARCHIVE.substring(0,16)).put("planSha256",PLAN).put("backend","native").put("engine","none")
                .put("phase","CANDIDATE_CLEANUP_MAY_HAVE_RUN").put("buildPlan",PLAN).put("buildScript","1".repeat(64)).put("buildTools","2".repeat(64));
            author.put(CHILD,new Checkpoint(child.toString()));
        }

        Request request(CommandRequest command) throws Exception {
            for(var checkpoint:recovery.values()) for(var mode:Mode.values()) {
                var binding=DeploymentCandidateCompensationProgram.decodeBinding(JSON.readTree(checkpoint.snapshot.payload()).path("binding"));
                String observation = mode == Mode.EXECUTE ? retained().path("observationSha256").asText() : "";
                if (mode == Mode.EXECUTE && observation.isEmpty()) continue;
                var candidate=new Request(mode,binding,observation,record.createdAt().toEpochMilli());
                try { return DeploymentCandidateCompensationProgram.verify(DeploymentCandidateCompensationProgram.intent(server,candidate,command),command,layout); }
                catch(IllegalArgumentException ignored) { }
            }
            throw new AssertionError("Unknown fixed request");
        }
        String activeKey() {
            return recovery.keySet().stream().filter(key -> key.endsWith("-observation")).findFirst().orElseGet(() -> recovery.keySet().iterator().next());
        }
        ObjectNode retained() throws Exception { return (ObjectNode)JSON.readTree(recovery.get(activeKey()).snapshot().payload()); }
        Checkpoint legacyRefusal() throws Exception {
            var child = JSON.readTree(author.get(CHILD).snapshot.payload());
            var binding = new Binding(taskId, UUID.randomUUID(), record.identity().applicationId(), child.path("application").asText(),
                child.path("candidate").asText(), PLAN, OWNER, ARCHIVE, layout,
                gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.helperSha256(layout));
            var response = JSON.createObjectNode().put("formatVersion", 1).put("mode", "observe").put("taskId", taskId.toString())
                .put("operationId", binding.operationId().toString()).put("bindingSha256", binding.sha256()).put("status", "REFUSED")
                .put("reason", "other-root-execution-chain").put("candidatePresent", false).put("quiescent", false)
                .put("observationSha256", "").put("receiptSha256", "");
            var node = JSON.createObjectNode().put("formatVersion", 1).put("childKey", CHILD).put("stage", "REFUSED")
                .put("observationSha256", "").put("questionSha256", "").put("response", DeploymentCandidateCompensationProgram.canonical(response)).put("event", "");
            node.set("binding", binding.json()); node.set("originalGraph", JSON.readTree(author.get(DeploymentStandardGraphPublishActionLog.KEY).snapshot.payload()));
            node.set("originalChild", child);
            var checkpoint = new Checkpoint(node.toString());
            checkpoint.snapshot = new TaskCheckpoint.Snapshot(2, node.toString(), true);
            recovery.put(DeploymentCandidateCompensation.key(binding.candidateId()), checkpoint);
            return checkpoint;
        }
        boolean run() throws Exception {
            return DeploymentCandidateCompensation.reconcile(context,task,record,DeploymentStandardGraphPublishActionLog.reopen(context,task).orElseThrow(),CHILD,
                DeploymentPublishCleanupActionLog.reopen(context,task,CHILD).orElseThrow(),status,session);
        }
        void mutate(String mutation) throws Exception {
            var child=(ObjectNode)JSON.readTree(author.get(CHILD).snapshot().payload());
            var root=(ObjectNode)JSON.readTree(author.get(DeploymentStandardGraphPublishActionLog.KEY).snapshot().payload());
            if(mutation.equals("STATIC")) record=new DeploymentExecutionRecord(new DeploymentExecutionIdentity(record.identity().executionId(),taskId,Optional.empty(),"fixture-app",server,DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC,ROOT_PLAN),"EXECUTING",record.outcome(),"{}","{}",Optional.empty(),false,false,Instant.EPOCH,Instant.EPOCH);
            if(mutation.equals("COMMITTED")) record=new DeploymentExecutionRecord(record.identity(),"EXECUTING",Optional.of(TaskResult.SUCCEEDED),"{}","{}",Optional.of("{}"),true,false,Instant.EPOCH,Instant.EPOCH);
            if(mutation.equals("ROOT_PHASE")) root.put("phase","PUBLISHING");
            if(mutation.equals("ROOT_RESULT")) {root.put("verifiedOutcome","SUCCEEDED");root.remove(List.of("failureCode","failureEvent"));}
            if(mutation.equals("CHILD_PHASE")) child.put("phase","CANDIDATE_MAY_EXIST");
            if(mutation.equals("CONTAINER")) {child.put("backend","container");child.put("engine","docker");}
            if(mutation.equals("RELEASE")) child.put("releaseSha256","9".repeat(64));
            if(mutation.equals("CHILD_REBOUND")) child.put("archiveSha256","3".repeat(64));
            author.put(CHILD,new Checkpoint(child.toString())); author.put(DeploymentStandardGraphPublishActionLog.KEY,new Checkpoint(root.toString()));
            status=status(mutation.equals("CURRENT"),mutation.equals("SNAPSHOT"),mutation.equals("BUILD_ACTIVE"),mutation.equals("MAINTENANCE"),mutation.equals("UNIT"),false,mutation.equals("RETAINED")?1:0);
        }
        final class Checkpoint implements TaskCheckpoint {
            Snapshot snapshot;
            Checkpoint(String payload) {snapshot=new Snapshot(0,payload,false);}
            public Snapshot snapshot(){return snapshot;}
            public Snapshot advance(Snapshot expected,String next) throws ApiException {
                assertEquals(snapshot,expected);assertFalse(snapshot.released());
                if(scenario.equals("CAS_DISPATCH")&&next.contains("\"stage\":\"DISPATCHED\"")) throw new ApiException(new Problem(ProblemCode.UNKNOWN,"CHECKPOINT_CAS_UNCONFIRMED","CAS unknown",Map.of(),Optional.empty()));
                return snapshot=new Snapshot(snapshot.revision()+1,next,false);
            }
            public void close(){snapshot=new Snapshot(snapshot.revision()+1,snapshot.payload(),true);}
        }
    }

    private static DeploymentOriginalPublishStatus status(boolean current,boolean snapshot,boolean build,boolean maintenance,boolean unit,boolean container,int retained) {
        return new DeploymentOriginalPublishStatus(current?Optional.of("8".repeat(64)):Optional.empty(),current?"deployment":"none",current?"DAEMON":"none",snapshot,
            snapshot?Optional.of("7".repeat(64)):Optional.empty(),snapshot?"deployment":"none",snapshot?Optional.of(false):Optional.empty(),maintenance,true,build,unit,Optional.of(container),current?Math.max(1,retained):retained);
    }
    private static <T> OperationResult<T> ok(T value){return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(value),Optional.empty());}
    private static <T> T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
}
