package gold.debug.windowstolinux.app.db.persistence.serialization;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.config.persistence.serialization.HealthCheckCodec;
import java.io.IOException;

/** Desktop facade for the shared health format. / 桌面持久化复用共享健康格式。 */
public final class HealthCheckPersistenceCodec {
    private final HealthCheckCodec codec = new HealthCheckCodec();
    public byte[] write(HealthCheck health) throws IOException { return codec.write(health); }
    public HealthCheck read(byte[] bytes) throws IOException { return codec.read(bytes); }
}
