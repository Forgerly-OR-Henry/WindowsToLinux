package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.definition.DeploymentSourceInput;
import gold.debug.windowstolinux.app.service.source.SourceSelectionService;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.datatransfer.*;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentSourceCardTest {
    @TempDir Path directory;

    @Test void importsFolderAndGitTextAndClearsStaleSelectionBeforeInvalidInput() throws Exception {
        AtomicReference<DeploymentSourceInput> result = new AtomicReference<>();
        AtomicReference<CountDownLatch> completed = new AtomicReference<>(new CountDownLatch(1));
        var service = (AutomaticDeploymentApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AutomaticDeploymentApplicationFacade.class},
                (proxy, method, args) -> { assertFalse(SwingUtilities.isEventDispatchThread()); return SourceSelectionService.identify((String) args[0]); });
        AtomicReference<DeploymentSourceCard> card = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> card.set(new DeploymentSourceCard(service, new DesktopComponentFactory(ThemePalette.light()),
                new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")), value -> {
                    result.set(value); if (value.directory().isPresent() || !value.gitAddress().isEmpty()) completed.get().countDown();
                })));
        Transferable files = new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
            public boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(DataFlavor.javaFileListFlavor); }
            public Object getTransferData(DataFlavor flavor) { return java.util.List.of(directory.toFile()); }
        };
        SwingUtilities.invokeAndWait(() -> assertTrue(card.get().getTransferHandler().importData(new TransferHandler.TransferSupport(card.get(), files))));
        assertTrue(completed.get().await(5, TimeUnit.SECONDS)); assertEquals(directory, result.get().directory().orElseThrow());
        completed.set(new CountDownLatch(1));
        SwingUtilities.invokeAndWait(() -> card.get().getTransferHandler().importData(new TransferHandler.TransferSupport(card.get(), new StringSelection("https://example.test/repo.git"))));
        assertTrue(completed.get().await(5, TimeUnit.SECONDS)); assertEquals("https://example.test/repo.git", result.get().gitAddress());
        SwingUtilities.invokeAndWait(() -> {
            card.get().getTransferHandler().importData(new TransferHandler.TransferSupport(card.get(), new StringSelection("invalid")));
            assertTrue(result.get().directory().isEmpty()); assertTrue(result.get().gitAddress().isBlank());
            card.get().restore("draft-input"); assertEquals("draft-input", card.get().inputText());
        });
    }
}
