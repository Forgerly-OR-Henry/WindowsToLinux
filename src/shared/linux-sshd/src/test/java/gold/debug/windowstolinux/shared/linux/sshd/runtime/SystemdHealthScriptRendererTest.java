package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemdHealthScriptRendererTest {
    @Test
    void bindsHttpListenerToTheWholeManagedCgroup() {
        String script = SystemdHealthScriptRenderer.render("windowstolinux-node.service",
                new HealthCheck.Http(URI.create("http://127.0.0.1:31234/health"), 200, 30));

        assertTrue(script.contains("--property ControlGroup"));
        assertTrue(script.contains("/proc/$listener_pid/cgroup"));
        assertTrue(script.contains("unit_owns_port 'windowstolinux-node.service' 31234"));
        assertFalse(script.contains("grep -F \"pid=$pid\""));
    }

    @Test
    void bindsTcpProbeToItsExactPort() {
        String script = SystemdHealthScriptRenderer.render("windowstolinux-python.service",
                new HealthCheck.Tcp(32123, 2, 30));

        assertTrue(script.contains("</dev/tcp/127.0.0.1/32123"));
        assertTrue(script.contains("unit_owns_port 'windowstolinux-python.service' 32123"));
    }
}
