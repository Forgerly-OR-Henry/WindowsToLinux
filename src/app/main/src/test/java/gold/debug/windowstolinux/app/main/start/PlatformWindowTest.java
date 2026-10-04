package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformWindowTest {
    @TempDir
    Path root;

    @Test
    void realPlatformWindowSetsUpH2LocksOwnedViewsAndClosesOffEdt() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
        var window = new AtomicReference<PlatformFrame>();
        var dialog = new AtomicReference<JDialog>();
        var pageLocked = new AtomicBoolean();
        var pageClosed = new AtomicInteger();
        ModPageRegistry.PageScope scope = null;
        try {
            onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var frame = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                    ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                    FailureReportStore.disabled());
                window.set(frame);
                frame.onExit(runtime);
                frame.setVisible(true);
                assertEquals("shell.home", frame.currentPage().platformId());
                ((JButton) named(frame, "navigation.settings")).doClick(0);
                ((JButton) named(frame, "navigation.security.settings")).doClick(0);
                assertTrue(named(frame, "security.settings.password").isShowing());
                assertFalse(((JButton) named(frame, "security.lock")).isEnabled());
                capture(frame, "platform-first-use.png");
                ((JPasswordField) named(frame, "security.settings.password")).setText("isolated-window-password");
                ((JPasswordField) named(frame, "security.settings.confirmation")).setText("isolated-window-password");
                ((JButton) named(frame, "security.settings.submit")).doClick(0);
                return null;
            });
            await(() -> runtime.security().securityState().unlocked()
                && !runtime.security().securityState().changing());
            await(() -> named(window.get(), "security.lock").isEnabled());
            runtime.started();
            scope = window.get().pages().begin("example.mod", false);
            scope.register(new PageSpec("sample", "sample", "示例 Mod 页面", false), () -> new ModPage() {
                public JComponent create(UiEnvironment environment) {
                    return new JLabel("Mod-owned view");
                }

                public void environmentChanged(UiEnvironment environment) {
                    pageLocked.set(environment.locked());
                }

                public void close() {
                    pageClosed.incrementAndGet();
                }
            });
            scope.commit();
            scope.open("sample");
            onEdt(() -> {
                var owned = new JDialog(window.get(), "private details", false);
                dialog.set(owned);
                var password = new JPasswordField("temporary-owned-secret");
                password.setName("fixture.password");
                owned.add(password);
                owned.setSize(320, 160);
                owned.setVisible(true);
                ((JButton) named(window.get(), "security.lock")).doClick(0);
                assertTrue(window.get().getContentPane().isVisible());
                assertTrue(window.get().getGlassPane().isVisible());
                assertFalse(owned.getContentPane().isVisible());
                assertEquals(0, ((JPasswordField) named(owned, "fixture.password")).getPassword().length);
                assertTrue(pageLocked.get());
                capture(window.get(), "platform-locked.png");
                owned.dispose();
                return null;
            });
            unlockPassword(window.get(), "isolated-window-password");
            await(() -> runtime.security().securityState().unlocked()
                && !runtime.security().securityState().changing());
            await(() -> !pageLocked.get());
            scope.close();
            scope = null;
            assertEquals(1, pageClosed.get());
            onEdt(() -> {
                window.get().dispatchEvent(new WindowEvent(window.get(), WindowEvent.WINDOW_CLOSING));
                return null;
            });
            await(() -> !window.get().isDisplayable());
        } finally {
            if (scope != null)
                scope.close();
            onEdt(() -> {
                if (dialog.get() != null)
                    dialog.get().dispose();
                if (window.get() != null)
                    window.get().dispose();
                return null;
            });
            runtime.close();
        }
        try (var reopened = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            assertTrue(reopened.security().securityState().configured());
            assertFalse(reopened.security().securityState().unlocked());
            onEdt(() -> {
                var frame = new PlatformFrame(reopened.security(), MessageCatalog.forLanguageTag("zh-CN"),
                    ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true),
                    FailureReportStore.disabled());
                try {
                    frame.setVisible(true);
                    assertTrue(frame.getContentPane().isVisible());
                    assertTrue(frame.getGlassPane().isVisible());
                    assertEquals("解锁", ((JButton) named(frame, "security.lock")).getText());
                } finally {
                    frame.dispose();
                }
                return null;
            });
        }
    }

    static Component named(Component root, String name) {
        return descendants(root).filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root),
            root instanceof Container container
                ? Stream.of(container.getComponents()).flatMap(PlatformWindowTest::descendants)
                : Stream.empty());
    }

    static <T> T onEdt(Callable<T> action) throws Exception {
        var future = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }

    static void unlockPassword(PlatformFrame frame, String value) throws Exception {
        SwingUtilities.invokeLater(() -> ((JButton) named(frame, "security.lock")).doClick(0));
        await(() -> java.util.Arrays.stream(frame.getOwnedWindows())
            .anyMatch(window -> window.isShowing() && "security.unlockDialog".equals(window.getName())));
        onEdt(() -> {
            Window prompt = java.util.Arrays.stream(frame.getOwnedWindows())
                .filter(window -> window.isShowing() && "security.unlockDialog".equals(window.getName())).findFirst().orElseThrow();
            ((JPasswordField) named(prompt, "security.password")).setText(value);
            ((JButton) named(prompt, "security.submit")).doClick(0);
            return null;
        });
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (onEdt(condition::getAsBoolean))
                return;
            Thread.sleep(25);
        }
        assertTrue(onEdt(condition::getAsBoolean));
    }

    static void capture(Window frame, String name) throws Exception {
        for (int pass = 0; pass < 3; pass++) {
            descendants(frame).filter(Container.class::isInstance).map(Container.class::cast)
                .forEach(Container::invalidate);
            frame.validate();
        }
        var bitmap = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = bitmap.createGraphics();
        try {
            frame.paint(graphics);
        } finally {
            graphics.dispose();
        }
        var output = Path.of("target/platform-startup", name);
        Files.createDirectories(output.getParent());
        ImageIO.write(bitmap, "png", output.toFile());
    }
}
