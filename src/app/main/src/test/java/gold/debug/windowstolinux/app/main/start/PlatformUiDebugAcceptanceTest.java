package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.count;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.main.runtime.RunModeResolver.RunMode;
import gold.debug.windowstolinux.app.ui.debug.UiDebugDialog;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PlatformUiDebugAcceptanceTest {
    @TempDir
    Path root;

    private static final String PASSWORD = "isolated-debug-password";

    private PlatformFrame frame(PlatformRuntime runtime, boolean classMode, AtomicInteger appearanceSaves)
        throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        return onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var result = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                FailureReportStore.disabled());
            result.installAppearance(new DesktopDisplayConfig("zh-CN", ThemeMode.LIGHT), requested -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                appearanceSaves.incrementAndGet();
            });
            result.installUiDebug(classMode);
            result.setVisible(true);
            return result;
        });
    }

    private static Window owned(Window owner, String name) {
        return Arrays.stream(owner.getOwnedWindows()).filter(window -> name.equals(window.getName()))
            .filter(Window::isDisplayable).findFirst().orElseThrow();
    }

    private static void click(Component root, String name) throws Exception {
        onEdt(() -> {
            ((AbstractButton) named(root, name)).doClick(0);
            return null;
        });
    }

    private static void openModal(Window gallery, String button, String dialog) throws Exception {
        SwingUtilities.invokeLater(() -> ((JButton) named(gallery, button)).doClick(0));
        await(() -> Arrays.stream(gallery.getOwnedWindows())
            .anyMatch(window -> dialog.equals(window.getName()) && window.isShowing()));
    }

    private static Map<String, Long> effectCounts(PlatformRuntime runtime) throws Exception {
        var result = new LinkedHashMap<String, Long>();
        for (String table : java.util.List.of("server_profile", "credential", "ai_model", "platform_task",
            "platform_task_resource", "mod_operation", "mod_server_reference", "mod_credential_reference",
            "browser_recovery_task", "browser_recovery_event", "desktop_preference"))
            result.put(table, count(runtime.persistence().database(), "SELECT COUNT(*) FROM " + table));
        return result;
    }

    @ParameterizedTest
    @EnumSource(value = RunMode.class, names = {"RUN_JAR", "RUN_APP", "RUN_UNKNOWN"})
    void nonClassCompositionHasNoAdvancedEntryOrPreviewAuthority(RunMode mode) throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var saves = new AtomicInteger();
            var frame = frame(runtime, mode == RunMode.RUN_CLASS, saves);
            var created = new AtomicInteger();
            try (var owner = frame.pages().begin("example.debug", false)) {
                owner.registerPreview(new PageSpec("preview", "fixture.preview", "Preview", true), () -> {
                    created.incrementAndGet();
                    return new ModPage() {
                        public JComponent create(UiEnvironment environment) {
                            return new JPanel();
                        }

                        public void environmentChanged(UiEnvironment environment) {
                        }

                        public void close() {
                        }
                    };
                });
                owner.commit();
                onEdt(() -> {
                    assertTrue(
                        descendants(frame).noneMatch(component -> "settings.uiDebug".equals(component.getName())));
                    assertTrue(
                        descendants(frame).noneMatch(component -> "settings.advanced".equals(component.getName())));
                    return null;
                });
                assertTrue(frame.pages().previews().isEmpty());
                assertEquals("PREVIEW_CLASS_REQUIRED", assertThrows(ApiException.class,
                    () -> frame.pages().openPreview(new ModPageRegistry.PageKey("example.debug", "preview")))
                    .problem().eventCode());
                assertEquals(0, created.get());
                assertEquals(0, saves.get());
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void classGalleryUsesRealEditorsKeepsOneWindowAcrossAppearanceAndHasNoPreviewEffects() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var sentinel = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var connections = new AtomicInteger();
            var socketFailure = new AtomicReference<Throwable>();
            var listener = new Thread(() -> {
                try {
                    while (!sentinel.isClosed()) {
                        try (var connection = sentinel.accept()) {
                            connections.incrementAndGet();
                        }
                    }
                } catch (java.io.IOException failure) {
                    if (!sentinel.isClosed())
                        socketFailure.set(failure);
                }
            }, "debug-effects-sentinel");
            listener.setDaemon(true);
            listener.start();
            var appearanceSaves = new AtomicInteger();
            var frame = frame(runtime, true, appearanceSaves);
            var businessCreated = new AtomicInteger();
            var previewCreated = new AtomicInteger();
            var previewClosed = new AtomicInteger();
            var environments = new ArrayList<UiEnvironment>();
            var previewRoot = onEdt(JPanel::new);
            try (var owner = frame.pages().begin("example.debug", false)) {
                owner.register(new PageSpec("workspace", "fixture.workspace", "Fixture workspace", false), () -> {
                    businessCreated.incrementAndGet();
                    return new ModPage() {
                        public JComponent create(UiEnvironment environment) {
                            return new JLabel("actual author workspace");
                        }

                        public void environmentChanged(UiEnvironment environment) {
                        }

                        public void close() {
                        }
                    };
                });
                owner.registerPreview(new PageSpec("preview", "fixture.preview", "Fixture preview", true), () -> {
                    previewCreated.incrementAndGet();
                    return new ModPage() {
                        public JComponent create(UiEnvironment environment) {
                            return previewRoot;
                        }

                        public void environmentChanged(UiEnvironment environment) {
                            environments.add(environment);
                        }

                        public void close() {
                            previewClosed.incrementAndGet();
                        }
                    };
                });
                owner.commit();
                var before = effectCounts(runtime);
                onEdt(() -> {
                    ((JTabbedPane) named(frame, "platform.pages")).setSelectedIndex(1);
                    assertFalse(((JToggleButton) named(frame, "settings.advanced")).isSelected());
                    assertFalse(named(frame, "settings.uiDebug").isShowing());
                    ((JToggleButton) named(frame, "settings.advanced")).doClick(0);
                    assertTrue(named(frame, "settings.uiDebug").isShowing());
                    return null;
                });
                click(frame, "settings.uiDebug");
                var gallery = (UiDebugDialog) onEdt(() -> owned(frame, "settings.uiDebug.gallery"));
                click(frame, "settings.uiDebug");
                onEdt(() -> {
                    assertSame(gallery, owned(frame, "settings.uiDebug.gallery"));
                    assertEquals(1, Arrays.stream(frame.getOwnedWindows()).filter(UiDebugDialog.class::isInstance)
                        .filter(Window::isDisplayable).count());
                    assertEquals(0, businessCreated.get());
                    assertEquals(0, previewCreated.get());
                    return null;
                });
                click(gallery, "debug.page.example.debug.workspace");
                assertEquals(1, businessCreated.get());
                click(gallery, "debug.open");
                var preview = onEdt(() -> owned(gallery, "debug.preview.example.debug.preview"));
                assertEquals(1, previewCreated.get());
                openModal(gallery, "debug.server", "debug.preview.server");
                var server = onEdt(() -> owned(gallery, "debug.preview.server"));
                onEdt(() -> {
                    assertTrue(server instanceof gold.debug.windowstolinux.app.ui.server.ServerProfileDialog);
                    ((JTextField) named(server, "server.host")).setText("127.0.0.1");
                    ((JTextField) named(server, "server.port")).setText(Integer.toString(sentinel.getLocalPort()));
                    ((JPasswordField) named(server, "server.password")).setText("isolated-preview-secret");
                    assertFalse(named(server, "server.save").isEnabled());
                    ((JButton) named(server, "server.save")).doClick(0);
                    return null;
                });
                String originalServerTitle = onEdt(() -> ((JDialog) server).getTitle());
                onEdt(() -> {
                    server.dispose();
                    return null;
                });
                openModal(gallery, "debug.model", "debug.preview.model");
                var model = onEdt(() -> owned(gallery, "debug.preview.model"));
                onEdt(() -> {
                    assertEquals("gold.debug.windowstolinux.app.ui.ai.PlatformModelDialog", model.getClass().getName());
                    ((JTextField) named(model, "model.endpoint"))
                        .setText("http://127.0.0.1:" + sentinel.getLocalPort() + "/v1/chat/completions");
                    var save = (JButton) named(model, "model.save");
                    assertFalse(save.isEnabled());
                    save.doClick(0);
                    for (var action : save.getActionListeners())
                        action.actionPerformed(new ActionEvent(save, ActionEvent.ACTION_PERFORMED, "isolated-absence"));
                    return null;
                });
                var oldTitle = onEdt(gallery::getTitle);
                for (var requested : java.util.List.of(new DesktopDisplayConfig("en", ThemeMode.DARK),
                    new DesktopDisplayConfig("zh-CN", ThemeMode.LIGHT))) {
                    onEdt(() -> {
                        ((JComboBox<?>) named(frame, "appearance.language")).setSelectedItem(requested.localeTag());
                        ((JComboBox<?>) named(frame, "appearance.theme")).setSelectedItem(requested.themeMode());
                        ((JButton) named(frame, "appearance.save")).doClick(0);
                        return null;
                    });
                    await(() -> !DesktopTaskRunner.hasActiveTasks() && gallery.getTitle()
                        .equals(MessageCatalog.forLanguageTag(requested.localeTag()).text("settings.debug.open")));
                    onEdt(() -> {
                        assertSame(gallery, owned(frame, "settings.uiDebug.gallery"));
                        assertSame(model, owned(gallery, "debug.preview.model"));
                        assertSame(preview, owned(gallery, "debug.preview.example.debug.preview"));
                        assertEquals(ThemeMode.DARK == requested.themeMode() ? "DARK" : "LIGHT",
                            owner.environment().themeId());
                        assertEquals(MessageCatalog.forLanguageTag(requested.localeTag()).locale(),
                            owner.environment().locale());
                        return null;
                    });
                }
                assertEquals(oldTitle, onEdt(gallery::getTitle));
                assertEquals(2, appearanceSaves.get());
                assertEquals(2, environments.size());
                assertEquals(before, effectCounts(runtime));
                assertEquals(0, connections.get());
                assertNull(socketFailure.get());
                assertEquals(0, runtime.security().securityState().activeTasks());
                click(frame, "security.lock");
                await(() -> !gallery.isDisplayable() && !model.isDisplayable() && !preview.isDisplayable());
                assertEquals(1, previewClosed.get());
                onEdt(() -> {
                    assertTrue(frame.getContentPane().isVisible());
                    assertTrue(frame.getGlassPane().isVisible());
                    assertFalse(named(frame, "settings.uiDebug").isEnabled());
                    assertEquals(0, ((JPasswordField) named(server, "server.password")).getPassword().length);
                    return null;
                });
                runtime.security().unlock(PASSWORD.toCharArray());
                await(() -> named(frame, "settings.uiDebug").isEnabled());
                click(frame, "settings.uiDebug");
                var reopened = onEdt(() -> owned(frame, "settings.uiDebug.gallery"));
                assertNotSame(gallery, reopened);
                openModal(reopened, "debug.server", "debug.preview.server");
                var currentServer = onEdt(() -> owned(reopened, "debug.preview.server"));
                assertEquals(originalServerTitle, onEdt(() -> ((JDialog) currentServer).getTitle()));
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
                assertFalse(onEdt(reopened::isDisplayable));
                assertFalse(onEdt(currentServer::isDisplayable));
                assertEquals(before, effectCounts(runtime));
                assertEquals(0, connections.get());
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
                sentinel.close();
                listener.join(5000);
                assertFalse(listener.isAlive());
                assertNull(socketFailure.get());
            }
        }
    }
}
