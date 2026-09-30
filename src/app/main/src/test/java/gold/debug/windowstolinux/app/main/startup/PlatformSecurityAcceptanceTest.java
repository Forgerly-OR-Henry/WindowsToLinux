package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.shell.PlatformFrame;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformSecurityAcceptanceTest {
    @TempDir
    Path root;

    private PlatformFrame open(PlatformRuntime runtime) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        return onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var frame = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                    ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                    FailureReportStore.disabled());
            frame.onExit(runtime);
            frame.setVisible(true);
            return frame;
        });
    }

    private void setup(PlatformFrame frame, PlatformRuntime runtime) throws Exception {
        onEdt(() -> {
            assertTrue(named(frame, "security.settings.password").isShowing());
            assertFalse(named(frame, "security.settings.current").isShowing());
            assertFalse(named(frame, "security.settings.encryption").isShowing());
            ((JPasswordField) named(frame, "security.settings.password")).setText(PASSWORD);
            ((JPasswordField) named(frame, "security.settings.confirmation")).setText(PASSWORD);
            ((JButton) named(frame, "security.settings.submit")).doClick(0);
            return null;
        });
        await(() -> runtime.security().securityState().unlocked() && !runtime.security().securityState().changing());
    }

    @Test
    void lockedShellRetainsAcceptedTaskRejectsNewTasksAndReopensLocked() throws Exception {
        var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
        var frame = open(runtime);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        JDialog owned = null;
        try {
            setup(frame, runtime);
            register(runtime.persistence().database(), null);
            var servers = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.empty());
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var task = binding.submit(request(), context -> {
                started.countDown();
                assertTrue(release.await(8, TimeUnit.SECONDS));
                assertFalse(runtime.security().securityState().unlocked());
                assertTrue(servers.list(Optional.empty(), 20, context).items().isEmpty());
                return success(context.id());
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            owned = onEdt(() -> {
                var dialog = new JDialog(frame, "private details", false);
                var secret = new JPasswordField("temporary-secret");
                secret.setName("fixture.secret");
                dialog.add(secret);
                dialog.setSize(320, 160);
                dialog.setVisible(true);
                ((JButton) named(frame, "security.lock")).doClick(0);
                return dialog;
            });
            var privateDialog = owned;
            await(() -> !frame.getContentPane().isVisible() && !privateDialog.getContentPane().isVisible());
            assertTrue(runtime.security().securityState().activeTasks() > 0);
            var callbacks = new AtomicInteger();
            var refused = assertThrows(ApiException.class, () -> binding.submit(request(), context -> {
                callbacks.incrementAndGet();
                return success(null);
            }));
            assertEquals(ProblemCode.ACCESS_DENIED, refused.problem().code());
            assertEquals(0, callbacks.get());
            onEdt(() -> {
                assertEquals(0, ((JPasswordField) named(privateDialog, "fixture.secret")).getPassword().length);
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                assertTrue(frame.isDisplayable());
                return null;
            });
            release.countDown();
            var result = task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            assertEquals(task.id(), result.value().orElseThrow());
            assertEquals(0, runtime.security().securityState().activeTasks());
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            assertFalse(runtime.security().securityState().unlocked());
            onEdt(() -> {
                privateDialog.dispose();
                ((JButton) named(frame, "security.enter")).doClick(0);
                ((JPasswordField) named(frame, "security.password")).setText(PASSWORD);
                ((JButton) named(frame, "security.submit")).doClick(0);
                return null;
            });
            await(() -> runtime.security().securityState().unlocked()
                    && !runtime.security().securityState().changing());
            onEdt(() -> {
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                return null;
            });
            await(() -> !frame.isDisplayable());
        } finally {
            release.countDown();
            var privateDialog = owned;
            onEdt(() -> {
                if (privateDialog != null)
                    privateDialog.dispose();
                frame.dispose();
                return null;
            });
            runtime.close();
        }
        try (var reopened = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            assertTrue(reopened.security().securityState().configured());
            assertFalse(reopened.security().securityState().unlocked());
        }
    }

    @Test
    void deferringInitialSettingsAllowsShellButGrantsNoTaskAuthority() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var frame = open(runtime);
            try {
                register(runtime.persistence().database(), null);
                onEdt(() -> {
                    ((JTabbedPane) named(frame, "platform.pages")).setSelectedIndex(0);
                    assertTrue(frame.getContentPane().isVisible());
                    assertFalse(named(frame, "security.settings.password").isShowing());
                    assertFalse(named(frame, "security.lock").isEnabled());
                    return null;
                });
                var callbacks = new AtomicInteger();
                var refused = assertThrows(ApiException.class,
                        () -> runtime.tasks().bind(OWNER, VERSION, false).submit(request(), context -> {
                            callbacks.incrementAndGet();
                            return success(null);
                        }));
                assertEquals(ProblemCode.ACCESS_DENIED, refused.problem().code());
                assertEquals(0, callbacks.get());
                assertFalse(runtime.security().securityState().configured());
                assertEquals(0, runtime.security().securityState().activeTasks());
                assertEquals(0, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                onEdt(() -> {
                    frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                    return null;
                });
                await(() -> !frame.isDisplayable());
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void invalidInitialPasswordShowsReadableFeedbackAndKeyboardRetrySucceeds() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var frame = open(runtime);
            try {
                onEdt(() -> {
                    ((JButton) named(frame, "security.settings.submit")).doClick(0);
                    return null;
                });
                await(() -> java.util.Arrays.stream(frame.getOwnedWindows()).anyMatch(Window::isShowing));
                onEdt(() -> {
                    var error = java.util.Arrays.stream(frame.getOwnedWindows()).filter(Window::isShowing).findFirst()
                            .orElseThrow();
                    var details = descendants(error).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                            .findFirst().orElseThrow();
                    assertTrue(details.getText()
                            .startsWith(MessageCatalog.forLanguageTag("zh-CN").text("security.error.passwordInvalid")));
                    assertTrue(details.isShowing());
                    assertEquals(0, details.getCaretPosition());
                    assertEquals(0, details.getVisibleRect().y);
                    assertTrue(
                            details.getVisibleRect().height >= details.getFontMetrics(details.getFont()).getHeight());
                    error.dispose();
                    return null;
                });
                assertFalse(runtime.security().securityState().configured());
                onEdt(() -> {
                    assertTrue(named(frame, "security.settings.submit").isEnabled());
                    var status = (JLabel) named(frame, "security.settings.status");
                    assertEquals(MessageCatalog.forLanguageTag("zh-CN").text("security.saveFailed"), status.getText());
                    assertEquals(0, status.getVisibleRect().y);
                    assertTrue(status.getVisibleRect().height >= status.getFontMetrics(status.getFont()).getHeight());
                    var password = (JPasswordField) named(frame, "security.settings.password");
                    password.requestFocusInWindow();
                    return null;
                });
                await(() -> named(frame, "security.settings.password").isFocusOwner());
                onEdt(() -> {
                    var password = (JPasswordField) named(frame, "security.settings.password");
                    for (char letter : PASSWORD.toCharArray())
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(password,
                                KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, letter));
                    assertEquals(PASSWORD.length(), password.getPassword().length);
                    named(frame, "security.settings.confirmation").requestFocusInWindow();
                    return null;
                });
                await(() -> named(frame, "security.settings.confirmation").isFocusOwner());
                onEdt(() -> {
                    var confirmation = (JPasswordField) named(frame, "security.settings.confirmation");
                    for (char letter : PASSWORD.toCharArray())
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(confirmation,
                                KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, letter));
                    assertEquals(PASSWORD.length(), confirmation.getPassword().length);
                    for (int id : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED})
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(
                                new KeyEvent(confirmation, id, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n'));
                    return null;
                });
                await(() -> runtime.security().securityState().unlocked()
                        && !runtime.security().securityState().changing());
                onEdt(() -> {
                    assertEquals(0, ((JPasswordField) named(frame, "security.settings.password")).getPassword().length);
                    assertEquals(0,
                            ((JPasswordField) named(frame, "security.settings.confirmation")).getPassword().length);
                    return null;
                });
                assertEquals(0, runtime.security().securityState().activeTasks());
                assertEquals(0, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }
}
