package gold.debug.windowstolinux.app.ui.setting;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import org.junit.jupiter.api.Test;

class NavigationOrderPaneTest {
    @Test
    void clearedSelectionSurvivesReloadWithoutSavingAndEmptyInventoryKeepsItsScroll() throws Exception {
        var saved = new AtomicReference<>(List.of("platform.servers", "platform.models"));
        var writes = new AtomicInteger();
        var pane = edt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var root = new NavigationOrderPane(new DesktopPartFactory(ThemePalette.light()),
                new PageMessagePresenter(MessageCatalog.forLanguageTag("en"), FailureReportStore.disabled()));
            root.section("navigation.platform", saved::get, List::copyOf, ignored -> List.of(), value -> value,
                (expected, requested) -> {
                    writes.incrementAndGet();
                    return requested;
                }, ignored -> {
                });
            root.setLocked(false);
            return root;
        });
        try {
            var list = edt(() -> (JList<?>) named(pane, "navigation.platform.list"));
            await(() -> list.isEnabled() && list.getModel().getSize() == 2);
            edt(() -> {
                list.setSelectedIndex(0);
                list.clearSelection();
                assertFalse(named(pane, "navigation.platform.up").isEnabled());
                assertFalse(named(pane, "navigation.platform.down").isEnabled());
                pane.refresh();
                return null;
            });
            await(list::isEnabled);
            edt(() -> {
                assertEquals(-1, list.getSelectedIndex());
                saved.set(List.of());
                pane.refresh();
                return null;
            });
            await(() -> list.isEnabled() && list.getModel().getSize() == 0);
            edt(() -> {
                var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, list);
                assertTrue(scroll.isVisible());
                assertTrue(named(pane, "navigation.platform.empty").isVisible());
                assertEquals(0, writes.get());
                return null;
            });
        } finally {
            edt(() -> {
                pane.close();
                return null;
            });
            await(() -> !gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner.hasActiveTasks());
        }
    }

    @Test
    void realCardDraggingSavesOnlyItsSectionAndRetainsConfirmedOrderOnFailureOrLock() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
            for (int scale : new int[]{100, 150}) {
                var initial = List.of("platform.servers", "platform.models", "tasks.title");
                var saved = new AtomicReference<>(initial);
                var modOrder = new AtomicReference<>(List.of("Static / assisted deployment", "Applications"));
                var writes = new AtomicInteger();
                var modWrites = new AtomicInteger();
                var fail = new AtomicBoolean();
                var entered = new CountDownLatch(1);
                var released = new CountDownLatch(1);
                var delay = new AtomicBoolean();
                var pane = new AtomicReference<NavigationOrderPane>();
                var appearance = new AtomicReference<PlatformAppearancePane>();
                var frame = edt(() -> {
                    DesktopThemeService.install(theme, scale);
                    var parts = new DesktopPartFactory(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light());
                    var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"), FailureReportStore.disabled());
                    var order = new NavigationOrderPane(parts, messages);
                    pane.set(order);
                    order.section("navigation.platform", saved::get, List::copyOf, ignored -> initial,
                        messages::text, (expected, requested) -> {
                            assertEquals(saved.get(), expected);
                            writes.incrementAndGet();
                            if (delay.get()) {
                                entered.countDown();
                                assertTrue(released.await(10, TimeUnit.SECONDS));
                            }
                            if (fail.getAndSet(false)) throw new IOException("fixture write rejected");
                            saved.set(requested);
                            return requested;
                        }, ignored -> {
                        });
                    order.section("navigation.mods", modOrder::get, List::copyOf, ignored -> modOrder.get(),
                        value -> value, (expected, requested) -> {
                            modWrites.incrementAndGet();
                            modOrder.set(requested);
                            return requested;
                        }, ignored -> {
                        });
                    order.setLocked(false);
                    var choices = new PlatformAppearancePane(new DesktopDisplayConfig("zh-CN", theme), ignored -> {
                    },
                        ignored -> {
                        }, parts, messages);
                    appearance.set(choices);
                    var window = new JFrame("Navigation card fixture");
                    var scroll = new JScrollPane(order);
                    scroll.setBorder(BorderFactory.createEmptyBorder());
                    window.add(scroll);
                    Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                    window.setSize(UIScale.scale(620), Math.min(UIScale.scale(720), screen.height - 40));
                    window.setLocationRelativeTo(null);
                    window.setVisible(true);
                    window.toFront();
                    return window;
                });
                try {
                    var list = edt(() -> (JList<?>) named(pane.get(), "navigation.platform.list"));
                    var mods = edt(() -> (JList<?>) named(pane.get(), "navigation.mods.list"));
                    await(() -> list.isEnabled() && mods.isEnabled() && list.getModel().getSize() == 3);
                    var robot = new Robot();
                    robot.setAutoDelay(40);
                    robot.waitForIdle();
                    await(() -> frame.isActive());
                    edt(() -> {
                        capture(frame, "navigation-cards-" + theme + "-" + scale);
                        return null;
                    });
                    drag(robot, list, 0, list, 2, true);
                    var changed = List.of(initial.get(1), initial.get(2), initial.get(0));
                    await(() -> saved.get().equals(changed) && list.isEnabled());
                    assertEquals(changed, edt(() -> values(list)));
                    assertEquals(1, writes.get());

                    drag(robot, list, 0, mods, 0, true);
                    robot.waitForIdle();
                    assertEquals(changed, saved.get());
                    assertEquals(1, writes.get());
                    assertEquals(0, modWrites.get());

                    drag(robot, list, 2, list, 0, false);
                    await(() -> saved.get().equals(initial) && list.isEnabled());
                    delay.set(true);
                    fail.set(true);
                    drag(robot, list, 0, list, 2, true);
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertEquals(initial, edt(() -> values(list)), "An unconfirmed write must not reorder the visible cards");
                    assertFalse(edt(list::isEnabled));
                    released.countDown();
                    await(list::isEnabled);
                    assertEquals(initial, saved.get());
                    assertEquals(initial, edt(() -> values(list)));

                    edt(() -> {
                        pane.get().setLocked(true);
                        return null;
                    });
                    int beforeLock = writes.get();
                    assertFalse(edt(list::getDragEnabled));
                    drag(robot, list, 0, list, 2, true);
                    assertEquals(beforeLock, writes.get());
                    assertEquals(initial, edt(() -> values(list)), "Locking retains the card contents");
                    edt(() -> {
                        var choices = appearance.get();
                        frame.setContentPane(choices);
                        frame.setSize(UIScale.scale(620), UIScale.scale(350));
                        frame.validate();
                        capture(frame, "appearance-fields-" + theme);
                        return null;
                    });
                } finally {
                    released.countDown();
                    edt(() -> {
                        pane.get().close();
                        appearance.get().close();
                        frame.dispose();
                        return null;
                    });
                    await(() -> !gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner.hasActiveTasks());
                }
            }
    }

    private static void drag(Robot robot, JList<?> from, int source, JList<?> to, int target, boolean after) throws Exception {
        var original = edt(() -> values(from));
        boolean allowed = edt(() -> ((gold.debug.windowstolinux.app.ui.control.DesktopCardList<?>) from).canReorder());
        Point start = edt(() -> point(from, source, false, true));
        Point end = edt(() -> point(to, target, after, false));
        var diagnostics = edt(() -> new gold.debug.windowstolinux.app.ui.display.DesktopCardDragDiagnostics(from));
        robot.mouseMove(start.x, start.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        try {
            for (int step = 1; step <= 14; step++) {
                robot.mouseMove(start.x + (end.x - start.x) * step / 14, start.y + (end.y - start.y) * step / 14);
                if (step == 9 && from == to && allowed) {
                    robot.delay(140);
                    edt(() -> {
                        assertEquals(true, from.getClientProperty("desktop.cardDragActive"), diagnostics::describe);
                        assertEquals(original, values(from), "Drag preview must preserve the confirmed model");
                        capture((JFrame) SwingUtilities.getWindowAncestor(from), "navigation-drag-"
                            + UIManager.getLookAndFeel().getClass().getSimpleName() + "-" + source + "-" + target);
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

    private static Point point(JList<?> list, int index, boolean after, boolean center) {
        Point location = list.getLocationOnScreen();
        Rectangle bounds = list.getCellBounds(index, index);
        if (center) {
            var handle = ((gold.debug.windowstolinux.app.ui.control.DesktopCardList<?>) list).handleBounds(index);
            assertFalse(handle.isEmpty());
            return new Point(location.x + handle.x + handle.width / 2, location.y + handle.y + handle.height / 2);
        }
        return new Point(location.x + bounds.x + UIScale.scale(28),
            location.y + bounds.y + (center ? bounds.height / 2 : after ? bounds.height - 2 : 2));
    }

    private static List<?> values(JList<?> list) {
        return java.util.stream.IntStream.range(0, list.getModel().getSize()).mapToObj(list.getModel()::getElementAt).toList();
    }

    private static Component named(Component root, String name) {
        return components(root).filter(value -> name.equals(value.getName())).findFirst().orElseThrow();
    }

    private static Stream<Component> components(Component root) {
        return Stream.concat(Stream.of(root), root instanceof Container container
            ? Stream.of(container.getComponents()).flatMap(NavigationOrderPaneTest::components) : Stream.empty());
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (edt(condition::getAsBoolean)) return;
            Thread.sleep(25);
        }
        assertTrue(edt(condition::getAsBoolean));
    }

    private static void capture(JFrame frame, String name) throws Exception {
        frame.validate();
        var bitmap = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = bitmap.createGraphics();
        try {
            frame.paint(graphics);
        } finally {
            graphics.dispose();
        }
        Path file = Path.of("target/navigation-cards", name + "-" + System.getProperty("flatlaf.uiScale", "1.0") + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(bitmap, "png", file.toFile());
    }
}
