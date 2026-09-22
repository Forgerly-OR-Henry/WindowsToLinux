package gold.debug.windowstolinux.shared.config.persistence.serialization;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.model.project.application.*;
import org.junit.jupiter.api.Test;

class ApplicationRuntimeCodecTest {
    @Test
    void workerCommandsRoundTripWithoutChangingSingleProcessFormats() throws Exception {
        var workload = new ApplicationWorkload(ApplicationWorkload.ExecutionMode.DAEMON, true,
                ApplicationCommand.primary(), "", List.of(), Optional.empty(), "", Optional.empty(), List.of(),
                List.of(), "", List.of(new ApplicationWorker("queue",
                        new ApplicationCommand("worker.php", List.of("中文 参数", "$HOME %i")))));
        var health = new HealthCheck.Process(10, 2);
        var runtime = new DeploymentRuntimeSpecification.PhpService("8.3", "public", "router.php", 8080, health)
                .withWorkload(workload);
        var codec = new DeploymentRuntimePersistenceCodec();
        assertEquals(6, codec.write(runtime)[4]);
        assertEquals(runtime, codec.read(codec.write(runtime), health));
        var activation = new ManagedApplicationRuntimeConfiguration(health, Optional.empty(), runtime.identityPolicy(),
                workload);
        var activationCodec = new ApplicationRuntimeConfigurationCodec();
        assertEquals(activation, activationCodec.read(activationCodec.write(activation)));
        assertThrows(java.io.IOException.class,
                () -> codec.read(Arrays.copyOf(codec.write(runtime), codec.write(runtime).length - 1), health));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentRuntimeSpecification.Container(
                        DeploymentRuntimeSpecification.ContainerEngineType.DOCKER, Map.of(), List.of(), health)
                        .withWorkload(workload));
    }

    @Test
    void preservesOnDemandArgumentsAndReadOnlyInputsAcrossBothRuntimeFormats() throws Exception {
        var verify = new ApplicationCommand("", List.of("--self-test", "中文 空格"));
        var workload = new ApplicationWorkload(ApplicationWorkload.ExecutionMode.ON_DEMAND, true,
                new ApplicationCommand("cli/main.py", List.of("--format", "json")), "", List.of(), Optional.of(verify),
                "protocolVersion", Optional.empty(), List.of(new ApplicationInput("logs", "/srv/中文 输入", "/inputs/中文")),
                List.of());
        var health = new HealthCheck.Command(verify, "protocolVersion", 10);
        var runtime = new DeploymentRuntimeSpecification.PythonService("3.12", "main", health).withWorkload(workload);
        var codec = new DeploymentRuntimePersistenceCodec();
        assertEquals(runtime, codec.read(codec.write(runtime), health));
        var activation = new ManagedApplicationRuntimeConfiguration(health, Optional.empty(), runtime.identityPolicy(),
                workload);
        var activationCodec = new ApplicationRuntimeConfigurationCodec();
        assertEquals(activation, activationCodec.read(activationCodec.write(activation)));
        byte[] previousVersion = codec.write(runtime);
        previousVersion[4] = 4;
        assertThrows(java.io.IOException.class, () -> codec.read(previousVersion, health));
        assertEquals(ApplicationWorkload.CategoryType.APP, workload.category());
        assertFalse(workload.supportsLifecycle());
    }

    @Test
    void allHealthVariantsRoundTripAndRejectCorruptOrTrailingData() throws Exception {
        var codec = new HealthCheckCodec();
        for (var health : List.of(new HealthCheck.Process(10, 5),
                new HealthCheck.Command(ApplicationCommand.primary(), "ok", 10),
                new HealthCheck.Udp(9000, "70696e67", "706f6e67", Optional.empty(), 10),
                new HealthCheck.Udp(9000, "", "", Optional.of(new ApplicationCommand("probe.py", List.of())), 10))) {
            byte[] bytes = codec.write(health);
            assertEquals(health, codec.read(bytes));
            assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(bytes, bytes.length + 1)));
            assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(bytes, bytes.length - 1)));
        }
    }

    @Test
    void exposureAndTransportAreIndependentOfHealthAndPortNumber() {
        var http = new ApplicationEndpoint("health", ApplicationEndpoint.ProtocolType.HTTP, "127.0.0.1", 9000, 9000,
                ApplicationEndpoint.ExposureType.INTERNAL, "");
        var udp = new ApplicationEndpoint("events", ApplicationEndpoint.ProtocolType.UDP, "0.0.0.0", 9000, 9000,
                ApplicationEndpoint.ExposureType.EXTERNAL, "");
        var internal = new ApplicationWorkload(ApplicationWorkload.ExecutionMode.DAEMON, true,
                ApplicationCommand.primary(), "", List.of(http), Optional.empty(), "", Optional.empty(), List.of(),
                List.of());
        assertEquals(ApplicationWorkload.CategoryType.APP, internal.category());
        assertEquals(ApplicationWorkload.CategoryType.WEBSITE,
                new ApplicationWorkload(internal.mode(), true, internal.command(), "", List.of(http, udp),
                        Optional.empty(), "", Optional.empty(), List.of(), List.of()).category());
        assertThrows(IllegalArgumentException.class, () -> new ApplicationEndpoint("bad",
                ApplicationEndpoint.ProtocolType.TCP, "abc", 9, 9, ApplicationEndpoint.ExposureType.EXTERNAL, ""));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationCommand("../escape", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ApplicationWorkload(ApplicationWorkload.ExecutionMode.ON_DEMAND, true,
                        ApplicationCommand.primary(), "", List.of(), Optional.empty(), "", Optional.empty(), List.of(),
                        List.of()));
    }
}
