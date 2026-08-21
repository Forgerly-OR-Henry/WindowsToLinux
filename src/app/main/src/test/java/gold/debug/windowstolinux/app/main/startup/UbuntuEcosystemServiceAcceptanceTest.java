package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in product-entrypoint acceptance for all six ecosystem service adapters. / 六个生态服务适配器的可选产品入口验收。 */
@EnabledIfSystemProperty(named = "managed.runtime.service", matches = "true")
class UbuntuEcosystemServiceAcceptanceTest {
    private static final int PORT_BASE = 43000 + (int) ((System.currentTimeMillis() / 1000) % 9000);
    private static final String RUN_ID = Long.toUnsignedString(System.nanoTime(), 36);
    @TempDir Path temporaryDirectory;

    @TestFactory
    Stream<DynamicTest> deploysBuildsRollsBackAndRestoresEveryEcosystemServiceAdapter() {
        String selected = System.getProperty("managed.runtime.service.type", "all").trim();
        return Stream.of(DeploymentProjectType.GO_SERVICE, DeploymentProjectType.RUST_SERVICE,
                        DeploymentProjectType.DOTNET_SERVICE, DeploymentProjectType.KOTLIN_SERVICE,
                        DeploymentProjectType.PHP_SERVICE, DeploymentProjectType.RUBY_SERVICE)
                .filter(type -> selected.equalsIgnoreCase("all") || type.name().equalsIgnoreCase(selected))
                .map(type -> DynamicTest.dynamicTest(
                type.name().toLowerCase(java.util.Locale.ROOT), () -> exercise(type)));
    }

    private void exercise(DeploymentProjectType projectType) throws Exception {
        int port = PORT_BASE + projectType.ordinal() + 1;
        String suffix = projectType.name().toLowerCase(java.util.Locale.ROOT).replace("_service", "");
        String applicationId = "wtl-service-" + suffix + "-" + RUN_ID;
        Path stateRoot = temporaryDirectory.resolve("state-" + suffix);
        Path sources = stateRoot.resolve("sources");
        SecretReference firstSecret = new SecretReference("service-" + suffix, 1);
        SecretReference secondSecret = new SecretReference("service-" + suffix, 2);
        String firstSecretValue = randomSecret();
        String secondSecretValue = randomSecret();

        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(stateRoot)) {
            LinuxCapabilityFacts capabilities = context.inspectDeploymentCapabilities();
            String version = projectType == DeploymentProjectType.KOTLIN_SERVICE ? "2.0.21"
                    : capabilities.serviceRuntimeVersions().getOrDefault(projectType, java.util.Set.of())
                    .stream().sorted().findFirst().orElseThrow(() -> new AssertionError(
                            "prepared target did not expose an exact " + projectType + " runtime version"));
            Path wrapper = projectType == DeploymentProjectType.KOTLIN_SERVICE ? verifiedWrapperJar() : null;
            Path sourceRoot = EcosystemServiceAcceptanceFixture.create(sources, projectType, applicationId, version,
                    suffix + "-live-v1", true, wrapper);
            context.saveSecret(firstSecret, "application/service/" + suffix + "/1",
                    firstSecretValue.toCharArray());
            ReviewedSourcePreparation firstSource = context.prepare(sourceRoot, projectType);
            DeploymentRuntimeSpecification runtime = runtime(projectType, version, applicationId, port);
            DeploymentResult first = context.deploy(firstSource, 1, configuration(port), List.of(firstSecret),
                    runtime, access(port));

            assertSuccessful(first, applicationId);
            assertSecretFree(first, firstSecretValue);
            assertHttp(port, suffix + "-live-v1");
            verifyLifecycle(context, applicationId);

            context.saveSecret(secondSecret, "application/service/" + suffix + "/2",
                    secondSecretValue.toCharArray());
            EcosystemServiceAcceptanceFixture.create(sources, projectType, applicationId, version, "unused", false, wrapper);
            ReviewedSourcePreparation failedSource = context.prepare(sourceRoot, projectType);
            DeploymentResult failed = context.deploy(failedSource, 2, configuration(port), List.of(secondSecret),
                    runtime, access(port));

            assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, failed.status(), () -> failed.events().toString());
            assertTrue(failed.events().stream().anyMatch(event -> event.step().code().equals("rollback") && event.succeeded()),
                    () -> failed.events().toString());
            assertSecretFree(failed, firstSecretValue);
            assertSecretFree(failed, secondSecretValue);
            LifecycleObservation restored = context.lifecycle(applicationId, LifecycleAction.REFRESH_STATUS);
            assertEquals(RuntimeState.RUNNING, restored.runtimeState());
            assertEquals(AutostartState.DISABLED, restored.autostartState());
            assertTrue(restored.ownershipVerified());
            assertHttp(port, suffix + "-live-v1");
        }

        try (LiveTypedDeploymentContext restarted = new LiveTypedDeploymentContext(stateRoot)) {
            LifecycleObservation restored = restarted.lifecycle(applicationId, LifecycleAction.REFRESH_STATUS);
            assertEquals(RuntimeState.RUNNING, restored.runtimeState());
            assertEquals(AutostartState.DISABLED, restored.autostartState());
            assertTrue(restored.ownershipVerified());
            assertEquals(RuntimeState.RUNNING,
                    restarted.lifecycle(applicationId, LifecycleAction.RESTART).runtimeState());
            assertHttp(port, suffix + "-live-v1");
        }
    }

    private static DeploymentRuntimeSpecification runtime(
            DeploymentProjectType projectType, String version, String applicationId, int port) {
        String artifact = switch (projectType) {
            case GO_SERVICE -> "w2l-app";
            case RUST_SERVICE -> "w2l_rust";
            case DOTNET_SERVICE -> "W2lDotnet";
            case KOTLIN_SERVICE -> applicationId;
            case PHP_SERVICE -> "public";
            case RUBY_SERVICE -> "bundle";
            default -> throw new IllegalArgumentException("unsupported ecosystem service type");
        };
        String entrypoint = switch (projectType) {
            case GO_SERVICE -> "main.go";
            case RUST_SERVICE -> "src/main.rs";
            case DOTNET_SERVICE -> "W2lDotnet.dll";
            case KOTLIN_SERVICE -> "acceptance.MainKt";
            case PHP_SERVICE -> "public/index.php";
            case RUBY_SERVICE -> "config.ru";
            default -> throw new IllegalArgumentException("unsupported ecosystem service type");
        };
        return switch (projectType) {
            case GO_SERVICE -> new DeploymentRuntimeSpecification.GoService(version, artifact, entrypoint, health(port));
            case RUST_SERVICE -> new DeploymentRuntimeSpecification.RustService(version, artifact, entrypoint, health(port));
            case DOTNET_SERVICE -> new DeploymentRuntimeSpecification.DotNetService(version, artifact, entrypoint, health(port));
            case KOTLIN_SERVICE -> new DeploymentRuntimeSpecification.KotlinService(version, artifact, entrypoint, health(port));
            case PHP_SERVICE -> new DeploymentRuntimeSpecification.PhpService(version, artifact, entrypoint, port, health(port));
            case RUBY_SERVICE -> new DeploymentRuntimeSpecification.RubyService(version, artifact, entrypoint, port, health(port));
            default -> throw new IllegalArgumentException("unsupported ecosystem service type");
        };
    }

    private static List<ConfigurationEntry> configuration(int port) {
        return List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                new ConfigurationValue.Number(port)));
    }

    private static HealthCheck.Http health(int port) {
        return new HealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/"), 200, 45);
    }

    private static Optional<UserAccessUrl> access(int port) {
        return Optional.of(new UserAccessUrl(URI.create("http://" + requiredProperty("managed.ssh.host")
                + ":" + port + "/")));
    }

    private static void assertSuccessful(DeploymentResult result, String applicationId) {
        assertEquals(DeploymentStatus.SUCCEEDED, result.status(), () -> result.events().toString());
        assertTrue(result.publishedReleaseSha256().orElseThrow().matches("[0-9a-f]{64}"));
        LifecycleObservation observation = result.finalObservation().orElseThrow();
        assertEquals(applicationId, observation.application().id());
        assertEquals(RuntimeState.RUNNING, observation.runtimeState());
        assertTrue(observation.ownershipVerified());
        for (String required : List.of("target-capabilities", "typed-host-compatibility", "source-upload",
                "remote-build", "deployment-inputs", "snapshot", "publish", "candidate-health",
                "final-observation", "release-retention", "candidate-cleanup")) {
            assertTrue(result.events().stream().anyMatch(event -> event.step().equals(required) && event.succeeded()),
                    () -> "missing successful " + required + ": " + result.events());
        }
    }

    private static void verifyLifecycle(LiveTypedDeploymentContext context, String applicationId) throws Exception {
        assertEquals(RuntimeState.RUNNING,
                context.lifecycle(applicationId, LifecycleAction.REFRESH_STATUS).runtimeState());
        assertEquals(RuntimeState.STOPPED, context.lifecycle(applicationId, LifecycleAction.STOP).runtimeState());
        assertEquals(RuntimeState.RUNNING, context.lifecycle(applicationId, LifecycleAction.START).runtimeState());
        assertEquals(RuntimeState.RUNNING, context.lifecycle(applicationId, LifecycleAction.RESTART).runtimeState());
        assertEquals(AutostartState.ENABLED,
                context.lifecycle(applicationId, LifecycleAction.ENABLE_AUTOSTART).autostartState());
        LifecycleObservation disabled = context.lifecycle(applicationId, LifecycleAction.DISABLE_AUTOSTART);
        assertEquals(RuntimeState.RUNNING, disabled.runtimeState());
        assertEquals(AutostartState.DISABLED, disabled.autostartState());
    }

    private static void assertHttp(int port, String marker) throws Exception {
        URI uri = URI.create("http://" + requiredProperty("managed.ssh.host") + ":" + port + "/");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        try {
            assertEquals(200, connection.getResponseCode(), () -> "desktop HTTP access failed: " + uri);
            try (InputStream input = connection.getInputStream()) {
                assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains(marker));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void assertSecretFree(DeploymentResult result, String secret) {
        assertFalse(result.toString().contains(secret), "deployment evidence exposed secret material");
        assertFalse(result.toString().contains("typed-live-acceptance-master"),
                "deployment evidence exposed secret-store master material");
    }

    private static Path verifiedWrapperJar() throws Exception {
        Path path = Path.of(requiredProperty("managed.gradle.wrapper.jar")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), "managed.gradle.wrapper.jar must identify a regular file");
        String expected = requiredProperty("managed.gradle.wrapper.sha256").toLowerCase(java.util.Locale.ROOT);
        assertTrue(expected.matches("[0-9a-f]{64}"), "managed.gradle.wrapper.sha256 must be a SHA-256 digest");
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        assertEquals(expected, actual, "Gradle wrapper JAR did not match the reviewed digest");
        return path;
    }

    private static String randomSecret() {
        byte[] value = new byte[24];
        new SecureRandom().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }
}
