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
                (proxy, method, args) -> List.of(first, second).stream().map(value -> new gold.debug.windowstolinux.app.service.server.ServerSummary(value, Optional.empty(), false, "")).toList());
        SwingUtilities.invokeAndWait(() -> { pane.set(new ServerSelectionPane(service, components, messages, ignored -> notifications.incrementAndGet())); pane.get().reload(); });
        awaitTasks();
        SwingUtilities.invokeAndWait(() -> { assertEquals(first, pane.get().profile()); pane.get().select(second.id()); });
        awaitTasks();
        SwingUtilities.invokeAndWait(() -> { assertEquals(second, pane.get().profile()); pane.get().select("missing"); });
        awaitTasks();
        SwingUtilities.invokeAndWait(() -> assertNull(pane.get().profile()));
        assertEquals(1, notifications.get());
    }

    @Test void searchesDisplayNamesAndAddressesCaseInsensitively() {
        var named = new ServerProfile(first.id(), first.host(), 22, first.username(), first.credentialKey(), first.credentialMode(), "Production EU");
        var summary = new gold.debug.windowstolinux.app.service.server.ServerSummary(named, Optional.empty(), false, "");
        assertTrue(summary.matches("DUCt")); assertTrue(summary.matches("192.0")); assertTrue(summary.matches("  "));
        assertFalse(summary.matches("other"));
    }

    @Test void appearanceStatePreservesDisplayNameAndCustomCredentialReference() {
        ServerPage before = new ServerPage(null, null, components, messages);
        ServerProfile profile = new ServerProfile(first.id(), first.host(), 22, first.username(), "custom/key", first.credentialMode(), "Production EU");
        before.selectProfile(profile);
        try (var state = before.captureState()) {
            ServerPage after = new ServerPage(null, null, components, messages);
            after.restoreState(state); assertEquals(profile, after.profile());
        }
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
