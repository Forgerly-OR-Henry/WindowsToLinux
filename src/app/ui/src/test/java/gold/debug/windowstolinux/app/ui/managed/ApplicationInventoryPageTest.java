package gold.debug.windowstolinux.app.ui.managed;

import com.formdev.flatlaf.FlatLightLaf;
import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import gold.debug.windowstolinux.app.ui.server.ServerContext;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationInventoryPageTest {
    private ApplicationSummary app(String name, String type, String server) {
        return new ApplicationSummary("managed:" + name, name, type, server, server, "192.0.2.1", Optional.of(Instant.now()), Optional.empty(), RuntimeState.UNKNOWN,
                Optional.empty(), Optional.empty(), false, true, true, false);
    }
    @Test void rendersCardsAndCombinesFiltersWhileRetainingState() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        List<ApplicationSummary> values = List.of(app("web-one", "WEBSITE", "first"), app("worker", "APP", "first"), app("web-two", "WEBSITE", "second"));
        var service = (ManagedApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ManagedApplicationFacade.class}, (proxy, method, args) -> values);
        var context = (ServerContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ServerContext.class}, (proxy, method, args) -> new char[0]);
        AtomicReference<JFrame> frame = new AtomicReference<>(); AtomicReference<ManagedPage> page = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup(); page.set(new ManagedPage(service, context, new DesktopComponentFactory(ThemePalette.light()), new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"))));
            frame.set(new JFrame()); frame.get().setContentPane(page.get().panel()); frame.get().setSize(950, 720); frame.get().setVisible(true);
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (DesktopTaskExecutor.hasActiveTasks() && System.nanoTime() < deadline) Thread.sleep(10);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(hasLabel(frame.get(), "worker")); assertTrue(hasLabel(frame.get(), "web-two"));
                var combos = descendants(page.get().panel()).filter(JComboBox.class::isInstance).map(JComboBox.class::cast).toList();
                combos.get(0).setSelectedItem("WEBSITE"); combos.get(1).setSelectedIndex(1);
                assertTrue(hasLabel(frame.get(), "web-one")); assertFalse(hasLabel(frame.get(), "web-two")); assertFalse(hasLabel(frame.get(), "worker"));
                var state = page.get().captureState(); assertEquals("WEBSITE", state.typeFilter()); assertEquals("first", state.serverFilter());
                var restored = new ManagedPage(null, context, new DesktopComponentFactory(ThemePalette.light()), new PageMessagePresenter(MessageCatalog.forLanguageTag("en")));
                restored.restoreState(state); assertEquals(state, restored.captureState());
                try {
                    RepaintManager.currentManager(frame.get()).validateInvalidComponents(); frame.get().validate();
                    var title = descendants(frame.get()).filter(JLabel.class::isInstance).map(JLabel.class::cast).filter(label -> label.getText().equals("web-one")).findFirst().orElseThrow();
                    assertTrue(title.getWidth() > 0 && title.getHeight() > 0, "card must be laid out before capture");
                    var image = new java.awt.image.BufferedImage(frame.get().getWidth(), frame.get().getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics(); frame.get().paint(graphics); graphics.dispose();
                    java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/visual-checks"));
                    javax.imageio.ImageIO.write(image, "png", java.nio.file.Path.of("target/visual-checks/application-cards.png").toFile());
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        } finally { SwingUtilities.invokeAndWait(() -> frame.get().dispose()); }
    }
    private static boolean hasLabel(Container root, String text) { return descendants(root).filter(JLabel.class::isInstance).map(JLabel.class::cast).anyMatch(label -> text.equals(label.getText())); }
    private static Stream<Component> descendants(Container root) { return Stream.of(root.getComponents()).flatMap(child -> child instanceof Container nested ? Stream.concat(Stream.of(child), descendants(nested)) : Stream.of(child)); }
}
