package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Controlled local route adoption only; no model, SSH or workflow execution. */
class DeploymentAgentRecoveryReviewTest {
    private static final String ORIGINAL = "a".repeat(64);
    private static final String FIRST = "b".repeat(64);
    private static final String SECOND = "c".repeat(64);
    private static final String THIRD = "e".repeat(64);

    @Test
    void unchangedOriginalRouteNeedsNeitherBindingNorAuthorization() throws Exception {
        try (var frame = new Frame()) {
            var before = frame.journal.events(frame.task);
            assertEquals(ORIGINAL, frame.revision());
            assertEquals(before, frame.journal.events(frame.task));
            assertEquals(0, frame.questions.size());
            assertEquals(frame.saved, frame.journal.find(frame.task).orElseThrow());
        }
    }

    @Test
    void legacySingleBindingIsReusedWithoutAnotherGrantOrMutation() throws Exception {
        try (var frame = new Frame()) {
            frame.bound(FIRST);
            frame.routes(FIRST);
            var before = frame.journal.events(frame.task);
            assertEquals(FIRST, frame.revision());
            assertEquals(FIRST, frame.revision());
            assertEquals(before, frame.journal.events(frame.task));
            assertEquals(0, frame.questions.size());
            assertEquals(frame.saved, frame.journal.find(frame.task).orElseThrow());
        }
    }

    @Test
    void firstRouteAdoptionRequiresItsExactOriginalRecoveryScope() throws Exception {
        try (var frame = new Frame()) {
            frame.routes(FIRST);
            var originalEvents = frame.journal.events(frame.task);
            assertEquals(FIRST, frame.revision());
            assertEquals(1, frame.questions.size());
            var question = frame.questions.getFirst();
            assertEquals("deployment.agent.recovery.review", question.type());
            assertEquals(List.of("original-server"), question.serverIds());
            assertEquals(frame.task.id().toString(), question.target());
            assertEquals(FIRST, question.revision());
            assertTrue(question.exactAction().contains("originalTask=" + frame.task.id()));
            assertTrue(question.exactAction().contains("targetDigest=" + frame.saved.identity().targetDigest()));
            assertTrue(question.exactAction().contains("originalModelRevision=" + ORIGINAL));
            assertTrue(question.exactAction().contains("previousBindingEvent=none"));
            assertTrue(question.exactAction().endsWith("allowed=original-status,original-settlement,original-cleanup"));
            assertTrue(question.effectSummary().contains("old deployment session will not resume or replay"));
            var binding = frame.bindings().getFirst();
            assertEquals("RECOVERY_REVIEW_BOUND", binding.type());
            assertEquals(ORIGINAL, binding.action());
            assertEquals(FIRST, binding.detail());
            assertEquals(originalEvents, frame.journal.events(frame.task).subList(0, originalEvents.size()));
            assertEquals(frame.saved, frame.journal.find(frame.task).orElseThrow());
        }
    }

    @Test
    void successiveChangesAppendStrictPredecessorBindingsAndPreserveOriginalEffects() throws Exception {
        try (var frame = new Frame()) {
            frame.bound(FIRST);
            var before = frame.journal.events(frame.task);
            var first = frame.bindings().getFirst();
            frame.routes(SECOND);
            assertEquals(SECOND, frame.revision());
            var second = frame.bindings().getLast();
            assertEquals("RECOVERY_REVIEW_REBOUND", second.type());
            assertEquals(ORIGINAL, second.action());
            assertEquals(rebound(first, FIRST, SECOND), second.detail());
            assertTrue(frame.questions.getLast().exactAction().contains("previousBindingEvent=" + first.id()));
            assertTrue(frame.questions.getLast().exactAction().contains("previousReviewRevision=" + FIRST));
            frame.routes(THIRD);
            assertEquals(THIRD, frame.revision());
            var third = frame.bindings().getLast();
            assertEquals(rebound(second, SECOND, THIRD), third.detail());
            frame.routes(ORIGINAL);
            assertEquals(ORIGINAL, frame.revision(), "Returning to the deployment route still requires current recovery authorization");
            assertEquals(rebound(third, THIRD, ORIGINAL), frame.bindings().getLast().detail());
            assertEquals(3, frame.questions.size());
            var all = frame.journal.events(frame.task);
            assertEquals(before, all.subList(0, before.size()));
            assertEquals(frame.saved, frame.journal.find(frame.task).orElseThrow());
            assertEquals(ORIGINAL, frame.saved.identity().configurationDigest());
            assertEquals(ORIGINAL, frame.checkpointRevision);
        }
    }

    @Test
    void unchangedLastRebindingDoesNotRepeatAuthorizationOrReuseAnOlderRevision() throws Exception {
        try (var frame = new Frame()) {
            frame.bound(FIRST);
            frame.routes(SECOND);
            assertEquals(SECOND, frame.revision());
            var before = frame.journal.events(frame.task);
            assertEquals(SECOND, frame.revision());
            assertEquals(1, frame.questions.size());
            assertEquals(before, frame.journal.events(frame.task));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED", "UNKNOWN", "EMPTY_GRANT"})
    void refusalOrMissingCurrentGrantNeverAppendsOrUsesPriorPermission(String refusal) throws Exception {
        try (var frame = new Frame()) {
            frame.bound(FIRST);
            frame.routes(SECOND);
            frame.emptyGrant = refusal.equals("EMPTY_GRANT");
            frame.outcome = frame.emptyGrant ? TaskResult.SUCCEEDED : TaskResult.valueOf(refusal);
            var before = frame.journal.events(frame.task);
            var error = assertThrows(ApiException.class, frame::revision);
            assertEquals("DEPLOYMENT_AGENT_RECOVERY_BINDING_REFUSED", error.problem().eventCode());
            assertEquals(1, frame.questions.size());
            assertEquals(before, frame.journal.events(frame.task));
            assertEquals(frame.saved, frame.journal.find(frame.task).orElseThrow());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"language", "approval", "identity", "chain", "checkpoint"})
    void changesDuringAuthorizationStopBeforeAppendingARebinding(String mutation) throws Exception {
        try (var frame = new Frame()) {
            frame.bound(FIRST);
            frame.routes(SECOND);
            var before = frame.bindings();
            frame.onAuthorize = () -> {
                switch (mutation) {
                    case "language" -> frame.language = THIRD;
                    case "approval" -> frame.approval = THIRD;
                    case "identity" -> frame.journal.state(frame.task, "WAITING");
                    case "chain" -> frame.journal.event(frame.task, "RECOVERY_REVIEW_BOUND", ORIGINAL, THIRD);
                    case "checkpoint" -> frame.checkpointRevision = THIRD;
                    default -> throw new AssertionError(mutation);
                }
            };
            var error = assertThrows(Exception.class, frame::revision);
            if (mutation.equals("checkpoint")) assertInstanceOf(SecurityException.class, error);
            else {
                String expected = switch (mutation) {
                    case "identity" -> "DEPLOYMENT_AGENT_RECOVERY_IDENTITY_CHANGED";
                    case "chain" -> "DEPLOYMENT_AGENT_RECOVERY_BINDING_CHANGED";
                    default -> "DEPLOYMENT_COMMAND_CONFIGURATION_CHANGED";
                };
                assertEquals(expected, assertInstanceOf(ApiException.class, error).problem().eventCode());
            }
            assertEquals(1, frame.questions.size());
            assertEquals(before, frame.bindings().subList(0, before.size()));
            assertEquals(mutation.equals("chain") ? 2 : 1, frame.bindings().size());
            assertTrue(frame.bindings().stream().noneMatch(event -> event.type().equals("RECOVERY_REVIEW_REBOUND")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate-bound", "missing-bound", "foreign-bound", "foreign-rebound", "invalid-sha",
        "previous-id", "previous-revision", "same-revision", "extra-field", "unknown-event"})
    void malformedOrUnlinkedHistoriesFailBeforeAskingForAnyNewAuthorization(String mutation) throws Exception {
        try (var frame = new Frame()) {
            if (!mutation.equals("missing-bound")) {
                frame.journal.event(frame.task, "RECOVERY_REVIEW_BOUND", mutation.equals("foreign-bound") ? THIRD : ORIGINAL,
                    mutation.equals("invalid-sha") ? "b".repeat(63) : FIRST);
            }
            var first = frame.bindings().stream().findFirst();
            switch (mutation) {
                case "duplicate-bound" -> frame.bound(SECOND);
                case "missing-bound" -> frame.journal.event(frame.task, "RECOVERY_REVIEW_REBOUND", ORIGINAL,
                    "previousEventId=1\npreviousReviewRevision=" + FIRST + "\nreviewRevision=" + SECOND);
                case "foreign-rebound" -> frame.journal.event(frame.task, "RECOVERY_REVIEW_REBOUND", THIRD, rebound(first.orElseThrow(), FIRST, SECOND));
                case "previous-id" -> frame.journal.event(frame.task, "RECOVERY_REVIEW_REBOUND", ORIGINAL,
                    rebound(first.orElseThrow(), FIRST, SECOND).replace("previousEventId=" + first.orElseThrow().id(), "previousEventId=999999"));
                case "previous-revision" -> frame.journal.event(frame.task, "RECOVERY_REVIEW_REBOUND", ORIGINAL,
                    rebound(first.orElseThrow(), FIRST, SECOND).replace("previousReviewRevision=" + FIRST, "previousReviewRevision=" + THIRD));
                case "same-revision" -> frame.journal.event(frame.task, "RECOVERY_REVIEW_REBOUND", ORIGINAL, rebound(first.orElseThrow(), FIRST, FIRST));
                case "extra-field" -> frame.journal.event(frame.task, "RECOVERY_REVIEW_REBOUND", ORIGINAL, rebound(first.orElseThrow(), FIRST, SECOND) + "\npermission=APPROVED");
                case "unknown-event" -> frame.journal.event(frame.task, "RECOVERY_REVIEW_UNKNOWN", ORIGINAL, SECOND);
                default -> {
                }
            }
            frame.routes(SECOND);
            var before = frame.journal.events(frame.task);
            var error = assertThrows(ApiException.class, frame::revision);
            assertEquals("DEPLOYMENT_AGENT_RECOVERY_BINDING_INVALID", error.problem().eventCode());
            assertEquals(0, frame.questions.size());
            assertEquals(before, frame.journal.events(frame.task));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "checkpoint"})
    void originalIdentityChangesAreRejectedBeforeRouteAdoption(String mutation) throws Exception {
        try (var frame = new Frame()) {
            frame.routes(SECOND);
            if (mutation.equals("snapshot")) frame.journal.state(frame.task, "WAITING");
            else frame.checkpointRevision = THIRD;
            var before = frame.journal.events(frame.task);
            assertThrows(Exception.class, frame::revision);
            assertEquals(0, frame.questions.size());
            assertEquals(before, frame.journal.events(frame.task));
        }
    }

    @Test
    void adoptedRevisionRemainsFrozenForTheCurrentCommandScope() throws Exception {
        try (var frame = new Frame()) {
            frame.bound(FIRST);
            frame.routes(SECOND);
            String selected = frame.revision();
            var before = frame.journal.events(frame.task);
            frame.routes(THIRD);
            var error = assertThrows(ApiException.class, () -> new DeploymentCommandReviewScope(frame.context,
                frame.host, frame.task, frame.saved.identity().approvalMode(), frame.saved.identity().rememberAuthorization(), () -> selected));
            assertEquals("DEPLOYMENT_COMMAND_CONFIGURATION_CHANGED", error.problem().eventCode());
            assertEquals(1, frame.questions.size());
            assertEquals(before, frame.journal.events(frame.task));
        }
    }

    private static String rebound(DeploymentAgentActionLog.Event previous, String priorRevision, String selected) {
        return "previousEventId=" + previous.id() + "\npreviousReviewRevision=" + priorRevision + "\nreviewRevision=" + selected;
    }

    private static final class Frame implements AutoCloseable {
        private final UUID taskId = UUID.randomUUID();
        private final String jdbc = "jdbc:h2:mem:recovery-review-" + taskId;
        private final Connection retained = DriverManager.getConnection(jdbc);
        private final TaskContext task = proxy(TaskContext.class, (name, args) -> {
            assertEquals("id", name);
            return taskId;
        });
        private final DeploymentAgentActionLog journal;
        private final DeploymentAgentActionLog.Snapshot saved;
        private final List<ActionIntent> questions = new ArrayList<>();
        private final InteractionService host;
        private final ModContext context;
        private String language = ORIGINAL;
        private String approval = ORIGINAL;
        private String checkpointRevision = ORIGINAL;
        private TaskResult outcome = TaskResult.SUCCEEDED;
        private boolean emptyGrant;
        private Hook onAuthorize = () -> {
        };

        Frame() throws Exception {
            try (var source = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
                "/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql")), StandardCharsets.UTF_8)) {
                org.h2.tools.RunScript.execute(retained, source);
            }
            var db = proxy(ModDatabase.class, (name, args) -> name.equals("identity")
                ? new SchemaIdentity(DeploymentSchemaIdentity.OWNER, "1", taskId) : DriverManager.getConnection(jdbc));
            journal = new DeploymentAgentActionLog(db);
            journal.create(task, new DeploymentAgentActionLog.Identity("original-server", "d".repeat(64), ORIGINAL, "MANUAL_REVIEW", false));
            journal.state(task, "UNKNOWN");
            journal.event(task, "SOURCE", "original-source", "f".repeat(64));
            journal.event(task, "CANDIDATE", "original-component", "original-candidate");
            journal.event(task, "HEALTH_REVIEW_DENIED", "original-health-action", "original-denial-preserved");
            saved = journal.find(task).orElseThrow();
            var ai = proxy(AiService.class, (name, args) -> {
                assertEquals("configurationRevision", name, "Route adoption cannot call any model");
                assertSame(task, args[1]);
                return args[0] == AiPurpose.LANGUAGE ? language : approval;
            });
            var tasks = proxy(TaskService.class, (name, args) -> {
                assertEquals("findCheckpoint", name);
                assertSame(task, args[0]);
                return Optional.of(proxy(TaskCheckpoint.class, (method, supplied) -> {
                    assertEquals("snapshot", method, "Route adoption cannot advance, close or replay original commands");
                    return new TaskCheckpoint.Snapshot(0,
                        "{\"version\":1,\"task\":\"" + taskId + "\",\"server\":\"original-server\",\"fingerprint\":\"original-fingerprint\",\"modelRevision\":\""
                            + checkpointRevision + "\",\"commands\":[]}", false);
                }));
            });
            host = proxy(InteractionService.class, (name, args) -> {
                assertEquals("approveRequired", name, "Recovery route authorization is not a command or workflow grant");
                assertSame(task, args[1]);
                questions.add((ActionIntent) args[0]);
                onAuthorize.run();
                return new OperationResult<>(outcome, outcome == TaskResult.SUCCEEDED && !emptyGrant
                    ? Optional.of(new ApprovalGrant(UUID.randomUUID())) : Optional.empty(), outcome == TaskResult.SUCCEEDED
                    ? Optional.empty() : Optional.of(new Problem(outcome == TaskResult.UNKNOWN ? ProblemCode.UNKNOWN : ProblemCode.ACCESS_DENIED,
                        "CONTROLLED_RECOVERY_REBIND_REFUSAL", "Controlled route authorization was not granted", Map.of(), Optional.of(taskId))));
            });
            context = proxy(ModContext.class, (name, args) -> switch (name) {
                case "db" -> db;
                case "ai" -> ai;
                case "tasks" -> tasks;
                case "interaction" -> host;
                default -> throw new AssertionError("Route adoption cannot access " + name);
            });
        }

        private void routes(String selected) {
            language = selected;
            approval = selected;
        }

        private void bound(String revision) throws Exception {
            journal.event(task, "RECOVERY_REVIEW_BOUND", ORIGINAL, revision);
        }

        private List<DeploymentAgentActionLog.Event> bindings() throws Exception {
            return journal.events(task).stream().filter(event -> event.type().startsWith("RECOVERY_REVIEW_")).toList();
        }

        private String revision() throws Exception {
            return DeploymentAgentRecoveryReview.revision(context, task, saved, journal);
        }

        public void close() throws Exception {
            retained.close();
        }
    }

    @FunctionalInterface
    private interface Hook {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String name, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation callback) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (self, method, args) -> callback.call(method.getName(), args)));
    }
}
