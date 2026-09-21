package gold.debug.windowstolinux.app.db.persistence.serialization;

import gold.debug.windowstolinux.shared.config.persistence.serialization.HealthCheckCodec;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopHealthPersistenceTest {
    private final HealthCheckCodec codec = new HealthCheckCodec();

    @Test
    void roundTripsBothClosedHealthStrategies() throws Exception {
        for (HealthCheck health : java.util.List.of(
                new HealthCheck.Http(URI.create("http://127.0.0.1:8080/health"), 204, 15),
                new HealthCheck.Tcp(8080, 20, 3))) {
            assertEquals(health, codec.read(codec.write(health)));
        }
    }

    @Test
    void rejectsTruncatedExtendedAndUnknownDocuments() throws Exception {
        byte[] valid = codec.write(new HealthCheck.Tcp(8080, 20, 3));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, valid.length + 1)));
        byte[] unknown = valid.clone(); unknown[5] = 99;
        assertThrows(java.io.IOException.class, () -> codec.read(unknown));
    }
}
