package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;
import org.junit.jupiter.api.Test;

class DeploymentRuntimeCodecTest {
    private static final DeploymentHealthCheck.Tcp TCP = new DeploymentHealthCheck.Tcp(18080, 20, 2);

    private static final DeploymentHealthCheck.Http HTTP = new DeploymentHealthCheck.Http(
        URI.create("http://127.0.0.1:18081/health"), 200, 20);

    private final DeploymentRuntimeCodec codec = new DeploymentRuntimeCodec();

    @Test
    void genericProcessUsesVersionSevenAndNeverGuessesMissingRuntimeFields() throws Exception {
        var workload = new gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentWorkload(
            gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentWorkload.ExecutionMode.DAEMON,
            true,
            new gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentCommand("bin/zig-app",
                List.of("--listen", "18080")),
            "", List.of(), java.util.Optional.empty(), "", java.util.Optional.empty(), List.of(), List.of(), "",
            List.of());
        var runtime = new DeploymentRuntimeDefinition.ManagedProcess(TCP,
            gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentIdentityMode.SYSTEMD_STATIC,
            workload);
        var encoded = DeploymentCodecCompatibility.write(runtime);
        assertEquals(7, encoded[4]);
        assertEquals(runtime, codec.read(encoded, TCP));
        encoded[4] = 5;
        assertThrows(java.io.IOException.class, () -> codec.read(encoded, TCP));
        var historical = DeploymentCodecCompatibility.write(new DeploymentRuntimeDefinition.SpringBoot(TCP));
        assertEquals(5, historical[4]);
        assertEquals(new DeploymentRuntimeDefinition.SpringBoot(TCP), codec.read(historical, TCP));
    }

    @Test
    void preservesNewTargetsAndRejectsLegacyLayout() throws Exception {
        var java8 = new DeploymentRuntimeDefinition.SpringBoot("8", TCP);
        var kotlin8 = new DeploymentRuntimeDefinition.KotlinService("1.8.22", "demo", "demo.MainKt", "8", TCP);
        assertEquals(java8, codec.read(DeploymentCodecCompatibility.write(java8), TCP));
        assertEquals(kotlin8, codec.read(DeploymentCodecCompatibility.write(kotlin8), TCP));
        // A version-one Spring Boot payload contains only magic, format and runtime tag. / 第一版 Spring Boot 载荷仅包含魔数、格式和运行时标签。
        byte[] legacy = Arrays.copyOf(DeploymentCodecCompatibility.write(java8), 6);
        legacy[4] = 1;
        assertThrows(java.io.IOException.class, () -> codec.read(legacy, TCP));
    }

    @Test
    void roundTripsEveryReviewedRuntimeWithoutASecondHealthCopy() throws Exception {
        Map<Integer, Integer> ports = new LinkedHashMap<>();
        ports.put(18080, 8080);
        ports.put(18443, 8443);
        List<DeploymentRuntimeDefinition> runtimes = List.of(new DeploymentRuntimeDefinition.SpringBoot(TCP),
            new DeploymentRuntimeDefinition.JavaJar("app.jar", "demo.Main", "21", List.of("-Xmx256m"),
                List.of("--mode=test"), TCP),
            new DeploymentRuntimeDefinition.JavaSource("src", "demo.Main", "21", List.of("-Xmx256m"),
                List.of("--mode=test"), TCP),
            new DeploymentRuntimeDefinition.NodeService(22, TCP),
            new DeploymentRuntimeDefinition.PythonService("3.12", "demo.main", TCP),
            new DeploymentRuntimeDefinition.StaticSite("dist", OptionalInt.of(22), HTTP),
            new DeploymentRuntimeDefinition.Container(DeploymentRuntimeDefinition.ContainerEngineType.PODMAN, ports,
                List.of(new DeploymentRuntimeDefinition.ManagedVolume("windowstolinux-demo", "/var/lib/demo",
                    true)),
                TCP),
            new DeploymentRuntimeDefinition.GoService("1.24", "demo", "main.go", TCP),
            new DeploymentRuntimeDefinition.RustService("1.90.0", "demo", "src/main.rs", TCP),
            new DeploymentRuntimeDefinition.DotNetService("8.0", "demo", "demo.dll", TCP),
            new DeploymentRuntimeDefinition.KotlinService("2.1.0", "demo", "demo.MainKt", TCP),
            new DeploymentRuntimeDefinition.PhpService("8.3", "public", "public/index.php", 18080, TCP),
            new DeploymentRuntimeDefinition.RubyService("3.3.5", "source", "server.rb", 18080, TCP),
            new DeploymentRuntimeDefinition.CmakeService("release", "demo", "demo", TCP));

        for (DeploymentRuntimeDefinition runtime : runtimes) {
            assertEquals(runtime, codec.read(DeploymentCodecCompatibility.write(runtime), runtime.healthCheck()));
        }
    }

    @Test
    void rejectsUnsupportedTruncatedAndTrailingDocuments() throws Exception {
        byte[] valid = DeploymentCodecCompatibility.write(new DeploymentRuntimeDefinition.NodeService(22, TCP));
        byte[] unsupported = valid.clone();
        unsupported[5] = 99;
        assertThrows(java.io.IOException.class, () -> codec.read(unsupported, TCP));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, 5), TCP));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, valid.length + 1), TCP));
        assertThrows(java.io.IOException.class, () -> codec.read(new byte[1_048_577], TCP));
    }

    @Test
    void refusesToRebindStaticSiteToATcpHealthContract() throws Exception {
        byte[] runtime = DeploymentCodecCompatibility.write(new DeploymentRuntimeDefinition.StaticSite("dist", HTTP));
        assertThrows(java.io.IOException.class, () -> codec.read(runtime, TCP));
    }
}
