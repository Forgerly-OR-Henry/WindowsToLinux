package gold.debug.windowstolinux.app.db.persistence.serialization;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentRuntimePersistenceCodecTest {
    private static final HealthCheck.Tcp TCP = new HealthCheck.Tcp(18080, 20, 2);
    private static final HealthCheck.Http HTTP = new HealthCheck.Http(
            URI.create("http://127.0.0.1:18081/health"), 200, 20);
    private final DeploymentRuntimePersistenceCodec codec = new DeploymentRuntimePersistenceCodec();

    @Test
    void roundTripsEveryReviewedRuntimeWithoutASecondHealthCopy() throws Exception {
        Map<Integer, Integer> ports = new LinkedHashMap<>();
        ports.put(18080, 8080);
        ports.put(18443, 8443);
        List<DeploymentRuntimeSpecification> runtimes = List.of(
                new DeploymentRuntimeSpecification.SpringBoot(TCP),
                new DeploymentRuntimeSpecification.JavaJar("app.jar", "demo.Main", "21",
                        List.of("-Xmx256m"), List.of("--mode=test"), TCP),
                new DeploymentRuntimeSpecification.JavaSource("src", "demo.Main", "21",
                        List.of("-Xmx256m"), List.of("--mode=test"), TCP),
                new DeploymentRuntimeSpecification.NodeService(22, TCP),
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", TCP),
                new DeploymentRuntimeSpecification.StaticSite("dist", OptionalInt.of(22), HTTP),
                new DeploymentRuntimeSpecification.Container(
                        DeploymentRuntimeSpecification.ContainerEngineType.PODMAN, ports,
                        List.of(new DeploymentRuntimeSpecification.ManagedVolume(
                                "windowstolinux-demo", "/var/lib/demo", true)), TCP),
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
