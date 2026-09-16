package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentTransactionState;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationAutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationRuntimeState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reuses one product-entrypoint whole-application transaction for each explicitly selected live distribution.
 *
 * <p>为每个明确选定的实时发行版复用同一个产品入口整应用事务。
 */
final class ManagedMultiComponentDeploymentAcceptanceFixture {
    private static final BuildLimitConfiguration LIMITS = new BuildLimitConfiguration(1800, 1024, 3072,
            8L * 1024 * 1024, 4L * 1024 * 1024 * 1024, false);

    private ManagedMultiComponentDeploymentAcceptanceFixture() {
    }

    static void exercise(Path temporaryDirectory, String applicationPrefix) throws Exception {
        int portBase = 42000 + (int) Math.floorMod(System.nanoTime(), 7000);
        int apiPort = portBase + 1;
        int webPort = portBase + 2;
        String applicationId = applicationPrefix + "-" + Long.toUnsignedString(System.nanoTime(), 36);
        Path root = temporaryDirectory.resolve("sources").resolve(applicationId);
        TypedAcceptanceFixture.javaJar(root, "api", "api-v1", true);
        TypedAcceptanceFixture.javaJar(root, "web", "web-v1", true);

        try (AutoCloseable cleanup = () -> stopApplication(temporaryDirectory, applicationId)) {
            try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory)) {
                var firstPrepared = context.prepareMulti(root, applicationId, requests(apiPort, webPort));
                List<MultiComponentReviewInput> firstInputs = inputs(firstPrepared, apiPort, webPort, 1);
                var firstReview = context.reviewMulti(firstPrepared, firstInputs,
                        new ApplicationHealthGate("web", health(webPort)));
                var first = context.deployMulti(firstReview, firstInputs);

                assertEquals(DeploymentStatus.SUCCEEDED, first.status(),
                        () -> first.applicationEvents() + " components=" + first.componentResults());
                assertTrue(first.componentResults().stream()
                        .allMatch(component -> component.state() == ComponentTransactionState.SUCCEEDED));
                assertHttp(apiPort, "api-v1");
                assertHttp(webPort, "web-v1");
                context.assertRuntimeIdentity(firstPrepared.components().get("api").facts().applicationId(), runtime(apiPort));
                context.assertRuntimeIdentity(firstPrepared.components().get("web").facts().applicationId(), runtime(webPort));

                TypedAcceptanceFixture.javaJar(root, "api", "api-v2", true);
                TypedAcceptanceFixture.javaJar(root, "web", "unused", false);
                var failedPrepared = context.prepareMulti(root, applicationId, requests(apiPort, webPort));
                List<MultiComponentReviewInput> failedInputs = inputs(failedPrepared, apiPort, webPort, 2);
                var failedReview = context.reviewMulti(failedPrepared, failedInputs,
                        new ApplicationHealthGate("web", health(webPort)));
                var failed = context.deployMulti(failedReview, failedInputs);

                assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, failed.status(),
                        () -> failed.applicationEvents() + " components=" + failed.componentResults());
                assertTrue(failed.componentResults().stream()
                        .allMatch(component -> component.state() == ComponentTransactionState.RESTORED));
                assertHttp(apiPort, "api-v1");
                assertHttp(webPort, "web-v1");
            }

            try (LiveTypedDeploymentContext restarted = new LiveTypedDeploymentContext(temporaryDirectory)) {
                var restored = restarted.service.findManagedMultiComponentApplication(applicationId).orElseThrow();
                assertEquals(List.of("api", "web"), restored.plan().startOrder());
                Set<String> all = Set.of("api", "web");
                var stopped = restarted.lifecycleMulti(applicationId, all, LifecycleAction.STOP);
                assertTrue(stopped.accepted(), stopped::toString);
                assertEquals(ApplicationRuntimeState.STOPPED, stopped.runtimeState());
                var started = restarted.lifecycleMulti(applicationId, all, LifecycleAction.START);
                assertTrue(started.accepted(), started::toString);
                assertEquals(ApplicationRuntimeState.RUNNING, started.runtimeState());
                var restartedApplication = restarted.lifecycleMulti(applicationId, all, LifecycleAction.RESTART);
                assertTrue(restartedApplication.accepted(), restartedApplication::toString);
                assertEquals(ApplicationRuntimeState.RUNNING, restartedApplication.runtimeState());
                var enabled = restarted.lifecycleMulti(applicationId, all, LifecycleAction.ENABLE_AUTOSTART);
                assertTrue(enabled.accepted(), enabled::toString);
                assertEquals(ApplicationAutostartState.ENABLED, enabled.autostartState());
                var disabled = restarted.lifecycleMulti(applicationId, all, LifecycleAction.DISABLE_AUTOSTART);
                assertTrue(disabled.accepted(), disabled::toString);
                assertEquals(ApplicationAutostartState.DISABLED, disabled.autostartState());
                assertHttp(apiPort, "api-v1");
                assertHttp(webPort, "web-v1");
            }
        }
    }

    private static void stopApplication(Path directory, String applicationId) throws Exception {
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(directory)) {
            if (context.service.findManagedMultiComponentApplication(applicationId).isEmpty()) {
                context.stopTestApplications();
                return;
            }
            Set<String> all = Set.of("api", "web");
            var disabled = context.lifecycleMulti(applicationId, all, LifecycleAction.DISABLE_AUTOSTART);
            assertTrue(disabled.accepted(), disabled::toString);
            assertEquals(ApplicationAutostartState.DISABLED, disabled.autostartState());
            for (int attempt = 0; attempt < 2; attempt++) {
                var stopped = context.lifecycleMulti(applicationId, all, LifecycleAction.STOP);
                assertTrue(stopped.accepted(), stopped::toString);
                assertEquals(ApplicationRuntimeState.STOPPED, stopped.runtimeState());
            }
            System.out.println("LIVE_MULTI_STOPPED application=" + applicationId);
        }
    }

    private static List<ComponentAnalysisRequest> requests(int apiPort, int webPort) {
        return List.of(request("api", apiPort, Set.of()), request("web", webPort, Set.of("api")));
    }

    private static ComponentAnalysisRequest request(String componentId, int port, Set<String> dependencies) {
        return new ComponentAnalysisRequest(componentId, componentId, DeploymentProjectType.JAVA_JAR,
                Optional.of(runtime(port)), List.of(componentId + "/app.jar"), Set.of(port), List.of("PORT"),
                List.of(), List.of(), dependencies, true, ComponentIsolationSpecification.managed());
    }

    private static List<MultiComponentReviewInput> inputs(
            gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource prepared,
            int apiPort, int webPort, long revision) {
        return List.of(input(prepared, "api", apiPort, revision), input(prepared, "web", webPort, revision));
    }

    private static MultiComponentReviewInput input(
            gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource prepared,
            String componentId, int port, long revision) {
        String managedId = prepared.components().get(componentId).facts().applicationId();
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create(managedId, revision, "acceptance-v1",
                Instant.now(), List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                        new ConfigurationValue.Number(port))));
        return new MultiComponentReviewInput(componentId, configuration, List.of(),
                Optional.of(new UserAccessUrl(URI.create("http://" + requiredProperty("managed.ssh.host")
                        + ":" + port + "/"))), LIMITS, false, false);
    }

    private static DeploymentRuntimeSpecification.JavaJar runtime(int port) {
        return new DeploymentRuntimeSpecification.JavaJar("app.jar", "acceptance.Probe", "21", List.of(),
                List.of(), health(port));
    }

    private static HealthCheck.Http health(int port) {
        return new HealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/health"), 200, 20);
    }

    private static void assertHttp(int port, String marker) throws Exception {
        URI uri = URI.create("http://" + requiredProperty("managed.ssh.host") + ":" + port + "/");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        try {
            assertEquals(200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains(marker));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }
}
