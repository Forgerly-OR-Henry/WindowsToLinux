package gold.debug.windowstolinux.app.ui.deployment.automatic;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.datatransfer.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.definition.DeploymentSourceInput;
import gold.debug.windowstolinux.app.service.source.SourceSelectionService;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentSourceCardTest {
    @TempDir
    Path directory;

    @Test
    void acceptsOneSourceDisplaysItInTheCardAndRequiresClearingBeforeReplacement() throws Exception {
        AtomicReference<DeploymentSourceInput> result = new AtomicReference<>();
        AtomicReference<CountDownLatch> completed = new AtomicReference<>(new CountDownLatch(1));
        var service = (AutomaticDeploymentApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AutomaticDeploymentApplicationFacade.class}, (proxy, method, args) -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    return SourceSelectionService.identify((String) args[0]);
                });
        AtomicReference<DeploymentSourceCard> card = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
                () -> card.set(new DeploymentSourceCard(service, new DesktopComponentFactory(ThemePalette.light()),
                        new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")), value -> {
                            result.set(value);
                            if (value.directory().isPresent() || !value.gitAddress().isEmpty())
                                completed.get().countDown();
                        })));
        Transferable files = new Transferable() {
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.javaFileListFlavor};
            }

            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return flavor.equals(DataFlavor.javaFileListFlavor);
            }

            public Object getTransferData(DataFlavor flavor) {
                return java.util.List.of(directory.toFile());
            }
        };
        SwingUtilities.invokeAndWait(() -> assertTrue(
                card.get().getTransferHandler().importData(new TransferHandler.TransferSupport(card.get(), files))));
        assertTrue(completed.get().await(5, TimeUnit.SECONDS));
        assertEquals(directory, result.get().directory().orElseThrow());
        SwingUtilities.invokeAndWait(() -> {
            assertLocked(card.get(), directory.toString());
            assertFalse(card.get().getTransferHandler().importData(new TransferHandler.TransferSupport(card.get(),
                    new StringSelection("https://example.test/ignored.git"))));
            assertEquals(directory, result.get().directory().orElseThrow());
            button(card.get(), "清除").doClick(0);
            assertTrue(result.get().directory().isEmpty());
            assertTrue(result.get().gitAddress().isBlank());
        });
        completed.set(new CountDownLatch(1));
        SwingUtilities.invokeAndWait(() -> card.get().getTransferHandler().importData(
                new TransferHandler.TransferSupport(card.get(), new StringSelection("https://example.test/repo.git"))));
        assertTrue(completed.get().await(5, TimeUnit.SECONDS));
        assertEquals("https://example.test/repo.git", result.get().gitAddress());
        SwingUtilities.invokeAndWait(() -> {
            assertLocked(card.get(), "https://example.test/repo.git");
            assertFalse(
                    card.get().getTransferHandler().importData(new TransferHandler.TransferSupport(card.get(), files)));
            button(card.get(), "清除").doClick(0);
            card.get().getTransferHandler()
                    .importData(new TransferHandler.TransferSupport(card.get(), new StringSelection("invalid")));
            assertTrue(result.get().directory().isEmpty());
            assertTrue(result.get().gitAddress().isBlank());
            card.get().restore("draft-input", false);
            assertEquals("draft-input", card.get().inputText());
            assertTrue(input(card.get()).isVisible());
            assertTrue(input(card.get()).isEnabled());
            card.get().restore("https://example.test/repo.git", true);
            assertLocked(card.get(), "https://example.test/repo.git");
        });
    }

    @Test
    void clearingWhileIdentificationRunsDiscardsItsLateResult() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<DeploymentSourceInput> result = new AtomicReference<>();
        var service = (AutomaticDeploymentApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AutomaticDeploymentApplicationFacade.class}, (proxy, method, args) -> {
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return SourceSelectionService.identify((String) args[0]);
                });
        AtomicReference<DeploymentSourceCard> card = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            card.set(new DeploymentSourceCard(service, new DesktopComponentFactory(ThemePalette.light()),
                    new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")), result::set));
            card.get().getTransferHandler().importData(new TransferHandler.TransferSupport(card.get(),
                    new StringSelection("https://example.test/repo.git")));
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> button(card.get(), "清除").doClick(0));
        } finally {
            release.countDown();
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (DesktopTaskExecutor.hasActiveTasks() && System.nanoTime() < until)
            Thread.sleep(10);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("", card.get().inputText());
            assertTrue(result.get().gitAddress().isEmpty());
            assertTrue(input(card.get()).isVisible());
            assertTrue(input(card.get()).isEnabled());
        });
        assertFalse(DesktopTaskExecutor.hasActiveTasks());
    }

    private static void assertLocked(DeploymentSourceCard card, String value) {
        assertEquals(value, card.inputText());
        assertNotNull(button(card, value));
        assertFalse(button(card, value).isEnabled());
        assertFalse(input(card).isVisible());
        assertFalse(input(card).isEnabled());
        assertEquals("", input(card).getText());
    }

    private static JTextField input(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JTextField field)
                return field;
            if (child instanceof Container nested) {
                JTextField found = input(nested);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static JButton button(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && button.getText().equals(text))
                return button;
            if (child instanceof Container nested) {
                JButton found = button(nested, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
}
