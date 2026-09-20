package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

/** The transaction tests accept only the exact maintenance completion capability. */
final class ApplicationMaintenanceFixture {
    static RemoteBackupArtifactPort port() {
        return (RemoteBackupArtifactPort) Proxy.newProxyInstance(RemoteBackupArtifactPort.class.getClassLoader(),
                new Class<?>[]{RemoteBackupArtifactPort.class}, (proxy, method, arguments) -> {
                    assertEquals("endMaintenance",method.getName());
                    assertTrue(((String)arguments[1]).matches("deployment-[0-9a-f]{64}"));
                    return null;
                });
    }
}
