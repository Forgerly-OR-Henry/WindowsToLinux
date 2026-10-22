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
import gold.debug.windowstolinux.app.service.ai.AiModelListAccess;
import gold.debug.windowstolinux.app.service.ai.AiModelProfile;
import gold.debug.windowstolinux.app.service.ai.AiModelInfo;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityCheck;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityStatus;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import org.junit.jupiter.api.Test;

class AiModelListTest {
    private final DesktopPartFactory c = new DesktopPartFactory(ThemePalette.light());

    private final PageMessagePresenter messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));

    private AiModelInfo model(String id, int order) {
        return new AiModelInfo(new AiModelProfile(id, URI.create("https://example.test/v1/chat/completions"),
            "model-" + id, "ai/" + id), id, order, 1, Optional.of(Instant.now()), Optional.of(Instant.now()));
    }

    @Test
    void actualCardDraggingKeepsInventoryPersistenceSeparateFromPurposeDraft() throws Exception {
        for (var theme : gold.debug.windowstolinux.app.ui.display.ThemeMode.values())
            for (int scale : new int[]{100, 150}) {
                if (theme == gold.debug.windowstolinux.app.ui.display.ThemeMode.SYSTEM) continue;
                var values = new AtomicReference<>(List.of(model("first", 0), model("second", 1), model("third", 2)));
                var saved = new AtomicReference<>(List.of(new AiPurposeAssignment("first", true),
                    new AiPurposeAssignment("second", true), new AiPurposeAssignment("third", true)));
                var saves = new AtomicInteger();
                AiModelListAccess service = (AiModelListAccess) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{AiModelListAccess.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "listAiConfigurations" -> values.get();
                        case "listAiPurpose" -> args[0] == AiPurposeType.APPROVAL ? saved.get() : List.of();
                        case "reorderAiProviders" -> {
                            var prior = values.get();
                            values.set(((List<String>) args[0]).stream().map(id -> prior.stream()
                                .filter(value -> value.profile().id().equals(id)).findFirst().orElseThrow()).toList());
                            yield null;
                        }
                        case "saveAiPurpose" -> {
                            saves.incrementAndGet();
                            saved.set((List<AiPurposeAssignment>) args[1]);
                            yield null;
                        }
                        default -> throw new AssertionError(method);
                    });
                var frame = edt(() -> new JFrame());
                var pane = edt(() -> {
                    gold.debug.windowstolinux.app.ui.display.DesktopThemeService.install(theme, scale);
                    var view = new AiModelListPane(service, new DesktopPartFactory(theme == gold.debug.windowstolinux.app.ui.display.ThemeMode.LIGHT
                        ? ThemePalette.light() : ThemePalette.dark()), messages, ignored -> {
                    });
                    frame.setContentPane(view);
                    frame.setSize(1000, 660);
                    frame.setLocation(100, 80);
                    frame.setAlwaysOnTop(true);
                    frame.setVisible(true);
                    view.refresh();
                    return view;
                });
                var robot = new Robot();
                try {
                    idle();
                    JList<String> inventory = field(pane, "inventory", JList.class);
                    drag(robot, inventory, 0, inventory, 2, "inventory-" + theme + "-" + scale);
                    idle();
                    assertEquals(List.of("second", "third", "first"), values.get().stream().map(value -> value.profile().id()).toList());
                    edt(() -> {
                        pane.open(AiPurposeType.APPROVAL);
                        frame.validate();
                        return null;
                    });
                    JList<String> selected = field(pane, "selected", JList.class);
                    drag(robot, selected, 0, selected, 2, "purpose-" + theme + "-" + scale);
                    assertEquals(List.of("second", "third", "first"), edt(() -> pane.capture().draft().stream().map(AiPurposeAssignment::profileId).toList()));
                    assertEquals(0, saves.get(), "Dragging purpose cards only edits the existing draft");
                    var before = edt(() -> pane.capture().draft());
                    drag(robot, inventory, 0, selected, 1, "cross-list-" + theme + "-" + scale);
                    assertEquals(before, edt(() -> pane.capture().draft()), "Cards must not transfer between lists");
                    edt(() -> {
                        button(pane, "ai.purpose.save").doClick();
                        return null;
                    });
                    idle();
                    assertEquals(1, saves.get());
                    assertEquals(List.of("second", "third", "first"), saved.get().stream().map(AiPurposeAssignment::profileId).toList());
                } finally {
                    edt(() -> {
                        pane.close();
                        frame.dispose();
                        return null;
                    });
                }
            }
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

    @Test
    void oldLockedQueryCannotRenderOrClearNewRequestAndClosedViewRejectsCompletion() throws Exception {
        var gates = List.of(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        var entered = List.of(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        var calls = new AtomicInteger();
        AiModelListAccess service = (AiModelListAccess) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{AiModelListAccess.class}, (proxy, method, args) -> switch (method.getName()) {
                case "listAiConfigurations" -> {
                    int call = calls.getAndIncrement();
                    entered.get(call).countDown();
                    assertTrue(gates.get(call).await(8, TimeUnit.SECONDS));
                    yield List.of(model("query-" + call, 0));
                }
                case "listAiPurpose" -> List.of();
                default -> throw new AssertionError(method);
            });
        var pane = new AtomicReference<AiModelListPane>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                pane.set(new AiModelListPane(service, c, messages, ignored -> {
                }));
                pane.get().refresh();
            });
            assertTrue(entered.get(0).await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                pane.get().setLocked(true);
                pane.get().setLocked(false);
                pane.get().refresh();
            });
            assertTrue(entered.get(1).await(3, TimeUnit.SECONDS));
            gates.get(0).countDown();
            Thread.sleep(150);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(field(pane.get(), "inventory", JList.class).isEnabled());
                assertEquals(0, field(pane.get(), "inventory", JList.class).getModel().getSize());
            });
            gates.get(1).countDown();
            idle();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("query-1", field(pane.get(), "inventory", JList.class).getModel().getElementAt(0));
                pane.get().refresh();
            });
            assertTrue(entered.get(2).await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> pane.get().close());
            gates.get(2).countDown();
            idle();
            SwingUtilities.invokeAndWait(() -> assertEquals(0,
                field(pane.get(), "inventory", JList.class).getModel().getSize()));
        } finally {
            gates.forEach(CountDownLatch::countDown);
            idle();
            if (pane.get() != null) SwingUtilities.invokeAndWait(() -> pane.get().close());
        }
    }

    @Test
    void inventoryRemainsVisibleAndPurposeDraftDoesNotChangeDisplayOrder() throws Exception {
        var values = new AtomicReference<>(List.of(model("first", 0), model("second", 1), model("third", 2)));
        var roles = new EnumMap<AiPurposeType, List<AiPurposeAssignment>>(AiPurposeType.class);
        for (var role : AiPurposeType.values())
            roles.put(role, List.of());
        var failSave = new AtomicBoolean();
        AiModelListAccess service = (AiModelListAccess) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{AiModelListAccess.class}, (p, m, a) -> {
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
        AtomicReference<AiModelListPane> pane = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            pane.set(new AiModelListPane(service, c, messages, v -> {
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
    }

    @Test
    void clearingBothSelectionsPreservesPurposeDraftAcrossRefreshWithoutWriting() throws Exception {
        var writes = new AtomicInteger();
        AiModelListAccess service = (AiModelListAccess) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{AiModelListAccess.class}, (p, m, a) -> switch (m.getName()) {
                case "listAiConfigurations" -> List.of(model("first", 0), model("second", 1));
                case "listAiPurpose" -> List.of(new AiPurposeAssignment("first", true));
                default -> {
                    writes.incrementAndGet();
                    throw new AssertionError(m);
                }
            });
        var pane = new AtomicReference<AiModelListPane>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                FlatLightLaf.setup();
                pane.set(new AiModelListPane(service, c, messages, ignored -> {
                }));
                pane.get().refresh();
            });
            idle();
            SwingUtilities.invokeAndWait(() -> {
                var root = pane.get();
                root.open(AiPurposeType.APPROVAL);
                var inventory = field(root, "inventory", JList.class);
                var purpose = field(root, "selected", JList.class);
                inventory.setSelectedIndex(1);
                field(root, "add", JButton.class).doClick();
                purpose.setSelectedIndex(1);
                inventory.clearSelection();
                purpose.clearSelection();
                assertFalse(field(root, "add", JButton.class).isEnabled());
                assertFalse(field(root, "remove", JButton.class).isEnabled());
                assertFalse(field(root, "enabled", JCheckBox.class).isEnabled());
                root.refresh();
            });
            idle();
            SwingUtilities.invokeAndWait(() -> {
                var state = pane.get().capture();
                assertEquals(Optional.of(AiPurposeType.APPROVAL), state.purpose());
                assertEquals(List.of("first", "second"), state.draft().stream().map(AiPurposeAssignment::profileId).toList());
                assertEquals("", state.selected());
                assertEquals("", state.selectedPurpose());
                assertEquals(0, writes.get());
            });
        } finally {
            if (pane.get() != null) SwingUtilities.invokeAndWait(() -> pane.get().close());
        }
    }

    @Test
    void inlineColumnsRemainEqualAndRestoreFullWidthInBothThemes() throws Exception {
        AiModelListAccess service = (AiModelListAccess) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{AiModelListAccess.class}, (p, m, a) -> switch (m.getName()) {
                case "listAiConfigurations" -> List.of(model("deployment", 0), model("review", 1), model("vision", 2));
                case "listAiPurpose" -> List.of(new AiPurposeAssignment("review", true));
                default -> throw new AssertionError(m);
            });
        for (boolean dark : List.of(false, true)) {
            var pane = new AtomicReference<AiModelListPane>();
            SwingUtilities.invokeAndWait(() -> {
                if (dark)
                    com.formdev.flatlaf.FlatDarkLaf.setup();
                else
                    FlatLightLaf.setup();
                pane.set(new AiModelListPane(service,
                    new DesktopPartFactory(dark ? ThemePalette.dark() : ThemePalette.light()), messages, v -> {
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

    @Test
    void pureVoiceRowsShowIndependentLocalizedStatesAndRequireAtLeastOneVerifiedVoiceMode() throws Exception {
        var now = Optional.of(Instant.now());
        var file = new AiModelInfo(new AiModelProfile("file", null, "voice", "ai/file",
            URI.create("https://example.test/transcriptions"), null), "<file>", 0, 1,
            Optional.empty(), Optional.empty(), now, Optional.empty());
        var live = new AiModelInfo(new AiModelProfile("live", null, "voice", "ai/live",
            null, URI.create("wss://example.test/realtime")), "Live", 1, 1,
            Optional.empty(), Optional.empty(), Optional.empty(), now);
        var rejected = new AiModelInfo(new AiModelProfile("rejected", null, "voice", "ai/rejected",
            URI.create("https://example.test/transcriptions"), null), "Rejected", 2, 1,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Map.of(AiCapabilityType.VOICE_FILE, new AiCapabilityCheck(AiCapabilityStatus.UNSUPPORTED,
                "MODEL_PROBE_UNSUPPORTED", now)));
        var rows = List.of(file, live, rejected);
        AiModelListAccess service = (AiModelListAccess) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{AiModelListAccess.class}, (p, m, a) -> switch (m.getName()) {
                case "listAiConfigurations" -> rows;
                case "listAiPurpose" -> List.of();
                default -> throw new AssertionError(m);
            });
        for (String language : List.of("zh-CN", "en")) {
            var localized = new PageMessagePresenter(MessageCatalog.forLanguageTag(language));
            var pane = new AtomicReference<AiModelListPane>();
            SwingUtilities.invokeAndWait(() -> {
                pane.set(new AiModelListPane(service, c, localized, ignored -> {
                }));
                pane.get().refresh();
            });
            idle();
            SwingUtilities.invokeAndWait(() -> {
                pane.get().open(AiPurposeType.VOICE);
                JList<String> inventory = field(pane.get(), "inventory", JList.class);
                inventory.setSelectedIndex(0);
                assertTrue(field(pane.get(), "add", JButton.class).isEnabled());
                JLabel label = rowLabel(inventory, "file", 0);
                assertTrue(label.getText().contains("&lt;file&gt;"));
                assertTrue(label.getText().contains(localized.text("ai.check.AVAILABLE")));
                assertTrue(label.getText().contains(localized.text("ai.check.UNVERIFIED")));
                inventory.setSelectedIndex(1);
                assertTrue(field(pane.get(), "add", JButton.class).isEnabled());
                inventory.setSelectedIndex(2);
                assertFalse(field(pane.get(), "add", JButton.class).isEnabled());
                label = rowLabel(inventory, "rejected", 2);
                assertTrue(label.getText().contains(localized.text("ai.check.UNSUPPORTED")));
                assertTrue(label.getToolTipText().contains(localized.text("ai.check.reason.MODEL_PROBE_UNSUPPORTED")));
            });
        }
    }

    private static JLabel rowLabel(JList<String> list, String id, int index) {
        var card = (Container) list.getCellRenderer().getListCellRendererComponent(list, id, index, false, false);
        return descendants(card).filter(JLabel.class::isInstance).map(JLabel.class::cast)
            .filter(label -> label.getText() != null && !label.getText().isBlank()).findFirst().orElseThrow();
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
        while (DesktopTaskRunner.hasActiveTasks() && System.nanoTime() < end)
            Thread.sleep(10);
        assertFalse(DesktopTaskRunner.hasActiveTasks());
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    private static Stream<Component> descendants(Container root) {
        return Stream.of(root.getComponents()).flatMap(
            v -> v instanceof Container nested ? Stream.concat(Stream.of(v), descendants(nested)) : Stream.of(v));
    }
}
