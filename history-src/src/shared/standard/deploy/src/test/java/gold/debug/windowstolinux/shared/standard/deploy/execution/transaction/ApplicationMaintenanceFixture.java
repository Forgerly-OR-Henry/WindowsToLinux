package gold.debug.windowstolinux.shared.standard.deploy.execution.transaction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;

import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort;

/** The transaction tests accept only the exact maintenance completion capability. */
final class ApplicationMaintenanceFixture {
    static RemoteBackupArtifactPort port() {
        return (RemoteBackupArtifactPort) Proxy.newProxyInstance(RemoteBackupArtifactPort.class.getClassLoader(),
                new Class<?>[]{RemoteBackupArtifactPort.class}, (proxy, method, arguments) -> {
                    assertEquals("endMaintenance", method.getName());
                    assertTrue(((String) arguments[1]).matches("deployment-[0-9a-f]{64}"));
                    return null;
                });
    }
}
