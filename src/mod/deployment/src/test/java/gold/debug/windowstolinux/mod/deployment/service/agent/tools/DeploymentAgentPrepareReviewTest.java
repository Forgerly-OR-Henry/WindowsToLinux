package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentSession;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.api.Test;

/** Actual H2 task ledger and native Agent roles, with local controlled model and transport only. */
class DeploymentAgentPrepareReviewTest extends DeploymentRuntimeFixture {
    private static final String REVISION = "a".repeat(64);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts interactionPrompts() {
        return proxy(gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts.class, (p, m, a) -> {
            if (m.getName().equals("approve")) return true;
            throw new AssertionError("No text, transfer or unrequested interaction");
        });
    }

    @Test
    void openInstallRefusalDeliversBoundReplyThenReviewsCurrentSelectionBeforeFirstInstallSend() throws Exception {
        runDiscussion(false);
    }

    @Test
    void closedInstallRefusalCannotReopenFromReplyReplanNonceOrIdenticalReadiness() throws Exception {
        runDiscussion(true);
    }

    @Test
    void onlyCapturedOriginalNativeDeniedVerdictsQualifyForAlternativeFeedback() {
        UUID id = UUID.randomUUID();
        TaskContext task = proxy(TaskContext.class, (p, m, a) -> id);
        for (String status : List.of("OPEN", "CLOSED")) {
            var data = new HashMap<>(Map.of("task", id.toString(), "reviewBinding", "b".repeat(64), "discussionStatus", status));
            var denied = new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "Actual review data", data, Optional.of(id)));
            var stopped = new DeploymentAgentPrepareNotSentException(denied, task, "api", REVISION, new ServerId("native-host"), REVISION, REVISION, REVISION);
            assertSame(denied, stopped.originalFailure());
            assertSame(denied, stopped.getCause());
            assertSame(denied, stopped.nativeReviewDenial(task).orElseThrow());
            assertTrue(stopped.nativeReviewDenial(proxy(TaskContext.class, (p, m, a) -> id)).isEmpty());
            for (String field : List.of("task", "reviewBinding", "discussionStatus")) {
                var invalid = new HashMap<>(data);
                invalid.put(field, "unbound");
                var fake = new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "Unbound review data", invalid, Optional.of(id)));
                assertTrue(new DeploymentAgentPrepareNotSentException(fake, task, "api", REVISION, new ServerId("native-host"), REVISION, REVISION, REVISION)
                    .nativeReviewDenial(task).isEmpty());
            }
            for (var problem : List.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "Unknown", data, Optional.of(id)),
                new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_INVALID", "Invalid", data, Optional.of(id))))
                assertTrue(new DeploymentAgentPrepareNotSentException(new ApiException(problem), task, "api", REVISION, new ServerId("native-host"), REVISION, REVISION, REVISION)
                    .nativeReviewDenial(task).isEmpty());
        }
    }

    private void runDiscussion(boolean closed) throws Exception {
        var source = Files.createDirectories(root.resolve("native-source"));
        Files.writeString(source.resolve("index.html"), "<html>actual local source</html>\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(gold.debug.windowstolinux.app.service.server.ServerProfile.fromStored(
                new gold.debug.windowstolinux.app.db.server.StoredServerProfile("native-host", "127.0.0.1", 22, "root", "fixture")), "fixture".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var finished = new java.util.concurrent.CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("native-prepare-review", "Bound local native refusal discussion", List.of("native-host"), true), task -> {
                try {
                    var selection = actual.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, 1048576, 100, task);
                         var archive = new DeploymentSourceArchiver(actual.file()).prepare(material, 1048576, task)) {
                        new Frame(actual, task, material, archive, closed).run();
                    }
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                    throw error;
                } finally {
                    try {
                        actual.file().closeTaskResources(task);
                    } finally {
                        finished.complete(null);
                    }
                }
            });
            try {
                finished.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                handle.requestCancel();
                throw timeout;
            }
            if (failure.get() != null)
                throw new AssertionError("Controlled native prepare discussion failed", failure.get());
        }
    }

    private final class Frame {
        final TaskContext task;

        final DeploymentSourceMaterial material;

        final DeploymentSourceArchive archive;

        final boolean closed;

        final ServerId server = new ServerId("native-host");

        final DeploymentAgentActionLog journal;

        final RemoteSession peer;

        final ModContext context;

        final List<String> installerRequests = new ArrayList<>();

        final IllegalStateException stop = new IllegalStateException("Fixture stopped after the independently approved installer");

        int decisions, approvalCalls, installReviews, installGrants, installSends, readiness;

        String deniedBinding = "";

        Frame(ModContext actual, TaskContext task, DeploymentSourceMaterial material, DeploymentSourceArchive archive, boolean closed) {
            this.task = task;
            this.material = material;
            this.archive = archive;
            this.closed = closed;
            journal = new DeploymentAgentActionLog(actual.db());
            peer = proxy(RemoteSession.class, (p, m, a) -> switch (m.getName()) {
                case "facts" -> new RemoteFacts("Linux", "x86_64", "SHA256:native-controlled");
                case "close" -> null;
                case "execute" -> transport((CommandRequest) a[0]);
                default -> throw new AssertionError("No SSH or transfer operation: " + m.getName());
            });
            var ai = proxy(AiService.class, (p, m, a) -> {
                if (m.getName().equals("configurationRevision")) return REVISION;
                assertEquals("call", m.getName());
                assertSame(task, a[a.length - 1]);
                var request = (AiRequest) a[0];
                return succeeded(request.purpose() == AiPurpose.APPROVAL ? approve(request) : decide(request));
            });
            var host = proxy(InteractionService.class, (p, m, a) -> {
                if (m.getName().equals("approveReviewedAction")) {
                    var intent = (gold.debug.windowstolinux.appapi.task.action.ActionIntent) a[0];
                    if (intent.semantics().orElseThrow().operation().equals("deployment.helper.install.files.custom.v1")) {
                        assertFalse(closed);
                        assertEquals(2, installReviews);
                        assertEquals(0, installSends);
                        installGrants++;
                    }
                }
                try {
                    return m.invoke(actual.interaction(), a);
                } catch (InvocationTargetException failed) {
                    throw failed.getCause();
                }
            });
            context = proxy(ModContext.class, (p, m, a) -> {
                if (m.getName().equals("ai")) return ai;
                if (m.getName().equals("interaction")) return host;
                try {
                    return m.invoke(actual, a);
                } catch (InvocationTargetException failed) {
                    throw failed.getCause();
                }
            });
        }

        void run() throws Exception {
            journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), REVISION, "FULL_CONTROL", true));
            journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
            try (var remote = new DeploymentAgentRemoteTools(context, peer, context.interaction(), server, task, archive, material,
                REVISION, () -> REVISION, journal, Optional.empty(), "FULL_CONTROL", true);
                 var authorization = DeploymentWorkflowAuthorization.open(context, task, server, REVISION, "FULL_CONTROL", true,
                     "source=" + material.snapshot().sha256() + "\narchive=" + archive.sha256())) {
                var selected = proxy(DeploymentAgentDeliveryPort.class, (p, m, a) -> {
                    if (m.getName().equals("prepare")) {
                        var component = (DeploymentAgentPlan.Part) a[1];
                        assertEquals(decisions == 4 || closed ? "rejected.sh" : "current.sh", component.runtime().workload().command().entrypoint(),
                            "Preparation receives the current independently chosen plan, never a cached rejected plan");
                    }
                    try {
                        return m.invoke(remote, a);
                    } catch (InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
                var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Build the actual source"),
                    new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256()), selected, journal,
                    "FULL_CONTROL", true, REVISION, () -> REVISION);
                var error = assertThrows(Exception.class, session::run);
                assertEquals(closed ? 8 : 7, decisions);
                assertEquals(closed ? 1 : 2, installReviews);
                assertEquals(closed ? 0 : 1, installGrants);
                assertEquals(closed ? 0 : 1, installSends);
                if (!closed) {
                    assertSame(stop, error);
                    assertEquals(installerRequests.get(0), installerRequests.get(1), "A fresh attempt retains the exact immutable installation identity");
                }
                var events = journal.events(task);
                var first = events.stream().filter(e -> e.type().equals("PREPARE_EXECUTING")).findFirst().orElseThrow();
                assertTrue(events.stream().anyMatch(e -> e.type().equals("PREPARE_DISPATCH_NOT_SENT") && e.action().equals(first.action())));
                assertTrue(events.stream().anyMatch(e -> e.type().equals("PREPARE_NOT_SENT") && e.action().equals(first.action())
                    && e.detail().equals(DeploymentAgentPrepareNotSentException.DETAIL)));
                assertTrue(events.stream().anyMatch(e -> e.type().equals("AGENT_OBSERVATION") && e.action().equals("ALTERNATIVE_REQUIRED")));
                assertEquals(closed ? 0 : 1, events.stream().filter(e -> e.type().equals("AGENT_OBSERVATION") && e.action().equals("REVIEW_RESPONSE")).count());
                assertTrue(events.stream().noneMatch(e -> Set.of("CANDIDATE", "PREPARE_RESULT", "SEALED_ARTIFACT", "AI_PUBLICATION_VERIFIED").contains(e.type())));
                if (closed) assertTrue(DeploymentAgentActionLog.effectsSettled(events));
                else
                    assertFalse(DeploymentAgentActionLog.effectsSettled(events), "Actually sent installation is never falsely settled by prior denial proof");
                assertTrue(readiness >= 2);
            }
        }

        AiResponse approve(AiRequest request) throws Exception {
            approvalCalls++;
            var input = JSON.readTree(request.messages().stream().filter(m -> m.role() == AiRole.USER).findFirst().orElseThrow().text());
            var facts = input.path("currentRequest");
            assertFalse(facts.isMissingNode(), "The actual native approval Agent supplies a fresh complete currentRequest");
            boolean install = facts.path("operation").asText().equals("deployment.helper.install.files.custom.v1");
            if (install) {
                installReviews++;
                assertEquals(0, installGrants);
                assertEquals(0, installSends);
                installerRequests.add(facts.path("exactAction").asText());
                if (installReviews == 1) {
                    deniedBinding = input.path("reviewBinding").asText();
                    return nativeCall("a" + approvalCalls, "submit_result", JSON.createObjectNode().put("decision", "DENY")
                        .put("requiresUserConfirmation", false).put("reason", "A remaining reachable evidence page needs independent review")
                        .put("discussionStatus", closed ? "CLOSED" : "OPEN").set("missingEvidence", JSON.createArrayNode().add("Approval Agent can read the remaining helper evidence page")));
                }
                assertFalse(closed);
                assertEquals(deniedBinding, input.path("discussion").path("priorReviewBinding").asText());
                assertFalse(input.path("discussion").path("sameMeaningfulFacts").asBoolean(), "The current changed plan is independently supplied alongside the historical reply");
                assertEquals("MODEL_AUTHORED_DATA", input.at("/discussion/response/trust").asText());
                assertEquals("NONE", input.at("/discussion/response/authority").asText());
                assertEquals("Review your reachable helper evidence pages against the current request", input.at("/discussion/response/message").asText());
                assertTrue(request.messages().stream().filter(m -> m.role() == AiRole.SYSTEM)
                    .noneMatch(m -> m.text().contains("Review your reachable helper evidence pages against the current request")));
            }
            return nativeCall("a" + approvalCalls, "submit_result", JSON.createObjectNode().put("decision", "APPROVE")
                .put("requiresUserConfirmation", false).put("reason", "Exact original locally controlled request"));
        }

        AiResponse decide(AiRequest request) throws Exception {
            var state = JSON.readTree(request.messages().getLast().text()).path("state");
            int turn = decisions++;
            String tool;
            ObjectNode args;
            if (turn == 0) {
                tool = "read";
                args = JSON.createObjectNode().put("path", "index.html").put("offset", 0).put("limit", 100);
            } else if (turn == 1) {
                tool = "identify";
                args = (ObjectNode) JSON.readTree("{\"components\":[{\"component\":\"api\",\"root\":\".\",\"technology\":\"Actual static HTML\",\"runtime\":\"Review generated launcher\",\"resources\":\"No database required\",\"uncertainties\":\"Build unverified\",\"evidence\":[\"o1\"]}]}");
            } else if (turn == 2 || turn == 5) {
                tool = "plan";
                args = (ObjectNode) JSON.readTree("""
                    {"applicationId":"demo","explanation":"Plan the actually read source","components":[{"id":"api","sourceRoot":".","dependencies":[],"evidence":["o1"],
                    "backend":"PROCESS","mode":"DAEMON","entrypoint":"current.sh","arguments":[],"workingDirectory":"","health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10},
                    "ports":[],"resources":[],"configuration":[],"databases":[],"secretReferences":[]}],"applicationHealth":{"component":"api","health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10}}}
                    """);
                if (turn == 2 || closed) ((ObjectNode) args.path("components").get(0)).put("entrypoint", "rejected.sh");
            } else if (turn == 4) {
                assertEquals(0, installSends);
                assertEquals(0, installGrants);
                assertTrue(request.messages().stream().flatMap(m -> m.toolResult().stream()).anyMatch(r -> r.text().contains(deniedBinding)));
                tool = "review_response";
                args = JSON.createObjectNode().put("reviewBinding", deniedBinding)
                    .put("message", "Review your reachable helper evidence pages against the current request");
            } else if (turn == 3 || turn == 6) {
                tool = "command";
                args = JSON.createObjectNode().put("component", "api").put("seconds", 10)
                    .put("script", turn == 3 ? "printf rejected > $WTL_OUTPUT/rejected.sh" : "printf current > $WTL_OUTPUT/current.sh");
            } else {
                assertTrue(closed);
                assertEquals(7, turn);
                tool = "unable";
                args = JSON.createObjectNode().put("reason", "Original CLOSED denial remains unresolved");
            }
            if (Set.of("plan", "command").contains(tool)) {
                var assessment = args.putObject("assessment");
                var basis = assessment.putArray("basis");
                var pending = state.path("judgement").path("pending");
                if (pending.isEmpty()) basis.add("o1");
                else pending.forEach(id -> basis.add(id.asText()));
                assessment.put("suitability", "Use the current source and original result IDs").put("differences", "Earlier denial grants no authority")
                    .put("resolution", "RESOLVED").put("decision", "Choose this current complete proposal");
            }
            return nativeCall("d" + decisions, tool, args);
        }

        OperationResult<CommandResult> transport(CommandRequest request) {
            String body = new String(request.stdin(), StandardCharsets.UTF_8);
            if (body.equals(DeploymentAgentHelperReadiness.script())) {
                readiness++;
                return reply(installSends == 0 ? "FORMAT=WTL_AGENT_HELPER_READINESS_1\nSTATUS=MISSING\nREASON=HELPER_MISSING\nPACKAGED_PROTOCOL="
                    + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nHELPER=ABSENT\nBUILD_ENTRY=ABSENT\nDEPENDENCIES=ABSENT\nROOTS=VALID_OR_ABSENT\nRECEIPTS=ABSENT\n"
                    : "FORMAT=WTL_AGENT_HELPER_READINESS_1\nSTATUS=READY\nREASON=CURRENT_TASK_READY\nPACKAGED_PROTOCOL="
                    + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nHELPER=MATCH\nBUILD_ENTRY=MATCH\nDEPENDENCIES=MATCH\nROOTS=VALID_OR_ABSENT\nRECEIPTS=SETTLED\n");
            }
            if (request.script().equals(DeploymentAgentHelperInstallerScript.LAUNCHER)) {
                DeploymentAgentHelperInstallerScript.parseOperation(body);
                assertEquals(1, installGrants);
                assertEquals(2, installReviews);
                installSends++;
                return reply("HELPER_INSTALL=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                    + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            }
            if (request.script().contains(" probe\n"))
                return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION
                    + "\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            assertEquals(1, installSends);
            throw stop;
        }
    }

    private static AiResponse nativeCall(String id, String tool, ObjectNode args) {
        if (tool.equals("submit_result")) {
            for (String field : List.of("missingEvidence", "adjustments", "candidateCommands"))
                if (!args.has(field)) args.putArray(field);
        }
        return new AiResponse("", "local-controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new AiToolCall(id, tool, args.toString())), Optional.empty());
    }

    private static <T> OperationResult<T> succeeded(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static OperationResult<CommandResult> reply(String output) {
        return succeeded(new CommandResult(Optional.of(0), output, "", false));
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
