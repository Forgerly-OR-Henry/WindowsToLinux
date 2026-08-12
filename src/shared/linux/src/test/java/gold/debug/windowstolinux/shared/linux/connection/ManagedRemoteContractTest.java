package gold.debug.windowstolinux.shared.linux.connection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedRemoteContractTest {
    @Test
    void exposesOnlyTheBoundedManagedOperationsAndNoRawCommandParameter() {
        Set<String> methods = java.util.Arrays.stream(LinuxRemoteSession.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "collectCapabilities", "prepareEnvironment", "uploadSource", "build", "cleanupCandidate", "snapshot", "publish", "checkHealth",
                "retainRecentSuccessfulReleases", "rollback", "observe", "executeLifecycle", "close"
        ), methods);
        for (Method method : LinuxRemoteSession.class.getMethods()) {
            assertFalse(java.util.Arrays.stream(method.getParameterTypes()).anyMatch(String.class::equals),
                    () -> method + " must not accept an arbitrary shell command, unit name or remote path");
        }
        assertTrue(java.util.Arrays.stream(LinuxGateway.class.getDeclaredMethods())
                .allMatch(method -> method.getName().equals("connect")),
                "gateway must not publish arbitrary SSH, SFTP or systemd entrypoints");
    }
}
