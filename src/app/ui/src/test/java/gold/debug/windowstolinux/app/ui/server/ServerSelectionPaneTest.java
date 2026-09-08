package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerSelectionPaneTest {
    private final DesktopComponentFactory components = new DesktopComponentFactory(ThemePalette.light());
    private final PageMessagePresenter messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));
    private final ServerProfile first = new ServerProfile("first", "192.0.2.1", 22, "tester", "ssh/first/password", CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER);
    private final ServerProfile second = new ServerProfile("second", "192.0.2.2", 22, "tester", "ssh/second/password", CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER);

    @Test void initialInventoryPublishesSelectionButRestoringDoesNotOverwriteUnsavedContext() throws Exception {
        AtomicInteger notifications = new AtomicInteger(); AtomicReference<ServerSelectionPane> pane = new AtomicReference<>();
        var service = (ServerApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ServerApplicationFacade.class},
                (proxy, method, args) -> List.of(first, second));
        SwingUtilities.invokeAndWait(() -> { pane.set(new ServerSelectionPane(service, components, messages, ignored -> notifications.incrementAndGet())); pane.get().reload(); });
        awaitTasks();
        SwingUtilities.invokeAndWait(() -> { assertEquals(first, pane.get().profile()); pane.get().select(second.id()); });
        awaitTasks();
        SwingUtilities.invokeAndWait(() -> { assertEquals(second, pane.get().profile()); pane.get().select("missing"); });
        awaitTasks();
        SwingUtilities.invokeAndWait(() -> assertNull(pane.get().profile()));
        assertEquals(1, notifications.get());
    }

    @Test void saveAndConnectionCheckRunOffTheEdtAndCannotBeSubmittedTwice() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger saves = new AtomicInteger(), verifications = new AtomicInteger();
        var service = (ServerApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ServerApplicationFacade.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "listServerProfiles" -> List.of();
                    case "saveServerProfile" -> { assertFalse(SwingUtilities.isEventDispatchThread()); saves.incrementAndGet(); started.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS)); yield null; }
                    case "verifyServer" -> { assertFalse(SwingUtilities.isEventDispatchThread()); verifications.incrementAndGet();
                        yield new ServerCapabilityFacts("fixture", "amd64", true, true, true, true, true, true, true, true, 5, 1000000, "fixture"); }
                    default -> throw new AssertionError(method.getName());
                });
        AtomicReference<ServerPage> page = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            page.set(new ServerPage(null, service, components, messages));
            try (var state = new ServerPageState("first", "192.0.2.1", "22", "tester", "fixture-password".toCharArray(),
                    CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER, new char[0], "")) { page.get().restoreState(state); }
        });
        awaitTasks();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JButton save = button(page.get().panel(), messages.text("button.saveServer")); save.doClick(); save.doClick();
                assertFalse(save.isEnabled()); assertTrue(DesktopTaskExecutor.hasActiveTasks());
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        awaitTasks();
        assertEquals(1, saves.get()); assertEquals(1, verifications.get());
        SwingUtilities.invokeAndWait(() -> assertTrue(button(page.get().panel(), messages.text("button.saveServer")).isEnabled()));
    }

    private static JButton button(Container parent, String label) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && button.getText().equals(label)) return button;
            if (child instanceof Container nested) { JButton found = button(nested, label); if (found != null) return found; }
        }
        return null;
    }
    private static void awaitTasks() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (DesktopTaskExecutor.hasActiveTasks() && System.nanoTime() < until) Thread.sleep(10);
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(DesktopTaskExecutor.hasActiveTasks(), "background test work must finish");
    }
}
