package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformModDeletionTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.count;
import static gold.debug.windowstolinux.app.main.startup.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;

import javax.swing.*;

import gold.debug.windowstolinux.appapi.task.Outcome;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModDeletionWindowTest {
    @TempDir
    Path root;

    PlatformModOriginalsTest ui;

    @BeforeEach
    void setup() throws Exception {
        ui = new PlatformModOriginalsTest();
        ui.root = root;
        ui.setup();
    }

    @AfterEach
    void cleanup() throws Exception {
        ui.cleanup();
    }

    Path installed() throws Exception {
        var original = ui.fixture.original("one.wtlmod", "1.0.0", "one");
        assertEquals(Outcome.SUCCEEDED, ui.installer.install(ui.installer.review(original)).outcome());
        acknowledged(ui.runtime);
        return original;
    }

    void window() throws Exception {
        ui.window(false);
        onEdt(() -> {
            var tabs = (JTabbedPane) named(ui.frame, "mods.management");
            tabs.setSelectedIndex(tabs.indexOfTab("已下载"));
            return null;
        });
        ui.idle();
    }

    String status() throws Exception {
        return onEdt(() -> ((JLabel) named(ui.frame, "mods.status")).getText());
    }

    @Test
    void confirmsActualFileScopeWithOriginalDeletionUncheckedAndCancelHasNoEffects() throws Exception {
        var original = installed();
        window();
        ui.click("mods.deleteVersion");
        var confirmation = ui.dialog();
        String details = onEdt(() -> ((JTextArea) named(confirmation, "mods.confirmation")).getText());
        assertTrue(details.contains("sample.bundle") && details.contains("1.0.0"));
        assertTrue(details.contains(ui.fixture.target("1.0.0").resolve("main/fixture.jar").toString()));
        assertTrue(details.contains(original.toString()));
        assertFalse(onEdt(() -> ((JCheckBox) named(confirmation, "mods.deleteOriginals")).isSelected()));
        onEdt(() -> {
            capture(confirmation, "platform-version-deletion-confirmation.png");
            return null;
        });
        ui.answer(confirmation, false);
        assertTrue(Files.exists(ui.fixture.target("1.0.0")));
        assertEquals(0, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        ui.click("mods.deleteVersion");
        ui.answer(ui.dialog(), true);
        assertEquals("所选版本删除完成。", status());
        assertFalse(Files.exists(ui.fixture.target("1.0.0")));
        assertTrue(Files.exists(original));
        assertEquals(0, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
    }

    @Test
    void explicitOriginalCheckboxDeletesOnlyReviewedMatchingArchive() throws Exception {
        var original = installed();
        var other = ui.fixture.original("other.wtlmod", "2.0.0", "two");
        window();
        ui.click("mods.deleteVersion");
        var confirmation = ui.dialog();
        onEdt(() -> {
            ((JCheckBox) named(confirmation, "mods.deleteOriginals")).setSelected(true);
            return null;
        });
        ui.answer(confirmation, true);
        assertEquals("所选版本删除完成。", status());
        assertFalse(Files.exists(original));
        assertTrue(Files.exists(other));
    }

    @Test
    void lockingInvalidatesDeletionConfirmationAndFileChangesRejectOldReview() throws Exception {
        installed();
        window();
        ui.click("mods.deleteVersion");
        var confirmation = ui.dialog();
        var lockedDialog = confirmation;
        ui.runtime.security().lock();
        await(() -> !lockedDialog.isShowing());
        assertTrue(Files.exists(ui.fixture.target("1.0.0")));
        ui.runtime.security().unlock(PlatformTaskAdmissionIntegrationTest.PASSWORD.toCharArray());
        ui.idle();
        await(() -> named(ui.frame, "mods.deleteVersion").isEnabled());
        ui.click("mods.deleteVersion");
        confirmation = ui.dialog();
        Files.writeString(ui.fixture.target("1.0.0").resolve("README.md"), "changed after review");
        ui.answer(confirmation, true);
        assertEquals("changed after review", Files.readString(ui.fixture.target("1.0.0").resolve("README.md")));
        assertEquals(0, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertNotEquals("所选版本删除完成。", status());
    }

    @Test
    void unknownFinalArchiveAppearsInInstalledPageAndExplicitContinueFinishesOriginal() throws Exception {
        installed();
        window();
        sql(ui.runtime,
                "ALTER TABLE runtime_log ADD CONSTRAINT fixture_delete_archive CHECK(event_code<>'MOD_VERSION_DELETED')");
        ui.click("mods.deleteVersion");
        ui.answer(ui.dialog(), true);
        assertEquals("删除仍待清理，请查看保留操作并继续原清理。", status());
        assertFalse(Files.exists(ui.fixture.target("1.0.0")));
        assertTrue(onEdt(() -> named(ui.frame, "mods.reconcileDeletion").isEnabled()));
        assertTrue(onEdt(() -> ((JTextArea) named(ui.frame, "mods.details")).getText()).contains("版本删除"));
        onEdt(() -> {
            capture(ui.frame, "platform-version-deletion-pending.png");
            return null;
        });
        sql(ui.runtime, "ALTER TABLE runtime_log DROP CONSTRAINT fixture_delete_archive");
        ui.click("mods.reconcileDeletion");
        ui.answer(ui.dialog(), true);
        assertEquals("所选版本删除完成。", status());
        assertEquals(0, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertEquals(1, count(ui.runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_VERSION_DELETED'"));
    }

    @Test
    void classCompositionDoesNotExposeInstalledDirectoryDeletion() throws Exception {
        ui.window(true);
        assertFalse(onEdt(() -> named(ui.frame, "mods.deleteVersion").isVisible()));
        assertFalse(onEdt(() -> named(ui.frame, "mods.reconcileDeletion").isVisible()));
    }
}
