package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.control.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Original picker state contracts expressed by the active platform table and independent real editor. / 以活动平台表格及独立真实编辑器表达原选择状态合同。 */
class PlatformServerSelectionMigrationTest {
    @TempDir
    Path root;

    private ServerProfile first() {
        return new ServerProfile("first", "192.0.2.1", 22, "tester", "custom/key", "Production EU");
    }

    private ServerProfile second() {
        return new ServerProfile("second", "192.0.2.2", 22, "tester", "second/key", "Staging");
    }

    @Test
    void initialInventoryPublishesSelectionButRestoringDoesNotOverwriteUnsavedContext() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Actual platform selection migration requires the local desktop");
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            setup(runtime);
            var view = new AtomicReference<PlatformServersPane>();
            var frame = new AtomicReference<JFrame>();
            var editor = new AtomicReference<ServerProfileDialog>();
            var catalog = MessageCatalog.forLanguageTag("zh-CN");
            var messages = new PageMessagePresenter(catalog);
            try {
                create(runtime, catalog, view, frame);
                settled(view.get());
                onEdt(() -> {
                    assertEquals("Production EU", selected(table(view.get())));
                    var dialog = new ServerProfileDialog(frame.get(), runtime.servers(), new DesktopComponentFactory(ThemePalette.light()), messages, first(), ignored -> fail("Unsaved draft cannot publish"));
                    dialog.setModalityType(Dialog.ModalityType.MODELESS);
                    editor.set(dialog);
                    ((JTextField) named(dialog, "server.name")).setText("Unsaved draft");
                    dialog.setVisible(true);
                    table(view.get()).clearSelection();
                    ((JButton) named(view.get(), "servers.refresh")).doClick(0);
                    return null;
                });
                settled(view.get());
                onEdt(() -> {
                    assertEquals(-1, table(view.get()).getSelectedRow(), "Reload cannot undo an explicit cleared selection");
                    assertEquals("Unsaved draft", ((JTextField) named(editor.get(), "server.name")).getText());
                    assertEquals("192.0.2.1", ((JTextField) named(editor.get(), "server.host")).getText());
                    var table = table(view.get());
                    table.setRowSelectionInterval(1, 1);
                    ((JButton) named(view.get(), "servers.refresh")).doClick(0);
                    return null;
                });
                settled(view.get());
                onEdt(() -> {
                    assertEquals("Staging", selected(table(view.get())));
                    return null;
                });
                runtime.servers().delete(second().id());
                onEdt(() -> {
                    ((JButton) named(view.get(), "servers.refresh")).doClick(0);
                    return null;
                });
                settled(view.get());
                onEdt(() -> {
                    assertEquals(-1, table(view.get()).getSelectedRow(), "Missing original identity cannot silently select another server");
                    return null;
                });
                assertEquals("Production EU", runtime.servers().list().getFirst().profile().displayName());
                assertEquals(0, PlatformTaskAdmissionIntegrationTest.count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
            } finally {
                close(view, frame, editor);
            }
        }
    }

    @Test
    void searchesDisplayNamesAndAddressesCaseInsensitively() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Actual filtering requires the local desktop");
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            setup(runtime);
            var view = new AtomicReference<PlatformServersPane>();
            var frame = new AtomicReference<JFrame>();
            try {
                create(runtime, MessageCatalog.forLanguageTag("zh-CN"), view, frame);
                settled(view.get());
                onEdt(() -> {
                    var table = table(view.get());
                    var search = (JTextField) named(view.get(), "servers.search");
                    search.setText("DUCt");
                    assertEquals(1, table.getRowCount());
                    assertEquals("Production EU", selected(table));
                    search.setText("192.0");
                    assertEquals(2, table.getRowCount());
                    search.setText("  ");
                    assertEquals(2, table.getRowCount());
                    assertEquals("Production EU", selected(table));
                    search.setText("other");
                    assertEquals(0, table.getRowCount());
                    assertEquals(-1, table.getSelectedRow());
                    search.setText("staging");
                    assertEquals(1, table.getRowCount());
                    assertEquals(-1, table.getSelectedRow(), "Filtering out the original selection cannot select the visible other server");
                    table.setRowSelectionInterval(0, 0);
                    assertEquals("Staging", selected(table));
                    search.setText("");
                    assertEquals("Staging", selected(table));
                    table.getRowSorter().toggleSortOrder(0);
                    table.getRowSorter().toggleSortOrder(0);
                    assertEquals("Staging", selected(table));
                    ((JButton) named(view.get(), "servers.refresh")).doClick(0);
                    return null;
                });
                settled(view.get());
                onEdt(() -> {
                    assertEquals("Staging", selected(table(view.get())));
                    return null;
                });
                assertEquals(0, PlatformTaskAdmissionIntegrationTest.count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
            } finally {
                close(view, frame, new AtomicReference<>());
            }
        }
    }

    @Test
    void appearanceStatePreservesDisplayNameAndCustomCredentialReference() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Actual appearance and draft migration requires the local desktop");
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            setup(runtime);
            var view = new AtomicReference<PlatformServersPane>();
            var frame = new AtomicReference<JFrame>();
            var editor = new AtomicReference<ServerProfileDialog>();
            var catalog = MessageCatalog.forLanguageTag("zh-CN");
            var saved = new AtomicReference<ServerProfile>();
            var notifications = new AtomicInteger();
            byte[] before;
            try (var credential = runtime.persistence().security().find(first().credentialKey()).orElseThrow()) {
                before = credential.payload();
            }
            try {
                create(runtime, catalog, view, frame);
                settled(view.get());
                onEdt(() -> {
                    var dialog = new ServerProfileDialog(frame.get(), runtime.servers(), new DesktopComponentFactory(ThemePalette.light()), new PageMessagePresenter(catalog), first(), value -> {
                        saved.set(value);
                        notifications.incrementAndGet();
                    });
                    editor.set(dialog);
                    dialog.setModalityType(Dialog.ModalityType.MODELESS);
                    dialog.setVisible(true);
                    ((JTextField) named(dialog, "server.name")).setText("Unsaved Production EU");
                    for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                        DesktopThemeService.apply(theme);
                        catalog.language(theme == ThemeMode.LIGHT ? "zh-CN" : "en");
                        LocaleBinding.refresh(frame.get(), catalog);
                        LocaleBinding.refresh(dialog.getRootPane(), catalog);
                        assertSame(view.get(), frame.get().getContentPane());
                        assertEquals("Production EU", selected(table(view.get())));
                        assertEquals("Unsaved Production EU", ((JTextField) named(dialog, "server.name")).getText());
                        assertEquals("192.0.2.1", ((JTextField) named(dialog, "server.host")).getText());
                        assertEquals("tester", ((JTextField) named(dialog, "server.user")).getText());
                        assertEquals(0, ((JPasswordField) named(dialog, "server.password")).getPassword().length);
                    }
                    assertEquals(new PageMessagePresenter(catalog).text("server.edit"), dialog.getTitle());
                    ((JButton) named(dialog, "server.save")).doClick(0);
                    return null;
                });
                await(() -> saved.get() != null && !editor.get().isDisplayable());
                assertEquals(1, notifications.get());
                assertEquals("custom/key", saved.get().credentialKey());
                assertEquals("Unsaved Production EU", saved.get().displayName());
                assertEquals(saved.get(), runtime.servers().list().getFirst().profile());
                try (var credential = runtime.persistence().security().find("custom/key").orElseThrow()) {
                    assertArrayEquals(before, credential.payload());
                }
                assertEquals(0, PlatformTaskAdmissionIntegrationTest.count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
            } finally {
                close(view, frame, editor);
                onEdt(() -> {
                    DesktopThemeService.apply(ThemeMode.LIGHT);
                    return null;
                });
            }
        }
    }

    private void setup(PlatformRuntime runtime) throws Exception {
        PlatformServerServiceTest.setup(runtime);
        runtime.started();
        runtime.servers().saveServerProfile(first(), "fixture-secret".toCharArray());
        runtime.servers().saveServerProfile(second(), "other-secret".toCharArray());
    }

    private void create(PlatformRuntime runtime, MessageCatalog catalog, AtomicReference<PlatformServersPane> view, AtomicReference<JFrame> frame) throws Exception {
        onEdt(() -> {
            var window = new JFrame("Platform selection migration");
            var pane = new PlatformServersPane(window, runtime.servers(), new DesktopComponentFactory(ThemePalette.light()), new PageMessagePresenter(catalog));
            view.set(pane);
            frame.set(window);
            window.setContentPane(pane);
            window.setSize(880, 560);
            window.setVisible(true);
            pane.setLocked(false);
            return null;
        });
    }

    private static JTable table(PlatformServersPane pane) {
        return (JTable) named(pane, "servers.table");
    }

    private static String selected(JTable table) {
        return table.getSelectedRow() < 0 ? null : table.getValueAt(table.getSelectedRow(), 0).toString();
    }

    private static void settled(PlatformServersPane pane) throws Exception {
        await(() -> named(pane, "servers.refresh").isEnabled() && table(pane).getModel().getRowCount() > 0);
    }

    private static void close(AtomicReference<PlatformServersPane> view, AtomicReference<JFrame> frame, AtomicReference<ServerProfileDialog> editor) throws Exception {
        onEdt(() -> {
            if (editor.get() != null) editor.get().dispose();
            if (view.get() != null) view.get().close();
            if (frame.get() != null) frame.get().dispose();
            return null;
        });
        await(() -> !gold.debug.windowstolinux.app.ui.task.DesktopTaskExecutor.hasActiveTasks());
    }
}
