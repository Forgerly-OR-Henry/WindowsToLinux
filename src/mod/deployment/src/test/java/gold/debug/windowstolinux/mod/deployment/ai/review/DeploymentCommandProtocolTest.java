package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms;
import org.junit.jupiter.api.Test;

class DeploymentCommandProtocolTest {
    private final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
    private final ServerId server = new ServerId("protocol-fixture");
    private final ModContext owner = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{ModContext.class}, (proxy, method, args) -> { throw new AssertionError("No capability access: " + method.getName()); });

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void trackedToolchainOperationsRequireTheSameExactOriginalInputInBothModes(boolean custom) throws Exception {
        var layout = custom ? new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
            "/srv/tracked/helper", "/srv/tracked/apps", "/srv/tracked/state", "/srv/tracked/config", "/srv/tracked/data")) : this.layout;
        UUID original = UUID.randomUUID();
        var identity = new gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.Identity(
            UUID.randomUUID().toString(), "sample-api", 1, "a".repeat(64), "b".repeat(64));
        var prepare = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.prepare(UUID.randomUUID(),
            original.toString(), identity, server.id(), layout,
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.NODE, "22", "-", 120, "-");
        var empty = new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains("fixture", List.of());
        String encoded = java.util.Base64.getEncoder().encodeToString(
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.encode(empty).getBytes(StandardCharsets.UTF_8));
        var binding = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.bind(UUID.randomUUID(),
            original.toString(), identity, server.id(), layout, encoded);
        var targets = Set.of(new ActionTarget(server.id(), "task", original.toString()),
            new ActionTarget(server.id(), "deployment-build", layout.toolchains()));
        var common = new DeploymentFixedCommandProtocol(owner);
        var agent = new DeploymentAgentCommandProtocol(owner, Map::of);
        for (String verb : List.of("prepare-tracked", "bind-tracked", "observe-tracked", "cleanup-tracked")) {
            var originalRequest = verb.equals("bind-tracked") ? binding : prepare;
            String operation = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.operation(verb);
            String invocation = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.command(verb, originalRequest);
            try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(invocation, layout),
                    originalRequest.bytes(), Duration.ofSeconds(60), 32768)) {
                var intent = intent(operation, request, targets);
                assertEquals(common.inspect(intent, request, layout, task(original)), agent.inspect(intent, request, layout, task(original)));
                assertEquals("TRACKED_TOOLCHAIN_REQUEST", common.inspect(intent, request, layout, task(original)).get("inputKind"));
                assertThrows(IllegalArgumentException.class, () -> common.inspect(intent, request, layout, task(UUID.randomUUID())));
                var changed = intent.withSemantics(new ActionSemantics(operation,
                    Set.of(new ActionTarget(server.id(), "task", original.toString()))));
                assertThrows(IllegalArgumentException.class, () -> common.inspect(changed, request, layout, task(original)));
                assertThrows(IllegalArgumentException.class, () -> agent.inspect(changed, request, layout, task(original)));
                String changedBytes = new String(originalRequest.bytes(), StandardCharsets.UTF_8).replace("sample-api", "other-api");
                try (var wrong = new CommandRequest(request.script(), changedBytes.getBytes(StandardCharsets.UTF_8), request.timeout(), request.outputLimitBytes())) {
                    var wrongIntent = intent(operation, wrong, targets);
                    assertThrows(IllegalArgumentException.class, () -> common.inspect(wrongIntent, wrong, layout, task(original)));
                    assertThrows(IllegalArgumentException.class, () -> agent.inspect(wrongIntent, wrong, layout, task(original)));
                }
            }
        }
    }

    @Test
    void actualHostProbeProducesIdenticalCompleteProtocolEvidenceInBothModes() throws Exception {
        var original = task(UUID.randomUUID());
        String script = gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityProbe.render();
        try (var request = new CommandRequest(script, new byte[0], Duration.ofMinutes(2), 65536)) {
            var intent = intent("deployment.candidate.host-probe.v1", request, Set.of(new ActionTarget(server.id(), "deployment-host", server.id())));
            var common = new DeploymentFixedCommandProtocol(owner).inspect(intent, request, layout, original);
            var agent = new DeploymentAgentCommandProtocol(owner, Map::of).inspect(intent, request, layout, original);
            assertEquals(common, agent);
            assertEquals("HOST", ((Map<?, ?>) common.get("platformProbe")).get("variant"));
            assertTrue(gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.facts(common, 1048576).contains(script.replace("\n", "\\n").substring(0, 30)));
        }
    }

    @Test
    void identicalCommonInputHasIdenticalEvidenceInBothModesAndChangedTargetsFail() throws Exception {
        var task = task(UUID.randomUUID());
        var common = new DeploymentFixedCommandProtocol(owner);
        var agent = new DeploymentAgentCommandProtocol(owner, () -> { throw new AssertionError("No Agent receipts for common input"); });
        try (var request = new CommandRequest(DeploymentCandidateChannel.capacityScript(layout), new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = intent("deployment.candidate.capacity.v1", request, Set.of(new ActionTarget(server.id(),
                "deployment-workspace", DeploymentCandidateChannel.capacityTarget(layout))));
            assertEquals(common.inspect(intent, request, layout, task), agent.inspect(intent, request, layout, task));
            var changed = intent.withSemantics(new ActionSemantics("deployment.candidate.capacity.v1",
                Set.of(new ActionTarget(server.id(), "deployment-workspace", "/changed"))));
            assertThrows(IllegalArgumentException.class, () -> common.inspect(changed, request, layout, task));
            assertThrows(IllegalArgumentException.class, () -> agent.inspect(changed, request, layout, task));
        }
    }

    @Test
    void originalSnapshotCannotBeReviewedUnderAnotherTask() throws Exception {
        UUID original = UUID.randomUUID();
        String script = DeploymentManagedHelperBundle.verifiedCommand("'" + layout.helperPath()
            + "' 'snapshot-deployment' 'demo' '" + "a".repeat(64) + "' '" + original + "'", layout);
        try (var request = new CommandRequest(script, new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = intent("deployment.publication.snapshot.v1", request, Set.of(
                new ActionTarget(server.id(), "deployment-release", layout.applicationRoot("demo")),
                new ActionTarget(server.id(), "deployment-snapshot", layout.snapshotRoot("demo", original))));
            var common = new DeploymentFixedCommandProtocol(owner);
            assertTrue(common.inspect(intent, request, layout, task(original)).containsKey("snapshotTaskMatchesOriginalContext"));
            assertThrows(IllegalArgumentException.class, () -> common.inspect(intent, request, layout, task(UUID.randomUUID())));
        }
    }

    @Test
    void optionalObservationsCannotAdmitUnknownOperationsOrChangedExecutableInput() throws Exception {
        var task = task(UUID.randomUUID());
        var observed = new AtomicInteger();
        DeploymentCurrentCommandEvidence observations = (intent, request, supplied) -> { observed.incrementAndGet(); return Map.of("approved", true); };
        InteractionService host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{InteractionService.class}, (proxy, method, args) -> { throw new AssertionError("No host grant"); });
        DeploymentCommandReviewModel model = (instructions, facts, intent, request, revision, supplied) -> { throw new AssertionError("No model call"); };
        try (var request = new CommandRequest("printf original", "original".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30), 4096);
             var changed = new CommandRequest(request.script(), "changed".getBytes(StandardCharsets.UTF_8), request.timeout(), request.outputLimitBytes())) {
            var unknown = intent("deployment.unknown.v1", request, Set.of(new ActionTarget(server.id(), "task", task.id().toString())));
            var refused = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(owner, host, unknown, request, task,
                "fixture", "AUTOMATIC", false, model, observations, true));
            assertEquals("DEPLOYMENT_COMMAND_UNREGISTERED", refused.problem().eventCode());
            var original = intent("deployment.build.preflight.v1", request, unknown.semantics().orElseThrow().targets());
            var drift = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(owner, host, original, changed, task,
                "fixture", "AUTOMATIC", false, model, observations, true));
            assertEquals("DEPLOYMENT_COMMAND_REQUEST_CHANGED", drift.problem().eventCode());
            assertEquals(0, observed.get());
        }
    }

    @Test
    void agentOperationIsRejectedByCommonProtocolAndMalformedPrivateInputCannotBecomeOpaque() throws Exception {
        var common = new DeploymentFixedCommandProtocol(owner);
        var agent = new DeploymentAgentCommandProtocol(owner, Map::of);
        byte[] source = DeploymentAgentToolchainPrograms.dependencies(layout).getBytes(StandardCharsets.UTF_8);
        try (var request = new CommandRequest("/usr/bin/python3 -I -S -c 'unrecognized launcher'", source, Duration.ofSeconds(30), 65536)) {
            var intent = intent("deployment.agent.toolchain.dependencies.v1", request,
                Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains())));
            assertFalse(common.registered(intent)); assertTrue(agent.registered(intent));
            assertThrows(IllegalArgumentException.class, () -> common.inspect(intent, request, layout, task(UUID.randomUUID())));
            assertThrows(IllegalArgumentException.class, () -> agent.inspect(intent, request, layout, task(UUID.randomUUID())));
        }
    }

    @Test
    void agentRunRequiresTheAuthenticTaskWhileOriginalCandidateQueriesRemainSupported() throws Exception {
        UUID original = UUID.randomUUID();
        var current = task(original);
        var other = task(UUID.randomUUID());
        var agent = new DeploymentAgentCommandProtocol(owner, Map::of);
        byte[] body = "printf fixture\n".getBytes(StandardCharsets.UTF_8);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        String invocation = "'" + layout.helperPath() + "' 'agent-run' 'demo' 'demo-aaaaaaaaaaaaaaaa' '" + original
            + "' '" + "a".repeat(64) + "' '10' '8192' 'ordinary' '" + sha + "' '-'";
        var targets = Set.of(new ActionTarget(server.id(), "deployment-build", layout.candidateRoot("demo-aaaaaaaaaaaaaaaa")));
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(invocation, layout), body, Duration.ofSeconds(30), 8192)) {
            var intent = intent("deployment.agent.helper.v1", request, targets);
            assertTrue(agent.inspect(intent, request, layout, current).containsKey("agentExecutableInputBinding"));
            assertThrows(IllegalArgumentException.class, () -> agent.inspect(intent, request, layout, other));
        }
        String query = "'" + layout.helperPath() + "' 'candidate-query-task' 'demo' 'demo-aaaaaaaaaaaaaaaa' '" + original + "'";
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(query, layout), new byte[0], Duration.ofSeconds(30), 8192)) {
            var intent = intent("deployment.agent.helper.v1", request, targets);
            assertEquals("EMPTY", agent.inspect(intent, request, layout, other).get("inputKind"));
        }
    }

    @Test
    void taskLayoutDriftAfterModelOrHostCannotReturnAnExecutableGrant() throws Exception {
        var changedLayout = new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
            "/srv/review-protocol/helper", "/srv/review-protocol/apps", "/srv/review-protocol/state", "/srv/review-protocol/config", "/srv/review-protocol/data"));
        for (String phase : List.of("model", "host")) {
            var supplied = task(UUID.randomUUID());
            var captured = new java.util.concurrent.atomic.AtomicReference<>(layout);
            var grants = new AtomicInteger();
            var checkpoint = (gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("snapshot")) throw new AssertionError(method.getName());
                    var json = new com.fasterxml.jackson.databind.ObjectMapper();
                    var node = json.createObjectNode().put("formatVersion", 1).put("task", supplied.id().toString()).put("layoutSha256", captured.get().digest());
                    node.set("layout", json.readTree(captured.get().canonical()));
                    return new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(0, node.toString(), false);
                });
            var tasks = (gold.debug.windowstolinux.appapi.task.TaskService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findCheckpoint") && args[0] == supplied) return java.util.Optional.of(checkpoint);
                    throw new AssertionError(method.getName());
                });
            var ai = (gold.debug.windowstolinux.appapi.ai.AiService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.ai.AiService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("configurationRevision")) return "fixture";
                    throw new AssertionError("No provider: " + method.getName());
                });
            var ui = (gold.debug.windowstolinux.appapi.ui.UiService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("environment")) return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH, "LIGHT", false);
                    throw new AssertionError(method.getName());
                });
            var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> switch (method.getName()) { case "tasks" -> tasks; case "ai" -> ai; case "ui" -> ui; default -> throw new AssertionError(method.getName()); });
            var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (proxy, method, args) -> {
                assertEquals("approveReviewedAction", method.getName()); grants.incrementAndGet(); captured.set(changedLayout);
                return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,
                    java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())), java.util.Optional.empty());
            });
            DeploymentCommandReviewModel model = (instruction, facts, intent, request, revision, actualTask) -> {
                if (phase.equals("model")) captured.set(changedLayout);
                return new DeploymentCommandReviewModel.Result(new DeploymentReviewDecision("APPROVE", false, "controlled"), Map.of());
            };
            try (var request = new CommandRequest("printf fixture", new byte[0], Duration.ofSeconds(30), 4096)) {
                var intent = intent("deployment.build.preflight.v1", request, Set.of(new ActionTarget(server.id(), "task", supplied.id().toString())));
                var refusal = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context, host, intent, request, supplied,
                    "fixture", "AUTOMATIC", false, model, DeploymentCurrentCommandEvidence.NONE, true));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", refusal.problem().eventCode());
                assertEquals(phase.equals("model") ? 0 : 1, grants.get());
            }
        }
    }

    @Test
    void completeProtocolSnapshotRejectsRemovedKeysAndNestedMutationAfterModelOrHost() throws Exception {
        for (String phase : List.of("model", "host")) {
            for (String change : List.of("removed-key", "nested-value")) {
                var fixture = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
                var context = reviewContext(fixture);
                var nested = new LinkedHashMap<String, Object>(); nested.put("value", "original");
                var protocolFacts = new LinkedHashMap<String, Object>();
                protocolFacts.put("operation", "deployment.build.preflight.v1");
                protocolFacts.put("requiredBinding", "present"); protocolFacts.put("nested", nested);
                Runnable drift = () -> { if (change.equals("removed-key")) protocolFacts.remove("requiredBinding"); else nested.put("value", "changed"); };
                var protocol = new DeploymentCommandProtocol() {
                    public boolean registered(ActionIntent intent) { return true; }
                    public Map<String, Object> inspect(ActionIntent intent, CommandRequest request, DeploymentRemoteLayout selected, TaskContext supplied) {
                        assertSame(fixture.task, supplied); assertEquals(layout, selected); return protocolFacts;
                    }
                };
                var grants = new AtomicInteger();
                var reviews = new AtomicInteger();
                var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (proxy, method, args) -> {
                    assertEquals("approveReviewedAction", method.getName()); grants.incrementAndGet(); drift.run();
                    return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,
                        java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())), java.util.Optional.empty());
                });
                DeploymentCommandReviewModel model = (instructions, facts, intent, request, revision, supplied) -> {
                    reviews.incrementAndGet();
                    @SuppressWarnings("unchecked") var frozenNested = (Map<String, Object>) facts.get("nested");
                    assertNotSame(nested, frozenNested);
                    assertEquals("original", frozenNested.get("value"));
                    assertThrows(UnsupportedOperationException.class, () -> frozenNested.put("value", "model-change"));
                    if (phase.equals("model")) drift.run();
                    assertEquals("original", frozenNested.get("value"), "Review bytes cannot alias subsequent protocol mutation");
                    return new DeploymentCommandReviewModel.Result(new DeploymentReviewDecision("APPROVE", false, "controlled"), Map.of());
                };
                try (var request = new CommandRequest("printf fixture", new byte[0], Duration.ofSeconds(30), 4096)) {
                    var intent = intent("deployment.build.preflight.v1", request, Set.of(new ActionTarget(server.id(), "task", fixture.task.id().toString())));
                    var refusal = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context, host, intent, request, fixture.task,
                        "fixture-route", "AUTOMATIC", false, model, DeploymentCurrentCommandEvidence.NONE, true, protocol), phase + ":" + change);
                    assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", refusal.problem().eventCode());
                    assertEquals(phase.equals("model") ? 0 : 1, grants.get()); assertEquals(1, reviews.get());
                    assertTrue(fixture.requests.isEmpty(), "No real provider call is needed for this boundary test");
                }
            }
        }
    }

    @Test
    void unchangedCompleteProtocolFactsReceiveFreshGrantsInBothModes() throws Exception {
        var fixture = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
        var context = reviewContext(fixture);
        var grants = new AtomicInteger();
        var reviews = new AtomicInteger();
        var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (proxy, method, args) -> {
            assertEquals("approveReviewedAction", method.getName()); grants.incrementAndGet();
            return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,
                java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())), java.util.Optional.empty());
        });
        DeploymentCommandReviewModel model = (instructions, facts, intent, request, revision, supplied) -> {
            assertEquals("deployment.candidate.capacity.v1", facts.get("operation")); reviews.incrementAndGet();
            return new DeploymentCommandReviewModel.Result(new DeploymentReviewDecision("APPROVE", false, "controlled"), Map.of());
        };
        try (var request = new CommandRequest(DeploymentCandidateChannel.capacityScript(layout), new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = intent("deployment.candidate.capacity.v1", request, Set.of(new ActionTarget(server.id(), "deployment-workspace", DeploymentCandidateChannel.capacityTarget(layout))));
            for (DeploymentCommandProtocol protocol : List.of(new DeploymentFixedCommandProtocol(context), new DeploymentAgentCommandProtocol(context, Map::of))) {
                var grant = DeploymentFixedCommandApproval.approve(context, host, intent, request, fixture.task, "fixture-route", "AUTOMATIC", false,
                    model, DeploymentCurrentCommandEvidence.NONE, true, protocol);
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, grant.outcome());
                assertTrue(grant.value().isPresent());
            }
        }
        assertEquals(2, reviews.get()); assertEquals(2, grants.get()); assertTrue(fixture.requests.isEmpty());
    }

    @Test
    void retainedEvidenceForwardsBothInstructionFormsWorkflowAndLifecycleInOrderAndRejectsNullKeyCollision() throws Exception {
        var events = new ArrayList<String>();
        WorkflowGrant workflow = () -> {};
        var first = observer("retained", events, workflow);
        var second = observer("current", events, workflow);
        var composition = DeploymentCommandReviewScope.class.getDeclaredMethod("retain", DeploymentCurrentCommandEvidence.class, DeploymentCurrentCommandEvidence.class);
        composition.setAccessible(true);
        var combined = (DeploymentCurrentCommandEvidence) composition.invoke(null, first, second);
        var task = task(UUID.randomUUID());
        try (var request = new CommandRequest("printf fixture", new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = intent("deployment.build.preflight.v1", request, Set.of(new ActionTarget(server.id(), "task", task.id().toString())));
            assertEquals(Map.of("retained", "facts", "current", "facts"), combined.facts(intent, request, task));
            assertEquals("retained-default\ncurrent-default", combined.reviewInstructions());
            assertEquals("retained-request\ncurrent-request", combined.reviewInstructions(intent, request, task));
            combined.workflowOpened(workflow); combined.verify(intent, request, task);
            combined.reviewed(intent, request, task, new DeploymentReviewDecision("APPROVE", false, "fixture"));
            assertEquals(List.of("retained-workflow", "current-workflow", "retained-verify", "current-verify", "retained-reviewed", "current-reviewed"), events);
            var missing = new LinkedHashMap<String, Object>(); missing.put("same", null);
            var collision = (DeploymentCurrentCommandEvidence) composition.invoke(null,
                (DeploymentCurrentCommandEvidence) (i, r, t) -> missing,
                (DeploymentCurrentCommandEvidence) (i, r, t) -> Map.of("same", "replacement"));
            assertThrows(IllegalArgumentException.class, () -> collision.facts(intent, request, task));
        }
    }

    private ModContext reviewContext(gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture fixture) {
        var ui = (gold.debug.windowstolinux.appapi.ui.UiService) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class}, (proxy, method, args) -> {
                if (method.getName().equals("environment")) return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH, "LIGHT", false);
                throw new AssertionError(method.getName());
            });
        return (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("ui")) return ui;
            return method.invoke(fixture.context, args);
        });
    }

    private DeploymentCurrentCommandEvidence observer(String name, List<String> events, WorkflowGrant expected) {
        return new DeploymentCurrentCommandEvidence() {
            public Map<String, ?> facts(ActionIntent i, CommandRequest r, TaskContext t) { return Map.of(name, "facts"); }
            public String reviewInstructions() { return name + "-default"; }
            public String reviewInstructions(ActionIntent i, CommandRequest r, TaskContext t) { return name + "-request"; }
            public void workflowOpened(WorkflowGrant workflow) { assertSame(expected, workflow); events.add(name + "-workflow"); }
            public void verify(ActionIntent i, CommandRequest r, TaskContext t) { events.add(name + "-verify"); }
            public void reviewed(ActionIntent i, CommandRequest r, TaskContext t, DeploymentReviewDecision verdict) { events.add(name + "-reviewed"); }
        };
    }
    private ActionIntent intent(String operation, CommandRequest request, Set<ActionTarget> targets) {
        return RemoteActions.command(server, "a".repeat(64), request).withSemantics(new ActionSemantics(operation, targets));
    }
    private TaskContext task(UUID id) {
        return (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class},
            (proxy, method, args) -> { if (method.getName().equals("id")) return id; throw new AssertionError(method.getName()); });
    }
}
