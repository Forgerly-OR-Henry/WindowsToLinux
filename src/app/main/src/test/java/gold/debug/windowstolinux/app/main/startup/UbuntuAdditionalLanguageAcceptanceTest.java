package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
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

/** Covers languages absent from the original live fixture matrix through the production service. / 通过生产服务补齐原实机矩阵缺少的语言夹具。 */
@EnabledIfSystemProperty(named = "managed.runtime.additional", matches = "true")
class UbuntuAdditionalLanguageAcceptanceTest {
    private static final String RUN_ID = Long.toUnsignedString(System.nanoTime(), 36);
    private static final int PORT_BASE = 55000 + (int) ((System.currentTimeMillis() / 1000) % 4000);
    @TempDir Path temporaryDirectory;

    @Test
    void deploysLocalMavenSpringBoot() throws Exception {
        int port = PORT_BASE;
        exercise("spring", "java/maven/spring-boot/phase3-ubuntu-regression", port,
                new DeploymentRuntimeSpecification.SpringBoot(health(port)), "phase three ubuntu regression");
    }

    @Test
    void deploysCppThroughCmake() throws Exception {
        int port = PORT_BASE + 1;
        exercise("cpp", "c/cmake/cpp-service/phase3-ready", port,
                new DeploymentRuntimeSpecification.CmakeService("w2l-release", "phase3_cmake", "phase3_cmake", health(port)),
                "phase3-live-ok");
    }

    @Test
    void buildsTypescriptAndDeploysCompiledNodeService() throws Exception {
        int port = PORT_BASE + 2;
        exercise("typescript", "node/npm/typescript-service/phase3-ready", port,
                new DeploymentRuntimeSpecification.NodeService(18, health(port)), "typescript-live-ok");
    }

    private void exercise(String kind, String fixture, int port, DeploymentRuntimeSpecification runtime, String marker)
            throws Exception {
        String id = "wtl-live-" + kind + "-" + RUN_ID;
        Path source = copyFixture(fixture, temporaryDirectory.resolve("sources").resolve(id));
        if (runtime.projectType() == DeploymentProjectType.SPRING_BOOT) {
            replace(source.resolve("pom.xml"), "phase3-ubuntu-regression", id);
        } else if (runtime.projectType() == DeploymentProjectType.NODE_SERVICE) {
            replace(source.resolve("package.json"), "wtl-typescript-live", id);
            replace(source.resolve("package-lock.json"), "wtl-typescript-live", id);
        }
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory.resolve("state"))) {
            var preparation = context.prepare(source, runtime.projectType());
            String applicationId = preparation.assessment().facts().orElseThrow().applicationId();
            String host = System.getProperty("managed.ssh.host");
            URI access = URI.create("http://" + host + ":" + port + "/");
            try {
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
            } finally {
                if (context.service.listManagedApplications().stream().anyMatch(app -> app.id().equals(applicationId))) {
                    context.lifecycle(applicationId, LifecycleAction.DISABLE_AUTOSTART);
                    assertEquals(RuntimeState.STOPPED, context.lifecycle(applicationId, LifecycleAction.STOP).runtimeState());
                    System.out.printf("LIVE_STOPPED application=%s%n", applicationId);
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
