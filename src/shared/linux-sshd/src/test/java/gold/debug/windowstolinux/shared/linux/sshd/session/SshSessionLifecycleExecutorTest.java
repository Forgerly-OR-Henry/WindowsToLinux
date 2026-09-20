package gold.debug.windowstolinux.shared.linux.sshd.session;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.future.CloseFuture;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SshSessionLifecycleExecutorTest {
    @Test void gracefulFailureStillForcesConnectionClose() {
        check("exception", List.of(false, true));
        check("timeout", List.of(false, true));
        check("completed", List.of(false));
    }

    private void check(String outcome, List<Boolean> expected) {
        List<Boolean> calls = new ArrayList<>();
        var session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("close")) throw new AssertionError(method);
                    boolean force = (boolean) arguments[0]; calls.add(force);
                    if (!force && outcome.equals("exception")) throw new IllegalStateException("graceful close failed");
                    return Proxy.newProxyInstance(CloseFuture.class.getClassLoader(), new Class<?>[]{CloseFuture.class},
                            (future, operation, values) -> {
                                if (operation.getName().equals("await")) return force || outcome.equals("completed");
                                throw new AssertionError(operation);
                            });
                });
        SshSessionLifecycleExecutor.closeQuietly(session);
        assertEquals(expected, calls);
    }
}
