package gold.debug.windowstolinux.app.ui.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.ui.control.*;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import org.junit.jupiter.api.Test;

class AiModelListTest {
    private static final class Store implements AiModelListAccess {
        volatile List<AiModelInfo> models = List.of(model("first", 0, true), model("second", 1, true), model("third", 2, false));

        final Map<AiPurposeType, List<AiPurposeAssignment>> members = new EnumMap<>(AiPurposeType.class);

        int orderWrites, purposeWrites;

        @Override
        public List<AiModelInfo> listAiConfigurations() {
            return models;
        }

        @Override
        public List<AiPurposeAssignment> listAiPurpose(AiPurposeType type) {
            return members.getOrDefault(type, List.of());
        }

        @Override
        public synchronized void reorderAiProviders(List<String> ids) {
            var prior = models;
            models = ids.stream().map(id -> prior.stream().filter(row -> row.profile().id().equals(id)).findFirst().orElseThrow()).toList();
            orderWrites++;
        }

        @Override
        public synchronized void saveAiPurpose(AiPurposeType type, List<AiPurposeAssignment> requested) {
            members.put(type, requested);
            purposeWrites++;
        }
    }

    private static AiModelInfo model(String id, int order, boolean verified) {
        var instant = verified ? Optional.of(Instant.now()) : Optional.<Instant>empty();
        return new AiModelInfo(new AiModelProfile(id, URI.create("https://example.test/full"), "model-" + id, "ai/" + id),
            id, order, 1, instant, instant);
    }

    @Test
    void completedClicksOpenDetailsButHandleDragsAndGapsDoNot() throws Exception {
        for (var theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
            for (int scale : new int[]{100, 150}) {
                var store = new Store();
                var pane = new java.util.concurrent.atomic.AtomicReference<AiModelListPane>();
                var frame = edt(() -> {
                    DesktopThemeService.install(theme, scale);
                    var view = new AiModelListPane(store, new DesktopPartFactory(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light()),
                        new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")), ignored -> {
                    });
                    pane.set(view);
                    var window = new JFrame();
                    window.add(view);
                    window.setSize(1100, 720);
                    window.setLocation(70, 50);
                    window.setVisible(true);
                    view.refresh();
                    return window;
                });
                try {
                    idle();
                    var inventory = edt(() -> find(pane.get(), "ai.inventory", DesktopCardList.class));
                    var purposeSizes = edt(() -> {
                        var sizes = new EnumMap<AiPurposeType, Dimension>(AiPurposeType.class);
                        for (var type : AiPurposeType.values())
                            sizes.put(type, find(pane.get(), "ai.open." + type.name(), JButton.class).getSize());
                        return sizes;
                    });
                    var robot = new Robot();
                    robot.setAutoDelay(40);
                    robot.waitForIdle();
                    click(robot, inventory, 0);
                    assertNotNull(edt(() -> field(pane.get(), "detailId", String.class)));
                    assertNull(edt(() -> find(pane.get(), "ai.open.DEPLOYMENT", JButton.class)));
                    click(robot, inventory, 0);
                    assertNull(edt(() -> field(pane.get(), "detailId", String.class)));
                    drag(robot, inventory, 0, inventory, 2, "inventory-" + theme + "-" + scale);
                    assertEquals(List.of("first", "second", "third"), store.models.stream().map(row -> row.profile().id()).toList());
                    assertNull(edt(() -> field(pane.get(), "detailId", String.class)));
                    assertEquals(0, store.orderWrites);
                    edt(() -> {
                        pane.get().open(AiPurposeType.VISION);
                        return null;
                    });
                    idle();
                    assertEquals(0, store.orderWrites);
                    edt(() -> {
                        for (var type : AiPurposeType.values()) {
                            var button = find(pane.get(), "ai.open." + type.name(), JButton.class);
                            assertEquals(purposeSizes.get(type), button.getSize());
                            assertEquals(type == AiPurposeType.VISION, button.isSelected());
                        }
                        var toggle = find(pane.get(), "ai.open.VISION", JButton.class);
                        var activeColor = toggle.getBackground();
                        toggle.doClick(0);
                        assertTrue(pane.get().capture().purpose().isEmpty());
                        assertFalse(toggle.isSelected());
                        assertNotEquals(activeColor, toggle.getBackground());
                        toggle.doClick(0);
                        assertEquals(Optional.of(AiPurposeType.VISION), pane.get().capture().purpose());
                        assertTrue(toggle.isSelected());
                        assertEquals(activeColor, toggle.getBackground());
                        inventory.setSelectedValue("third", false);
                        assertFalse(find(pane.get(), "ai.purpose.add", JButton.class).isEnabled());
                        inventory.setSelectedValue("first", false);
                        find(pane.get(), "ai.purpose.add", JButton.class).doClick(0);
                        assertEquals(0, store.purposeWrites);
                        assertNull(field(pane.get(), "detailId", String.class));
                        pane.get().open(AiPurposeType.APPROVAL);
                        return null;
                    });
                    idle();
                    assertEquals(1, store.purposeWrites);
                    assertEquals(List.of(new AiPurposeAssignment("first")), store.members.get(AiPurposeType.VISION));
                    assertEquals(0, store.orderWrites);
                    assertTrue(edt(pane.get()::saveBeforeLeave).toCompletableFuture().get(5, TimeUnit.SECONDS));
                    assertEquals(1, store.orderWrites);
                } finally {
                    edt(() -> {
                        pane.get().close();
                        frame.dispose();
                        return null;
                    });
                    idle();
                }
            }
    }

    private static void click(Robot robot, JList<?> list, int row) throws Exception {
        var point = edt(() -> {
            var bounds = list.getCellBounds(row, row);
            var value = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
            SwingUtilities.convertPointToScreen(value, list);
            return value;
        });
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        Thread.sleep(100);
    }

    private static void drag(Robot robot, JList<String> source, int from, JList<String> target, int to, String name) throws Exception {
        Point start = edt(() -> cardCenter(source, from)), end = edt(() -> cardCenter(target, to));
        var before = edt(() -> java.util.stream.IntStream.range(0, source.getModel().getSize())
            .mapToObj(source.getModel()::getElementAt).toList());
        var diagnostics = edt(() -> new gold.debug.windowstolinux.app.ui.display.DesktopCardDragDiagnostics(source));
        robot.mouseMove(start.x, start.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        try {
            for (int step = 1; step <= 18; step++) {
                robot.mouseMove(start.x + (end.x - start.x) * step / 18, start.y + (end.y - start.y) * step / 18);
                robot.delay(18);
                if (step == 10 && source == target) {
                    robot.delay(140);
                    edt(() -> {
                        assertEquals(Boolean.TRUE, source.getClientProperty("desktop.cardDragActive"), diagnostics::describe);
                        assertEquals(before, java.util.stream.IntStream.range(0, source.getModel().getSize())
                            .mapToObj(source.getModel()::getElementAt).toList());
                        render((JComponent) SwingUtilities.getRootPane(source).getContentPane(), "drag-" + name + ".png");
                        return null;
                    });
                }
            }
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            edt(() -> {
                diagnostics.close();
                return null;
            });
        }
        robot.waitForIdle();
    }

    private static Point cardCenter(JList<?> list, int index) {
        var bounds = ((gold.debug.windowstolinux.app.ui.control.DesktopCardList<?>) list).handleBounds(index);
        assertFalse(bounds.isEmpty());
        var point = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        SwingUtilities.convertPointToScreen(point, list);
        return point;
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void render(JComponent root, String name) {
        try {
            var bitmap = new java.awt.image.BufferedImage(root.getWidth(), root.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = bitmap.createGraphics();
            root.paint(graphics);
            graphics.dispose();
            var path = java.nio.file.Path.of("target/ai-cards", name + ".png");
            java.nio.file.Files.createDirectories(path.getParent());
            javax.imageio.ImageIO.write(bitmap, "png", path.toFile());
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static <T> T find(Container root, String name, Class<T> type) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container nested) {
                var found = find(nested, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        do {
            Thread.sleep(20);
            edt(() -> null);
        }
        while (DesktopTaskRunner.hasActiveTasks() && System.nanoTime() < deadline);
        assertFalse(DesktopTaskRunner.hasActiveTasks());
    }
}
