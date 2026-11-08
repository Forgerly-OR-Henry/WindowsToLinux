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
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.query.PageSlice;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import gold.debug.windowstolinux.mod.deployment.service.mirror.ResolvedMirrorPolicy;

@ResourceLock("managed.live.mavenDistributionBaseUrl")
class DeploymentAssistedMirrorPreparationTest extends DeploymentRuntimeFixture {
    private static final ServerId TARGET = new ServerId("live-official-mirror-fixture");
    private static final String MAVEN_DISTRIBUTION_BASE_URL = "managed.live.mavenDistributionBaseUrl";

    @Test
    void skipsPreparationForAgentMode() throws Exception {
        assertTrue(DeploymentStaticLiveAcceptanceTest.prepareLiveMirrors(null, DeploymentStaticLiveAcceptanceTest.Mode.AGENT).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = DeploymentStaticLiveAcceptanceTest.Mode.class, names = {"STATIC", "ASSISTED"})
    void savesOfficialSourcesThroughProductBeforeCaptureWithoutConsentOrNetwork(
            DeploymentStaticLiveAcceptanceTest.Mode mode) throws Exception {
        verifiesPreparedSources(mode, "");
    }

    @ParameterizedTest
    @EnumSource(value = DeploymentStaticLiveAcceptanceTest.Mode.class, names = {"STATIC", "ASSISTED"})
    void savesExplicitMavenDistributionSourceThroughProductBeforeCaptureWithoutConsentOrNetwork(
            DeploymentStaticLiveAcceptanceTest.Mode mode) throws Exception {
        verifiesPreparedSources(mode, "https://dlcdn.apache.org/maven/");
    }

    private void verifiesPreparedSources(DeploymentStaticLiveAcceptanceTest.Mode mode, String mavenDistributionBaseUrl) throws Exception {
        String previous = System.getProperty(MAVEN_DISTRIBUTION_BASE_URL);
        var network = new AtomicInteger();
        var privacy = new AtomicInteger();
        try {
            if (mavenDistributionBaseUrl.isEmpty()) System.clearProperty(MAVEN_DISTRIBUTION_BASE_URL);
            else System.setProperty(MAVEN_DISTRIBUTION_BASE_URL, mavenDistributionBaseUrl);
            enable();
            try (var runtime = open()) {
                start(runtime, true);
                var context = noNetwork(runtime.modContexts().get(OWNER).context(), network);
                var preparation = DeploymentStaticLiveAcceptanceTest.prepareLiveMirrors(context, mode).orElseThrow();
                var saved = preparation.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
                assertEquals(TaskResult.SUCCEEDED, saved.outcome(), saved::toString);
                var settings = saved.value().orElseThrow();
                var read = new DeploymentMirrorTasks(context).read().completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
                assertEquals(TaskResult.SUCCEEDED, read.outcome(), read::toString);
                assertEquals(settings, read.value().orElseThrow().settings());
                var catalog = MirrorCatalog.load();
                assertTrue(settings.serverRegions().isEmpty());
                for (var point : DownloadPoint.values()) {
                    assertEquals(1, settings.rows(point).size());
                    var row = settings.rows(point).getFirst();
                    assertFalse(row.automatic());
                    assertFalse(row.privacyConsent());
                    assertEquals("", row.secretRef());
                    if (point == DownloadPoint.MAVEN_DISTRIBUTION && !mavenDistributionBaseUrl.isEmpty()) {
                        assertEquals("", row.providerId());
                        assertEquals(mavenDistributionBaseUrl, row.customUrl());
                    } else {
                        assertEquals("", row.customUrl());
                        assertTrue(catalog.providersFor(point).stream()
                            .anyMatch(provider -> provider.official() && provider.id().equals(row.providerId())));
                    }
                }
                DeploymentMirrorTasks.privacyPrompt(() -> {
                    privacy.incrementAndGet();
                    throw new AssertionError("Fixed sources must not request region consent");
                });
                var observed = new AtomicReference<Throwable>();
                var captured = context.tasks().submit(new TaskRequest("live_mirror_preparation_test",
                    "Capture prepared fixed sources", List.of(), false), task -> {
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
                                if (point == DownloadPoint.MAVEN_DISTRIBUTION && !mavenDistributionBaseUrl.isEmpty()) {
                                    assertFalse(endpoint.official());
                                    assertTrue(endpoint.custom());
                                    assertEquals(mavenDistributionBaseUrl, endpoint.baseUrl());
                                    assertEquals(List.of("dlcdn.apache.org"), endpoint.allowedHosts());
                                    for (var route : endpoint.routes()) {
                                        assertEquals("dlcdn.apache.org", java.net.URI.create(route.prefix()).getHost());
                                        if (!route.pattern().isEmpty()) assertEquals("dlcdn.apache.org", route.templateTargetHost());
                                    }
                                } else {
                                    assertTrue(endpoint.official());
                                    assertFalse(endpoint.custom());
                                    assertEquals(settings.rows(point).getFirst().providerId(), endpoint.providerId());
                                }
                            }
                            return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                        } catch (Exception | AssertionError failure) {
                            observed.set(failure);
                            return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                                ProblemCode.IO_FAILURE, "LIVE_MIRROR_PREPARATION_TEST_FAILED",
                                "Prepared mirror capture failed", Map.of(), Optional.empty())));
                        } finally {
                            DeploymentMirrorTasks.forget(task);
                        }
                    });
                var result = captured.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
                if (observed.get() != null) throw new AssertionError(observed.get());
                assertEquals(TaskResult.SUCCEEDED, result.outcome(), result::toString);
                assertEquals(0, privacy.get());
                assertEquals(0, network.get());
            }
        } finally {
            DeploymentMirrorTasks.privacyPrompt(() -> false);
            if (previous == null) System.clearProperty(MAVEN_DISTRIBUTION_BASE_URL);
            else System.setProperty(MAVEN_DISTRIBUTION_BASE_URL, previous);
        }
    }

    private ModContext noNetwork(ModContext original, AtomicInteger attempts) {
        var servers = (ServerService) Proxy.newProxyInstance(ServerService.class.getClassLoader(),
            new Class<?>[]{ServerService.class}, (proxy, method, args) -> {
                if (method.getName().equals("get")) {
                    assertEquals(TARGET, args[0]);
                    return new ServerInfo(TARGET, "Fixture", "192.0.2.1", 22, "root", Optional.empty(), Optional.empty());
                }
                if (method.getName().equals("listConfigured"))
                    return new PageSlice<ServerInfo>(List.of(), Optional.empty());
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
