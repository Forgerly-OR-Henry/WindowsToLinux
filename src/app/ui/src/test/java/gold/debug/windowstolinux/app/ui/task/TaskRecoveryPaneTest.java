package gold.debug.windowstolinux.app.ui.task;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

import gold.debug.windowstolinux.app.service.task.TaskManagement;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.appapi.task.control.*;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.api.Test;

class TaskRecoveryPaneTest {
    static final UUID RUNNING = UUID.fromString("7d4a82c1-58b2-4e96-8f14-2166ac32b781");

    static final UUID WAITING = UUID.fromString("b481c2f7-9d36-42aa-92e4-6f105d85a9b2");

    static final UUID RECOVERY = UUID.fromString("c381c2f7-9d36-42aa-92e4-6f105d85a9b2");

    static TaskManagement.Entry entry(UUID id, TaskState state, TaskControlSnapshot.State control, String title, double fraction) {
        var snapshot = new TaskSnapshot(id, "gold.debug.windowstolinux.mod.deployment", "1.0.0", "deployment-publication",
            state, state == TaskState.ENDED ? Optional.of(TaskResult.SUCCEEDED) : Optional.empty(),
            state == TaskState.WAITING ? "等待确认发布 / Review publication" : "发布应用 / Publishing application",
            Instant.parse("2026-10-26T06:32:00Z"), state == TaskState.ENDED ? Optional.of(Instant.parse("2026-10-26T06:35:00Z")) : Optional.empty(), List.of());
        var controls = new TaskControlSnapshot(control, control == TaskControlSnapshot.State.RUNNING,
            control == TaskControlSnapshot.State.PAUSED, true, "");
        var availability = state == TaskState.UNKNOWN ? TaskManagement.RecoveryState.RECONCILE : TaskManagement.RecoveryState.NOT_READY;
        return new TaskManagement.Entry(snapshot, true, availability, title, TaskManagement.projection(snapshot,
            availability, fraction < 0 ? Optional.empty() : Optional.of(fraction), controls));
    }

    static class Service implements TaskManagement {
        volatile List<Entry> values = List.of(
            entry(RUNNING, TaskState.RUNNING, TaskControlSnapshot.State.RUNNING, "部署应用 · inventory-api", .6),
            entry(WAITING, TaskState.WAITING, TaskControlSnapshot.State.WAITING_USER, "审阅发布 · archive-service", -1),
            entry(RECOVERY, TaskState.RUNNING, TaskControlSnapshot.State.PAUSED, "部署应用 · survey-service", .4),
            entry(UUID.randomUUID(), TaskState.QUEUED, TaskControlSnapshot.State.UNAVAILABLE, "未开始", -1),
            entry(UUID.randomUUID(), TaskState.ENDED, TaskControlSnapshot.State.UNAVAILABLE, "已完成", 1));

        final AtomicInteger intervention = new AtomicInteger();

        final AtomicInteger cancelled = new AtomicInteger();

        public List<Entry> list() {
            assertFalse(SwingUtilities.isEventDispatchThread());
            return values;
        }

        public TaskSnapshot requestCancel(UUID id) {
            cancelled.incrementAndGet();
            throw new AssertionError();
        }

        public TaskSnapshot continueWaiting(UUID id) {
            throw new AssertionError("Intervention must not continue or approve");
        }

        public CompletionStage<TaskSnapshot> reconcile(UUID id, boolean confirmed) {
            throw new AssertionError();
        }

        public void control(UUID id, TaskCommand command) {
            assertFalse(SwingUtilities.isEventDispatchThread());
            if (command == TaskCommand.INTERVENE) {
                intervention.incrementAndGet();
                return;
            }
            values = values.stream().map(value -> value.snapshot().id().equals(id)
                ? entry(id, TaskState.RUNNING, command == TaskCommand.PAUSE ? TaskControlSnapshot.State.PAUSE_REQUESTED
                : TaskControlSnapshot.State.RUNNING, value.title(), value.view().fraction().orElse(-1.0)) : value).toList();
        }
    }

    static <T> T edt(Callable<T> action) throws Exception {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() != null) throw new RuntimeException(failure.get());
        return result.get();
    }

    static void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (DesktopTaskRunner.hasActiveTasks() && System.nanoTime() < deadline) Thread.sleep(10);
        edt(() -> null);
        assertFalse(DesktopTaskRunner.hasActiveTasks());
    }

    static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            var result = named(child, name);
            if (result != null) return result;
        }
        return null;
    }

    static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (var child : container.getComponents()) layout(child);
        }
    }

    static void select(TaskRecoveryPane pane, UUID id) {
        var list = (JList<?>) named(pane, "tasks.list");
        for (int i = 0; i < list.getModel().getSize(); i++)
            if (list.getModel().getElementAt(i) instanceof TaskManagement.Entry entry
                && entry.snapshot().id().equals(id)) {
                list.setSelectedIndex(i);
                return;
            }
        fail("Task is missing");
    }

    @Test
    void simpleCardsFilteringDetailsAndStateDependentControls() throws Exception {
        var service = new Service();
        var logs = new AtomicInteger();
        var pane = edt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var value = new TaskRecoveryPane(service, new DesktopPartFactory(ThemePalette.light()),
                new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")), logs::incrementAndGet);
            value.setSize(1280, 800);
            value.setLocked(false);
            return value;
        });
        try {
            idle();
            edt(() -> {
                layout(pane);
                assertEquals("3", ((JLabel) named(pane, "tasks.count")).getText());
                assertFalse(named(pane, "tasks.details").isVisible());
                assertNull(named(pane, "tasks.primary"));
                assertNull(named(pane, "tasks.progress"));
                assertTrue(named(pane, "tasks.inventory").getWidth() > 1200);
                select(pane, RUNNING);
                layout(pane);
                assertEquals("暂停任务", ((JButton) named(pane, "tasks.primary")).getText());
                assertEquals(600, ((JProgressBar) named(pane, "tasks.progress")).getValue());
                ((JButton) named(pane, "tasks.primary")).doClick(0);
                return null;
            });
            idle();
            edt(() -> {
                assertFalse(named(pane, "tasks.primary").isEnabled());
                assertTrue(((JTextArea) named(pane, "tasks.controlReason")).getText().contains("安全暂停"));
                select(pane, RECOVERY);
                assertEquals("继续任务", ((JButton) named(pane, "tasks.primary")).getText());
                assertTrue(named(pane, "tasks.primary").isEnabled());
                select(pane, WAITING);
                assertFalse(named(pane, "tasks.primary").isEnabled());
                assertTrue(((JProgressBar) named(pane, "tasks.progress")).isIndeterminate());
                ((JButton) named(pane, "tasks.intervene")).doClick(0);
                return null;
            });
            idle();
            assertEquals(1, service.intervention.get());
            assertEquals(0, service.cancelled.get());
            edt(() -> {
                ((JTextField) named(pane, "tasks.search")).setText("inventory");
                assertEquals("1", ((JLabel) named(pane, "tasks.count")).getText());
                assertFalse(named(pane, "tasks.details").isVisible());
                ((JButton) named(pane, "tasks.history")).doClick(0);
                pane.setLocked(true);
                assertEquals("0", ((JLabel) named(pane, "tasks.count")).getText());
                assertFalse(((JTextField) named(pane, "tasks.search")).isEnabled());
                return null;
            });
            assertEquals(1, logs.get());
        } finally {
            edt(() -> {
                pane.close();
                return null;
            });
            idle();
        }
    }

    @Test
    void unchangedEmptySnapshotDoesNotInvalidateItsWorkspace() throws Exception {
        var service = new Service();
        service.values = List.of();
        var pane = edt(() -> {
            var value = new TaskRecoveryPane(service, new DesktopPartFactory(ThemePalette.light()),
                new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")), () -> {
            });
            value.setLocked(false);
            return value;
        });
        RepaintManager previous = null;
        try {
            idle();
            var invalidations = new AtomicInteger();
            var repaints = new AtomicInteger();
            previous = edt(() -> {
                var workspace = (JComponent) SwingUtilities.getAncestorOfClass(DesktopSplitPane.class,
                    named(pane, "tasks.inventory"));
                assertNotNull(workspace);
                var original = RepaintManager.currentManager(pane);
                RepaintManager.setCurrentManager(new RepaintManager() {
                    @Override
                    public synchronized void addInvalidComponent(JComponent component) {
                        if (component == workspace) invalidations.incrementAndGet();
                        super.addInvalidComponent(component);
                    }

                    @Override
                    public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
                        if (component == workspace) repaints.incrementAndGet();
                        super.addDirtyRegion(component, x, y, width, height);
                    }
                });
                pane.refresh();
                return original;
            });
            idle();
            edt(() -> {
                assertEquals("0", ((JLabel) named(pane, "tasks.count")).getText());
                assertFalse(named(pane, "tasks.details").isVisible());
                assertEquals(0, invalidations.get());
                assertEquals(0, repaints.get());
                return null;
            });
        } finally {
            RepaintManager original = previous;
            edt(() -> {
                if (original != null) RepaintManager.setCurrentManager(original);
                pane.close();
                return null;
            });
            idle();
        }
    }

    @Test
    void refreshUpdatesOnlyChangedRowsAndPreservesExplicitSelectionAndLocalization() throws Exception {
        var service = new Service();
        var catalog = MessageCatalog.forLanguageTag("zh-CN");
        var pane = edt(() -> {
            var value = new TaskRecoveryPane(service, new DesktopPartFactory(ThemePalette.light()),
                new PageMessagePresenter(catalog), () -> {
            });
            value.setLocked(false);
            return value;
        });
        try {
            idle();
            var changes = new AtomicInteger();
            var structuralChanges = new AtomicInteger();
            var detail = edt(() -> {
                select(pane, RUNNING);
                var list = (JList<?>) named(pane, "tasks.list");
                list.getModel().addListDataListener(new ListDataListener() {
                    public void intervalAdded(ListDataEvent event) {
                        structuralChanges.incrementAndGet();
                    }

                    public void intervalRemoved(ListDataEvent event) {
                        structuralChanges.incrementAndGet();
                    }

                    public void contentsChanged(ListDataEvent event) {
                        changes.incrementAndGet();
                    }
                });
                var result = named(pane, "tasks.progressText");
                pane.refresh();
                return result;
            });
            idle();
            edt(() -> {
                assertSame(detail, named(pane, "tasks.progressText"));
                assertEquals(0, changes.get());
                assertEquals(0, structuralChanges.get());
                return null;
            });
            service.values = service.values.stream().map(value -> value.snapshot().id().equals(WAITING)
                ? entry(WAITING, TaskState.WAITING, TaskControlSnapshot.State.WAITING_USER, value.title(), .45)
                : value).toList();
            edt(() -> {
                pane.refresh();
                return null;
            });
            idle();
            edt(() -> {
                assertEquals(1, changes.get());
                assertEquals(0, structuralChanges.get());
                assertSame(detail, named(pane, "tasks.progressText"));
                pane.selectTask(WAITING);
                return null;
            });
            idle();
            edt(() -> {
                var list = (JList<?>) named(pane, "tasks.list");
                assertEquals(WAITING, ((TaskManagement.Entry) list.getSelectedValue()).snapshot().id());
                assertEquals(1, changes.get());
                assertEquals(0, structuralChanges.get());
                assertEquals(450, ((JProgressBar) named(pane, "tasks.progress")).getValue());
                catalog.language("en");
                pane.localize();
                assertEquals(WAITING, ((TaskManagement.Entry) list.getSelectedValue()).snapshot().id());
                assertTrue(named(pane, "tasks.details").isVisible());
                assertEquals("Continue task", ((JButton) named(pane, "tasks.primary")).getText());
                return null;
            });
        } finally {
            edt(() -> {
                pane.close();
                return null;
            });
            idle();
        }
    }

    @Test
    void actualSwingThemeLanguageScaleAndViewportMatrix() throws Exception {
        String target = System.getProperty("task.ui.screenshots");
        for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
            for (String locale : new String[]{"zh-CN", "en"})
                for (int scale : new int[]{100, 150})
                    for (var size : List.of(new Dimension(1440, 900), new Dimension(960, 640)))
                        for (boolean embedded : new boolean[]{false, true}) {
                            var pane = edt(() -> {
                                DesktopThemeService.install(theme, scale);
                                var p = new TaskRecoveryPane(new Service(), new DesktopPartFactory(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light()),
                                    new PageMessagePresenter(MessageCatalog.forLanguageTag(locale)), () -> {
                                });
                                p.setSize(size);
                                p.setLocked(false);
                                return p;
                            });
                            var frame = edt(() -> {
                                var value = new JFrame("Task UI validation");
                                value.setUndecorated(true);
                                if (embedded) {
                                    var tabs = new JTabbedPane();
                                    tabs.addTab("Mod tasks", pane);
                                    value.setContentPane(tabs);
                                } else value.setContentPane(pane);
                                value.setSize(size);
                                value.setVisible(true);
                                return value;
                            });
                            try {
                                idle();
                                edt(() -> {
                                    layout(pane);
                                    layout(pane);
                                    layout(pane);
                                    var toolbar = named(pane, "tasks.history").getParent();
                                    assertTrue(contains(named(pane, "tasks.inventory"), toolbar));
                                    assertTrue(named(pane, "tasks.history").getX() < named(pane, "tasks.reload").getX()
                                        || named(pane, "tasks.history").getY() < named(pane, "tasks.reload").getY());
                                    save(pane, target, theme + "-" + locale + "-" + scale + "-" + size.width + (embedded ? "-mod" : "") + "-list");
                                    select(pane, RUNNING);
                                    return null;
                                });
                                // Let deferred wrapping measurements finish before checking or capturing the actual window.
                                for (int pass = 0; pass < 3; pass++)
                                    edt(() -> {
                                        frame.validate();
                                        return null;
                                    });
                                edt(() -> {
                                    assertTrue(named(pane, "tasks.search").getHeight() > 0);
                                    assertTrue(named(pane, "tasks.details").isVisible());
                                    assertTrue(named(pane, "tasks.inventory").getBounds().getMaxX() <= named(pane, "tasks.details").getX());
                                    assertTrue(named(pane, "tasks.primary").getWidth() > 0);
                                    var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, named(pane, "tasks.progress"));
                                    assertEquals(0, viewport.getViewPosition().y, "Opening details must keep progress and controls visible");
                                    assertTextFits(named(pane, "tasks.details"));
                                    save(pane, target, theme + "-" + locale + "-" + scale + "-" + size.width + (embedded ? "-mod" : "") + "-detail");
                                    var list = (JList<Object>) named(pane, "tasks.list");
                                    Color surface = null;
                                    for (int i = 0; i < list.getModel().getSize(); i++)
                                        if (list.getModel().getElementAt(i) instanceof TaskManagement.Entry entry) {
                                            var cell = list.getCellRenderer().getListCellRendererComponent(list, entry, i, true, false);
                                            var card = named(cell, "tasks.card");
                                            if (surface == null) surface = card.getBackground();
                                            else assertEquals(surface, card.getBackground());
                                            assertNull(named(cell, "tasks.progress"));
                                            assertNull(named(cell, "tasks.primary"));
                                        }
                                    return null;
                                });
                            } finally {
                                edt(() -> {
                                    pane.close();
                                    frame.dispose();
                                    return null;
                                });
                                idle();
                            }
                        }
    }

    static boolean contains(Component root, Component child) {
        for (var current = child; current != null; current = current.getParent()) if (current == root) return true;
        return false;
    }

    static void assertTextFits(Component component) {
        if (component instanceof JTextArea area && !area.getText().isBlank())
            assertTrue(area.getHeight() >= area.getPreferredSize().height,
                () -> "Clipped task detail: " + area.getText());
        if (component instanceof Container parent) for (var child : parent.getComponents()) assertTextFits(child);
    }

    static void save(JComponent pane, String target, String name) throws Exception {
        if (target == null) return;
        Files.createDirectories(Path.of(target));
        var image = new BufferedImage(pane.getWidth(), pane.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        pane.printAll(g);
        g.dispose();
        ImageIO.write(image, "png", Path.of(target, name + ".png").toFile());
    }
}
