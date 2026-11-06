package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import gold.debug.windowstolinux.mod.deployment.service.mirror.ResolvedMirrorPolicy;

class DeploymentAssistedMirrorPreparationTest extends DeploymentRuntimeFixture {
    private static final ServerId TARGET = new ServerId("live-official-mirror-fixture");

    @Test
    void skipsPreparationForAgentMode() throws Exception {
        assertTrue(DeploymentStaticLiveAcceptanceTest.prepareLiveMirrors(null, DeploymentStaticLiveAcceptanceTest.Mode.AGENT).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = DeploymentStaticLiveAcceptanceTest.Mode.class, names = {"STATIC", "ASSISTED"})
    void savesOfficialSourcesThroughProductBeforeCaptureWithoutConsentOrNetwork(
            DeploymentStaticLiveAcceptanceTest.Mode mode) throws Exception {
        enable();
        var network = new AtomicInteger();
        var privacy = new AtomicInteger();
        try (var runtime = open()) {
            start(runtime, true);
            var context = noNetwork(runtime.modContexts().get(OWNER).context(), network);
            var preparation = DeploymentStaticLiveAcceptanceTest.prepareLiveMirrors(context, mode).orElseThrow();
            var saved = preparation.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, saved.outcome(), saved::toString);
            var settings = saved.value().orElseThrow();
            var catalog = MirrorCatalog.load();
            assertTrue(settings.serverRegions().isEmpty());
            for (var point : DownloadPoint.values()) {
                assertEquals(1, settings.rows(point).size());
                var row = settings.rows(point).getFirst();
                assertFalse(row.automatic());
                assertFalse(row.privacyConsent());
                assertEquals("", row.customUrl());
                assertEquals("", row.secretRef());
                assertTrue(catalog.providersFor(point).stream()
                    .anyMatch(provider -> provider.official() && provider.id().equals(row.providerId())));
            }
            DeploymentMirrorTasks.privacyPrompt(() -> {
                privacy.incrementAndGet();
                throw new AssertionError("Fixed official sources must not request region consent");
            });
            var observed = new AtomicReference<Throwable>();
            var captured = context.tasks().submit(new TaskRequest("live_mirror_preparation_test",
                "Capture prepared official sources", List.of(), false), task -> {
                    try {
                        new DeploymentMirrorTasks(context).capture(task, TARGET);
                        var policy = ResolvedMirrorPolicy.fromJson(DeploymentMirrorTasks.policy(task),
                            task.id().toString(), TARGET.id());
                        assertEquals("", policy.country());
                        assertEquals("", policy.region());
                        for (var point : DownloadPoint.values()) {
                            var endpoints = policy.points().get(point);
                            assertEquals(1, endpoints.size());
                            var endpoint = endpoints.getFirst();
                            assertTrue(endpoint.official());
                            assertFalse(endpoint.custom());
                            assertEquals(settings.rows(point).getFirst().providerId(), endpoint.providerId());
                        }
                        return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                    } catch (Exception | AssertionError failure) {
                        observed.set(failure);
                        return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                            ProblemCode.IO_FAILURE, "LIVE_MIRROR_PREPARATION_TEST_FAILED",
                            "Official mirror capture failed", Map.of(), Optional.empty())));
                    } finally {
                        DeploymentMirrorTasks.forget(task);
                    }
                });
            var result = captured.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            if (observed.get() != null) throw new AssertionError(observed.get());
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result::toString);
            assertEquals(0, privacy.get());
            assertEquals(0, network.get());
        } finally {
            DeploymentMirrorTasks.privacyPrompt(() -> false);
        }
    }

    private ModContext noNetwork(ModContext original, AtomicInteger attempts) {
        var servers = (ServerService) Proxy.newProxyInstance(ServerService.class.getClassLoader(),
            new Class<?>[]{ServerService.class}, (proxy, method, args) -> {
                if (method.getName().equals("get")) {
                    assertEquals(TARGET, args[0]);
                    return new ServerInfo(TARGET, "Fixture", "192.0.2.1", 22, "root", Optional.empty(), Optional.empty());
                }
                throw new AssertionError("Unexpected server access: " + method.getName());
            });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("linux")) {
                    attempts.incrementAndGet();
                    throw new AssertionError("No IP probe or other remote operation expected");
                }
                if (method.getName().equals("servers")) return servers;
                try { return method.invoke(original, args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
    }
}
