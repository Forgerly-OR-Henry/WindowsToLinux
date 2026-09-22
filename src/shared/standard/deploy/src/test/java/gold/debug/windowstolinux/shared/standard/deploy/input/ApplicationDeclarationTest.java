package gold.debug.windowstolinux.shared.standard.deploy.input;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.model.project.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationDeclarationTest {
    @TempDir
    Path root;
    @Test
    void workerDeclarationsPreserveArgumentsAndRejectUnlistedOrOnDemandProcesses() {
        var values = new HashMap<String, String>();
        ApplicationDeclaration.applyText(
                "mode=DAEMON\nworkers=queue\nworker.queue.entrypoint=worker.php\nworker.queue.arg.0=中文 参数", values);
        assertEquals(List.of(new ApplicationWorker("queue", new ApplicationCommand("worker.php", List.of("中文 参数")))),
                ApplicationDeclaration.resolve(values).workers());
        var unlisted = new HashMap<>(values);
        unlisted.remove("application.workers");
        assertThrows(IllegalArgumentException.class, () -> ApplicationDeclaration.resolve(unlisted));
        values.put("executionMode", "ON_DEMAND");
        values.put("application.verification.arg.0", "--help");
        assertThrows(IllegalArgumentException.class, () -> ApplicationDeclaration.resolve(values));
        assertThrows(IllegalArgumentException.class,
                () -> new ApplicationWorker("queue", ApplicationCommand.primary()));
    }

    @Test
    void onDemandManifestDoesNotAskForPortAndPreservesArgumentBoundaries() throws Exception {
        Files.writeString(root.resolve(ApplicationDeclaration.FILE), """
                version=1
                mode=ON_DEMAND
                command.entrypoint=main.py
                verification.arg.0=--self-test
                verification.arg.1=中文 文件.txt
                expectedOutput=ok
                """);
        var resolver = new AutomaticRuntimeResolver();
        var values = resolver.defaults(DeploymentProjectType.PYTHON_SERVICE, null, root);
        values.put("primary", "3.12");
        values.put("secondary", "main");
        assertTrue(resolver.missing("app", DeploymentProjectType.PYTHON_SERVICE, values).isEmpty());
        var runtime = resolver.runtime(DeploymentProjectType.PYTHON_SERVICE, values);
        assertInstanceOf(HealthCheck.Command.class, runtime.healthCheck());
        assertEquals(List.of("--self-test", "中文 文件.txt"), runtime.workload().verification().orElseThrow().arguments());
        assertTrue(resolver.access(values, "example.invalid").isEmpty());
    }

    @Test
    void sourceDeclarationsRejectUnresolvedValuesDuplicateKeysAndArgumentGaps() throws Exception {
        for (String content : List.of("mode=DAEMON\nmode=ON_DEMAND", "mode=${MODE}", "unsupported=true")) {
            Files.writeString(root.resolve(ApplicationDeclaration.FILE), content);
            assertThrows(IllegalArgumentException.class, () -> ApplicationDeclaration.read(root, new HashMap<>()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationDeclaration.command(Map.of("application.verification.arg.1", "bad"), "verification"));
    }

    @Test
    void internalHttpHealthDoesNotBecomeAWebsite() throws Exception {
        Files.writeString(root.resolve(ApplicationDeclaration.FILE), """
                mode=DAEMON
                health.mode=HTTP
                health.port=8080
                endpoints=local
                endpoint.local.protocol=HTTP
                endpoint.local.port=8080
                endpoint.local.bind=127.0.0.1
                endpoint.local.exposure=INTERNAL
                """);
        var resolver = new AutomaticRuntimeResolver();
        var values = resolver.defaults(DeploymentProjectType.NODE_SERVICE, null, root);
        values.put("version", "22");
        assertEquals(ApplicationWorkload.CategoryType.APP,
                resolver.runtime(DeploymentProjectType.NODE_SERVICE, values).workload().category());
        assertTrue(resolver.access(values, "example.invalid").isEmpty());
    }

    @Test
    void allExecutableAdaptersAcceptReviewedNoPortInstallationVerification() {
        var resolver = new AutomaticRuntimeResolver();
        for (var type : DeploymentProjectType.deployableTypes()) {
            if (type == DeploymentProjectType.STATIC_SITE || type == DeploymentProjectType.RECOGNITION_PREVIEW)
                continue;
            var values = new HashMap<String, String>();
            values.put("applicationDeclaration", "mode=ON_DEMAND\nverification.arg.0=--help\nexpectedOutput=help");
            values.put("version", "21");
            values.put("primary", "app.jar");
            values.put("secondary", "demo.Main");
            switch (type) {
                case JAVA_SOURCE -> values.put("primary", "src");
                case NODE_SERVICE -> values.put("version", "22");
                case PYTHON_SERVICE -> {
                    values.put("primary", "3.12");
                    values.put("secondary", "main");
                }
                case GO_SERVICE -> {
                    values.put("version", "1.24");
                    values.put("primary", "demo");
                    values.put("secondary", "main.go");
                }
                case RUST_SERVICE -> {
                    values.put("version", "1.89.0");
                    values.put("primary", "demo");
                    values.put("secondary", "src/main.rs");
                }
                case DOTNET_SERVICE -> {
                    values.put("version", "8.0.408");
                    values.put("primary", "Demo");
                    values.put("secondary", "Demo.dll");
                }
                case KOTLIN_SERVICE -> {
                    values.put("version", "2.0.21");
                    values.put("primary", "demo");
                    values.put("secondary", "demo.MainKt");
                    values.put("jvmTarget", "21");
                }
                case PHP_SERVICE -> {
                    values.put("version", "8.3");
                    values.put("primary", "source");
                    values.put("secondary", "main.php");
                }
                case RUBY_SERVICE -> {
                    values.put("version", "3.3.5");
                    values.put("primary", "source");
                    values.put("secondary", "main.rb");
                }
                case CMAKE_SERVICE -> {
                    values.put("version", "w2l-release");
                    values.put("primary", "demo");
                    values.put("secondary", "demo");
                }
                default -> {
                }
            }
            var immutable = Map.copyOf(values);
            assertTrue(resolver.missing("app", type, immutable).isEmpty(), type.name());
            var runtime = resolver.runtime(type, immutable);
            assertEquals(ApplicationWorkload.ExecutionMode.ON_DEMAND, runtime.workload().mode(), type.name());
            assertTrue(runtime.healthCheck().portNumber().isEmpty(), type.name());
        }
    }

    @Test
    void spellingErrorsOrUnlistedResourcesCannotBeSilentlyIgnored() {
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationDeclaration.applyText("mode=DAEMON\nendpoint.web.scpoe=EXTERNAL", new HashMap<>()));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationDeclaration.resolve(Map.of("application.input.logs.hostPath", "/srv/logs")));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationInput("input", "/srv/data", "/usr/bin"));
    }

    @Test
    void unknownExposureRequiresReviewAndNoPortExternalServicesRequireEndpoints() {
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationDeclaration.resolve(Map.of("healthMode", "PROCESS")));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationDeclaration.resolve(Map.of("healthMode", "PROCESS", "exposure", "EXTERNAL")));
        assertEquals(ApplicationWorkload.CategoryType.APP,
                ApplicationDeclaration.resolve(Map.of("healthMode", "PROCESS", "exposure", "INTERNAL")).category());
    }

}
