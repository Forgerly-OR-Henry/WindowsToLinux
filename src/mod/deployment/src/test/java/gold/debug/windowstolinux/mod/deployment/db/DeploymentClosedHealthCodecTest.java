package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Arrays;

import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentHealthCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import org.junit.jupiter.api.Test;

/** Preserves the former APP health payload's HTTP/TCP and corrupt-document acceptance. */
class DeploymentClosedHealthCodecTest {
    private final DeploymentHealthCodec codec = new DeploymentHealthCodec();

    @Test
    void roundTripsBothClosedHealthStrategies() throws Exception {
        for (DeploymentHealthCheck health : java.util.List.of(
            new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:8080/health"), 204, 15),
            new DeploymentHealthCheck.Tcp(8080, 20, 3)))
            assertEquals(health, codec.read(codec.write(health)));
    }

    @Test
    void rejectsTruncatedExtendedAndUnknownDocuments() throws Exception {
        byte[] valid = codec.write(new DeploymentHealthCheck.Tcp(8080, 20, 3));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, valid.length + 1)));
        byte[] unknown = valid.clone();
        unknown[5] = 99;
        assertThrows(java.io.IOException.class, () -> codec.read(unknown));
    }
}
