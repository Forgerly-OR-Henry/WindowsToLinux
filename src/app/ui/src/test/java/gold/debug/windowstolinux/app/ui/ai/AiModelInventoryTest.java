package gold.debug.windowstolinux.app.ui.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

import javax.swing.*;

import com.formdev.flatlaf.FlatLightLaf;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import gold.debug.windowstolinux.shared.model.ai.*;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;

class AiModelInventoryTest {
    private final DesktopComponentFactory c = new DesktopComponentFactory(ThemePalette.light());

    private final PageMessagePresenter messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));
    private AiProviderSummary model(String id, int order) {
        return new AiProviderSummary(
                new AiProviderProfile(id, URI.create("https://example.test/v1/chat/completions"), "model-" + id,
                        "ai/" + id, CredentialStorageMode.MASTER_PASSWORD),
                id, order, 1, Optional.of(Instant.now()), Optional.of(Instant.now()));
    }

    @Test
    void inventoryRemainsVisibleAndPurposeDraftDoesNotChangeDisplayOrder() throws Exception {
        var values = new AtomicReference<>(List.of(model("first", 0), model("second", 1), model("third", 2)));
        var roles = new EnumMap<AiPurposeType, List<AiPurposeAssignment>>(AiPurposeType.class);
        for (var role : AiPurposeType.values())
            roles.put(role, List.of());
        var failSave = new AtomicBoolean();
        AiApplicationFacade service = (AiApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AiApplicationFacade.class}, (p, m, a) -> {
                    return switch (m.getName()) {
                        case "listAiConfigurations" -> values.get();
                        case "listAiPurpose" -> roles.get(a[0]);
                        case "saveAiPurpose" -> {
                            if (failSave.get())
                                throw new java.sql.SQLException("test save failure");
                            roles.put((AiPurposeType) a[0], (List<AiPurposeAssignment>) a[1]);
                            yield null;
                        }
                        case "reorderAiProviders" -> {
                            var prior = values.get();
                            values.set(((List<String>) a[0]).stream().map(id -> prior.stream()
                                    .filter(v -> v.profile().id().equals(id)).findFirst().orElseThrow()).toList());
                            yield null;
                        }
                        default -> throw new AssertionError(m);
                    };
                });
        AtomicReference<AiModelInventoryPane> pane = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            pane.set(new AiModelInventoryPane(service, c, messages, v -> {
            }));
            pane.get().refresh();
        });
        idle();
        AtomicReference<JList<String>> original = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var inventory = field(pane.get(), "inventory", JList.class);
            original.set(inventory);
            pane.get().open(AiPurposeType.APPROVAL);
            assertSame(inventory, field(pane.get(), "inventory", JList.class));
            assertEquals(3, inventory.getModel().getSize());
            inventory.setSelectedIndex(0);
            field(pane.get(), "add", JButton.class).doClick();
            assertFalse(field(pane.get(), "add", JButton.class).isEnabled());
            assertEquals(3, inventory.getModel().getSize());
            inventory.setSelectedIndex(1);
            field(pane.get(), "add", JButton.class).doClick();
            var selected = field(pane.get(), "selected", JList.class);
            selected.setSelectedIndex(1);
            selected.getActionMap().get("alt UP").actionPerformed(new ActionEvent(selected, 0, "alt UP"));
            assertEquals(List.of("second", "first"),
                    pane.get().capture().draft().stream().map(AiPurposeAssignment::profileId).toList());
            inventory.setSelectedIndex(0);
            inventory.getActionMap().get("alt DOWN").actionPerformed(new ActionEvent(inventory, 0, "alt DOWN"));
        });
        idle();
        assertEquals(List.of("second", "first", "third"), values.get().stream().map(v -> v.profile().id()).toList());
        failSave.set(true);
        SwingUtilities.invokeAndWait(() -> button(pane.get(), "ai.purpose.save").doClick());
        idle();
        assertTrue(roles.get(AiPurposeType.APPROVAL).isEmpty());
        assertEquals(2, pane.get().capture().draft().size());
        failSave.set(false);
        SwingUtilities.invokeAndWait(() -> button(pane.get(), "ai.purpose.save").doClick());
        idle();
        assertEquals(List.of("second", "first"),
                roles.get(AiPurposeType.APPROVAL).stream().map(AiPurposeAssignment::profileId).toList());
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(pane.get().capture().purpose().isEmpty());
            assertSame(original.get(), field(pane.get(), "inventory", JList.class));
            pane.get().open(AiPurposeType.APPROVAL);
            field(pane.get(), "selected", JList.class).setSelectedIndex(0);
            field(pane.get(), "remove", JButton.class).doClick();
            button(pane.get(), "ai.purpose.cancel").doClick();
        });
        assertEquals(2, roles.get(AiPurposeType.APPROVAL).size());
        assertTrue(roles.get(AiPurposeType.DEPLOYMENT).isEmpty());
        assertEquals(List.of("b", "c", "a"), AiProviderDragTransfer.move(List.of("a", "b", "c"), "a", 3));
    }

    @Test
    void failedModelSaveRetainsInputsAndCancelWaitsForWorkerCleanup() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch started = new CountDownLatch(1), cleanup = new CountDownLatch(1),
                release = new CountDownLatch(1);
        boolean[] block = {false};
        AiApplicationFacade service = (AiApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AiApplicationFacade.class}, (p, m, a) -> {
                    if (!block[0])
                        throw gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.create(
                                gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType.AI_CONFIGURATION_TEST_FAILED,
                                "test failed");
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException interrupted) {
                        cleanup.countDown();
                        release.await();
                        throw new CancellationException();
                    }
                    return null;
                });
        AtomicReference<AiProviderDialog> dialog = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            dialog.set(new AiProviderDialog(null, service, c, messages, model("existing", 1),
                    () -> fail("must not save")));
            dialog.get().setModalityType(Dialog.ModalityType.MODELESS);
            dialog.get().setVisible(true);
            field(dialog.get(), "save", JButton.class).doClick();
        });
        try {
            idle();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(dialog.get().isShowing());
                assertEquals("existing", field(dialog.get(), "name", JTextField.class).getText());
                block[0] = true;
                field(dialog.get(), "save", JButton.class).doClick();
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(
                    () -> dialog.get().dispatchEvent(new WindowEvent(dialog.get(), WindowEvent.WINDOW_CLOSING)));
            assertTrue(cleanup.await(3, TimeUnit.SECONDS));
            assertTrue(dialog.get().isDisplayable());
            release.countDown();
            idle();
            assertFalse(dialog.get().isDisplayable());
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> dialog.get().dispose());
        }
    }

    @Test
    void inlineColumnsRemainEqualAndRestoreFullWidthInBothThemes() throws Exception {
        AiApplicationFacade service = (AiApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AiApplicationFacade.class}, (p, m, a) -> switch (m.getName()) {
                    case "listAiConfigurations" ->
                        List.of(model("deployment", 0), model("review", 1), model("vision", 2));
                    case "listAiPurpose" -> List.of(new AiPurposeAssignment("review", true));
                    default -> throw new AssertionError(m);
                });
        for (boolean dark : List.of(false, true)) {
            var pane = new AtomicReference<AiModelInventoryPane>();
            SwingUtilities.invokeAndWait(() -> {
                if (dark)
                    com.formdev.flatlaf.FlatDarkLaf.setup();
                else
                    FlatLightLaf.setup();
                pane.set(new AiModelInventoryPane(service,
                        new DesktopComponentFactory(dark ? ThemePalette.dark() : ThemePalette.light()), messages, v -> {
                        }));
                pane.get().refresh();
            });
            idle();
            SwingUtilities.invokeAndWait(() -> {
                var root = pane.get();
                root.setSize(850, 450);
                layout(root);
                var inventory = field(root, "inventoryScroll", JScrollPane.class);
                int width = inventory.getWidth();
                field(root, "inventory", JList.class).setSelectedIndex(1);
                root.open(AiPurposeType.APPROVAL);
                layout(root);
                var editor = field(root, "editor", JPanel.class);
                assertEquals(inventory.getWidth(), editor.getWidth(), 2);
                assertTrue(inventory.getWidth() > 320);
                assertEquals(3, field(root, "inventory", JList.class).getModel().getSize());
                render(root, "models-" + (dark ? "dark" : "light") + "-split.png");
                button(root, "ai.purpose.cancel").doClick();
                layout(root);
                assertEquals(width, inventory.getWidth());
                assertEquals("review", field(root, "inventory", JList.class).getSelectedValue());
                render(root, "models-" + (dark ? "dark" : "light") + "-full.png");
            });
        }
        SwingUtilities.invokeAndWait(FlatLightLaf::setup);
    }

    private static void layout(Container root) {
        root.doLayout();
        for (var child : root.getComponents())
            if (child instanceof Container nested)
                layout(nested);
    }

    private static void render(JComponent root, String name) {
        try {
            var image = new java.awt.image.BufferedImage(root.getWidth(), root.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(UIManager.getColor("Panel.background"));
            graphics.fillRect(0, 0, root.getWidth(), root.getHeight());
            root.paint(graphics);
            graphics.dispose();
            var directory = java.nio.file.Path.of("target/visual-checks");
            java.nio.file.Files.createDirectories(directory);
            javax.imageio.ImageIO.write(image, "png", directory.resolve(name).toFile());
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static JButton button(Container root, String name) {
        return descendants(root).filter(v -> name.equals(v.getName())).map(JButton.class::cast).findFirst()
                .orElseThrow();
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return type.cast(f.get(target));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void idle() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (DesktopTaskExecutor.hasActiveTasks() && System.nanoTime() < end)
            Thread.sleep(10);
        assertFalse(DesktopTaskExecutor.hasActiveTasks());
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    private static Stream<Component> descendants(Container root) {
        return Stream.of(root.getComponents()).flatMap(
                v -> v instanceof Container nested ? Stream.concat(Stream.of(v), descendants(nested)) : Stream.of(v));
    }
}
