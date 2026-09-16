package gold.debug.windowstolinux.shared.linux.sshd.command;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CommandOutputCaptureTest {
    @Test void sharesBudgetAcrossBothChannelsAndCancelsOnce() throws Exception {
        var cancelled = new AtomicInteger();
        var capture = new CommandOutputCapture(4, true, cancelled::incrementAndGet);
        capture.stdout().write(new byte[]{'a', 'b', 'c'});
        capture.stderr().write(new byte[]{'d', 'e'});
        capture.stdout().write(new byte[1000]);
        assertTrue(capture.exceeded());
        assertEquals(1, cancelled.get());
        assertEquals("abc", capture.output());
        assertEquals("d", capture.error());
    }

    @Test void stillEnforcesLimitsWhenDiagnosticsAreDiscarded() throws Exception {
        var capture = new CommandOutputCapture(4, false, () -> { });
        capture.stdout().write(new byte[5]);
        assertTrue(capture.exceeded());
        assertEquals("", capture.output());
        assertEquals("", capture.error());
    }
}
