package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitRuntimeConfiguration;
import org.junit.jupiter.api.Test;

class DeploymentUnitRuntimeCodecTest {
    @Test
    void workerCommandsRoundTripWithoutChangingSingleProcessFormats() throws Exception {
        var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true,
                DeploymentCommand.primary(), "", List.of(), Optional.empty(), "", Optional.empty(), List.of(),
                List.of(), "", List.of(new DeploymentWorker("queue",
                        new DeploymentCommand("worker.php", List.of("中文 参数", "$HOME %i")))));
        var health = new DeploymentHealthCheck.Process(10, 2);
        var runtime = new DeploymentRuntimeDefinition.PhpService("8.3", "public", "router.php", 8080, health)
                .withWorkload(workload);
        var codec = new DeploymentRuntimeCodec();
        assertEquals(6, DeploymentCodecCompatibility.write(runtime)[4]);
        assertEquals(runtime, codec.read(DeploymentCodecCompatibility.write(runtime), health));
        var activation = new DeploymentUnitRuntimeConfiguration(health, Optional.empty(), runtime.identityPolicy(),
                workload);
        var activationCodec = new DeploymentUnitRuntimeCodec();
        assertEquals(activation, activationCodec.read(DeploymentCodecCompatibility.write(activation)));
        assertThrows(java.io.IOException.class,
                () -> codec.read(Arrays.copyOf(DeploymentCodecCompatibility.write(runtime),
                        DeploymentCodecCompatibility.write(runtime).length - 1), health));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentRuntimeDefinition.Container(DeploymentRuntimeDefinition.ContainerEngineType.DOCKER,
                        Map.of(), List.of(), health).withWorkload(workload));
    }

    @Test
    void preservesOnDemandArgumentsAndReadOnlyInputsAcrossBothRuntimeFormats() throws Exception {
        var verify = new DeploymentCommand("", List.of("--self-test", "中文 空格"));
        var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.ON_DEMAND, true,
                new DeploymentCommand("cli/main.py", List.of("--format", "json")), "", List.of(), Optional.of(verify),
                "protocolVersion", Optional.empty(),
                List.of(new DeploymentInputField("logs", "/srv/中文 输入", "/inputs/中文")), List.of());
        var health = new DeploymentHealthCheck.Command(verify, "protocolVersion", 10);
        var runtime = new DeploymentRuntimeDefinition.PythonService("3.12", "main", health).withWorkload(workload);
        var codec = new DeploymentRuntimeCodec();
        assertEquals(runtime, codec.read(DeploymentCodecCompatibility.write(runtime), health));
        var activation = new DeploymentUnitRuntimeConfiguration(health, Optional.empty(), runtime.identityPolicy(),
                workload);
        var activationCodec = new DeploymentUnitRuntimeCodec();
        assertEquals(activation, activationCodec.read(DeploymentCodecCompatibility.write(activation)));
        byte[] previousVersion = DeploymentCodecCompatibility.write(runtime);
        previousVersion[4] = 4;
        assertThrows(java.io.IOException.class, () -> codec.read(previousVersion, health));
        assertEquals(DeploymentWorkload.CategoryType.APP, workload.category());
        assertFalse(workload.supportsLifecycle());
    }

    @Test
    void allHealthVariantsRoundTripAndRejectCorruptOrTrailingData() throws Exception {
        var codec = new DeploymentHealthCodec();
        for (var health : List.of(new DeploymentHealthCheck.Process(10, 5),
                new DeploymentHealthCheck.Command(DeploymentCommand.primary(), "ok", 10),
                new DeploymentHealthCheck.Udp(9000, "70696e67", "706f6e67", Optional.empty(), 10),
                new DeploymentHealthCheck.Udp(9000, "", "", Optional.of(new DeploymentCommand("probe.py", List.of())),
                        10))) {
            byte[] bytes = DeploymentCodecCompatibility.write(health);
            assertEquals(health, codec.read(bytes));
            assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(bytes, bytes.length + 1)));
            assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(bytes, bytes.length - 1)));
        }
    }

    @Test
    void exposureAndTransportAreIndependentOfHealthAndPortNumber() {
        var http = new DeploymentEndpoint("health", DeploymentEndpoint.ProtocolType.HTTP, "127.0.0.1", 9000, 9000,
                DeploymentEndpoint.ExposureType.INTERNAL, "");
        var udp = new DeploymentEndpoint("events", DeploymentEndpoint.ProtocolType.UDP, "0.0.0.0", 9000, 9000,
                DeploymentEndpoint.ExposureType.EXTERNAL, "");
        var internal = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true,
                DeploymentCommand.primary(), "", List.of(http), Optional.empty(), "", Optional.empty(), List.of(),
                List.of());
        assertEquals(DeploymentWorkload.CategoryType.APP, internal.category());
        assertEquals(DeploymentWorkload.CategoryType.WEBSITE,
                new DeploymentWorkload(internal.mode(), true, internal.command(), "", List.of(http, udp),
                        Optional.empty(), "", Optional.empty(), List.of(), List.of()).category());
        assertThrows(IllegalArgumentException.class, () -> new DeploymentEndpoint("bad",
                DeploymentEndpoint.ProtocolType.TCP, "abc", 9, 9, DeploymentEndpoint.ExposureType.EXTERNAL, ""));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentCommand("../escape", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentWorkload(DeploymentWorkload.ExecutionMode.ON_DEMAND, true,
                        DeploymentCommand.primary(), "", List.of(), Optional.empty(), "", Optional.empty(), List.of(),
                        List.of()));
    }
}
