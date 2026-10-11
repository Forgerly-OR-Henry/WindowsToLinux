package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.linux.helper.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript;
import org.junit.jupiter.api.Test;

class DeploymentAgentHelperBootstrapEvidenceTest {
    private static final String OPERATION = "0123456789abcdef0123456789abcdef";

    private static final String REVISION = "a".repeat(64);

    private final ServerId server = new ServerId("bootstrap-original");

    private CommandRequest request(String source, String launcher, Duration timeout, int output) {
        return new CommandRequest(launcher, source.getBytes(StandardCharsets.UTF_8), timeout, output);
    }

    private CommandRequest request() {
        return request(DeploymentAgentHelperInstallerScript.render(OPERATION), DeploymentAgentHelperInstallerScript.LAUNCHER, Duration.ofHours(2), 131072);
    }

    private ActionIntent original(CommandRequest request) {
        return RemoteActions.command(server, REVISION, request).withSemantics(new ActionSemantics(DeploymentAgentHelperBootstrapEvidence.OPERATION,
            gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper.installationTargets(server, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original())));
    }

    @Test
    void originalOperationAndCommandAcquireCompleteEffectsWithoutChangingExecutable() throws Exception {
        try (var request = request()) {
            var initial = original(request);
            var expanded = DeploymentAgentHelperBootstrapEvidence.upgrade(initial, request, server, REVISION, OPERATION);
            assertEquals(initial.exactAction(), expanded.exactAction());
            assertEquals(initial, expanded.withSemantics(initial.semantics().orElseThrow()));
            assertEquals(DeploymentAgentHelperBootstrapEvidence.targets(server), expanded.semantics().orElseThrow().targets());
            assertTrue(expanded.semantics().orElseThrow().targets().contains(new ActionTarget(server.id(), "system-package-transaction",
                "APT:authenticated-dependency-closure:HELPER_RUNTIME")));
            assertFalse(expanded.semantics().orElseThrow().targets().stream().anyMatch(t -> t.id().contains("crb")));
            assertTrue(expanded.semantics().orElseThrow().targets().contains(new ActionTarget(server.id(), "file", DeploymentRemoteLayoutAdmission.UNIT_PATH)));
            assertTrue(expanded.semantics().orElseThrow().targets().contains(new ActionTarget(server.id(), "systemd-service", DeploymentRemoteLayoutAdmission.UNIT_NAME)));
            assertTrue(expanded.semantics().orElseThrow().targets().contains(new ActionTarget(server.id(), "service-manager", "systemd")));
            for (String root : List.of("/usr/local/lib/windowstolinux", "/opt/windowstolinux/apps", "/var/lib/windowstolinux", "/etc/opt/windowstolinux/apps", "/var/opt/windowstolinux/apps"))
                assertTrue(expanded.semantics().orElseThrow().targets().contains(new ActionTarget(server.id(), "directory", root)));
            var evidence = new ObjectMapper().valueToTree(DeploymentAgentHelperBootstrapEvidence.describe(DeploymentAgentHelperInstallerScript.render(OPERATION)));
            assertEquals(DeploymentAgentHelperInstallerScript.render(OPERATION), DeploymentInstallerEvidence.reconstruct(evidence));
            assertArrayEquals(DeploymentAgentHelperInstallerScript.render(OPERATION).getBytes(StandardCharsets.UTF_8), request.stdin());
        }
    }

    @Test
    void changedSourceOperationLauncherBoundsOrOriginalIntentCannotBeUpgraded() {
        String source = DeploymentAgentHelperInstallerScript.render(OPERATION);
        for (String fault : List.of("source", "operation", "launcher", "timeout", "output", "server", "revision", "semantics", "targets", "expanded")) {
            try (var request = request(fault.equals("source") ? source + "echo changed\n" : source,
                fault.equals("launcher") ? "exec /bin/bash --noprofile --norc -s" : DeploymentAgentHelperInstallerScript.LAUNCHER,
                fault.equals("timeout") ? Duration.ofMinutes(1) : Duration.ofHours(2), fault.equals("output") ? 1 : 131072)) {
                ActionIntent initial = switch (fault) {
                    case "server" ->
                        RemoteActions.command(new ServerId("other"), REVISION, request).withSemantics(original(request).semantics().orElseThrow());
                    case "revision" ->
                        RemoteActions.command(server, "b".repeat(64), request).withSemantics(original(request).semantics().orElseThrow());
                    case "semantics" -> RemoteActions.command(server, REVISION, request);
                    case "targets" ->
                        original(request).withSemantics(new ActionSemantics(DeploymentAgentHelperBootstrapEvidence.OPERATION,
                            Set.of(new ActionTarget(server.id(), "file", "/unrelated"))));
                    case "expanded" ->
                        DeploymentAgentHelperBootstrapEvidence.upgrade(original(request), request, server, REVISION, OPERATION);
                    default -> original(request);
                };
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHelperBootstrapEvidence.upgrade(initial, request, server, REVISION,
                    fault.equals("operation") ? "f".repeat(32) : OPERATION), fault);
            }
        }
    }

    private Map<String, Object> current(ActionIntent expanded) {
        var current = new LinkedHashMap<String, Object>();
        current.put("operation", DeploymentAgentHelperBootstrapEvidence.OPERATION);
        current.put("serverTargets", expanded.serverIds());
        current.put("businessRevision", expanded.revision());
        current.put("exactAction", expanded.exactAction());
        current.put("resourceTargets", expanded.semantics().orElseThrow().targets().stream().toList());
        return current;
    }

    @Test
    void bootstrapNavigationLocatesActualGuardsAndFullDependencyProgramWithoutDuplicatingSource() throws Exception {
        String script = DeploymentAgentHelperInstallerScript.render(OPERATION);
        var json = new ObjectMapper();
        var evidence = json.valueToTree(DeploymentAgentHelperBootstrapEvidence.describe(script));
        var navigation = json.valueToTree(DeploymentAgentHelperBootstrapEvidence.navigation(script));
        assertEquals(DeploymentAgentHelperInstallerScript.LAUNCHER, navigation.path("launcher").asText());
        assertEquals("HELPER_RUNTIME", navigation.path("profile").asText());
        assertEquals(5, navigation.path("wrapperLocations").size());
        assertTrue(navigation.path("dependencyLocations").size() >= 20);
        var locations = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        locations.add(navigation.path("prefixSource"));
        locations.add(navigation.path("dependencyProgram"));
        navigation.path("wrapperLocations").forEach(locations::add);
        navigation.path("invocations").forEach(locations::add);
        for (var location : locations) {
            String source = evidence.at(location.path("sourcePointer").asText().substring("/completeExecutableInput".length())).textValue();
            int first = location.path("startCharacter").asInt();
            String fragment = source.substring(first, first + location.path("characters").asInt());
            assertEquals(location.path("utf8Bytes").asInt(), fragment.getBytes(StandardCharsets.UTF_8).length);
            assertEquals(location.path("sha256").asText(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(fragment.getBytes(StandardCharsets.UTF_8))));
            assertEquals(location.path("startLine").asInt(), 1 + source.substring(0, first).chars().filter(value -> value == '\n').count());
            assertFalse(location.has("excerpt"), "Navigation cannot repeat a complete executable prefix");
        }
        String prefix = evidence.at("/segments/0/text").asText();
        for (var location : navigation.path("dependencyLocations")) {
            assertEquals("/completeExecutableInput/segments/0/text", location.path("sourcePointer").asText());
            String line = prefix.lines().skip(location.path("startLine").asLong() - 1).findFirst().orElseThrow();
            assertTrue(line.startsWith("def " + location.path("function").asText() + "("));
        }
        assertTrue(navigation.toString().getBytes(StandardCharsets.UTF_8).length < 16000);
        assertFalse(navigation.toString().contains("DEPENDENCY_PACKAGES = json.loads("));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHelperBootstrapEvidence.navigation(script + "echo changed\n"));
    }

    @Test
    void currentEvidenceRejectsMissingExtraDuplicateOrForeignEffectsAndRequestIdentity() {
        try (var request = request()) {
            var expanded = DeploymentAgentHelperBootstrapEvidence.upgrade(original(request), request, server, REVISION, OPERATION);
            var complete = current(expanded);
            String source = DeploymentAgentHelperInstallerScript.render(OPERATION);
            assertDoesNotThrow(() -> DeploymentAgentHelperBootstrapEvidence.requireCurrent(complete, source));
            for (String fault : List.of("missing", "extra", "duplicate", "foreign", "action", "operation", "servers", "source")) {
                var changed = new LinkedHashMap<>(complete);
                var targets = new ArrayList<>(expanded.semantics().orElseThrow().targets());
                switch (fault) {
                    case "missing" -> targets.removeFirst();
                    case "extra" -> targets.add(new ActionTarget(server.id(), "file", "/extra"));
                    case "duplicate" -> targets.add(targets.getFirst());
                    case "foreign" ->
                        targets.set(0, new ActionTarget("other", targets.getFirst().kind(), targets.getFirst().id()));
                    case "action" -> changed.put("exactAction", expanded.exactAction() + " changed");
                    case "operation" -> changed.put("operation", "deployment.helper.install.files.v1");
                    case "servers" -> changed.put("serverTargets", List.of(server.id(), "other"));
                }
                changed.put("resourceTargets", targets);
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHelperBootstrapEvidence.requireCurrent(changed,
                    fault.equals("source") ? source + "echo extra\n" : source), fault);
            }
        }
    }

    @Test
    void independentDenialKeepsItsProblemAndDispatchesNothing() throws Exception {
        var calls = new AtomicInteger();
        var beforeSend = new AtomicInteger();
        var reviews = new AtomicInteger();
        var problem = new Problem(ProblemCode.ACCESS_DENIED, "BOOTSTRAP_DENIED", "Denied", Map.of(), Optional.empty());
        var fixture = new NativeAiFixture();
        var helper = new DeploymentManagedHelper(session(calls, new ArrayDeque<>()), noHostGrant(), server, fixture.task, REVISION,
            beforeSend::incrementAndGet, (intent, request, task) -> {
            reviews.incrementAndGet();
            DeploymentAgentHelperBootstrapEvidence.upgrade(intent, request, server, REVISION, OPERATION);
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(problem));
        });
        var result = helper.installFilesWithPrerequisites(DeploymentAgentHelperInstallerScript.LAUNCHER, DeploymentAgentHelperInstallerScript.prerequisites(OPERATION));
        assertEquals(TaskResult.FAILED, result.outcome());
        assertSame(problem, result.problem().orElseThrow());
        assertEquals(1, reviews.get());
        assertEquals(0, calls.get());
        assertEquals(0, beforeSend.get());
    }

    @Test
    void dispatchedUnconfirmedBootstrapIsUnknownAndNeverAutomaticallyReplayedOrProbed() throws Exception {
        var calls = new AtomicInteger();
        var problem = new Problem(ProblemCode.UNKNOWN, "BOOTSTRAP_UNCONFIRMED", "Unconfirmed", Map.of(), Optional.empty());
        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem)));
        var fixture = new NativeAiFixture();
        var helper = new DeploymentManagedHelper(session(calls, replies), noHostGrant(), server, fixture.task, REVISION, DeploymentSendGuard.NONE,
            (intent, request, task) -> {
                DeploymentAgentHelperBootstrapEvidence.upgrade(intent, request, server, REVISION, OPERATION);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())), Optional.empty());
            });
        var result = helper.installFilesWithPrerequisites(DeploymentAgentHelperInstallerScript.LAUNCHER, DeploymentAgentHelperInstallerScript.prerequisites(OPERATION));
        assertEquals(TaskResult.UNKNOWN, result.outcome());
        assertEquals(1, calls.get());
        assertTrue(replies.isEmpty());
    }

    private RemoteSession session(AtomicInteger calls, ArrayDeque<OperationResult<CommandResult>> replies) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
            if (method.getName().equals("execute")) {
                calls.incrementAndGet();
                assertEquals(DeploymentAgentHelperInstallerScript.render(OPERATION), new String(((CommandRequest) args[0]).stdin(), StandardCharsets.UTF_8));
                return replies.removeFirst();
            }
            throw new AssertionError(method.getName());
        });
    }

    private InteractionService noHostGrant() {
        return new InteractionService() {
            @Override
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task) {
                throw new AssertionError("Fixed independent review owns this grant");
            }

            @Override
            public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext task) {
                throw new AssertionError("No user input expected");
            }
        };
    }
}
