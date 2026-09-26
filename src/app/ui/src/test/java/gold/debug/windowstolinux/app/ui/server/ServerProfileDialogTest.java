package gold.debug.windowstolinux.app.ui.server;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import org.junit.jupiter.api.Test;

class ServerProfileDialogTest {
    @Test
    void failedSaveKeepsFormAndRetrySavesOnceWithoutConnecting() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger saves = new AtomicInteger(), checks = new AtomicInteger(), notifications = new AtomicInteger();
        var service = (ServerApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ServerApplicationFacade.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "saveServerProfile" -> {
                        assertFalse(SwingUtilities.isEventDispatchThread());
                        saves.incrementAndGet();
                        assertEquals(0, ((char[]) args[1]).length, "editing can retain the saved credential");
                        started.countDown();
                        assertTrue(release.await(8, TimeUnit.SECONDS));
                        if (saves.get() == 1)
                            throw new java.sql.SQLException("save fixture failure");
                        yield null;
                    }
                    case "verifyServer" -> {
                        assertFalse(SwingUtilities.isEventDispatchThread());
                        checks.incrementAndGet();
                        throw new IllegalStateException("connection fixture failure");
                    }
                    default -> throw new AssertionError(method.getName());
                });
        var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));
        AtomicReference<ServerProfileDialog> dialog = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            dialog.set(new ServerProfileDialog(null, service, new DesktopComponentFactory(ThemePalette.light()),
                    messages, new ServerProfile("first", "192.0.2.1", 22, "tester", "custom/credential", "Production"),
                    saved -> {
                        assertEquals("custom/credential", saved.credentialKey());
                        notifications.incrementAndGet();
                    }));
            dialog.get().setModalityType(Dialog.ModalityType.MODELESS);
            dialog.get().setVisible(true);
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                var save = descendants(dialog.get()).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> messages.text("button.saveServer").equals(button.getText())).findFirst()
                        .orElseThrow();
                save.doClick();
                save.doClick();
                assertFalse(save.isEnabled());
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (DesktopTaskExecutor.hasActiveTasks() && System.nanoTime() < deadline)
                Thread.sleep(10);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(dialog.get().isVisible());
                assertTrue(descendants(dialog.get()).filter(JTextField.class::isInstance).map(JTextField.class::cast)
                        .anyMatch(field -> field.getText().equals("Production")));
            });
            assertEquals(1, saves.get());
            assertEquals(0, checks.get());
            assertEquals(0, notifications.get());
            SwingUtilities.invokeAndWait(() -> descendants(dialog.get()).filter(JButton.class::isInstance)
                    .map(JButton.class::cast).filter(button -> "server.save".equals(button.getName())).findFirst()
                    .orElseThrow().doClick(0));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (DesktopTaskExecutor.hasActiveTasks() && System.nanoTime() < deadline)
                Thread.sleep(10);
            SwingUtilities.invokeAndWait(() -> assertFalse(dialog.get().isDisplayable()));
            assertEquals(2, saves.get());
            assertEquals(0, checks.get());
            assertEquals(1, notifications.get());
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> dialog.get().dispose());
        }
    }

    private static Stream<Component> descendants(Container root) {
        return Stream.of(root.getComponents())
                .flatMap(child -> child instanceof Container nested
                        ? Stream.concat(Stream.of(child), descendants(nested))
                        : Stream.of(child));
    }
}
