package gold.debug.windowstolinux.shared.config.persistence.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

class DeploymentRuntimePersistenceCodecTest {
    private static final HealthCheck.Tcp TCP = new HealthCheck.Tcp(18080, 20, 2);

    private static final HealthCheck.Http HTTP = new HealthCheck.Http(URI.create("http://127.0.0.1:18081/health"), 200,
            20);

    private final DeploymentRuntimePersistenceCodec codec = new DeploymentRuntimePersistenceCodec();

    @Test
    void genericProcessUsesVersionSevenAndNeverGuessesMissingRuntimeFields() throws Exception {
        var workload = new gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload(
                gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.DAEMON,
                true,
                new gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand("bin/zig-app",
                        List.of("--listen", "18080")),
                "", List.of(), java.util.Optional.empty(), "", java.util.Optional.empty(), List.of(), List.of(), "",
                List.of());
        var runtime = new DeploymentRuntimeSpecification.ManagedProcess(TCP,
                gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode.SYSTEMD_STATIC, workload);
        var encoded = codec.write(runtime);
        assertEquals(7, encoded[4]);
        assertEquals(runtime, codec.read(encoded, TCP));
        encoded[4] = 5;
        assertThrows(java.io.IOException.class, () -> codec.read(encoded, TCP));
        var historical = codec.write(new DeploymentRuntimeSpecification.SpringBoot(TCP));
        assertEquals(5, historical[4]);
        assertEquals(new DeploymentRuntimeSpecification.SpringBoot(TCP), codec.read(historical, TCP));
    }

    @Test
    void preservesNewTargetsAndRejectsLegacyLayout() throws Exception {
        var java8 = new DeploymentRuntimeSpecification.SpringBoot("8", TCP);
        var kotlin8 = new DeploymentRuntimeSpecification.KotlinService("1.8.22", "demo", "demo.MainKt", "8", TCP);
        assertEquals(java8, codec.read(codec.write(java8), TCP));
        assertEquals(kotlin8, codec.read(codec.write(kotlin8), TCP));
        // A version-one Spring Boot payload contains only magic, format and runtime tag. / 第一版 Spring Boot 载荷仅包含魔数、格式和运行时标签。
        byte[] legacy = Arrays.copyOf(codec.write(java8), 6);
        legacy[4] = 1;
        assertThrows(java.io.IOException.class, () -> codec.read(legacy, TCP));
    }

    @Test
    void roundTripsEveryReviewedRuntimeWithoutASecondHealthCopy() throws Exception {
        Map<Integer, Integer> ports = new LinkedHashMap<>();
        ports.put(18080, 8080);
        ports.put(18443, 8443);
        List<DeploymentRuntimeSpecification> runtimes = List.of(new DeploymentRuntimeSpecification.SpringBoot(TCP),
                new DeploymentRuntimeSpecification.JavaJar("app.jar", "demo.Main", "21", List.of("-Xmx256m"),
                        List.of("--mode=test"), TCP),
                new DeploymentRuntimeSpecification.JavaSource("src", "demo.Main", "21", List.of("-Xmx256m"),
                        List.of("--mode=test"), TCP),
                new DeploymentRuntimeSpecification.NodeService(22, TCP),
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", TCP),
                new DeploymentRuntimeSpecification.StaticSite("dist", OptionalInt.of(22), HTTP),
                new DeploymentRuntimeSpecification.Container(DeploymentRuntimeSpecification.ContainerEngineType.PODMAN,
                        ports,
                        List.of(new DeploymentRuntimeSpecification.ManagedVolume("windowstolinux-demo", "/var/lib/demo",
                                true)),
                        TCP),
                new DeploymentRuntimeSpecification.GoService("1.24", "demo", "main.go", TCP),
                new DeploymentRuntimeSpecification.RustService("1.90.0", "demo", "src/main.rs", TCP),
                new DeploymentRuntimeSpecification.DotNetService("8.0", "demo", "demo.dll", TCP),
                new DeploymentRuntimeSpecification.KotlinService("2.1.0", "demo", "demo.MainKt", TCP),
                new DeploymentRuntimeSpecification.PhpService("8.3", "public", "public/index.php", 18080, TCP),
                new DeploymentRuntimeSpecification.RubyService("3.3.5", "source", "server.rb", 18080, TCP),
                new DeploymentRuntimeSpecification.CmakeService("release", "demo", "demo", TCP));

        for (DeploymentRuntimeSpecification runtime : runtimes) {
            assertEquals(runtime, codec.read(codec.write(runtime), runtime.healthCheck()));
        }
    }

    @Test
    void rejectsUnsupportedTruncatedAndTrailingDocuments() throws Exception {
        byte[] valid = codec.write(new DeploymentRuntimeSpecification.NodeService(22, TCP));
        byte[] unsupported = valid.clone();
        unsupported[5] = 99;
        assertThrows(java.io.IOException.class, () -> codec.read(unsupported, TCP));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, 5), TCP));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, valid.length + 1), TCP));
        assertThrows(java.io.IOException.class, () -> codec.read(new byte[1_048_577], TCP));
    }

    @Test
    void refusesToRebindStaticSiteToATcpHealthContract() throws Exception {
        byte[] runtime = codec.write(new DeploymentRuntimeSpecification.StaticSite("dist", HTTP));
        assertThrows(java.io.IOException.class, () -> codec.read(runtime, TCP));
    }
}
