package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

final class DeploymentCodecCompatibility {
    static byte[] write(DeploymentRuntimeDefinition runtime) throws IOException {
        var encoded = new DeploymentRuntimeCodec().write(runtime);
        var previous = new gold.debug.windowstolinux.shared.config.persistence.serialization.DeploymentRuntimePersistenceCodec();
        var health = new gold.debug.windowstolinux.shared.config.persistence.serialization.HealthCheckCodec()
                .read(write(runtime.healthCheck()));
        assertArrayEquals(encoded, previous.write(previous.read(encoded, health)));
        return encoded;
    }

    static byte[] write(DeploymentHealthCheck health) throws IOException {
        var encoded = new DeploymentHealthCodec().write(health);
        var previous = new gold.debug.windowstolinux.shared.config.persistence.serialization.HealthCheckCodec();
        assertArrayEquals(encoded, previous.write(previous.read(encoded)));
        return encoded;
    }

    static byte[] write(DeploymentUnitRuntimeConfiguration runtime) throws IOException {
        var encoded = new DeploymentUnitRuntimeCodec().write(runtime);
        var previous = new gold.debug.windowstolinux.shared.config.persistence.serialization.ApplicationRuntimeConfigurationCodec();
        assertArrayEquals(encoded, previous.write(previous.read(encoded)));
        return encoded;
    }

    static byte[] write(DeploymentResourceBindings bindings) throws IOException {
        var encoded = new DeploymentResourceCodec().write(bindings);
        var previous = new gold.debug.windowstolinux.app.db.persistence.serialization.ManagedResourcePersistenceCodec();
        assertArrayEquals(encoded, previous.write(previous.read(encoded)));
        return encoded;
    }

    static byte[] write(List<DeploymentDataPath> paths) throws IOException {
        var encoded = new DeploymentDataPathCodec().write(paths);
        var previous = new gold.debug.windowstolinux.app.db.persistence.serialization.ComponentPathPersistenceCodec();
        assertArrayEquals(encoded, previous.write(previous.read(encoded)));
        return encoded;
    }
}
