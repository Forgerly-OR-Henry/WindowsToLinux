package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentAgentToolchainCommandsTest {
    private static final ServerId SERVER = new ServerId("original-server");

    private static final String OPERATION = "a".repeat(32);

    @Test
    void declinedDependenciesNeverDispatchPreparation() throws Exception {
        var fixture = new Fixture(false, request -> failReply());
        var result = fixture.agent.prepare(DeploymentToolchainType.PYTHON, "3.14", "3.14.0", 100, "-");
        assertEquals(TaskResult.FAILED, result.outcome());
        assertEquals("REVIEW_DECLINED", result.problem().orElseThrow().eventCode());
        assertEquals(1, fixture.intents.size());
        assertTrue(fixture.requests.isEmpty());
    }

    @Test
    void unknownDependenciesRetainUnknownAndNeverDispatchPreparation() throws Exception {
        var fixture = new Fixture(true, request -> new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(new Problem(ProblemCode.UNKNOWN, "UNKNOWN_DEPENDENCIES", "unknown", Map.of(), Optional.empty()))));
        var result = fixture.agent.prepare(DeploymentToolchainType.PYTHON, "3.14", "3.14.0", 100, "-");
        assertEquals(TaskResult.UNKNOWN, result.outcome());
        assertEquals(1, fixture.requests.size());
        assertEquals("deployment.agent.toolchain.dependencies.v1", fixture.intents.getFirst().semantics().orElseThrow().operation());
    }

    @Test
    void dependencyReceiptRequiresOriginalOperationAndEveryExactPackage() throws Exception {
        for (String invalid : List.of("TOOLCHAIN_DEPENDENCIES={}\n", dependencyReceipt() + "tail", dependencyReceipt().replace(OPERATION, "b".repeat(32)))) {
            var fixture = new Fixture(true, request -> reply(invalid));
            assertEquals(TaskResult.UNKNOWN, fixture.agent.prepare(DeploymentToolchainType.PYTHON, "3.14", "3.14.0", 100, "-").outcome());
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test
    void authenticatedDependenciesAndControllerHaveSeparateOriginalGrants() throws Exception {
        var count = new AtomicInteger();
        var fixture = new Fixture(true, request -> request.script().contains(" 'prepare-tracked' '") ? typedSuccess(request) : reply(switch (count.getAndIncrement()) {
            case 0 -> dependencyReceipt();
            case 1 ->
                "TOOLCHAIN_RELEASE=" + DeploymentAgentToolchainReleaseTest.encoded("PYTHON", "3.14", "3.14.0",
                    DeploymentAgentToolchainPrograms.arguments(request.script()).get(5)) + "\n";
            default -> throw new AssertionError("Unexpected fixture phase");
        }));
        var result = fixture.agent.prepare(DeploymentToolchainType.PYTHON, "3.14", "3.14.0", 100, "-");
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
        assertEquals(4, fixture.requests.size());
        assertEquals(4, fixture.grants.size());
        assertNotEquals(fixture.grants.get(0), fixture.grants.get(1));
        assertEquals("deployment.agent.toolchain.dependencies.v1", fixture.intents.get(0).semantics().orElseThrow().operation());
        assertEquals("deployment.agent.toolchain.resolve.v1", fixture.intents.get(1).semantics().orElseThrow().operation());
        assertEquals("deployment.agent.toolchain.controller.readiness.v1", fixture.intents.get(2).semantics().orElseThrow().operation());
        assertEquals("deployment.agent.toolchain.controller.prepare.v1", fixture.intents.get(3).semantics().orElseThrow().operation());
        assertNotEquals(fixture.grants.get(1), fixture.grants.get(2));
        var packageTargets = fixture.intents.get(0).semantics().orElseThrow().targets();
        assertTrue(packageTargets.contains(new ActionTarget(SERVER.id(), "system-package", "build-essential")));
        assertTrue(packageTargets.contains(new ActionTarget(SERVER.id(), "system-package", "gcc")));
        assertController(fixture.requests.get(3));
    }

    @Test
    void archivePreparationDoesNotPerformPlatformDependencyTransaction() throws Exception {
        String encoded = DeploymentAgentToolchainReleaseTest.encoded("RUST", "1.95", "1.95.0", OPERATION);
        var fixture = new Fixture(true, request -> request.script().contains("'resolve'") ? reply("TOOLCHAIN_RELEASE=" + encoded + "\n") : typedSuccess(request));
        fixture.agent.prepare(DeploymentToolchainType.RUST, "1.95", "1.95.0", 100, "-");
        assertEquals(3, fixture.requests.size());
        assertEquals(DeploymentAgentToolchainPrograms.resolver(), new String(fixture.requests.getFirst().stdin(), StandardCharsets.UTF_8));
        assertController(fixture.requests.getLast());
        assertEquals(List.of("prepare", "RUST", "1.95", "1.95.0", "100", "-", OPERATION, encoded), trackedRecipe(fixture.requests.getLast()).originalArgv());
        assertTrue(fixture.intents.getLast().semantics().orElseThrow().targets().contains(new ActionTarget(SERVER.id(),
            "systemd-unit", "windowstolinux-toolchain-" + OPERATION + ".service")));
    }

    @Test
    void everyBindingGetsASeparateReviewedControllerInvocation() throws Exception {
        var fixture = new Fixture(true, DeploymentAgentToolchainCommandsTest::typedSuccess);
        String encoded = binding();
        fixture.agent.bind(encoded);
        fixture.agent.bind(encoded);
        assertEquals(3, fixture.intents.size());
        assertEquals(3, fixture.requests.size());
        assertNotEquals(fixture.grants.get(0), fixture.grants.get(1));
        fixture.requests.subList(1, 3).forEach(DeploymentAgentToolchainCommandsTest::assertController);
        fixture.intents.subList(1, 3).forEach(intent -> assertEquals("deployment.agent.toolchain.controller.bind.v1", intent.semantics().orElseThrow().operation()));
    }

    @Test
    void malformedBindingAndPreparationArgumentsStopBeforeReview() throws Exception {
        var fixture = new Fixture(true, request -> failReply());
        for (String invalid : List.of("", binding().substring(1), "Jw==", "////", binding() + "\n"))
            assertThrows(IllegalArgumentException.class, () -> fixture.agent.bind(invalid));
        assertThrows(IllegalArgumentException.class, () -> fixture.agent.prepare(DeploymentToolchainType.RUST, "1.95;id", "-", 100, "-"));
        assertThrows(IllegalArgumentException.class, () -> fixture.agent.prepare(DeploymentToolchainType.RUST, "1.95", "1.95.0", 100, "/tmp/java"));
        assertTrue(fixture.intents.isEmpty());
        assertTrue(fixture.requests.isEmpty());
    }

    @Test
    void extraTargetsCannotChangeTheOriginalServer() throws Exception {
        var fixture = new Fixture(true, request -> failReply());
        assertThrows(IllegalArgumentException.class, () -> fixture.channel.send("deployment.agent.toolchain.prepare.v1",
            Set.of(new ActionTarget("other-server", "deployment-build", "/wrong")), "true", new byte[0], Duration.ofSeconds(1), 8192));
        assertTrue(fixture.intents.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APT", "DNF"})
    void nativePrerequisitesUseExactProfileReceiptAndOriginalIndependentGrant(String manager) throws Exception {
        var fixture = new Fixture(true, request -> reply(dependencyReceipt(manager, "NATIVE_BUILD")));
        assertEquals(TaskResult.SUCCEEDED, fixture.agent.prepareNativeBuild().outcome());
        assertEquals(1, fixture.requests.size());
        assertEquals(1, fixture.grants.size());
        assertTrue(fixture.requests.getFirst().script().endsWith("'NATIVE_BUILD' '" + OPERATION + "'"));
        assertEquals(DeploymentAgentToolchainPrograms.dependencies(), new String(fixture.requests.getFirst().stdin(), StandardCharsets.UTF_8));
        assertEquals(DeploymentAgentToolchainCommands.dependencyTargets(SERVER, "NATIVE_BUILD"),
            fixture.intents.getFirst().semantics().orElseThrow().targets());
        assertEquals(Set.of(new ActionTarget(SERVER.id(), "system-package-index", "APT:configured-authenticated-sources"),
            new ActionTarget(SERVER.id(), "system-package-index", "DNF:configured-authenticated-sources"),
            new ActionTarget(SERVER.id(), "system-package-transaction", "APT:authenticated-dependency-closure:NATIVE_BUILD"),
            new ActionTarget(SERVER.id(), "system-package-transaction", "DNF:authenticated-dependency-closure:NATIVE_BUILD"),
            new ActionTarget(SERVER.id(), "system-repository", "DNF:centos:crb:transaction-only"),
            new ActionTarget(SERVER.id(), "system-package", "cmake"), new ActionTarget(SERVER.id(), "system-package", "ninja-build"),
            new ActionTarget(SERVER.id(), "system-package", "gcc"), new ActionTarget(SERVER.id(), "system-package", "g++"),
            new ActionTarget(SERVER.id(), "system-package", "gcc-c++")), fixture.intents.getFirst().semantics().orElseThrow().targets());
        assertTrue(fixture.requests.stream().noneMatch(request -> request.script().contains("'prepare'")));
    }

    @Test
    void onlyPackagedDependencyProfilesExposeExactNativeTransactionScope() {
        for (String profile : List.of("NATIVE_BUILD", "PYTHON", "PHP", "RUBY")) {
            var transactions = DeploymentAgentToolchainCommands.dependencyTargets(SERVER, profile).stream()
                .filter(target -> target.kind().equals("system-package-transaction")).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of(new ActionTarget(SERVER.id(), "system-package-transaction", "APT:authenticated-dependency-closure:" + profile),
                new ActionTarget(SERVER.id(), "system-package-transaction", "DNF:authenticated-dependency-closure:" + profile)), transactions);
        }
        for (String profile : List.of("NONE", "RUST", "NATIVE_BUILD;apt", ""))
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainCommands.dependencyTargets(SERVER, profile));
    }

    @Test
    void nativePrerequisiteDenialNeverDispatchesAndMalformedReceiptRemainsUnknown() throws Exception {
        var denied = new Fixture(false, request -> failReply());
        assertEquals(TaskResult.FAILED, denied.agent.prepareNativeBuild().outcome());
        assertTrue(denied.requests.isEmpty());
        var malformed = new Fixture(true, request -> reply(dependencyReceipt("APT", "PYTHON")));
        assertEquals(TaskResult.UNKNOWN, malformed.agent.prepareNativeBuild().outcome());
        assertEquals(1, malformed.requests.size());
    }

    @Test
    void missingControllerInstallsOnceAndAllCallsRetainTheirOriginalNewGrants() throws Exception {
        var fixture = new Fixture(true, DeploymentAgentToolchainCommandsTest::typedSuccess);
        fixture.missing = true;
        fixture.agent.bind(binding());
        fixture.agent.bind(binding());
        assertEquals(4, fixture.requests.size());
        assertEquals(4, new java.util.HashSet<>(fixture.grants).size());
        assertEquals(DeploymentAgentToolchainProgramStore.installer(gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()), new String(fixture.requests.get(1).stdin(), StandardCharsets.UTF_8));
        assertController(fixture.requests.get(2));
        assertController(fixture.requests.get(3));
        assertTrue(fixture.memory.events.stream().filter(e -> e.type().equals("CONTROLLER_PROGRAM_ACTION")).count() == 4);
        assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(fixture.memory.tasks, fixture.memory.task, SERVER.id(), gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original(), fixture.memory.events));
        int oldBytes = DeploymentAgentToolchainPrograms.command(List.of("bind", binding(), "100", OPERATION)).getBytes(StandardCharsets.UTF_8).length + DeploymentAgentToolchainPrograms.controller().getBytes(StandardCharsets.UTF_8).length;
        System.out.println("CONTROLLER_INPUT_MEASUREMENT oldBindUtf8=" + oldBytes + " installedBindUtf8=" + fixture.requests.get(2).script().getBytes(StandardCharsets.UTF_8).length + " fullInstallerStdinUtf8=" + fixture.requests.get(1).stdin().length + " meaning=LOCAL_BYTES_NOT_PROVIDER_TOKENS");
    }

    @Test
    void deniedNotSentActionCanBeCorrectedButDispatchedUnknownCannotReplay() throws Exception {
        var denied = new Fixture(false, DeploymentAgentToolchainCommandsTest::typedSuccess);
        assertEquals(TaskResult.FAILED, denied.agent.bind(binding()).outcome());
        assertTrue(denied.requests.isEmpty());
        assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(denied.memory.tasks, denied.memory.task, SERVER.id(), gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original(), denied.memory.events));
        assertTrue(denied.memory.events.stream().anyMatch(e -> e.type().equals("CONTROLLER_PROGRAM_RESULT") && e.detail().contains("NOT_SENT")));
        denied.approved = true;
        assertEquals(TaskResult.SUCCEEDED, denied.agent.bind(binding()).outcome());
        assertEquals(3, denied.intents.size());
        assertEquals(2, denied.requests.size());
        var unknown = new Fixture(true, request -> new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_UNKNOWN", "controlled", Map.of(), Optional.empty()))));
        assertEquals(TaskResult.UNKNOWN, unknown.agent.bind(binding()).outcome());
        int count = unknown.requests.size();
        assertThrows(ApiException.class, () -> unknown.agent.bind(binding()));
        assertEquals(count, unknown.requests.size());
        var malformed = new Fixture(true, DeploymentAgentToolchainCommandsTest::typedSuccess);
        malformed.malformedStore = true;
        assertEquals(TaskResult.UNKNOWN, malformed.agent.bind(binding()).outcome());
        assertEquals(1, malformed.requests.size());
        assertThrows(ApiException.class, () -> malformed.agent.bind(binding()));
    }

    @Test
    void failedCancelledAndUnknownInstallationsNeverReplayAndChangedBindingsGetNewOriginalGrants() throws Exception {
        for (TaskResult outcome : List.of(TaskResult.FAILED, TaskResult.CANCELLED, TaskResult.UNKNOWN)) {
            var fixture = new Fixture(true, DeploymentAgentToolchainCommandsTest::typedSuccess);
            fixture.missing = true;
            fixture.installFailure = new OperationResult<>(outcome, Optional.empty(), Optional.of(new Problem(
                outcome == TaskResult.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.UNKNOWN, "CONTROLLED_INSTALL_" + outcome,
                "controlled original installation outcome", Map.of(), Optional.empty())));
            assertEquals(outcome == TaskResult.FAILED ? TaskResult.UNKNOWN : outcome, fixture.agent.bind(binding()).outcome());
            assertEquals(2, fixture.requests.size());
            assertThrows(ApiException.class, () -> fixture.agent.bind(binding()));
            assertEquals(2, fixture.requests.size(), "no automatic reinstall or invocation after original install uncertainty");
            assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(fixture.memory.tasks, fixture.memory.task, SERVER.id(), gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original(), fixture.memory.events));
        }
        var fixture = new Fixture(true, DeploymentAgentToolchainCommandsTest::typedSuccess);
        String changed = Base64.getEncoder().encodeToString(new String(Base64.getDecoder().decode(binding()), StandardCharsets.UTF_8).replace("1.95.0", "1.95.1").getBytes(StandardCharsets.UTF_8));
        fixture.agent.bind(binding());
        fixture.agent.bind(changed);
        assertEquals(3, fixture.requests.size());
        assertNotEquals(fixture.intents.get(1).exactAction(), fixture.intents.get(2).exactAction());
        assertNotEquals(fixture.grants.get(1), fixture.grants.get(2));
        var otherTask = new Fixture(true, DeploymentAgentToolchainCommandsTest::typedSuccess);
        otherTask.agent.bind(binding());
        assertNotEquals(fixture.memory.id, otherTask.memory.id);
        assertNotEquals(fixture.grants.get(1), otherTask.grants.get(1));
    }

    @Test
    void livePrecreationRefusalRetainsFailureAndRequiresFreshReviewedAction() throws Exception {
        var operations = new AtomicInteger();
        var invocations = new AtomicInteger();
        var fixture = new Fixture(true, request -> {
            if (request.script().contains("'resolve'")) {
                var argv = DeploymentAgentToolchainPrograms.arguments(request.script());
                return reply("TOOLCHAIN_RELEASE=" + DeploymentAgentToolchainReleaseTest.encoded(argv.get(1), argv.get(2), argv.get(3), argv.get(5)) + "\n");
            }
            return invocations.getAndIncrement() == 0 ? typedFailure(request) : typedSuccess(request);
        }, () -> String.format("%032x", operations.incrementAndGet()));
        var refused = fixture.agent.prepare(DeploymentToolchainType.JAVA, "21", "21.0.12.1+1", 100, "-");
        assertEquals(TaskResult.FAILED, refused.outcome());
        assertTrue(DeploymentAgentToolchainFailureEvidence.settledTrackedFailure(refused.problem()));
        var rows = DeploymentAgentToolchainProgramState.snapshot(fixture.memory.tasks, fixture.memory.task, SERVER.id(),
            DeploymentAgentToolchainProgramStateTest.LAYOUT, fixture.memory.events);
        var first = (com.fasterxml.jackson.databind.JsonNode) rows.getLast().get("payload");
        var original = first.deepCopy();
        assertEquals("CONFIRMED", first.path("state").asText());
        assertEquals("FAILED", first.path("receipt").path("executionStatus").asText());
        assertTrue((Boolean) rows.getLast().get("released"));
        assertTrue(first.has("rawResultRef"));
        assertEquals("SETTLED_NO_EFFECTS", first.path("receipt").path("effectState").asText());
        assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(fixture.memory.tasks, fixture.memory.task, SERVER.id(),
            DeploymentAgentToolchainProgramStateTest.LAYOUT, fixture.memory.events));
        assertEquals(TaskResult.SUCCEEDED, fixture.agent.prepare(DeploymentToolchainType.RUST, "1.95", "1.95.0", 100, "-").outcome());
        assertEquals(5, fixture.requests.size());
        assertEquals(5, new java.util.HashSet<>(fixture.grants).size(), "Every channel request obtains a fresh reviewed grant; no action is replayed");
        assertEquals(DeploymentAgentToolchainProgramStore.PREFIX + "prepare.v1", fixture.intents.get(2).semantics().orElseThrow().operation());
        assertEquals(DeploymentAgentToolchainProgramStore.PREFIX + "prepare.v1", fixture.intents.get(4).semantics().orElseThrow().operation());
        assertNotEquals(fixture.intents.get(2).exactAction(), fixture.intents.get(4).exactAction());
        var current = DeploymentAgentToolchainProgramState.snapshot(fixture.memory.tasks, fixture.memory.task, SERVER.id(),
            DeploymentAgentToolchainProgramStateTest.LAYOUT, fixture.memory.events);
        assertEquals(original, current.get(1).get("payload"), "A later successful action must not rewrite the original failed receipt");
        assertNotEquals(first.at("/index/operation").asText(), ((com.fasterxml.jackson.databind.JsonNode) current.getLast().get("index")).path("operation").asText());
    }

    @Test
    void existingOnlyRefusalAllowsFreshNodeResolutionAndReadinessWithoutUnconfirmedCheckpoint() throws Exception {
        var operations = new AtomicInteger();
        Supplier<String> nextOperation = () -> String.format("%032x", operations.incrementAndGet());
        var resolves = new ArrayList<String>();
        var prepares = new ArrayList<String>();
        var fixture = new Fixture(true, request -> {
            if (request.script().contains("'resolve'")) {
                var argv = DeploymentAgentToolchainPrograms.arguments(request.script());
                resolves.add(argv.get(1));
                assertEquals(DeploymentAgentToolchainPrograms.resolver(DeploymentAgentToolchainProgramStateTest.LAYOUT),
                    new String(request.stdin(), StandardCharsets.UTF_8));
                return reply("TOOLCHAIN_RELEASE=" + DeploymentAgentToolchainProgramStateTest.existingRelease(argv.get(1), argv.get(2), argv.get(3), argv.get(5)) + "\n");
            }
            var recipe = trackedRecipe(request);
            assertEquals("EXISTING_INLINE", recipe.invocationKind());
            assertEquals(DeploymentAgentToolchainPrograms.trackedCommand(recipe), request.script());
            assertArrayEquals(DeploymentAgentToolchainPrograms.trackedInput(recipe), request.stdin());
            prepares.add(recipe.originalArgv().get(1));
            return typedFailure(request);
        }, nextOperation);
        var go = fixture.existingAgent(nextOperation).prepare(DeploymentToolchainType.GO, "1.24", "1.24.13", 100, "-");
        assertEquals(TaskResult.FAILED, go.outcome());
        assertEquals(Optional.of(0), go.value().orElseThrow().exitCode(), "Actual SDK transport remains separate from worker failure");
        var goFailure = new ObjectMapper().readTree(go.problem().orElseThrow().context().get("agentToolchainFailure"));
        assertEquals("SUCCEEDED", goFailure.path("transportOutcome").asText());
        assertEquals("FAILED", goFailure.path("originalExecutionStatus").asText());
        assertEquals("FAILED", goFailure.path("productOutcome").asText());
        assertTrue(DeploymentAgentToolchainFailureEvidence.settledTrackedFailure(go.problem()));
        var firstRows = DeploymentAgentToolchainProgramState.snapshot(fixture.memory.tasks, fixture.memory.task, SERVER.id(),
            DeploymentAgentToolchainProgramStateTest.LAYOUT, fixture.memory.events);
        assertEquals(2, firstRows.size());
        var original = ((com.fasterxml.jackson.databind.JsonNode) firstRows.getLast().get("payload")).deepCopy();
        assertEquals("CONFIRMED", original.path("state").asText());
        assertEquals("FAILED", original.path("receipt").path("executionStatus").asText());
        assertTrue((Boolean) firstRows.getLast().get("released"));
        assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(fixture.memory.tasks, fixture.memory.task, SERVER.id(),
            DeploymentAgentToolchainProgramStateTest.LAYOUT, fixture.memory.events));
        // A new component creates a new Commands consumer, so Node must pass its own original READINESS action.
        var node = fixture.existingAgent(nextOperation).prepare(DeploymentToolchainType.NODE, "24", "24.22.0", 100, "-");
        assertEquals(TaskResult.FAILED, node.outcome());
        assertEquals(Optional.of(0), node.value().orElseThrow().exitCode());
        var nodeFailure = new ObjectMapper().readTree(node.problem().orElseThrow().context().get("agentToolchainFailure"));
        assertEquals("SUCCEEDED", nodeFailure.path("transportOutcome").asText());
        assertEquals("FAILED", nodeFailure.path("originalExecutionStatus").asText());
        assertEquals("FAILED", nodeFailure.path("productOutcome").asText());
        assertEquals("EXECUTION_FAILED", nodeFailure.path("failureCode").asText());
        assertTrue(DeploymentAgentToolchainFailureEvidence.settledTrackedFailure(node.problem()));
        assertEquals(List.of("GO", "NODE"), resolves);
        assertEquals(List.of("GO", "NODE"), prepares);
        assertEquals(6, fixture.requests.size());
        assertEquals(6, new java.util.HashSet<>(fixture.grants).size(), "Both resolvers, both READINESS actions and both failed prepares require independent fresh grants");
        for (int offset : List.of(0, 3)) {
            assertEquals("deployment.agent.toolchain.resolve.v1", fixture.intents.get(offset).semantics().orElseThrow().operation());
            assertEquals(DeploymentAgentToolchainProgramStore.PREFIX + "readiness.v1", fixture.intents.get(offset + 1).semantics().orElseThrow().operation());
            assertEquals("deployment.agent.toolchain.prepare.v1", fixture.intents.get(offset + 2).semantics().orElseThrow().operation());
        }
        assertNotEquals(fixture.intents.get(2).exactAction(), fixture.intents.get(5).exactAction());
        var rows = DeploymentAgentToolchainProgramState.snapshot(fixture.memory.tasks, fixture.memory.task, SERVER.id(),
            DeploymentAgentToolchainProgramStateTest.LAYOUT, fixture.memory.events);
        assertEquals(4, rows.size());
        assertTrue(rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("released"))));
        assertEquals(original, rows.get(1).get("payload"), "The second component must not rewrite the original Go failure");
        for (int index : List.of(1, 3)) {
            var payload = (com.fasterxml.jackson.databind.JsonNode) rows.get(index).get("payload");
            assertEquals("CONFIRMED", payload.path("state").asText());
            assertEquals("SUCCEEDED", payload.path("transportOutcome").asText());
            assertEquals("FAILED", payload.path("receipt").path("executionStatus").asText());
            assertEquals("SETTLED_NO_EFFECTS", payload.path("receipt").path("effectState").asText());
            assertTrue(payload.path("confirmationMeaning").asText().startsWith("STRICT_ORIGINAL_TRACKED_EFFECT_SETTLEMENT"));
        }
        assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(fixture.memory.tasks, fixture.memory.task, SERVER.id(),
            DeploymentAgentToolchainProgramStateTest.LAYOUT, fixture.memory.events));
    }

    private static void assertController(CommandRequest request) {
        assertTrue(request.script().startsWith("/usr/bin/python3 -I -S -c "));
        assertTrue(request.script().contains("PROGRAM_SOURCE"));
        assertFalse(request.script().contains("managed-helper"));
        var recipe = trackedRecipe(request);
        assertEquals(DeploymentAgentToolchainProgramStore.trackedCommand(recipe.phase().toLowerCase(Locale.ROOT) + "-tracked", recipe), request.script());
        assertTrue(request.stdin().length > 0);
        assertTrue(request.stdin().length < 1048576);
    }

    private static DeploymentAgentToolchainTracked.Recipe trackedRecipe(CommandRequest request) {
        byte[] input = request.stdin();
        int offset = input.length > 0 && input[0] == '{' ? 0 : DeploymentAgentToolchainPrograms.existingController(DeploymentAgentToolchainProgramStateTest.LAYOUT).getBytes(StandardCharsets.UTF_8).length;
        return DeploymentAgentToolchainTracked.decode(Arrays.copyOfRange(input, offset, input.length));
    }

    private static OperationResult<CommandResult> typedSuccess(CommandRequest request) {
        var recipe = trackedRecipe(request);
        return DeploymentAgentToolchainTrackedTest.result(DeploymentAgentToolchainTrackedTest.observation(recipe,
            recipe.phase().toLowerCase(Locale.ROOT) + "-tracked", "SUCCEEDED", true));
    }

    private static OperationResult<CommandResult> typedFailure(CommandRequest request) {
        var recipe = trackedRecipe(request);
        var receipt = DeploymentAgentToolchainTrackedTest.observation(recipe, recipe.phase().toLowerCase(Locale.ROOT) + "-tracked", "FAILED", true);
        receipt.put("effectState", "SETTLED_NO_EFFECTS").put("failureCode", "EXECUTION_FAILED").putNull("selection").putNull("bindingSha256");
        receipt.putArray("retainedEffects");
        return DeploymentAgentToolchainTrackedTest.result(receipt);
    }

    private static String dependencyReceipt() {
        return dependencyReceipt("APT", "PYTHON");
    }

    private static String dependencyReceipt(String manager, String profile) {
        try {
            var versions = new java.util.LinkedHashMap<String, String>();
            DeploymentAgentToolchainPrograms.packages(manager, profile).forEach(name -> versions.put(name, "verified-version"));
            return "TOOLCHAIN_DEPENDENCIES=" + new ObjectMapper().writeValueAsString(Map.of("operation", OPERATION,
                "ecosystem", profile, "manager", manager, "packages", versions)) + "\n";
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    static String binding() {
        var requirement = ToolchainRequirement.declared(DeploymentToolchainType.RUST, "1.95", "Cargo.toml", ToolchainRequirement.PurposeType.BUILD);
        var selected = new DeploymentResolvedToolchains(DeploymentToolchainCatalog.defaults().revision(), List.of(
            new DeploymentResolvedToolchains.Selection(requirement, DeploymentToolchainVersion.parse(DeploymentToolchainType.RUST, "1.95.0").orElseThrow(),
                "/usr/local/lib/windowstolinux/toolchains/versions/rust-" + "c".repeat(64), DeploymentResolvedToolchains.OriginType.MANAGED,
                "https://static.rust-lang.org/dist/original", "c".repeat(64))));
        return Base64.getEncoder().encodeToString(DeploymentToolchainBindingCodec.encode(selected).getBytes(StandardCharsets.UTF_8));
    }

    private static OperationResult<CommandResult> reply(String stdout) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), stdout, "", false)), Optional.empty());
    }

    private static OperationResult<CommandResult> failReply() {
        throw new AssertionError("Unexpected remote dispatch");
    }

    private static final class Fixture {
        final List<ActionIntent> intents = new ArrayList<>();

        final DeploymentAgentToolchainProgramStateTest.Memory memory = new DeploymentAgentToolchainProgramStateTest.Memory();

        boolean approved;

        boolean missing;

        boolean malformedStore;

        OperationResult<CommandResult> installFailure;

        final List<CommandRequest> requests = new ArrayList<>();

        final List<ApprovalGrant> grants = new ArrayList<>();

        final DeploymentBuildCommandChannel channel;

        final DeploymentAgentToolchainCommands agent;

        DeploymentAgentToolchainCommands existingAgent(Supplier<String> operations) throws ReflectiveOperationException {
            var constructor = DeploymentAgentToolchainCommands.class.getDeclaredConstructor(DeploymentBuildCommandChannel.class, ServerId.class,
                Supplier.class, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.class, DeploymentAgentToolchainProgramState.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(channel, SERVER, operations, DeploymentAgentToolchainProgramStateTest.LAYOUT,
                memory.state(SERVER, DeploymentAgentToolchainProgramStateTest.LAYOUT), true);
        }

        Fixture(boolean approved, Function<CommandRequest, OperationResult<CommandResult>> response) {
            this(approved, response, freshOperations());
        }

        private static Supplier<String> freshOperations() {
            var sequence = new AtomicInteger(); return () -> sequence.getAndIncrement() == 0 ? OPERATION : String.format("%032x", sequence.get());
        }

        Fixture(boolean approved, Function<CommandRequest, OperationResult<CommandResult>> response, Supplier<String> operations) {
            this.approved = approved;
            var session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("facts")) return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("linux", "x86_64", DeploymentAgentToolchainTrackedTest.FINGERPRINT);
                if (!method.getName().equals("execute")) throw new AssertionError("Unexpected session call");
                requests.add((CommandRequest) args[0]);
                grants.add((ApprovalGrant) args[1]);
                var request = (CommandRequest) args[0];
                String phase = intents.getLast().semantics().orElseThrow().operation();
                if (phase.equals(DeploymentAgentToolchainProgramStore.PREFIX + "readiness.v1") || phase.equals(DeploymentAgentToolchainProgramStore.PREFIX + "install.v1")) {
                    if (malformedStore) return reply("AGENT_CONTROLLER_STORE={}\n");
                    boolean installation = phase.endsWith("install.v1");
                    if (installation && installFailure != null) return installFailure;
                    var arguments = installation ? DeploymentAgentToolchainPrograms.arguments(request.script())
                        : DeploymentAgentToolchainProgramStore.arguments(request.script(), gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()).subList(1, 3);
                    var receipt = DeploymentAgentToolchainProgramStoreTest.observation(arguments.getFirst(), arguments.getLast(), !missing || installation);
                    if (installation) {
                        receipt.put("requestedInstallOperation", arguments.getLast());
                        missing = false;
                    }
                    return reply("AGENT_CONTROLLER_STORE=" + receipt + "\n");
                }
                return response.apply(request);
            });
            var task = memory.task;
            var interaction = new InteractionService() {
                public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext actualTask) {
                    assertSame(task, actualTask);
                    assertEquals(List.of(SERVER.id()), intent.serverIds());
                    intents.add(intent);
                    return Fixture.this.approved ? new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())), Optional.empty())
                        : new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.ACCESS_DENIED, "REVIEW_DECLINED", "declined", Map.of(), Optional.empty())));
                }

                public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext actualTask) {
                    throw new AssertionError("No text interaction");
                }
            };
            channel = new DeploymentBuildCommandChannel(session, interaction, SERVER, task, "a".repeat(64), new DeploymentBuildBudget(120));
            agent = new DeploymentAgentToolchainCommands(channel, SERVER, operations, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original(), memory.state(SERVER, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()));
        }
    }
}
