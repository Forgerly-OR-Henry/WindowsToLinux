package gold.debug.windowstolinux.app.ui.window;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.mod.UiExtensionRegistry;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.ui.extension.*;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;

class UiExtensionRegistryTest {
    @Test
    void reusedComponentOwnerCannotStealAnotherMountAndNativeInputStopsWhileLocked() throws Exception {
        var registry = new UiExtensionRegistry(environment(false));
        var scope = registry.begin("example.component", false, hostAccess());
        var point = registry.platform().provide("preview.extra", ServerId.class, UiExtensionKind.COMPONENT);
        var host = new Host();
        var pressed = new AtomicInteger();
        var text = new JTextField("draft", 16);
        var button = new JButton("Run");
        button.addActionListener(event -> pressed.incrementAndGet());
        var body = new JPanel();
        body.add(text);
        body.add(button);
        var owner = new UiComponent<ServerId>() {
            public JComponent create(Optional<ServerId> context, UiEnvironment environment) {
                return body;
            }

            public void contextChanged(Optional<ServerId> context) {
            }

            public void environmentChanged(UiEnvironment environment) {
            }

            public void close() {
            }
        };
        var registration = scope.registerComponent(point.point(), new UiExtensionSpec("native", 0), () -> owner);
        scope.publish();
        var mount = point.mount(host, Optional.empty());
        var original = body.getParent();
        point.mount(new Host(), Optional.empty());
        assertEquals(UiExtensionState.FAILED, registration.state());
        assertSame(original, body.getParent());
        var window = edt(() -> {
            var frame = new JFrame();
            frame.setContentPane(host.panel);
            frame.pack();
            frame.setLocation(40, 40);
            frame.setVisible(true);
            frame.toFront();
            return frame;
        });
        var robot = new java.awt.Robot();
        robot.setAutoDelay(50);
        try {
            nativeClick(robot, button);
            assertEquals(1, pressed.get());
            nativeClick(robot, text);
            registry.environment(environment(true));
            nativeClick(robot, button);
            robot.keyPress(java.awt.event.KeyEvent.VK_X);
            robot.keyRelease(java.awt.event.KeyEvent.VK_X);
            robot.waitForIdle();
            assertEquals(1, pressed.get());
            assertEquals("draft", edt(text::getText));
            registry.environment(environment(false));
            nativeClick(robot, button);
            assertEquals(2, pressed.get());
            mount.update(Optional.empty(), false);
            nativeClick(robot, button);
            assertEquals(2, pressed.get());
            edt(() -> {
                var directory = java.nio.file.Path.of("target/ui-extensions-preview");
                java.nio.file.Files.createDirectories(directory);
                var bitmap = new java.awt.image.BufferedImage(window.getWidth(), window.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = bitmap.createGraphics();
                window.paint(graphics);
                graphics.dispose();
                javax.imageio.ImageIO.write(bitmap, "png", directory.resolve("component-mount.png").toFile());
                return null;
            });
        } finally {
            scope.close();
            point.close();
            edt(() -> {
                window.dispose();
                return null;
            });
        }
    }

    private static void nativeClick(java.awt.Robot robot, JComponent target) throws Exception {
        var location = edt(() -> {
            var point = target.getLocationOnScreen();
            point.translate(target.getWidth() / 2, target.getHeight() / 2);
            return point;
        });
        robot.mouseMove(location.x, location.y);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    private static UiEnvironment environment(boolean locked) {
        return new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "light", locked);
    }

    private static <T> T edt(java.util.concurrent.Callable<T> action) throws Exception {
        var task = new java.util.concurrent.FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static final class Host implements UiExtensionHost {
        final JPanel panel = new JPanel();

        List<JComponent> children = List.of();

        boolean failClear;

        final List<Runnable> callbacks = new ArrayList<>();

        public JComponent anchor() {
            return panel;
        }

        public JComponent createAction(UiActionPresentation state, Runnable invoke) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            var button = new JButton();
            callbacks.add(invoke);
            button.addActionListener(event -> invoke.run());
            updateAction(button, state);
            return button;
        }

        public void updateAction(JComponent component, UiActionPresentation state) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            ((JButton) component).setText(state.text());
            component.setEnabled(state.enabled());
        }

        public void setContents(List<JComponent> children) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            if (failClear && children.isEmpty()) throw new IllegalStateException("private author text");
            this.children = List.copyOf(children);
            panel.removeAll();
            children.forEach(panel::add);
        }
    }

    private static UiAction<ServerId> action(String label, List<ServerId> clicked) {
        return new UiAction<>() {
            public UiActionPresentation presentation(Optional<ServerId> context, UiEnvironment environment) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                return new UiActionPresentation(label, true, context.isPresent());
            }

            public void invoke(Optional<ServerId> context) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                clicked.add(context.orElseThrow());
            }
        };
    }

    @Test
    void stagedActionsWaitSortAndCaptureCurrentServerWhileLocksAndStopRevokeCallbacks() throws Exception {
        var registry = new UiExtensionRegistry(environment(false));
        var scope = registry.begin("example.files", false, hostAccess());
        var clicks = new ArrayList<ServerId>();
        var second = scope.registerAction(PlatformUiExtensions.SERVER_MORE, new UiExtensionSpec("second", 20), action("second", clicks));
        var first = scope.registerAction(PlatformUiExtensions.SERVER_MORE, new UiExtensionSpec("first", 10), action("first", clicks));
        assertEquals(UiExtensionState.WAITING, first.state());
        var point = registry.platform().provide("servers.more", ServerId.class, UiExtensionKind.ACTION);
        var host = new Host();
        var mount = point.mount(host, Optional.of(new ServerId("a")));
        assertTrue(host.children.isEmpty());
        scope.publish();
        assertEquals(UiExtensionState.MOUNTED, first.state());
        assertEquals(List.of("first", "second"), host.children.stream().map(item -> ((JButton) item).getText()).toList());
        var original = host.children.getFirst();
        edt(() -> {
            ((JButton) original).doClick();
            return null;
        });
        mount.update(Optional.of(new ServerId("b")), true);
        edt(() -> {
            ((JButton) original).doClick();
            return null;
        });
        assertEquals(List.of(new ServerId("a"), new ServerId("b")), clicks);
        assertSame(original, host.children.getFirst());
        registry.environment(environment(true));
        assertFalse(original.isEnabled());
        registry.environment(environment(false));
        assertTrue(original.isEnabled());
        mount.update(Optional.empty(), true);
        assertFalse(original.isEnabled());
        mount.update(Optional.of(new ServerId("b")), true);
        scope.seal();
        edt(() -> {
            ((JButton) original).doClick();
            return null;
        });
        assertEquals(2, clicks.size());
        assertThrows(ApiException.class, () -> scope.registerAction(PlatformUiExtensions.SERVER_MORE,
            new UiExtensionSpec("late", 0), action("late", clicks)));
        scope.close();
        host.callbacks.getFirst().run();
        assertEquals(2, clicks.size());
        var callbackField = host.callbacks.getFirst().getClass().getDeclaredField("action");
        callbackField.setAccessible(true);
        assertNull(callbackField.get(host.callbacks.getFirst()));
        assertEquals(UiExtensionState.CLOSED, first.state());
        assertEquals(UiExtensionState.CLOSED, second.state());
        assertTrue(host.children.isEmpty());
        point.close();
    }

    private static gold.debug.windowstolinux.app.ui.mod.UiExtensionAccess hostAccess() {
        return new gold.debug.windowstolinux.app.ui.mod.UiExtensionAccess() {
            public String version() {
                return "1.0.0";
            }

            public boolean active() {
                return true;
            }

            public void checkContextType(Class<?> type, boolean owned) {
                if (type != ServerId.class && type != String.class) throw new IllegalArgumentException();
            }

            public void checkTarget(UiExtensionPoint<?> target, Optional<String> version) {
                checkContextType(target.contextType(), false);
            }

            public Object copy(Object value) throws Exception {
                return gold.debug.windowstolinux.app.mod.service.ServiceValueCopier.copy(value,
                    type -> type.getName().startsWith("gold.debug.windowstolinux.appapi."));
            }
        };
    }

    private static final class Component implements UiComponent<ServerId> {
        final JTextField input = new JTextField("retained draft");

        int contexts, environments, closes;

        boolean failClose, failUpdate;

        public JComponent create(Optional<ServerId> context, UiEnvironment environment) {
            return input;
        }

        public void contextChanged(Optional<ServerId> context) {
            contexts++;
            if (failUpdate) throw new IllegalStateException("secret input");
        }

        public void environmentChanged(UiEnvironment environment) {
            environments++;
        }

        public void close() {
            closes++;
            if (failClose) throw new IllegalStateException("secret input");
        }
    }

    @Test
    void freshComponentsKeepDraftsAcrossThemesAndContextAndCloseWithTheirPage() throws Exception {
        var registry = new UiExtensionRegistry(environment(false));
        var provider = registry.begin("example.provider", false, hostAccess());
        var consumer = registry.begin("example.consumer", false, hostAccess());
        var point = provider.provide("details.extra", ServerId.class, UiExtensionKind.COMPONENT);
        var created = new ArrayList<Component>();
        var registration = consumer.registerComponent(point.point(), new UiExtensionSpec("detail", 0), () -> {
            var component = new Component();
            created.add(component);
            return component;
        });
        provider.publish();
        consumer.publish();
        assertTrue(created.isEmpty());
        var host = new Host();
        var mount = point.mount(host, Optional.of(new ServerId("a")));
        assertEquals(1, created.size());
        var body = created.getFirst();
        var wrapper = host.children.getFirst();
        var layer = (JLayer<?>) wrapper;
        int listeners = layer.getGlassPane().getMouseListeners().length;
        for (int i = 0; i < 4; i++)
            registry.environment(new UiEnvironment(Locale.ENGLISH, i % 2 == 0 ? "dark" : "light", i % 2 == 0));
        mount.update(Optional.of(new ServerId("b")), true);
        assertSame(wrapper, host.children.getFirst());
        assertEquals("retained draft", body.input.getText());
        assertEquals(4, body.environments);
        assertEquals(1, body.contexts);
        assertEquals(listeners, layer.getGlassPane().getMouseListeners().length);
        registry.closeWithin(host.panel);
        assertEquals(1, body.closes);
        assertEquals(UiExtensionState.WAITING, registration.state());
        assertTrue(host.children.isEmpty());
        assertEquals(listeners - 1, layer.getGlassPane().getMouseListeners().length);
        point.mount(new Host(), Optional.empty());
        assertEquals(2, created.size());
        consumer.close();
        provider.close();
        assertEquals(UiExtensionState.CLOSED, registration.state());
        assertEquals(1, created.get(1).closes);
    }

    @Test
    void failedUpdateIsIsolatedAndCloseFailureRetainsOccupancyUntilRetry() throws Exception {
        var registry = new UiExtensionRegistry(environment(false));
        var provider = registry.begin("example.provider", false, hostAccess());
        var consumer = registry.begin("example.consumer", false, hostAccess());
        var point = provider.provide("extra", ServerId.class, UiExtensionKind.COMPONENT);
        var broken = new Component();
        broken.failUpdate = true;
        broken.failClose = true;
        var registration = consumer.registerComponent(point.point(), new UiExtensionSpec("broken", 0), () -> broken);
        var healthy = new Component();
        consumer.registerComponent(point.point(), new UiExtensionSpec("healthy", 1), () -> healthy);
        provider.publish();
        consumer.publish();
        var host = new Host();
        var mount = point.mount(host, Optional.of(new ServerId("a")));
        mount.update(Optional.of(new ServerId("b")), true);
        assertEquals(UiExtensionState.FAILED, registration.state());
        assertEquals(1, healthy.contexts);
        assertFalse(registration.problem().orElseThrow().message().contains("secret"));
        assertThrows(ApiException.class, provider::close);
        assertEquals(1, healthy.closes);
        assertThrows(ApiException.class, () -> registry.begin("example.provider", false, hostAccess()));
        broken.failClose = false;
        provider.close();
        assertEquals(2, broken.closes);
        assertEquals(1, healthy.closes);
        consumer.close();
    }

    @Test
    void mismatchedKindsDuplicateIdsAndRecoveryAreRejectedAndHostCleanupIsRetryable() throws Exception {
        var registry = new UiExtensionRegistry(environment(false));
        var scope = registry.begin("example.consumer", false, hostAccess());
        var point = registry.platform().provide("servers.more", ServerId.class, UiExtensionKind.ACTION);
        var clicks = new ArrayList<ServerId>();
        scope.registerAction(point.point(), new UiExtensionSpec("same", 0), action("same", clicks));
        assertThrows(ApiException.class, () -> scope.registerAction(point.point(), new UiExtensionSpec("same", 1), action("same", clicks)));
        assertThrows(ApiException.class, () -> scope.registerComponent(point.point(), new UiExtensionSpec("component", 2), Component::new));
        var wrong = new UiExtensionPoint<>("platform", "servers.more", String.class, UiExtensionKind.ACTION);
        assertThrows(ApiException.class, () -> scope.registerAction(wrong, new UiExtensionSpec("wrong", 2), new UiAction<>() {
            public UiActionPresentation presentation(Optional<String> context, UiEnvironment environment) {
                return new UiActionPresentation("wrong", true, true);
            }

            public void invoke(Optional<String> context) {
            }
        }));
        var recovery = registry.begin("example.recovery", true, hostAccess());
        assertThrows(ApiException.class, () -> recovery.provide("extra", String.class, UiExtensionKind.COMPONENT));
        recovery.close();
        scope.publish();
        var host = new Host();
        point.mount(host, Optional.of(new ServerId("a")));
        host.failClear = true;
        assertThrows(ApiException.class, point::close);
        host.failClear = false;
        point.close();
        scope.close();
    }

    @Test
    void waitingTypeMismatchAndCreationFailureRemainObservableWithoutBreakingHealthyActions() throws Exception {
        var registry = new UiExtensionRegistry(environment(false));
        var scope = registry.begin("example.consumer", false, hostAccess());
        var target = new UiExtensionPoint<>("platform", "future", ServerId.class, UiExtensionKind.COMPONENT);
        var registration = scope.registerComponent(target, new UiExtensionSpec("wrong", 0), Component::new);
        scope.publish();
        var point = registry.platform().provide("future", String.class, UiExtensionKind.COMPONENT);
        point.mount(new Host(), Optional.of("actual"));
        assertEquals(UiExtensionState.FAILED, registration.state());
        assertTrue(registration.problem().isPresent());
        point.close();
        scope.close();
    }
}
