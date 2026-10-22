package gold.debug.windowstolinux.mod.deployment.linux.build;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainSetup;
import org.junit.jupiter.api.Test;

class DeploymentTrackedToolchainsTest {
    private static final UUID OP = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TASK = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String EXECUTION = "33333333-3333-3333-3333-333333333333";
    private static final DeploymentRemoteLayout LAYOUT = DeploymentRemoteLayout.original();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void originalRequestMatchesHelperGoldenAndRetainsOnlyDefensiveByteCopies() {
        var request = DeploymentTrackedToolchains.prepare(OP, TASK.toString(),
            new DeploymentTrackedToolchains.Identity(EXECUTION, "sample-api", 1, "a".repeat(64), "b".repeat(64)),
            "fixture-server", LAYOUT, DeploymentToolchainType.NODE, "22", "-", 120, "-");
        assertEquals("5c8a3904fa80a54f92bfa08c20ed25d2f2b85adf75e24648126216bf48d35897", request.sha256());
        byte[] encoded = request.bytes();
        var decoded = DeploymentTrackedToolchains.decode(encoded);
        encoded[0] = 0;
        assertArrayEquals(request.bytes(), decoded.bytes());
        assertEquals(request.sha256(), decoded.sha256());
    }

    @Test
    void malformedUnknownDuplicateBooleanVersionBomAndTrailingRequestsFailClosed() {
        String original = new String(original().bytes(), StandardCharsets.UTF_8);
        for (String invalid : List.of(original.replace("\"formatVersion\":1", "\"formatVersion\":true"),
            original.replace("\"seconds\":120", "\"seconds\":true"),
            original.replace("\"formatVersion\":1", "\"formatVersion\":1,\"extra\":0"),
            original.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            original + " {}", "\ufeff" + original, original.replace("\"javaHome\":\"-\"", "\"javaHome\":\"/tmp/unrelated\""), original.replace("\"recoveryAttempt\":1", "\"recoveryAttempt\":0"),
            original.replace(EXECUTION, "not-an-execution"))) {
            assertThrows(IllegalArgumentException.class, () -> DeploymentTrackedToolchains.decode(invalid.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(IllegalArgumentException.class, () -> DeploymentTrackedToolchains.decode(new byte[]{(byte) 0xc3, (byte) 0x28}));
        assertThrows(IllegalArgumentException.class, () -> DeploymentTrackedToolchains.decode(new byte[131073]));
    }

    @Test
    void lostOriginalExecutionCanStillHaveVerifiedEffectsWithoutBecomingSucceeded() {
        var request = original();
        var observation = DeploymentTrackedToolchains.observation(receipt(request, "UNKNOWN", true), request);
        assertEquals(DeploymentTrackedToolchains.ExecutionStatus.UNKNOWN, observation.executionStatus());
        assertTrue(observation.prepared());
        assertFalse(observation.bound());
        assertThrows(IllegalArgumentException.class, () -> DeploymentTrackedToolchains.observation(
            new String(receipt(request, "UNKNOWN", true), StandardCharsets.UTF_8).replace(request.sha256(), "0".repeat(64)).getBytes(StandardCharsets.UTF_8), request));
    }

    @Test
    void bindingObservationRequiresTheCompleteOriginalBindingAndExactDigest() {
        var tools = new DeploymentResolvedToolchains("fixture-v1", List.of());
        String encoded = Base64.getEncoder().encodeToString(DeploymentToolchainBindingCodec.encode(tools).getBytes(StandardCharsets.UTF_8));
        var request = DeploymentTrackedToolchains.bind(OP, TASK.toString(), identity(), "fixture-server", LAYOUT, encoded);
        var value = DeploymentTrackedToolchains.observation(receipt(request, "UNKNOWN", true), request);
        assertTrue(value.bound());
        assertEquals(DeploymentToolchainBindingCodec.identity(tools), value.bindingSha256());
        assertEquals(tools, request.binding());
        String changed = new String(receipt(request, "UNKNOWN", true), StandardCharsets.UTF_8)
            .replace(value.bindingSha256(), "c".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> DeploymentTrackedToolchains.observation(changed.getBytes(StandardCharsets.UTF_8), request));
    }

    @Test
    void recipeRestoresTheExactToolchainSlotAndRejectsUnknownFormatterVersions() {
        var tools = new DeploymentResolvedToolchains("fixture-v1", List.of());
        var recipe = new DeploymentTrackedToolchains.ScriptRecipe(1, 1, "prefix\n", "suffix\n");
        assertEquals("prefix\nsuffix\n", recipe.render(tools));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentTrackedToolchains.ScriptRecipe(1, 2, "", ""));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentTrackedToolchains.ScriptRecipe(2, 1, "", ""));
    }

    @Test
    void everyTrackedActionPersistsIntentBeforeReviewAndDispatchAfterGuard() throws Exception {
        var fixture = new Fixture();
        var channel = fixture.channel();
        assertEquals(TaskResult.SUCCEEDED, channel.prepareTracked(DeploymentToolchainType.NODE, "22", "-", 120, "-").outcome());
        var request = fixture.requests.getFirst();
        fixture.replyStatus = "UNKNOWN";
        assertEquals(TaskResult.SUCCEEDED, channel.observeTracked(request).outcome());
        assertEquals(TaskResult.SUCCEEDED, channel.cleanupTracked(request).outcome());
        assertEquals(List.of("intent:prepare-tracked", "review", "guard", "dispatch:prepare-tracked", "execute", "observed:prepare-tracked",
            "intent:observe-tracked", "review", "guard", "dispatch:observe-tracked", "execute", "observed:observe-tracked",
            "intent:cleanup-tracked", "review", "guard", "dispatch:cleanup-tracked", "execute", "observed:cleanup-tracked"), fixture.events);
        assertEquals(3, fixture.grants.size());
        assertEquals(3, fixture.grants.stream().distinct().count());
        for (byte[] input : fixture.inputs) assertArrayEquals(request.bytes(), input);
    }

    @Test
    void failedDurableIntentDispatchOrHostCheckNeverSends() throws Exception {
        for (String failure : List.of("intent", "dispatch", "fingerprint")) {
            var fixture = new Fixture(); fixture.fail = failure;
            if (failure.equals("fingerprint")) assertThrows(IllegalArgumentException.class,
                () -> fixture.channel().prepareTracked(DeploymentToolchainType.NODE, "22", "-", 120, "-"));
            else assertThrows(ApiException.class,
                () -> fixture.channel().prepareTracked(DeploymentToolchainType.NODE, "22", "-", 120, "-"));
            assertEquals(0, fixture.inputs.size());
            if (failure.equals("intent") || failure.equals("fingerprint")) assertFalse(fixture.events.contains("review"));
        }
        var fixture = new Fixture(); fixture.deny = true;
        assertEquals(TaskResult.FAILED, fixture.channel().prepareTracked(DeploymentToolchainType.NODE, "22", "-", 120, "-").outcome());
        assertEquals(List.of("intent:prepare-tracked", "review", "observed:prepare-tracked"), fixture.events);
        assertTrue(fixture.inputs.isEmpty());
    }

    @Test
    void cancellationBeforeSendAndFailureWithoutReceiptAfterSendRemainDistinct() throws Exception {
        var cancelled = new Fixture(); cancelled.deny = true; cancelled.deniedOutcome = TaskResult.CANCELLED;
        assertEquals(TaskResult.CANCELLED, cancelled.channel().prepareTracked(DeploymentToolchainType.NODE, "22", "-", 120, "-").outcome());
        assertTrue(cancelled.inputs.isEmpty());
        var failed = new Fixture(); failed.transportFailure = true;
        assertEquals(TaskResult.UNKNOWN, failed.channel().prepareTracked(DeploymentToolchainType.NODE, "22", "-", 120, "-").outcome());
        assertEquals(1, failed.inputs.size());
    }

    @Test
    void setupStopsOnUnknownWithoutPublishingAnUnsentBindingAndNeverReplaysPreparation() throws Exception {
        var fixture = new Fixture(); fixture.replyStatus = "UNKNOWN";
        var channel = fixture.channel();
        var requirement = ToolchainRequirement.declared(DeploymentToolchainType.NODE, "22", "package.json", ToolchainRequirement.PurposeType.BUILD);
        var result = new DeploymentToolchainSetup(channel, DeploymentToolchainCatalog.defaults()).prepare(List.of(requirement), 0, 120);
        assertEquals(TaskResult.UNKNOWN, result.outcome());
        assertEquals(1, fixture.inputs.size());
        assertTrue(fixture.events.contains("dispatch:prepare-tracked"));
        assertFalse(fixture.events.contains("dispatch:bind-tracked"));
        var original = fixture.requests.getFirst();
        assertEquals(TaskResult.SUCCEEDED, channel.observeTracked(original).outcome());
        assertEquals(2, fixture.inputs.size());
        assertArrayEquals(fixture.inputs.getFirst(), fixture.inputs.getLast());
    }

    @Test
    void inconsistentSuccessfulReceiptWithUnsettledResidueCannotAdvancePreparationOrBinding() throws Exception {
        var requirement = ToolchainRequirement.declared(DeploymentToolchainType.NODE, "22", "package.json", ToolchainRequirement.PurposeType.BUILD);
        for (String phase : List.of("COMPLETED", "CLEANED")) {
            for (var kind : DeploymentTrackedToolchains.Kind.values()) {
                var fixture = new Fixture(); fixture.residue = kind; fixture.residuePhase = phase;
                var result = new DeploymentToolchainSetup(fixture.channel(), DeploymentToolchainCatalog.defaults())
                    .prepare(List.of(requirement), 0, 120);
                assertEquals(TaskResult.UNKNOWN, result.outcome());
                assertTrue(result.value().isEmpty());
                assertEquals(kind == DeploymentTrackedToolchains.Kind.PREPARE ? 1 : 2, fixture.inputs.size());
                var original = fixture.requests.getLast();
                assertEquals(kind, original.kind());
                if (phase.equals("COMPLETED")) {
                    assertEquals(TaskResult.UNKNOWN, fixture.lastObservation.outcome());
                    assertTrue(fixture.lastObservation.value().isEmpty(), "A contradictory completion receipt is rejected by the codec");
                } else {
                    assertEquals(DeploymentTrackedToolchains.ExecutionStatus.SUCCEEDED,
                        fixture.lastObservation.value().orElseThrow().executionStatus(), "An original successful exit does not settle currently unconfirmed cleanup");
                    assertEquals(DeploymentTrackedToolchains.CleanupState.REQUIRED, fixture.lastObservation.value().orElseThrow().cleanupState());
                }
                assertEquals("SUCCEEDED", JSON.readTree(fixture.lastReply).path("executionStatus").asText());
                assertEquals("REQUIRED", JSON.readTree(fixture.lastReply).path("cleanupState").asText());
            }
        }
    }

    private static DeploymentTrackedToolchains.Identity identity() {
        return new DeploymentTrackedToolchains.Identity(EXECUTION, "sample-api", 1, "a".repeat(64),
            DeploymentTrackedToolchains.sha256("fingerprint".getBytes(StandardCharsets.UTF_8)));
    }
    private static DeploymentTrackedToolchains.Request original() {
        return DeploymentTrackedToolchains.prepare(OP, TASK.toString(), identity(), "fixture-server", LAYOUT,
            DeploymentToolchainType.NODE, "22", "-", 120, "-");
    }
    private static byte[] receipt(DeploymentTrackedToolchains.Request request, String status, boolean verified) {
        var root = JSON.createObjectNode().put("formatVersion", 1).put("operationId", request.operationId().toString())
            .put("taskId", request.taskId()).put("requestSha256", request.sha256()).put("originalIntentSha256", request.identity().originalIntentSha256())
            .put("kind", request.kind().name()).put("executionStatus", status)
            .put("effectState", verified ? request.kind() == DeploymentTrackedToolchains.Kind.PREPARE ? "PREPARED_VERIFIED" : "BINDING_VERIFIED" : "UNSETTLED")
            .put("phase", status.equals("SUCCEEDED") ? "COMPLETED" : "PREPARING");
        if (request.kind() == DeploymentTrackedToolchains.Kind.PREPARE && verified) {
            root.putObject("selection").put("ecosystem", "NODE").put("version", "22.0.0")
                .put("directory", LAYOUT.toolchains() + "/versions/node-" + "c".repeat(64)).put("origin", "MANAGED")
                .put("source", "https://nodejs.org/dist/v22.0.0/node-v22.0.0-linux-x64.tar.xz").put("sha256", "d".repeat(64));
        } else root.putNull("selection");
        if (request.kind() == DeploymentTrackedToolchains.Kind.BIND && verified)
            root.put("bindingSha256", DeploymentToolchainBindingCodec.identity(request.binding()));
        else root.putNull("bindingSha256");
        root.putNull("failureCategory").put("cleanupState", "NOT_NEEDED").putArray("retainedEffects").add(
            request.kind() == DeploymentTrackedToolchains.Kind.PREPARE ? "SHARED_INSTALLATION" : "IMMUTABLE_BINDING");
        return (root + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static final class Fixture implements DeploymentTrackedToolchains.OperationLog {
        final List<String> events = new ArrayList<>();
        final List<byte[]> inputs = new ArrayList<>();
        final List<UUID> grants = new ArrayList<>();
        final List<DeploymentTrackedToolchains.Request> requests = new ArrayList<>();
        String fail = "", replyStatus = "SUCCEEDED";
        boolean deny, transportFailure;
        DeploymentTrackedToolchains.Kind residue;
        OperationResult<DeploymentTrackedToolchains.Observation> lastObservation;
        String lastReply;
        String residuePhase = "COMPLETED";
        TaskResult deniedOutcome = TaskResult.FAILED;
        DeploymentBuildCommandChannel channel() {
            var task = (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class},
                (proxy, method, args) -> { if (method.getName().equals("id")) return TASK; throw new AssertionError(method.getName()); });
            var remote = (RemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RemoteSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("facts")) return new RemoteFacts("linux", "amd64", fail.equals("fingerprint") ? "changed" : "fingerprint");
                    if (!method.getName().equals("execute")) throw new AssertionError(method.getName());
                    events.add("execute"); var command = (CommandRequest) args[0]; inputs.add(command.stdin());
                    if (transportFailure) return new OperationResult<CommandResult>(TaskResult.FAILED, Optional.empty(), Optional.of(problem()));
                    var request = DeploymentTrackedToolchains.decode(command.stdin());
                    String stdout = new String(receipt(request, replyStatus, true), StandardCharsets.UTF_8);
                    if (request.kind() == residue) stdout = stdout.replace("\"cleanupState\":\"NOT_NEEDED\"", "\"cleanupState\":\"REQUIRED\"")
                        .replace("\"phase\":\"COMPLETED\"", "\"phase\":\"" + residuePhase + "\"");
                    lastReply = stdout;
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), stdout, "", false)), Optional.empty());
                });
            var interaction = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("approve")) throw new AssertionError(method.getName());
                    events.add("review");
                    if (deny) return new OperationResult<>(deniedOutcome, Optional.empty(), Optional.of(problem()));
                    var grant = UUID.randomUUID(); grants.add(grant);
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(grant)), Optional.empty());
                });
            return new DeploymentBuildCommandChannel(remote, interaction, new ServerId("fixture-server"), task, "a".repeat(64),
                new DeploymentBuildBudget(180), () -> events.add("guard"), LAYOUT, DeploymentPreparationObserver.NONE,
                new DeploymentTrackedToolchains.Session(identity(), this));
        }
        public void intent(DeploymentTrackedToolchains.Request request, String verb) throws ApiException {
            events.add("intent:" + verb); if (fail.equals("intent")) throw new ApiException(problem()); requests.add(request);
        }
        public void dispatched(DeploymentTrackedToolchains.Request request, String verb) throws ApiException {
            events.add("dispatch:" + verb); if (fail.equals("dispatch")) throw new ApiException(problem());
        }
        public void observed(DeploymentTrackedToolchains.Request request, String verb,
                             OperationResult<DeploymentTrackedToolchains.Observation> observation) { events.add("observed:" + verb); lastObservation = observation; }
        public void recipe(DeploymentTrackedToolchains.Identity identity, DeploymentTrackedToolchains.ScriptRecipe recipe) { events.add("recipe"); }
        private static Problem problem() { return new Problem(ProblemCode.IO_FAILURE, "CONTROLLED_FAILURE", "controlled", Map.of(), Optional.empty()); }
    }
}
