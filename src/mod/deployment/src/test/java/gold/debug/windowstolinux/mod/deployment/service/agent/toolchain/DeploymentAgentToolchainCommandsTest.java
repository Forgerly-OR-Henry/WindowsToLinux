package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
        var fixture = new Fixture(true, request -> reply(count.getAndIncrement() == 0 ? dependencyReceipt() : "prepared\n"));
        assertEquals(TaskResult.SUCCEEDED, fixture.agent.prepare(DeploymentToolchainType.PYTHON, "3.14", "3.14.0", 100, "-").outcome());
        assertEquals(2, fixture.requests.size());
        assertEquals(2, fixture.grants.size());
        assertNotEquals(fixture.grants.get(0), fixture.grants.get(1));
        assertEquals("deployment.agent.toolchain.dependencies.v1", fixture.intents.get(0).semantics().orElseThrow().operation());
        assertEquals("deployment.agent.toolchain.prepare.v1", fixture.intents.get(1).semantics().orElseThrow().operation());
        var packageTargets = fixture.intents.get(0).semantics().orElseThrow().targets();
        assertTrue(packageTargets.contains(new ActionTarget(SERVER.id(), "system-package", "build-essential")));
        assertTrue(packageTargets.contains(new ActionTarget(SERVER.id(), "system-package", "gcc")));
        assertController(fixture.requests.get(1));
    }

    @Test
    void archivePreparationDoesNotPerformPlatformDependencyTransaction() throws Exception {
        var fixture = new Fixture(true, request -> reply("prepared\n"));
        fixture.agent.prepare(DeploymentToolchainType.RUST, "1.95", "1.95.0", 100, "-");
        assertEquals(1, fixture.requests.size());
        assertController(fixture.requests.getFirst());
        assertTrue(fixture.requests.getFirst().script().endsWith("'prepare' 'RUST' '1.95' '1.95.0' '100' '-' '" + OPERATION + "'"));
        assertTrue(fixture.intents.getFirst().semantics().orElseThrow().targets().contains(new ActionTarget(SERVER.id(),
            "systemd-unit", "windowstolinux-toolchain-" + OPERATION + ".service")));
    }

    @Test
    void everyBindingGetsASeparateReviewedControllerInvocation() throws Exception {
        var fixture = new Fixture(true, request -> reply("bound\n"));
        String encoded = binding();
        fixture.agent.bind(encoded);
        fixture.agent.bind(encoded);
        assertEquals(2, fixture.intents.size());
        assertEquals(2, fixture.requests.size());
        assertNotEquals(fixture.grants.get(0), fixture.grants.get(1));
        fixture.requests.forEach(DeploymentAgentToolchainCommandsTest::assertController);
        fixture.intents.forEach(intent -> assertEquals("deployment.agent.toolchain.bind.v1", intent.semantics().orElseThrow().operation()));
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
        assertTrue(fixture.requests.stream().noneMatch(request -> request.script().contains("'prepare'")));
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

    private static void assertController(CommandRequest request) {
        assertTrue(request.script().startsWith("/usr/bin/python3 -I -S -c "));
        assertTrue(request.script().contains("PROGRAM_SOURCE"));
        assertFalse(request.script().contains("managed-helper"));
        assertEquals(DeploymentAgentToolchainPrograms.controller(), new String(request.stdin(), StandardCharsets.UTF_8));
        assertTrue(request.stdin().length < 1048576);
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

        final List<CommandRequest> requests = new ArrayList<>();

        final List<ApprovalGrant> grants = new ArrayList<>();

        final DeploymentBuildCommandChannel channel;

        final DeploymentAgentToolchainCommands agent;

        Fixture(boolean approved, Function<CommandRequest, OperationResult<CommandResult>> response) {
            var session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (!method.getName().equals("execute")) throw new AssertionError("Unexpected session call");
                requests.add((CommandRequest) args[0]);
                grants.add((ApprovalGrant) args[1]);
                return response.apply((CommandRequest) args[0]);
            });
            var task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class}, (proxy, method, args) -> {
                throw new AssertionError("Unexpected task call");
            });
            var interaction = new InteractionService() {
                public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext actualTask) {
                    assertSame(task, actualTask);
                    assertEquals(List.of(SERVER.id()), intent.serverIds());
                    intents.add(intent);
                    return approved ? new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())), Optional.empty())
                        : new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.ACCESS_DENIED, "REVIEW_DECLINED", "declined", Map.of(), Optional.empty())));
                }

                public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext actualTask) {
                    throw new AssertionError("No text interaction");
                }
            };
            channel = new DeploymentBuildCommandChannel(session, interaction, SERVER, task, "a".repeat(64), new DeploymentBuildBudget(120));
            agent = new DeploymentAgentToolchainCommands(channel, SERVER, () -> OPERATION);
        }
    }
}
