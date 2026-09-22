package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import gold.debug.windowstolinux.app.main.startup.EcosystemExtensionAcceptanceFixture.ArchitectureType;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.model.capability.EcosystemToolType;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in product-entrypoint deployment, lifecycle, rollback, and reconnect acceptance for every extension architecture.
 *
 * <p>针对每个扩展架构的可选产品入口部署、生命周期、回滚与重连验收。
 */
@EnabledIfSystemProperty(named = "managed.runtime.extension", matches = "true")
class EcosystemExtensionProductEntryAcceptanceTest {
    private static final int PORT_BASE = 47000 + (int) ((System.currentTimeMillis() / 1000) % 8000);

    private static final String RUN_ID = Long.toUnsignedString(System.nanoTime(), 36);

    private static final Object PREPARATION_LOCK = new Object();

    private static boolean environmentPrepared;

    @TempDir
    Path temporaryDirectory;

    @TestFactory
    Stream<DynamicTest> deploysRollsBackAndRestoresEveryChangedArchitecture() {
        String selected = System.getProperty("managed.runtime.extension.type", "all").trim();
        List<ArchitectureType> architectures = selected.equalsIgnoreCase("all")
                ? List.of(ArchitectureType.values())
                : Arrays.stream(selected.split(",", -1)).map(value -> Arrays.stream(ArchitectureType.values())
                        .filter(architecture -> architecture.key().equalsIgnoreCase(value.trim())
                                || architecture.name().equalsIgnoreCase(value.trim()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("unknown extension architecture: " + value)))
                        .distinct().toList();
        assertFalse(architectures.isEmpty(), () -> "unknown managed.runtime.extension.type: " + selected);
        return architectures.stream()
                .map(architecture -> DynamicTest.dynamicTest(architecture.key(), () -> exercise(architecture)));
    }

    private void exercise(ArchitectureType architecture) throws Exception {
        int port = PORT_BASE + architecture.ordinal() + 1;
        String applicationId = "wtl-ext-" + architecture.key() + "-" + RUN_ID;
        Path stateRoot = temporaryDirectory.resolve("state-" + architecture.key());
        Path sourceParent = stateRoot.resolve("sources");
        SecretReference firstSecret = new SecretReference("extension-" + architecture.key(), 1);
        SecretReference secondSecret = new SecretReference("extension-" + architecture.key(), 2);
        String firstSecretValue = randomSecret();
        String secondSecretValue = randomSecret();

        try (AutoCloseable cleanup = () -> {
            try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(stateRoot)) {
                context.stopTestApplications();
            }
        }) {
            try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(stateRoot)) {
                LinuxCapabilityFacts capabilities = preparedCapabilities(context);
                VersionSelection versions = versions(architecture, capabilities);
                Path sourceRoot = EcosystemExtensionAcceptanceFixture.create(sourceParent, architecture, applicationId,
                        versions.runtimeVersion(), versions.toolVersion(), architecture.key() + "-live-v1", true);
                ReviewedSourcePreparation firstSource = context.prepare(sourceRoot, architecture.projectType());
                DeploymentProjectFacts firstFacts = firstSource.assessment().facts().orElseThrow();
                assertEquals(architecture.buildTool(), firstFacts.buildTool());
                assertEquals(applicationId, firstFacts.applicationId(),
                        "live fixtures must have isolated application identities");
                DeploymentRuntimeSpecification runtime = runtime(architecture, versions.runtimeVersion(), applicationId,
                        port);

                context.saveSecret(firstSecret, "application/extension/" + architecture.key() + "/1",
                        firstSecretValue.toCharArray());
                DeploymentResult first = context.deploy(firstSource, 1, configuration(port), List.of(firstSecret),
                        runtime, access(port));
                assertSuccessful(first, applicationId);
                assertSecretFree(first, firstSecretValue);
                assertHttp(port, architecture.key() + "-live-v1");
                verifyLifecycle(context, applicationId);

                context.saveSecret(secondSecret, "application/extension/" + architecture.key() + "/2",
                        secondSecretValue.toCharArray());
                EcosystemExtensionAcceptanceFixture.create(sourceParent, architecture, applicationId,
                        versions.runtimeVersion(), versions.toolVersion(), "unused", false);
                ReviewedSourcePreparation rejectedSource = context.prepare(sourceRoot, architecture.projectType());
                DeploymentResult rejected = context.deploy(rejectedSource, 2, configuration(port),
                        List.of(secondSecret), runtime, access(port));
                assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, rejected.status(),
                        () -> rejected.events().toString());
                assertTrue(
                        rejected.events().stream()
                                .anyMatch(event -> event.step().code().equals("rollback") && event.succeeded()),
                        () -> rejected.events().toString());
                assertSecretFree(rejected, firstSecretValue);
                assertSecretFree(rejected, secondSecretValue);
                assertRestored(context, applicationId, port, architecture.key() + "-live-v1");
            }

            try (LiveTypedDeploymentContext restarted = new LiveTypedDeploymentContext(stateRoot)) {
                assertRestored(restarted, applicationId, port, architecture.key() + "-live-v1");
                assertEquals(RuntimeState.RUNNING,
                        restarted.lifecycle(applicationId, LifecycleAction.RESTART).runtimeState());
            }
        }
    }

    private static LinuxCapabilityFacts preparedCapabilities(LiveTypedDeploymentContext context) throws Exception {
        if (Boolean.getBoolean("managed.runtime.extension.prepare-environment")) {
            synchronized (PREPARATION_LOCK) {
                if (!environmentPrepared) {
                    context.prepareEnvironment();
                    environmentPrepared = true;
                }
            }
        }
        return context.inspectDeploymentCapabilities();
    }

    private static VersionSelection versions(ArchitectureType architecture, LinuxCapabilityFacts capabilities) {
        return switch (architecture) {
            case JAVA_JDK -> new VersionSelection("21", "");
            case NODE_NPM, NODE_PNPM, NODE_YARN -> {
                int node = capabilities.nodeMajorVersions().stream().filter(major -> major >= 18 && major <= 24)
                        .min(Comparator.naturalOrder()).orElse(22);
                String manager = switch (architecture) {
                    case NODE_NPM -> requireTool(capabilities, EcosystemToolType.NPM, ignored -> true, "npm");
                    case NODE_PNPM -> "10.15.1";
                    case NODE_YARN -> "4.9.2";
                    default -> throw new IllegalStateException("not a Node architecture");
                };
                yield new VersionSelection(Integer.toString(node), manager);
            }
            case PYTHON_PIP, PYTHON_PIPENV, PYTHON_POETRY, PYTHON_UV -> {
                String python = Stream.of("3.12", "3.11").filter(capabilities.pythonVersions()::contains).findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "target must expose reviewed Python 3.12 or 3.11 with venv support"));
                yield new VersionSelection(python, "");
            }
            case KOTLIN_KOTLINC -> new VersionSelection("2.0.21", "2.0.21");
            case PHP_CLI -> {
                String runtime = serviceRuntime(capabilities, architecture);
                yield new VersionSelection(runtime,
                        requireTool(capabilities, EcosystemToolType.PHP, runtime::equals, "matching PHP CLI"));
            }
            case RUBY_CLI -> {
                String runtime = serviceRuntime(capabilities, architecture);
                yield new VersionSelection(runtime,
                        requireTool(capabilities, EcosystemToolType.RUBY, runtime::equals, "matching Ruby CLI"));
            }
            case CMAKE -> {
                requireTool(capabilities, EcosystemToolType.C_COMPILER, ignored -> true, "C compiler");
                requireTool(capabilities, EcosystemToolType.NINJA, ignored -> true, "Ninja generator");
                yield new VersionSelection("",
                        requireTool(capabilities, EcosystemToolType.CMAKE,
                                version -> major(version) > 3 || major(version) == 3 && minor(version) >= 25,
                                "CMake 3.25 or newer"));
            }
        };
    }

    private static String serviceRuntime(LinuxCapabilityFacts capabilities, ArchitectureType architecture) {
        return capabilities.serviceRuntimeVersions().getOrDefault(architecture.projectType(), Set.of()).stream()
                .sorted().findFirst().orElseThrow(() -> new AssertionError(
                        "target does not expose an exact " + architecture.projectType() + " runtime"));
    }

    private static String requireTool(LinuxCapabilityFacts capabilities, EcosystemToolType tool,
            Predicate<String> accepted, String description) {
        return capabilities.ecosystemToolVersions().getOrDefault(tool, Set.of()).stream().filter(accepted).sorted()
                .findFirst()
                .orElseThrow(() -> new AssertionError("target does not expose " + description + "; observed " + tool
                        + " versions: " + capabilities.ecosystemToolVersions().getOrDefault(tool, Set.of())));
    }

    private static int major(String version) {
        String[] segments = version.split("[.]", 3);
        try {
            return Integer.parseInt(segments[0].replaceFirst("[^0-9].*$", ""));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static int minor(String version) {
        String[] segments = version.split("[.]", 3);
        if (segments.length < 2)
            return 0;
        try {
            return Integer.parseInt(segments[1].replaceFirst("[^0-9].*$", ""));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static DeploymentRuntimeSpecification runtime(ArchitectureType architecture, String version,
            String applicationId, int port) {
        return switch (architecture) {
            case JAVA_JDK -> new DeploymentRuntimeSpecification.JavaSource("src", "acceptance.Main", "21", List.of(),
                    List.of(), health(port));
            case NODE_NPM, NODE_PNPM, NODE_YARN ->
                new DeploymentRuntimeSpecification.NodeService(Integer.parseInt(version), health(port));
            case PYTHON_PIP, PYTHON_PIPENV, PYTHON_POETRY, PYTHON_UV ->
                new DeploymentRuntimeSpecification.PythonService(version, "http_service_fixture", health(port));
            case KOTLIN_KOTLINC -> new DeploymentRuntimeSpecification.KotlinService(version, applicationId,
                    "acceptance.MainKt", health(port));
            case PHP_CLI -> new DeploymentRuntimeSpecification.PhpService(version, "public", "public/index.php", port,
                    health(port));
            case RUBY_CLI ->
                new DeploymentRuntimeSpecification.RubyService(version, "source", "server.rb", port, health(port));
            case CMAKE -> new DeploymentRuntimeSpecification.CmakeService("w2l-release", "http_service", "http_service",
                    health(port));
        };
    }

    private static List<ConfigurationEntry> configuration(int port) {
        return List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(port)));
    }

    private static HealthCheck.Http health(int port) {
        return new HealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/"), 200, 45);
    }

    private static Optional<UserAccessUrl> access(int port) {
        return Optional
                .of(new UserAccessUrl(URI.create("http://" + requiredProperty("managed.ssh.host") + ":" + port + "/")));
    }

    private static void assertSuccessful(DeploymentResult result, String applicationId) {
        assertEquals(DeploymentStatus.SUCCEEDED, result.status(), () -> result.events().toString());
        assertTrue(result.publishedReleaseSha256().orElseThrow().matches("[0-9a-f]{64}"));
        LifecycleObservation observation = result.finalObservation().orElseThrow();
        assertEquals(applicationId, observation.application().id());
        assertEquals(RuntimeState.RUNNING, observation.runtimeState());
        assertTrue(observation.ownershipVerified());
        for (String required : List.of("target-capabilities", "typed-host-compatibility", "source-upload",
                "remote-build", "deployment-inputs", "snapshot", "publish", "candidate-health", "final-observation",
                "release-retention", "candidate-cleanup")) {
            assertTrue(
                    result.events().stream()
                            .anyMatch(event -> event.step().code().equals(required) && event.succeeded()),
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

    private static void assertRestored(LiveTypedDeploymentContext context, String applicationId, int port,
            String marker) throws Exception {
        LifecycleObservation restored = context.lifecycle(applicationId, LifecycleAction.REFRESH_STATUS);
        assertEquals(RuntimeState.RUNNING, restored.runtimeState());
        assertEquals(AutostartState.DISABLED, restored.autostartState());
        assertTrue(restored.ownershipVerified());
        assertHttp(port, marker);
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
        assertSummaryApi(uri.resolve("api/summary?values=2,3,5"), 200);
        assertSummaryApi(uri.resolve("api/summary?values=invalid"), 400);
    }

    private static void assertSummaryApi(URI uri, int expectedStatus) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        try {
            assertEquals(expectedStatus, connection.getResponseCode(), () -> "summary API failed: " + uri);
            if (expectedStatus == 200) {
                assertTrue(connection.getContentType().startsWith("application/json"));
                try (InputStream input = connection.getInputStream()) {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var body = mapper.readTree(input);
                    assertEquals("ok", body.path("status").asText());
                    assertEquals(10, body.path("total").asInt());
                    assertEquals(mapper.valueToTree(List.of(2, 3, 5)), body.path("items"));
                }
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

    private record VersionSelection(String runtimeVersion, String toolVersion) {
    }
}
