package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit disposable-host reset through the real platform SDK, outside deployment acceptance. */
@EnabledIfSystemProperty(named = "managed.runtime.disposableHostReset", matches = "true")
class DeploymentDisposableHostResetLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Test
    void runsExplicitDisposableHostResetThroughProduct() throws Exception {
        assertEquals("DeploymentDisposableHostResetLiveTest#runsExplicitDisposableHostResetThroughProduct",
            System.getProperty("test"), "Reset requires an exact opt-in test selection");
        assertEquals("true", System.getProperty("managed.runtime.staticLive"));
        String host = System.getProperty("managed.ssh.host");
        assertNotNull(host);
        assertEquals(host, System.getProperty("managed.reset.expectedHost"));
        String fingerprint = System.getProperty("managed.ssh.fingerprint");
        assertNotNull(fingerprint);
        assertTrue(fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"));
        String mode = System.getProperty("managed.reset.mode");
        assertTrue(Set.of("inventory", "plan", "apply").contains(mode));
        String receiptName = System.getProperty("managed.reset.receiptName");
        assertNotNull(receiptName);
        assertTrue(receiptName.matches("[a-z0-9][a-z0-9-]{0,95}"));
        var fixture = new Fixture();
        Path workspace = fixture.workspaceRoot();
        Path evidence = Path.of(System.getProperty("managed.reset.evidenceDirectory")).toRealPath();
        assertTrue(evidence.startsWith(workspace.resolve(".ai-workspace/evidence").toRealPath()));
        Path receipt = evidence.resolve(receiptName + ".json");
        assertFalse(Files.exists(receipt), "Retained reset receipts must not be overwritten");
        byte[] source;
        try (var input = getClass().getResourceAsStream("disposable-host-reset.py")) {
            assertNotNull(input);
            source = input.readAllBytes();
        }
        assertTrue(source.length > 0 && source.length < 262144);
        String command = "exec /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin HOME=/root LANG=C.UTF-8 "
            + "/usr/bin/python3 -I -B - " + mode;
        String revision = gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs
            .hash(new String(source, StandardCharsets.UTF_8));
        System.out.println("DISPOSABLE_RESET_PROGRAM sha256=" + revision + " bytes=" + source.length);
        if (mode.equals("apply")) {
            Path planFile = Path.of(System.getProperty("managed.reset.planFile")).toRealPath();
            assertEquals(evidence, planFile.getParent());
            assertTrue(Files.size(planFile) < 262144);
            var planReceipt = JSON.readTree(Files.readString(planFile));
            assertEquals("plan", planReceipt.path("mode").asText());
            assertTrue(planReceipt.path("ok").asBoolean());
            assertTrue(planReceipt.path("plan").isObject());
            byte[] plan = JSON.writeValueAsBytes(planReceipt.path("plan"));
            command += " " + Base64.getEncoder().encodeToString(plan);
        }
        fixture.freshState(workspace.resolve("src/app/db/data/static-live-reset-"
            + Long.toUnsignedString(System.nanoTime(), 36)));
        System.out.println("DISPOSABLE_RESET_STATE " + fixture.state());
        fixture.enableFixture();
        try (var runtime = fixture.openFixture()) {
            fixture.startFixture(runtime);
            await(runtime.servers().check(fixture.saveFixtureServer(runtime)), 2);
            var context = runtime.modContexts().get("gold.debug.windowstolinux.mod.deployment").context();
            final String script = command;
            var handle = context.tasks().submit(new TaskRequest("disposable-host-reset",
                "Explicit disposable host " + mode, List.of("static-live"), false), task -> {
                    String phase = "CONNECT";
                    String failurePhase = null;
                    System.out.println("DISPOSABLE_RESET_CALLBACK ENTER mode=" + mode + " task=" + task.id());
                    try {
                        var server = new ServerId("static-live");
                        System.out.println("DISPOSABLE_RESET_CONNECT ENTER mode=" + mode + " task=" + task.id());
                        try (var session = context.linux().connect(server, task)) {
                            System.out.println("DISPOSABLE_RESET_CONNECT RETURN mode=" + mode + " task=" + task.id());
                            try {
                                phase = "REQUEST";
                                try (var request = new CommandRequest(script, source, Duration.ofMinutes(mode.equals("apply") ? 30 : 3), 524288)) {
                                    try {
                                        assertEquals(fingerprint, session.facts().fingerprint());
                                        phase = "REVIEW";
                                        System.out.println("DISPOSABLE_RESET_REVIEW ENTER mode=" + mode + " task=" + task.id());
                                        var intent = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(
                                            "deployment.test.disposable-host-reset.v1", Set.of(
                                                new ActionTarget(server.id(), "disposable-host", host),
                                                new ActionTarget(server.id(), "task", task.id().toString()))));
                                        var grant = context.interaction().approve(intent, task);
                                        System.out.println("DISPOSABLE_RESET_REVIEW RETURN mode=" + mode + " task=" + task.id()
                                            + " outcome=" + grant.outcome() + " grantPresent=" + grant.value().isPresent());
                                        assertEquals(TaskResult.SUCCEEDED, grant.outcome());
                                        phase = "EXECUTE";
                                        System.out.println("DISPOSABLE_RESET_EXECUTE ENTER mode=" + mode + " task=" + task.id());
                                        var result = session.execute(request, grant.value().orElseThrow());
                                        System.out.println("DISPOSABLE_RESET_EXECUTE RETURN mode=" + mode + " task=" + task.id()
                                            + " outcome=" + result.outcome() + " valuePresent=" + result.value().isPresent()
                                            + result.value().map(value -> " exitPresent=" + value.exitCode().isPresent()
                                                + value.exitCode().map(exit -> " exit=" + exit).orElse("")
                                                + " truncated=" + value.truncated()).orElse(""));
                                        if (result.value().isPresent()) {
                                            phase = "RECEIPT";
                                            CommandResult value = result.value().orElseThrow();
                                            Files.writeString(receipt, value.stdout(), StandardOpenOption.CREATE_NEW);
                                            System.out.println("DISPOSABLE_RESET_COMMAND mode=" + mode + " outcome=" + result.outcome()
                                                + " exit=" + value.exitCode() + " truncated=" + value.truncated());
                                        }
                                        return result;
                                    } catch (Exception | Error failure) {
                                        failurePhase = phase;
                                        throw failure;
                                    } finally {
                                        phase = "REQUEST_CLOSE";
                                    }
                                }
                            } catch (Exception | Error failure) {
                                if (failurePhase == null) failurePhase = phase;
                                throw failure;
                            } finally {
                                phase = "SESSION_CLOSE";
                            }
                        }
                    } catch (ApiException failure) {
                        var problem = failure.problem();
                        String code = Set.of(ProblemCode.INVALID_INPUT, ProblemCode.UNAVAILABLE, ProblemCode.ACCESS_DENIED,
                            ProblemCode.BUSY, ProblemCode.IO_FAILURE, ProblemCode.TIMEOUT, ProblemCode.CANCELLED,
                            ProblemCode.UNKNOWN, ProblemCode.CONTRACT_VIOLATION).contains(problem.code()) ? problem.code().name() : "OTHER";
                        String event = Set.of("SSH_PLATFORM_CONNECT", "SSH_HOST_KEY_REJECTED", "SSH_TRUST_CANCELLED",
                            "SSH_ADAPTER_UNCERTAIN", "SSH_OPERATION_UNCERTAIN", "SSH_SESSION_BUSY", "SSH_SESSION_CLOSING",
                            "SSH_REVIEW_STALE", "SSH_ORIGINAL_TASK_CHANGED", "SSH_ORIGINAL_TARGET_CHANGED", "SSH_ORIGINAL_TRUST_CHANGED",
                            "APPROVAL_ACTION", "APPROVAL_IDENTITY", "APPROVAL_INTENT", "APPROVAL_UNAVAILABLE")
                            .contains(problem.eventCode()) ? problem.eventCode() : "OTHER";
                        System.out.println("DISPOSABLE_RESET_API_FAILURE mode=" + mode + " task=" + task.id()
                            + " phase=" + (failurePhase == null ? phase : failurePhase) + " code=" + code + " event=" + event);
                        throw failure;
                    } catch (Exception | Error failure) {
                        System.out.println("DISPOSABLE_RESET_CALLBACK_FAILURE mode=" + mode + " task=" + task.id()
                            + " phase=" + (failurePhase == null ? phase : failurePhase) + " type=" + failure.getClass().getSimpleName());
                        throw failure;
                    } finally {
                        System.out.println("DISPOSABLE_RESET_CALLBACK EXIT mode=" + mode + " task=" + task.id() + " phase=" + phase);
                    }
                });
            var commandResult = await(handle, mode.equals("apply") ? 32 : 4);
            assertFalse(commandResult.truncated());
            assertEquals(0, commandResult.exitCode().orElseThrow());
            var body = JSON.readTree(commandResult.stdout());
            assertEquals(mode, body.path("mode").asText());
            assertTrue(body.path("ok").asBoolean(), body::toString);
            assertTrue(Files.isRegularFile(receipt));
            if (mode.equals("inventory")) {
                String probe = gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityProbe.render();
                var probeTask = context.tasks().submit(new TaskRequest("disposable-host-platform-observation",
                    "Observe actual platform tools after inventory", List.of("static-live"), false), task -> {
                        var server = new ServerId("static-live");
                        try (var session = context.linux().connect(server, task);
                             var request = new CommandRequest(probe, new byte[0], Duration.ofMinutes(2), 65536)) {
                            assertEquals(fingerprint, session.facts().fingerprint());
                            var grant = context.interaction().approve(RemoteActions.command(server,
                                "disposable-host-read-only-platform", request), task);
                            return session.execute(request, grant.value().orElseThrow());
                        }
                    });
                var observed = await(probeTask, 3);
                Files.writeString(evidence.resolve(receiptName + "-platform.txt"), observed.stdout(),
                    StandardOpenOption.CREATE_NEW);
                assertFalse(observed.truncated());
                assertEquals(0, observed.exitCode().orElseThrow());
                System.out.println("DISPOSABLE_RESET_PLATFORM_VERIFIED receipt=" + receiptName);
            }
        }
        System.out.println("DISPOSABLE_RESET_VERIFIED mode=" + mode + " receipt=" + receiptName);
    }

    private static <T> T await(TaskHandle<T> handle, int minutes) throws Exception {
        System.out.println("DISPOSABLE_RESET_TASK " + handle.id());
        gold.debug.windowstolinux.appapi.task.result.OperationResult<T> result;
        try {
            result = handle.completion().toCompletableFuture().get(minutes, TimeUnit.MINUTES);
        } catch (TimeoutException timeout) {
            var snapshot = handle.snapshot();
            System.out.println("DISPOSABLE_RESET_TASK_WAIT_TIMEOUT task=" + handle.id() + " state=" + snapshot.state()
                + " outcome=" + snapshot.outcome().map(Enum::name).orElse("NONE"));
            throw timeout;
        }
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result::toString);
        return result.value().orElseThrow();
    }

    private static final class Fixture extends DeploymentStaticLiveAcceptanceTest {
        Path workspaceRoot() throws Exception { return workspace(); }
        void freshState(Path state) throws Exception {
            root = state;
            var field = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField("connectionOnly");
            field.setAccessible(true);
            field.setBoolean(this, false);
        }
        Path state() { return root; }
        void enableFixture() throws Exception { enable(); }
        PlatformRuntime openFixture() throws Exception { return open(); }
        void startFixture(PlatformRuntime runtime) throws Exception { start(runtime, true); }
        ServerProfile saveFixtureServer(PlatformRuntime runtime) throws Exception {
            var method = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("saveServer", PlatformRuntime.class);
            method.setAccessible(true);
            try { return (ServerProfile) method.invoke(this, runtime); }
            catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw failure;
            }
        }
    }
}
