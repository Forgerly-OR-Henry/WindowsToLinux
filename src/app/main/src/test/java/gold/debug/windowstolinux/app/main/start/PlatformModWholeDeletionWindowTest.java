package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModDeletionTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformModInstallationTest.OWNER;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.count;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.db.mod.ModNavigationPreferences;
import gold.debug.windowstolinux.app.ui.mod.ModNavigationPane;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey;
import gold.debug.windowstolinux.appapi.task.Outcome;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModWholeDeletionWindowTest {
    @TempDir
    Path root;

    PlatformModDeletionWindowTest fixture;

    PlatformModOriginalsTest ui;

    @BeforeEach
    void setup() throws Exception {
        fixture = new PlatformModDeletionWindowTest();
        fixture.root = root;
        fixture.setup();
        ui = fixture.ui;
    }

    @AfterEach
    void cleanup() throws Exception {
        fixture.cleanup();
    }

    Path privateFile() throws Exception {
        Path path = root.resolve("data/mod").resolve(OWNER).resolve("cache.bin");
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "reviewed private bytes");
    }

    int rows() throws Exception {
        return onEdt(() -> ((JList<?>) named(ui.frame, "mods.inventory")).getModel().getSize());
    }

    @Test
    void reviewsAllVersionsAndOwnedFilesAndCancelsBeforeDeletingWithOriginalsRetained() throws Exception {
        var original = fixture.installed();
        var second = ui.fixture.original("two.wtlmod", "2.0.0", "two");
        assertEquals(Outcome.SUCCEEDED, ui.installer.install(ui.installer.review(second)).outcome());
        acknowledged(ui.runtime);
        Path cache = privateFile();
        var backend = new PlatformModWholeDeletionTest();
        backend.schema(ui.runtime);
        backend.category(ui.runtime);
        try (var c = ui.runtime.persistence().database().openConnection()) {
            c.setAutoCommit(false);
            ModNavigationPreferences.write(c,
                java.util.List.of(new ModNavigationPreferences.PagePin(OWNER, "settings")));
            c.commit();
        }
        fixture.window();
        assertTrue(onEdt(
            () -> ((ModNavigationPane) named(ui.frame, "mods.navigation")).pinned(new PageKey(OWNER, "settings"))));
        ui.click("mods.deleteMod");
        var dialog = ui.dialog();
        String scope = onEdt(() -> ((JTextArea) named(dialog, "mods.confirmation")).getText());
        assertTrue(scope.contains("整个 " + OWNER));
        assertTrue(scope.contains("1.0.0, 2.0.0"));
        assertTrue(scope.contains("Schema 7"));
        assertTrue(scope.contains("runtime"));
        assertTrue(scope.contains("settings"));
        assertTrue(scope.contains(cache.toString()));
        assertTrue(scope.contains(original.toString()));
        assertFalse(onEdt(() -> ((JCheckBox) named(dialog, "mods.deleteOriginals")).isSelected()));
        onEdt(() -> {
            capture(dialog, "platform-whole-deletion-confirmation.png");
            return null;
        });
        ui.answer(dialog, false);
        assertTrue(Files.exists(cache));
        assertTrue(Files.exists(ui.fixture.target("2.0.0")));
        assertEquals(0, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        ui.click("mods.deleteMod");
        ui.answer(ui.dialog(), true);
        assertEquals("整个 Mod 删除完成。", fixture.status());
        assertEquals(0, rows());
        assertFalse(Files.exists(cache));
        assertFalse(Files.exists(ui.fixture.target("1.0.0")));
        assertFalse(Files.exists(ui.fixture.target("2.0.0")));
        assertTrue(Files.exists(original));
        assertTrue(Files.exists(second));
        assertTrue(ui.runtime.modIntents().pins().isEmpty());
        assertFalse(onEdt(
            () -> ((ModNavigationPane) named(ui.frame, "mods.navigation")).pinned(new PageKey(OWNER, "settings"))));
    }

    @Test
    void wholeDeletionCheckboxIncludesUninstalledOriginals() throws Exception {
        var original = fixture.installed();
        var second = ui.fixture.original("two.wtlmod", "2.0.0", "two");
        fixture.window();
        ui.click("mods.deleteMod");
        var dialog = ui.dialog();
        onEdt(() -> {
            ((JCheckBox) named(dialog, "mods.deleteOriginals")).setSelected(true);
            return null;
        });
        ui.answer(dialog, true);
        assertEquals("整个 Mod 删除完成。", fixture.status());
        assertFalse(Files.exists(original));
        assertFalse(Files.exists(second));
    }

    @Test
    void orphanedCleanupRemainsVisibleAcrossWindowAndRuntimeReopenAndUsesOriginalOperation() throws Exception {
        fixture.installed();
        fixture.window();
        sql(ui.runtime, "ALTER TABLE runtime_log ADD CONSTRAINT reject_whole_archive CHECK(event_code<>'MOD_DELETED')");
        ui.click("mods.deleteMod");
        ui.answer(ui.dialog(), true);
        assertEquals("删除仍待清理，请查看保留操作并继续原清理。", fixture.status());
        assertEquals(0, count(ui.runtime.persistence().database(),
            "SELECT COUNT(*) FROM mod_registry WHERE mod_id='" + OWNER + "'"));
        assertEquals(1, rows());
        var pending = ui.runtime.modIntents().list().getFirst();
        var id = pending.operations().getFirst().id();
        assertEquals("UNREGISTERED", pending.runtime());
        onEdt(() -> {
            assertFalse(named(ui.frame, "mods.enabled").isEnabled());
            assertFalse(named(ui.frame, "mods.deleteMod").isEnabled());
            assertFalse(named(ui.frame, "mods.deleteVersion").isEnabled());
            assertTrue(named(ui.frame, "mods.reconcileDeletion").isEnabled());
            String details = ((JTextArea) named(ui.frame, "mods.details")).getText();
            assertTrue(details.contains("整 Mod 删除"));
            assertTrue(details.contains("登记已移除"));
            capture(ui.frame, "platform-whole-deletion-pending.png");
            return null;
        });
        // Remove the injected schema fault before reopening the formally validated database.
        // 重启前移除注入的结构故障；持久化的待清理操作仍须跨窗口和运行时保留。
        sql(ui.runtime, "ALTER TABLE runtime_log DROP CONSTRAINT reject_whole_archive");
        ui.cleanup();
        ui.frame = null;
        ui.runtime = ui.fixture.runtime();
        ui.installer = ui.fixture.installer(ui.runtime);
        fixture.window();
        assertEquals(1, rows());
        assertEquals(id, ui.runtime.modIntents().list().getFirst().operations().getFirst().id());
        ui.click("mods.reconcileDeletion");
        ui.answer(ui.dialog(), false);
        assertEquals(1, rows());
        ui.click("mods.reconcileDeletion");
        ui.answer(ui.dialog(), true);
        assertEquals("整个 Mod 删除完成。", fixture.status());
        assertEquals(0, rows());
        assertEquals(0, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertEquals(1, count(ui.runtime.persistence().database(),
            "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_DELETED' AND operation_id='" + id + "'"));
    }

    @Test
    void lockDisposesWholeConfirmationAndChangedPrivateFilesCannotUseItsEarlierReview() throws Exception {
        fixture.installed();
        Path cache = privateFile();
        fixture.window();
        ui.click("mods.deleteMod");
        var dialog = ui.dialog();
        var oldOption = onEdt(() -> (JOptionPane) descendants(dialog).filter(JOptionPane.class::isInstance).findFirst()
            .orElseThrow());
        ui.runtime.security().lock();
        await(() -> !dialog.isShowing());
        onEdt(() -> {
            oldOption.setValue(JOptionPane.OK_OPTION);
            return null;
        });
        assertTrue(Files.exists(cache));
        ui.runtime.security().unlock(PlatformTaskAdmissionIntegrationTest.PASSWORD.toCharArray());
        ui.idle();
        await(() -> named(ui.frame, "mods.deleteMod").isEnabled());
        ui.click("mods.deleteMod");
        var changed = ui.dialog();
        Files.writeString(cache, "unreviewed replacement bytes");
        ui.answer(changed, true);
        assertEquals("unreviewed replacement bytes", Files.readString(cache));
        assertEquals(0, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertNotEquals("整个 Mod 删除完成。", fixture.status());
    }

    @Test
    void emptyRegisteredOwnerCanBeDeletedWithoutSelectingANonexistentVersion() throws Exception {
        var original = fixture.installed();
        assertEquals(Outcome.SUCCEEDED,
            ui.installer.deletion().delete(ui.installer.deletion().review(OWNER, "1.0.0"), false).outcome());
        fixture.window();
        assertFalse(onEdt(() -> named(ui.frame, "mods.deleteVersion").isEnabled()));
        assertTrue(onEdt(() -> named(ui.frame, "mods.deleteMod").isEnabled()));
        ui.click("mods.deleteMod");
        ui.answer(ui.dialog(), true);
        assertEquals("整个 Mod 删除完成。", fixture.status());
        assertEquals(0, rows());
        assertTrue(Files.exists(original));
    }
}
