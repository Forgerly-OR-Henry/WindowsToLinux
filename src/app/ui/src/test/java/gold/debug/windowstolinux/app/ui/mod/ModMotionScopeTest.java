package gold.debug.windowstolinux.app.ui.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.app.ui.display.DesktopMotion;
import gold.debug.windowstolinux.appapi.ui.page.*;

class ModMotionScopeTest {
    @Test
    void framesBelongToExactCommittedActivationAndStopOnLockAndSeal() throws Exception {
        var registry = new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment next) {
            }
        }, new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
        var scope = registry.begin("motion.fixture", "1.0.0", false, UiExtensionValues.PLATFORM);
        var samples = new ArrayList<Double>();
        var owner = new JPanel() {
            @Override
            public boolean isShowing() {
                return true;
            }
        };
        var handle = new AtomicReference<UiMotionHandle>();
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        try {
            SwingUtilities.invokeAndWait(() -> {
                System.setProperty("flatlaf.animation", "true");
                DesktopMotion.preference(true);
                scope.motion().orElseThrow().animate(owner, UiMotionKind.DETAIL, samples::add);
                assertTrue(samples.isEmpty(), "Uncommitted activation must never receive frames");
            });
            scope.commit();
            SwingUtilities.invokeAndWait(() -> {
                handle.set(scope.motion().orElseThrow().animate(owner, UiMotionKind.DETAIL, samples::add));
                assertFalse(samples.isEmpty());
            });
            registry.environment(new UiEnvironment(Locale.ENGLISH, "LIGHT", true));
            int lockedCount = samples.size();
            SwingUtilities.invokeAndWait(() -> handle.get().finish());
            Thread.sleep(60);
            assertEquals(lockedCount, samples.size());
            registry.environment(new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
            SwingUtilities.invokeAndWait(() -> handle.set(scope.motion().orElseThrow().animate(owner, UiMotionKind.DETAIL, samples::add)));
            scope.seal();
            int sealedCount = samples.size();
            SwingUtilities.invokeAndWait(() -> {
                handle.get().finish();
                scope.motion().orElseThrow().animate(owner, UiMotionKind.DETAIL, samples::add);
            });
            Thread.sleep(60);
            assertEquals(sealedCount, samples.size());
        } finally {
            scope.close();
            SwingUtilities.invokeAndWait(() -> {
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
            });
        }
    }
}
