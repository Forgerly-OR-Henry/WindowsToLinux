package gold.debug.windowstolinux.app.main.start;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.server.PlatformServerService;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.control.DesktopCardTableOrder;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.server.PlatformServersPane;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PlatformServerOrderWindowTest {
    @TempDir
    Path root;

    @Test
    void handleDraggingPersistsOrderWithoutChangingSelectionAndBodyOrFilteredGesturesDoNotReorder() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            var frame = create(service);
            var pane = (PlatformServersPane) frame.getContentPane();
            try {
                await(() -> ready(pane));
                var originalModel = onEdt(() -> table(pane).getModel());
                var originalSorter = onEdt(() -> table(pane).getRowSorter());
                int fullWidth = onEdt(() -> named(pane, "servers.inventory").getWidth());
                onEdt(() -> {
                    assertEquals(-1, table(pane).getSelectedRow());
                    var handle = handle(pane, 0);
                    mouse(table(pane), MouseEvent.MOUSE_PRESSED, handle, true);
                    mouse(table(pane), MouseEvent.MOUSE_RELEASED, handle, false);
                    assertEquals(-1, table(pane).getSelectedRow(), "A handle click must not select the server");
                    drag(pane, 0, 2, true);
                    return null;
                });
                await(() -> ready(pane) && names(pane).equals(List.of("Server B", "Server C", "Server A")));
                assertEquals(List.of("b", "c", "a"), ids(service));
                onEdt(() -> {
                    assertSame(originalModel, table(pane).getModel());
                    assertSame(originalSorter, table(pane).getRowSorter());
                    assertEquals(-1, table(pane).getSelectedRow());
                    assertFalse(named(pane, "servers.details.scroll").isVisible());
                    assertEquals(fullWidth, named(pane, "servers.inventory").getWidth());
                    table(pane).setRowSelectionInterval(1, 1);
                    frame.validate();
                    assertEquals("Server C", ((JLabel) named(pane, "servers.details.title")).getText());
                    drag(pane, 1, 0, true);
                    return null;
                });
                await(() -> ready(pane) && names(pane).equals(List.of("Server C", "Server B", "Server A")));
                onEdt(() -> {
                    assertEquals(0, table(pane).getSelectedRow());
                    assertEquals("Server C", ((JLabel) named(pane, "servers.details.title")).getText());
                    assertTrue(named(pane, "servers.details.scroll").isVisible());
                    drag(pane, 0, 2, false);
                    assertEquals(List.of("Server C", "Server B", "Server A"), names(pane));
                    ((JTextField) named(pane, "servers.search")).setText("Server");
                    drag(pane, 0, 2, true);
                    assertEquals(List.of("Server C", "Server B", "Server A"), names(pane));
                    ((JTextField) named(pane, "servers.search")).setText("");
                    pane.setLocked(true);
                    drag(pane, 0, 2, true);
                    assertEquals(List.of("Server C", "Server B", "Server A"), names(pane));
                    pane.setLocked(false);
                    return null;
                });
                await(() -> ready(pane));
                assertEquals(List.of("c", "b", "a"), ids(service));
            } finally {
                close(pane, frame);
            }
        }
    }

    @Test
    void staleDragRestoresAuthoritativeOrderAndSelectedIdentity() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            var frame = create(service);
            var pane = (PlatformServersPane) frame.getContentPane();
            try {
                await(() -> ready(pane));
                onEdt(() -> {
                    table(pane).setRowSelectionInterval(1, 1);
                    frame.validate();
                    return null;
                });
                // Complete the detail pane's queued resize before starting a distinct pointer gesture.
                var destination = onEdt(() -> {
                    assertEquals("Server B", ((JLabel) named(pane, "servers.details.title")).getText());
                    var start = handle(pane, 0);
                    var end = new Point(start.x, table(pane).getCellRect(2, 0, true).y + table(pane).getRowHeight() / 2);
                    mouse(table(pane), MouseEvent.MOUSE_PRESSED, start, true);
                    mouse(table(pane), MouseEvent.MOUSE_DRAGGED, end, true);
                    assertEquals(Boolean.TRUE, table(pane).getClientProperty("desktop.cardDragActive"),
                        "The stale-order scenario requires an active handle drag");
                    return end;
                });
                service.reorderServerProfiles(List.of("a", "b", "c"), List.of("c", "a", "b"));
                onEdt(() -> {
                    assertEquals(Boolean.TRUE, table(pane).getClientProperty("desktop.cardDragActive"),
                        "An unrelated persisted order change must not cancel the current UI gesture");
                    assertEquals(List.of("Server A", "Server B", "Server C"), names(pane));
                    assertEquals("Server B", table(pane).getValueAt(table(pane).getSelectedRow(), 0));
                    mouse(table(pane), MouseEvent.MOUSE_RELEASED, destination, false);
                    return null;
                });
                await(() -> ready(pane) && names(pane).equals(List.of("Server C", "Server A", "Server B"))
                    && ((JLabel) named(pane, "servers.feedback")).getText().contains("已重新读取"));
                onEdt(() -> {
                    assertEquals("Server B", table(pane).getValueAt(table(pane).getSelectedRow(), 0));
                    assertEquals("Server B", ((JLabel) named(pane, "servers.details.title")).getText());
                    return null;
                });
                assertEquals(List.of("c", "a", "b"), ids(service));
            } finally {
                close(pane, frame);
            }
        }
    }

    private static PlatformServerService setup(PlatformRuntime runtime) throws Exception {
        var service = PlatformServerServiceTest.setup(runtime);
        runtime.started();
        for (String id : List.of("a", "b", "c"))
            service.saveServerProfile(new ServerProfile(id, "192.0.2.1", 22, "tester", "order/" + id,
                "Server " + id.toUpperCase(java.util.Locale.ROOT)), "isolated-order-secret".toCharArray());
        return service;
    }

    private static JFrame create(PlatformServerService service) throws Exception {
        return onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var frame = new JFrame("Server handle ordering");
            var pane = new PlatformServersPane(frame, service, new DesktopPartFactory(ThemePalette.light()),
                new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")));
            frame.setContentPane(pane);
            frame.setSize(1440, 810);
            frame.setVisible(true);
            pane.setLocked(false);
            return frame;
        });
    }

    private static boolean ready(PlatformServersPane pane) {
        return named(pane, "servers.refresh").isEnabled() && table(pane).getRowCount() == 3;
    }

    private static JTable table(PlatformServersPane pane) {
        return (JTable) named(pane, "servers.table");
    }

    private static List<String> names(PlatformServersPane pane) {
        var table = table(pane);
        return java.util.stream.IntStream.range(0, table.getRowCount()).mapToObj(row -> table.getValueAt(row, 0).toString()).toList();
    }

    private static List<String> ids(PlatformServerService service) throws Exception {
        return service.list().stream().map(summary -> summary.profile().id()).toList();
    }

    private static Point handle(PlatformServersPane pane, int row) throws Exception {
        var field = PlatformServersPane.class.getDeclaredField("order");
        field.setAccessible(true);
        var bounds = ((DesktopCardTableOrder<?>) field.get(pane)).handleBounds(row);
        assertFalse(bounds.isEmpty(), "A draggable server must have an actual handle");
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    private static void drag(PlatformServersPane pane, int source, int destination, boolean grip) throws Exception {
        var table = table(pane);
        var start = grip ? handle(pane, source)
            : new Point(table.getWidth() / 2, table.getCellRect(source, 0, true).y + table.getRowHeight() / 2);
        var end = new Point(start.x, table.getCellRect(destination, 0, true).y + table.getRowHeight() / 2);
        mouse(table, MouseEvent.MOUSE_PRESSED, start, true);
        mouse(table, MouseEvent.MOUSE_DRAGGED, end, true);
        mouse(table, MouseEvent.MOUSE_RELEASED, end, false);
    }

    private static void mouse(JTable table, int id, Point point, boolean down) {
        table.dispatchEvent(new MouseEvent(table, id, System.currentTimeMillis(), down ? InputEvent.BUTTON1_DOWN_MASK : 0,
            point.x, point.y, id == MouseEvent.MOUSE_DRAGGED ? 0 : 1, false,
            id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1));
    }

    private static void close(PlatformServersPane pane, JFrame frame) throws Exception {
        onEdt(() -> {
            pane.close();
            frame.dispose();
            return null;
        });
        await(() -> !DesktopTaskRunner.hasActiveTasks());
    }
}
