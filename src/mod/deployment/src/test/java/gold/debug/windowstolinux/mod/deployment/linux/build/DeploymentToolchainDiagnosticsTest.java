package gold.debug.windowstolinux.mod.deployment.linux.build;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedPreparationReview;
import org.junit.jupiter.api.Test;

class DeploymentToolchainDiagnosticsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID TASK = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final DeploymentRemoteLayout LAYOUT = DeploymentRemoteLayout.original();
    private static final String FINGERPRINT = "controlled-fingerprint";

    private static DeploymentToolchainDiagnostics.Request original() {
        return new DeploymentToolchainDiagnostics.Request(UUID.fromString("11111111-1111-1111-1111-111111111111"),
            TASK.toString(), "fixture-server", DeploymentTrackedToolchains.sha256(FINGERPRINT.getBytes(StandardCharsets.UTF_8)),
            "b".repeat(64), LAYOUT, List.of("prepare", "PHP", "8.3", "-", "120", "-"));
    }

    private static byte[] attachment(DeploymentToolchainDiagnostics.Request request) throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(request.bytes());
        root.remove(List.of("remoteLayout", "arguments"));
        root.put("requestSha256", request.sha256()).put("preparationStatus", "FAILED").put("failureCategory", "installation")
            .put("stage", "system-dependencies").put("executable", "apt-get").put("actualExitCode", 100)
            .put("timedOut", false).put("cancelled", false).put("stdout", "actual stdout tail\n")
            .put("stderr", "actual stderr tail\n").put("stdoutTruncated", false).put("stderrTruncated", false);
        return (root + "\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void exactCanonicalOriginalRequestAndSeparateReadTargetsAreRequired() throws Exception {
        var request = original();
        assertArrayEquals(request.bytes(), DeploymentToolchainDiagnostics.decode(request.bytes()).bytes());
        var targets = Set.of(new ActionTarget("fixture-server", "deployment-toolchain-diagnostic", request.target()),
            new ActionTarget("fixture-server", "task", TASK.toString()));
        var facts = DeploymentToolchainDiagnostics.reviewFacts("diagnose-only", DeploymentToolchainDiagnostics.command("diagnose-only", request),
            request.bytes(), "fixture-server", targets, LAYOUT);
        assertEquals(request.sha256(), facts.get("originalRequestSha256"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentToolchainDiagnostics.reviewFacts("diagnose-only",
            DeploymentToolchainDiagnostics.command("diagnose-only", request), request.bytes(), "fixture-server",
            Set.of(new ActionTarget("fixture-server", "deployment-build", LAYOUT.toolchains())), LAYOUT));
        assertThrows(IllegalArgumentException.class, () -> DeploymentToolchainDiagnostics.decode((new String(request.bytes(), StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8)));
        String diagnostic = DeploymentToolchainDiagnostics.attachment(attachment(request), request);
        assertEquals(100, JSON.readTree(diagnostic).path("actualExitCode").intValue());
        assertThrows(IllegalArgumentException.class, () -> DeploymentToolchainDiagnostics.attachment(diagnostic.replace(request.sha256(), "f".repeat(64)).getBytes(StandardCharsets.UTF_8), request));
        assertThrows(IllegalArgumentException.class, () -> DeploymentToolchainDiagnostics.attachment(diagnostic.replace("actual stderr tail\\n", "x".repeat(16385)).getBytes(StandardCharsets.UTF_8), request));
        assertThrows(IllegalArgumentException.class, () -> DeploymentToolchainDiagnostics.attachment(diagnostic.replace("\"preparationStatus\":\"FAILED\"", "\"preparationStatus\":\"SUCCEEDED\"").getBytes(StandardCharsets.UTF_8), request));
    }

    @Test
    void assistedCurrentContractKeepsPreparationArgumentsAndAddsOriginalDiagnosticIdentity() throws Exception {
        var original = original();
        var command = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(DeploymentToolchainDiagnostics.command("prepare-diagnosed", original), LAYOUT),
            original.bytes(), java.time.Duration.ofSeconds(135), 65536);
        var intent = RemoteActions.command(new ServerId("fixture-server"), original.planRevision(), command).withSemantics(new ActionSemantics(
            "deployment.toolchain.prepare.v1", Set.of(new ActionTarget("fixture-server", "deployment-build", LAYOUT.toolchains()))));
        var facts = DeploymentAssistedPreparationReview.facts(intent, command, LAYOUT);
        assertEquals("PHP", ((Map<?, ?>) facts.get("arguments")).get("ecosystem"));
        assertEquals("8.3", ((Map<?, ?>) facts.get("arguments")).get("catalogBranch"));
        assertEquals(original.sha256(), ((Map<?, ?>) facts.get("diagnostic")).get("requestSha256"));
    }

    @Test
    void failureAttachmentUsesANewApprovalAndKeepsTheOriginalFailureWhenReadIsDenied() throws Exception {
        for (boolean denyDiagnostic : List.of(false, true)) {
            var fixture = new Fixture(denyDiagnostic);
            var result = fixture.channel().prepare(DeploymentToolchainType.PHP, "8.3", "-", 120, "-");
            assertEquals(TaskResult.FAILED, result.outcome());
            assertEquals("ORIGINAL_EXIT_NONZERO", result.problem().orElseThrow().eventCode());
            assertEquals(2, fixture.grants.size());
            assertNotEquals(fixture.grants.getFirst(), fixture.grants.getLast());
            assertEquals(denyDiagnostic ? 1 : 2, fixture.commands.size());
            assertEquals("persist", fixture.events.getFirst());
            if (!denyDiagnostic) {
                var context = result.problem().orElseThrow().context();
                assertEquals(100, JSON.readTree(context.get("toolchainDiagnostic")).path("actualExitCode").intValue());
                assertEquals("system-dependencies", JSON.readTree(context.get("toolchainDiagnostic")).path("stage").textValue());
                assertTrue(fixture.commands.getLast().script().contains("'diagnose-only'"));
                assertArrayEquals(fixture.commands.getFirst().stdin(), fixture.commands.getLast().stdin());
            } else assertFalse(result.problem().orElseThrow().context().containsKey("toolchainDiagnostic"));
        }
    }

    @Test
    void mismatchedOriginalTaskIsRejectedBeforeAnyReviewOrTransport() throws Exception {
        var fixture = new Fixture(false);
        var foreign = new DeploymentToolchainDiagnostics.Request(original().operationId(), UUID.randomUUID().toString(),
            original().serverId(), original().fingerprintSha256(), original().planRevision(), LAYOUT, original().arguments());
        assertThrows(IllegalArgumentException.class, () -> fixture.channel().toolchainDiagnostic(foreign));
        assertTrue(fixture.grants.isEmpty());
        assertTrue(fixture.commands.isEmpty());
    }

    @Test
    void durableRequestFailureStopsBeforeReviewAndOriginalBytesCanBeReadAfterRestart() throws Exception {
        var fixture = new Fixture(false); fixture.failPersistence = true;
        assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,
            () -> fixture.channel().prepare(DeploymentToolchainType.PHP, "8.3", "-", 120, "-"));
        assertTrue(fixture.grants.isEmpty());
        assertTrue(fixture.commands.isEmpty());
        var request = original();
        var task = (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class},
            (p, method, a) -> { if (method.getName().equals("id")) return TASK; throw new AssertionError(method.getName()); });
        var checkpoint = (gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.class}, (p, method, a) -> {
                if (method.getName().equals("snapshot")) return new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(
                    0, new String(request.bytes(), StandardCharsets.UTF_8), true);
                throw new AssertionError(method.getName());
            });
        var tasks = (gold.debug.windowstolinux.appapi.task.TaskService) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (p, method, a) -> {
                assertEquals("findCheckpoint", method.getName()); assertSame(task, a[0]);
                assertEquals(DeploymentToolchainDiagnostics.checkpointKey(request.operationId()), a[1]);
                return Optional.of(checkpoint);
            });
        assertArrayEquals(request.bytes(), DeploymentToolchainDiagnostics.retained(tasks, task, request.operationId()).orElseThrow().bytes());
    }

    private static final class Fixture {
        final boolean denyDiagnostic;
        final List<UUID> grants = new ArrayList<>();
        final List<CommandRequest> commands = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        boolean failPersistence;
        Fixture(boolean denyDiagnostic) { this.denyDiagnostic = denyDiagnostic; }
        DeploymentBuildCommandChannel channel() {
            var task = (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class}, (p, method, a) -> {
                if (method.getName().equals("id")) return TASK;
                throw new AssertionError(method.getName());
            });
            var remote = (RemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RemoteSession.class}, (p, method, a) -> {
                if (method.getName().equals("facts")) return new RemoteFacts("linux", "amd64", FINGERPRINT);
                if (!method.getName().equals("execute")) throw new AssertionError(method.getName());
                var command = (CommandRequest) a[0]; commands.add(command); events.add("execute");
                var request = DeploymentToolchainDiagnostics.decode(command.stdin());
                if (command.script().contains("'diagnose-only'")) return new OperationResult<>(TaskResult.SUCCEEDED,
                    Optional.of(new CommandResult(Optional.of(0), new String(attachment(request), StandardCharsets.UTF_8), "", false)), Optional.empty());
                return new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.of(65),
                    "TOOLCHAIN_FAILURE=installation:official toolchain installation command failed: apt-get\n", "", false)),
                    Optional.of(new Problem(ProblemCode.IO_FAILURE, "ORIGINAL_EXIT_NONZERO", "original failure", Map.of(), Optional.empty())));
            });
            var interaction = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (p, method, a) -> {
                if (!method.getName().equals("approve")) throw new AssertionError(method.getName());
                var grant = UUID.randomUUID(); grants.add(grant); events.add("review");
                if (denyDiagnostic && grants.size() == 2) return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.ACCESS_DENIED, "DIAGNOSTIC_DENIED", "controlled", Map.of(), Optional.empty())));
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(grant)), Optional.empty());
            });
            return new DeploymentBuildCommandChannel(remote, interaction, new ServerId("fixture-server"), task, "b".repeat(64),
                new DeploymentBuildBudget(600), () -> events.add("guard"), LAYOUT, new DeploymentPreparationObserver() {
                    @Override public void diagnosticRequested(DeploymentToolchainDiagnostics.Request request, TaskContext supplied)
                            throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                        assertEquals(TASK.toString(), request.taskId()); events.add("persist");
                        if (failPersistence) throw new gold.debug.windowstolinux.appapi.task.result.ApiException(new Problem(
                            ProblemCode.IO_FAILURE, "DURABLE_REQUEST_UNAVAILABLE", "controlled", Map.of(), Optional.empty()));
                    }
                });
        }
    }
}
