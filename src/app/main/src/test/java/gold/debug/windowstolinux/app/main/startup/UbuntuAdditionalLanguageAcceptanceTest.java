package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Covers additional source build combinations through the production service. / 通过生产服务补齐源码构建组合。 */
@EnabledIfSystemProperty(named = "managed.runtime.additional", matches = "true")
class UbuntuAdditionalLanguageAcceptanceTest {
    private static final String RUN_ID = Long.toUnsignedString(System.nanoTime(), 36);
    private static final int PORT_BASE = 55000 + (int) ((System.currentTimeMillis() / 1000) % 4000);
    @TempDir Path temporaryDirectory;

    @Test
    void deploysLocalMavenSpringBoot() throws Exception {
        int port = PORT_BASE;
        exercise("spring", "java/maven/spring-boot/success-deployment-smoke", port,
                new DeploymentRuntimeSpecification.SpringBoot(health(port)), "deployment-smoke-ok");
    }

    @Test
    void deploysCppThroughCmake() throws Exception {
        int port = PORT_BASE + 1;
        exercise("cpp", "c/cmake/cpp-service/success-deployment-smoke", port,
                new DeploymentRuntimeSpecification.CmakeService("w2l-release", "http_service", "http_service", health(port)),
                "deployment-smoke-ok");
    }

    @Test
    void buildsTypescriptAndDeploysCompiledNodeService() throws Exception {
        int port = PORT_BASE + 2;
        exercise("typescript", "node/npm/typescript-service/success-deployment-smoke", port,
                new DeploymentRuntimeSpecification.NodeService(18, health(port)), "deployment-smoke-ok");
    }

    @Test
    void deploysLocalMavenWrapperSpringBoot() throws Exception {
        int port = PORT_BASE + 3;
        exercise("spring-wrapper", "java/maven-wrapper/spring-boot/success-deployment-smoke", port,
                new DeploymentRuntimeSpecification.SpringBoot(health(port)), "deployment-smoke-ok");
    }

    @Test
    void deploysLocalGradleSpringBoot() throws Exception {
        int port = PORT_BASE + 4;
        exercise("spring-gradle", "java/gradle/spring-boot/success-deployment-smoke", port,
                new DeploymentRuntimeSpecification.SpringBoot(health(port)), "deployment-smoke-ok");
    }

    @Test
    void buildsTypescriptWithPnpm() throws Exception {
        int port = PORT_BASE + 5;
        exercise("typescript-pnpm", "node/pnpm/typescript-service/success-deployment-smoke", port,
                new DeploymentRuntimeSpecification.NodeService(18, health(port)), "deployment-smoke-ok");
    }

    @Test
    void buildsTypescriptWithYarn() throws Exception {
        int port = PORT_BASE + 6;
        exercise("typescript-yarn", "node/yarn/typescript-service/success-deployment-smoke", port,
                new DeploymentRuntimeSpecification.NodeService(18, health(port)), "deployment-smoke-ok");
    }

    private void exercise(String kind, String fixture, int port, DeploymentRuntimeSpecification runtime, String marker)
            throws Exception {
        String id = "wtl-live-" + kind + "-" + RUN_ID;
        Path source = copyFixture(fixture, temporaryDirectory.resolve("sources").resolve(id));
        if (runtime.projectType() == DeploymentProjectType.SPRING_BOOT) {
            for (String name : List.of("pom.xml", "settings.gradle")) {
                if (Files.isRegularFile(source.resolve(name))) replace(source.resolve(name), "fixture-http-service", id);
            }
        } else if (runtime.projectType() == DeploymentProjectType.NODE_SERVICE) {
            for (String name : List.of("package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock")) {
                if (Files.isRegularFile(source.resolve(name))) replace(source.resolve(name), "wtl-typescript-live", id);
            }
        }
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory.resolve("state"))) {
            var preparation = context.prepare(source, runtime.projectType());
            String applicationId = preparation.assessment().facts().orElseThrow().applicationId();
            assertEquals(id, applicationId);
            String host = System.getProperty("managed.ssh.host");
            URI access = URI.create("http://" + host + ":" + port + "/");
            try (AutoCloseable cleanup = context::stopTestApplications) {
                var result = context.deploy(preparation, 1, List.of(number("PORT", port), number("SERVER_PORT", port)),
                        List.of(), runtime, Optional.of(new UserAccessUrl(access)));
                assertEquals(DeploymentStatus.SUCCEEDED, result.status(), () -> result.events().toString());
                assertEquals(RuntimeState.RUNNING, result.finalObservation().orElseThrow().runtimeState());
                HttpURLConnection connection = (HttpURLConnection) access.toURL().openConnection();
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(30_000);
                try {
                    assertEquals(200, connection.getResponseCode());
                    try (var input = connection.getInputStream()) {
                        assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains(marker));
                    }
                } finally {
                    connection.disconnect();
                }
                System.out.printf("LIVE_HTTP application=%s url=%s marker=%s%n", applicationId, access, marker);
                if (kind.equals("typescript-yarn")) {
                    for (var action : List.of(
                            gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.STOP,
                            gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.STOP,
                            gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.START,
                            gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.RESTART,
                            gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.ENABLE_AUTOSTART,
                            gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.DISABLE_AUTOSTART)) {
                        var observed = context.lifecycle(applicationId, action);
                        assertTrue(observed.ownershipVerified());
                        assertEquals(action == gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.STOP
                                ? RuntimeState.STOPPED : RuntimeState.RUNNING, observed.runtimeState(), observed::toString);
                        System.out.printf("LIVE_YARN_LIFECYCLE application=%s action=%s evidence=%s%n", applicationId, action, observed.evidence());
                    }
                }
            }
        }
    }

    private static Path copyFixture(String relative, Path target) throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (repository != null && !Files.isDirectory(repository.resolve("test"))) repository = repository.getParent();
        assertNotNull(repository, "repository fixtures are required");
        Path template = repository.resolve("test").resolve(relative);
        try (var paths = Files.walk(template)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(template.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        return target;
    }

    private static void replace(Path path, String before, String after) throws Exception {
        Files.writeString(path, Files.readString(path).replace(before, after));
    }

    private static ConfigurationEntry number(String key, int value) {
        return new ConfigurationEntry(key, ConfigurationScope.RUNTIME, new ConfigurationValue.Number(value));
    }

    private static HealthCheck.Http health(int port) {
        return new HealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/"), 200, 45);
    }
}
