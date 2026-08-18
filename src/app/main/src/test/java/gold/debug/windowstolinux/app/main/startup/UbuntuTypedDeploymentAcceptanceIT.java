package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.git.GitRemote;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.LanguageEcosystem;
import gold.debug.windowstolinux.shared.model.project.SourceLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in product-entrypoint acceptance for every reviewed typed deployment runtime. / 针对每种经审阅类型化部署运行时的可选产品入口验收。 */
@EnabledIfSystemProperty(named = "managed.runtime.typed", matches = "true")
class UbuntuTypedDeploymentAcceptanceIT {
    private static final int PORT_BASE = 30000 + (int) ((System.currentTimeMillis() / 1000) % 10000);
    private static final String RUN_ID = Long.toUnsignedString(System.nanoTime(), 36);
    @TempDir Path temporaryDirectory;

    @Test
    void deploysJavaJarFromLocalUserSelection() throws Exception {
        int port = port(1);
        String applicationId = applicationId("java");
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory)) {
            ReviewedSourcePreparation source = context.prepare(TypedAcceptanceFixtures.javaJar(
                    temporaryDirectory.resolve("sources"), applicationId), DeploymentProjectType.JAVA_JAR);
            DeploymentResult result = context.deploy(source, 1, runtimeConfiguration(port), List.of(),
                    new DeploymentRuntimeSpecification.JavaJar("app.jar", "acceptance.Probe", "21", List.of(),
                            List.of(), health(port)), access(port));
            assertSuccessful(result, applicationId);
            assertHttp(port, "java-live-ok");
        }
    }

    @Test
    void deploysPythonFromLocalUserSelection() throws Exception {
        int port = port(2);
        String applicationId = applicationId("python");
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory)) {
            ReviewedSourcePreparation source = context.prepare(TypedAcceptanceFixtures.python(
                    temporaryDirectory.resolve("sources"), applicationId), DeploymentProjectType.PYTHON_SERVICE);
            DeploymentResult result = context.deploy(source, 1, runtimeConfiguration(port), List.of(),
                    new DeploymentRuntimeSpecification.PythonService("3.12", "demo", health(port)), access(port));
            assertSuccessful(result, applicationId);
            assertHttp(port, "python-live-ok");
        }
    }

    @Test
    void deploysPureStaticSiteWithoutInventingANodeVersion() throws Exception {
        int port = port(3);
        String applicationId = applicationId("static");
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory)) {
            ReviewedSourcePreparation source = context.prepare(TypedAcceptanceFixtures.staticSite(
                    temporaryDirectory.resolve("sources"), applicationId), DeploymentProjectType.STATIC_SITE);
            DeploymentResult result = context.deploy(source, 1, runtimeConfiguration(port), List.of(),
                    new DeploymentRuntimeSpecification.StaticSite("public", health(port)), access(port));
            assertSuccessful(result, applicationId);
            assertHttp(port, "static-live-ok");
        }
    }

    @Test
    void pinsAndDeploysCredentialFreeGradleGitSource() throws Exception {
        int port = port(4);
        String commit = requiredProperty("managed.gradle.commit");
        GitSourceRequest request = new GitSourceRequest(
                GitRemote.parse(System.getProperty("managed.gradle.remote",
                        "https://github.com/mikechao/simple-spring-boot-app.git")),
                new GitReference.Commit(commit), Set.of("github.com"), 512L * 1024 * 1024, false);
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory)) {
            ReviewedSourcePreparation source = context.prepare(request, DeploymentProjectType.SPRING_BOOT);
            assertEquals(commit, source.sourceRevision().orElseThrow().commit().orElseThrow());
            assertTrue(source.assessment().facts().orElseThrow().languageFacts().ecosystems()
                    .contains(LanguageEcosystem.JAVA));
            assertTrue(source.assessment().facts().orElseThrow().languageFacts().sourceLanguages()
                    .contains(SourceLanguage.JAVA));
            List<ConfigurationEntry> configuration = List.of(
                    text("ACCEPTANCE_RUN_ID", RUN_ID), text("LOG_PATH", "/var/tmp/"), number("SERVER_PORT", port));
            DeploymentResult result = context.deploy(source, 1, configuration, List.of(),
                    new DeploymentRuntimeSpecification.SpringBoot(health(port)), access(port));
            assertSuccessful(result, source.assessment().facts().orElseThrow().applicationId());
            assertHttp(port, "greeting");
        }
    }

    @Test
    void bindsNodeBuildRuntimeAndSecretInputsThenRollsBackAndRestoresLifecycle() throws Exception {
        int port = port(5);
        String applicationId = applicationId("node");
        SecretReference firstReference = new SecretReference("deployment-probe", 1);
        SecretReference secondReference = new SecretReference("deployment-probe", 2);
        String firstSecret = randomSecret();
        String secondSecret = randomSecret();
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory)) {
            context.saveSecret(firstReference, "application/deployment-probe/1", firstSecret.toCharArray());
            Path sourceRoot = TypedAcceptanceFixtures.node(temporaryDirectory.resolve("sources"), applicationId,
                    true, "node-live-v1");
            ReviewedSourcePreparation firstSource = context.prepare(sourceRoot, DeploymentProjectType.NODE_SERVICE);
            DeploymentResult first = context.deploy(firstSource, 1, nodeConfiguration(port, firstSecret),
                    List.of(firstReference), new DeploymentRuntimeSpecification.NodeService(18, health(port)), access(port));
            assertSuccessful(first, applicationId);
            assertSecretFree(first, firstSecret);
            assertHttp(port, "node-live-v1");
            verifyLifecycle(context, applicationId);

            context.saveSecret(secondReference, "application/deployment-probe/2", secondSecret.toCharArray());
            TypedAcceptanceFixtures.node(temporaryDirectory.resolve("sources"), applicationId, false, "unused");
            ReviewedSourcePreparation failedSource = context.prepare(sourceRoot, DeploymentProjectType.NODE_SERVICE);
            DeploymentResult failed = context.deploy(failedSource, 2, nodeConfiguration(port, secondSecret),
                    List.of(secondReference), new DeploymentRuntimeSpecification.NodeService(18, health(port)), access(port));
            assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, failed.status(), () -> failed.events().toString());
            assertTrue(failed.events().stream().anyMatch(event -> event.step().equals("rollback") && event.succeeded()));
            assertSecretFree(failed, firstSecret);
            assertSecretFree(failed, secondSecret);
            LifecycleObservation restored = context.lifecycle(applicationId, LifecycleAction.REFRESH_STATUS);
            assertEquals(RuntimeState.RUNNING, restored.runtimeState());
            assertEquals(AutostartState.DISABLED, restored.autostartState());
            assertHttp(port, "node-live-v1");
        }
        verifyLifecycleAfterDesktopRestart(temporaryDirectory, applicationId, port, "node-live-v1");
    }

    @Test
    void deploysDockerContainerThenRollsBackAndRestoresLifecycle() throws Exception {
        exerciseContainer(DeploymentRuntimeSpecification.ContainerEngine.DOCKER, "docker", 6);
    }

    @Test
    void deploysPodmanQuadletThenRollsBackAndRestoresLifecycle() throws Exception {
        exerciseContainer(DeploymentRuntimeSpecification.ContainerEngine.PODMAN, "podman", 7);
    }

    private void exerciseContainer(DeploymentRuntimeSpecification.ContainerEngine engine, String kind, int portOffset)
            throws Exception {
        int port = port(portOffset);
        String applicationId = applicationId(kind);
        String marker = kind + "-live-v1";
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory)) {
            Path sourceRoot = TypedAcceptanceFixtures.container(temporaryDirectory.resolve("sources"), applicationId, true,
                    marker);
            ReviewedSourcePreparation firstSource = context.prepare(sourceRoot,
                    DeploymentProjectType.DOCKERFILE_CONTAINER);
            DeploymentRuntimeSpecification.Container runtime = containerRuntime(engine, port);
            DeploymentResult first = context.deploy(firstSource, 1, runtimeConfiguration(port), List.of(), runtime,
                    access(port));
            assertSuccessful(first, applicationId);
            assertHttp(port, marker);
            verifyLifecycle(context, applicationId);

            TypedAcceptanceFixtures.container(temporaryDirectory.resolve("sources"), applicationId, false, "unused");
            ReviewedSourcePreparation failedSource = context.prepare(sourceRoot,
                    DeploymentProjectType.DOCKERFILE_CONTAINER);
            DeploymentResult failed = context.deploy(failedSource, 2, runtimeConfiguration(port), List.of(), runtime,
                    access(port));
            assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, failed.status(), () -> failed.events().toString());
            assertTrue(failed.events().stream().anyMatch(event -> event.step().equals("rollback") && event.succeeded()));
            LifecycleObservation restored = context.lifecycle(applicationId, LifecycleAction.REFRESH_STATUS);
            assertEquals(RuntimeState.RUNNING, restored.runtimeState());
            assertEquals(AutostartState.DISABLED, restored.autostartState());
            assertHttp(port, marker);
        }
        verifyLifecycleAfterDesktopRestart(temporaryDirectory, applicationId, port, marker);
    }

    private static DeploymentRuntimeSpecification.Container containerRuntime(
            DeploymentRuntimeSpecification.ContainerEngine engine, int port) {
        return new DeploymentRuntimeSpecification.Container(engine,
                Map.of(port, port), List.of(), health(port));
    }

    private static List<ConfigurationEntry> nodeConfiguration(int port, String secret) throws Exception {
        return List.of(text("ACCEPTANCE_RUN_ID", RUN_ID),
                text("BUILD_LABEL", "bounded-build", ConfigurationScope.BUILD),
                text("EXPECTED_SECRET_SHA256", sha256(secret)), number("PORT", port));
    }

    private static List<ConfigurationEntry> runtimeConfiguration(int port) {
        return List.of(text("ACCEPTANCE_RUN_ID", RUN_ID), number("PORT", port));
    }

    private static ConfigurationEntry number(String key, long value) {
        return new ConfigurationEntry(key, ConfigurationScope.RUNTIME, new ConfigurationValue.Number(value));
    }

    private static ConfigurationEntry text(String key, String value) {
        return text(key, value, ConfigurationScope.RUNTIME);
    }

    private static ConfigurationEntry text(String key, String value, ConfigurationScope scope) {
        return new ConfigurationEntry(key, scope, new ConfigurationValue.Text(value));
    }

    private static HealthCheck.Http health(int port) {
        return new HealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/"), 200, 30);
    }

    private static Optional<UserAccessUrl> access(int port) {
        return Optional.of(new UserAccessUrl(URI.create("http://" + requiredProperty("managed.ssh.host") + ":" + port + "/")));
    }

    private static void assertSuccessful(DeploymentResult result, String applicationId) {
        assertEquals(DeploymentStatus.SUCCEEDED, result.status(), () -> result.events().toString());
        assertTrue(result.publishedReleaseSha256().orElseThrow().matches("[0-9a-f]{64}"));
        LifecycleObservation observation = result.finalObservation().orElseThrow();
        assertEquals(applicationId, observation.application().id());
        assertTrue(observation.ownershipVerified(), () -> observation.toString());
        assertEquals(RuntimeState.RUNNING, observation.runtimeState());
        for (String required : List.of("target-capabilities", "typed-host-compatibility", "source-upload",
                "remote-build", "deployment-inputs", "snapshot", "publish", "candidate-health",
                "final-observation", "release-retention", "candidate-cleanup")) {
            assertTrue(result.events().stream().anyMatch(event -> event.step().equals(required) && event.succeeded()),
                    () -> "missing successful " + required + ": " + result.events());
        }
    }

    private static void verifyLifecycle(LiveTypedDeploymentContext context, String applicationId) throws Exception {
        LifecycleObservation initial = context.lifecycle(applicationId, LifecycleAction.REFRESH_STATUS);
        assertEquals(RuntimeState.RUNNING, initial.runtimeState());
        assertTrue(initial.ownershipVerified());
        LifecycleObservation stopped = context.lifecycle(applicationId, LifecycleAction.STOP);
        assertEquals(RuntimeState.STOPPED, stopped.runtimeState());
        AutostartState initialAutostart = stopped.autostartState();
        LifecycleObservation started = context.lifecycle(applicationId, LifecycleAction.START);
        assertEquals(RuntimeState.RUNNING, started.runtimeState());
        assertEquals(initialAutostart, started.autostartState());
        assertEquals(RuntimeState.RUNNING,
                context.lifecycle(applicationId, LifecycleAction.RESTART).runtimeState());
        assertEquals(AutostartState.ENABLED,
                context.lifecycle(applicationId, LifecycleAction.ENABLE_AUTOSTART).autostartState());
        assertEquals(RuntimeState.STOPPED,
                context.lifecycle(applicationId, LifecycleAction.STOP).runtimeState());
        LifecycleObservation enabledStart = context.lifecycle(applicationId, LifecycleAction.START);
        assertEquals(RuntimeState.RUNNING, enabledStart.runtimeState());
        assertEquals(AutostartState.ENABLED, enabledStart.autostartState());
        LifecycleObservation disabled = context.lifecycle(applicationId, LifecycleAction.DISABLE_AUTOSTART);
        assertEquals(RuntimeState.RUNNING, disabled.runtimeState());
        assertEquals(AutostartState.DISABLED, disabled.autostartState());
    }

    private static void verifyLifecycleAfterDesktopRestart(Path persistenceRoot, String applicationId, int port,
                                                           String marker) throws Exception {
        try (LiveTypedDeploymentContext restarted = new LiveTypedDeploymentContext(persistenceRoot)) {
            LifecycleObservation restored = restarted.lifecycle(applicationId, LifecycleAction.REFRESH_STATUS);
            assertTrue(restored.ownershipVerified());
            assertEquals(RuntimeState.RUNNING, restored.runtimeState());
            assertEquals(AutostartState.DISABLED, restored.autostartState());
            assertEquals(RuntimeState.RUNNING,
                    restarted.lifecycle(applicationId, LifecycleAction.RESTART).runtimeState());
            assertHttp(port, marker);
        }
    }

    private static void assertHttp(int port, String marker) throws Exception {
        URI uri = URI.create("http://" + requiredProperty("managed.ssh.host") + ":" + port + "/");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        try {
            assertEquals(200, connection.getResponseCode(), () -> "desktop HTTP access failed: " + uri);
            try (InputStream input = connection.getInputStream()) {
                String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.toLowerCase(java.util.Locale.ROOT).contains(marker.toLowerCase(java.util.Locale.ROOT)),
                        () -> "desktop HTTP response lacked the expected marker: " + uri);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void assertSecretFree(DeploymentResult result, String secret) {
        assertFalse(result.toString().contains(secret), "deployment evidence exposed secret material");
    }

    private static String applicationId(String kind) {
        return "wtl-" + kind + "-" + RUN_ID;
    }

    private static int port(int offset) {
        return PORT_BASE + offset;
    }

    private static String randomSecret() {
        byte[] value = new byte[24];
        new SecureRandom().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }
}
