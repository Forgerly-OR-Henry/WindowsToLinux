package gold.debug.windowstolinux.app.ui.deployment.single;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentInputDialogTest {
    @Test void inputFailuresRemainFailuresAndExplicitCancellationRemainsCancellation() throws Exception {
        var dispatch = DeploymentInputDialog.class.getDeclaredMethod("onEdt", Callable.class); dispatch.setAccessible(true);
        for (RuntimeException expected : new RuntimeException[]{new IllegalArgumentException("invalid field"), new CancellationException()}) {
            var failure = assertThrows(InvocationTargetException.class,
                    () -> dispatch.invoke(null, (Callable<Object>) () -> { throw expected; }));
            assertSame(expected, failure.getCause());
        }
    }
}
