package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.mod.PlatformModsPane;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real management windows over isolated persistent state, without restarting this test JVM. / 基于隔离持久状态的真实管理窗口，不重启测试JVM。 */
class ModWorkspaceWindowTest {
    @TempDir
    Path root;

    private PlatformModManagementTest ui;

    private void twoMods(boolean enabled, boolean dependent) throws Exception {
        ui = new PlatformModManagementTest();
        ui.root = root;
        var setup = new PlatformModIntentsTest();
        setup.root = root;
        ui.runtime = setup.runtime();
        add(ui.runtime, P, enabled, Map.of(), null);
        add(ui.runtime, C, enabled, dependent ? Map.of(P, VERSION) : Map.of(), null);
        release(ui.runtime, P, "2.0.0", Map.of(), null, List.of());
        ui.window();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (ui != null) ui.cleanup();
    }

    @Test
    void emptySelectionAndLeavingDiscardEditsWhileSameOwnerRefreshKeepsTheVisibleDraft() throws Exception {
        twoMods(false, false);
        assertNull(onEdt(() -> ((JList<?>) named(ui.frame, "mods.inventory")).getSelectedValue()));
        ui.select(P);
        ui.click("mods.enabled");
        ui.click("mods.version.2.0.0");
        ui.click("mods.refresh");
        ui.idle();
        assertTrue(onEdt(() -> ((JCheckBox) named(ui.frame, "mods.enabled")).isSelected()));
        assertEquals("2.0.0", version());
        assertEquals("FALSE", text(ui.runtime, "SELECT enabled FROM mod_registry WHERE mod_id='" + P + "'"));
        ui.select(C);
        assertFalse(onEdt(() -> ((JCheckBox) named(ui.frame, "mods.enabled")).isSelected()));
        ui.select(P);
        assertFalse(onEdt(() -> ((JCheckBox) named(ui.frame, "mods.enabled")).isSelected()));
        assertEquals(VERSION, version());
        ui.click("mods.enabled");
        ui.click("mods.collapse");
        await(() -> ((JList<?>) named(ui.frame, "mods.inventory")).getSelectedValue() == null);
        ui.select(P);
        assertFalse(onEdt(() -> ((JCheckBox) named(ui.frame, "mods.enabled")).isSelected()));
        assertEquals(0, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
    }

    @Test
    void saveOnlyAffectsCurrentModAndPendingRowsUndoIndependentRequests() throws Exception {
        twoMods(false, false);
        ui.select(P);
        ui.click("mods.enabled");
        ui.click("mods.version.2.0.0");
        ui.select(C);
        saveEnabled();
        assertEquals("FALSE", text(ui.runtime, "SELECT enabled FROM mod_registry WHERE mod_id='" + P + "'"));
        assertEquals(VERSION, text(ui.runtime, "SELECT selected_version FROM mod_registry WHERE mod_id='" + P + "'"));
        assertEquals(List.of(C), ui.runtime.modIntents().pendingChanges().stream().map(value -> value.owner()).toList());
        ui.select(P);
        saveEnabled();
        var pending = ui.runtime.modIntents().pendingChanges();
        assertEquals(2, pending.size());
        assertEquals(2, pending.stream().map(value -> value.requestId()).distinct().count());
        ui.click("mods.viewChanges");
        var changes = ui.dialog();
        assertEquals("mods.changes", changes.getName());
        assertTrue(onEdt(() -> ((JTextArea) named(changes, "mods.change." + P)).getText().contains("未启用 → 已启用")));
        assertTrue(onEdt(() -> ((JTextArea) named(changes, "mods.change." + C)).getText().contains("未启用 → 已启用")));
        SwingUtilities.invokeLater(() -> ((AbstractButton) named(changes, "mods.undo." + P)).doClick(0));
        var review = ui.dialog();
        assertTrue(ui.confirmation(review).contains(P));
        assertFalse(ui.confirmation(review).contains(C));
        ui.answer(review, true);
        ui.idle();
        assertEquals(List.of(C), ui.runtime.modIntents().pendingChanges().stream().map(value -> value.owner()).toList());
        assertEquals("FALSE", text(ui.runtime, "SELECT enabled FROM mod_registry WHERE mod_id='" + P + "'"));
        assertEquals("TRUE", text(ui.runtime, "SELECT enabled FROM mod_registry WHERE mod_id='" + C + "'"));
    }

    @Test
    void undoFromOnePendingModReviewsAndRestoresItsEntireDependencyGroup() throws Exception {
        twoMods(true, true);
        ui.select(P);
        saveEnabled();
        var pending = ui.runtime.modIntents().pendingChanges();
        assertEquals(2, pending.size());
        assertEquals(1, pending.stream().map(value -> value.requestId()).distinct().count());
        assertEquals(0, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry WHERE enabled"));
        var review = undo(C);
        assertTrue(ui.confirmation(review).contains(P));
        assertTrue(ui.confirmation(review).contains(C));
        ui.answer(review, false);
        ui.idle();
        assertEquals(2, ui.runtime.modIntents().pendingChanges().size());
        review = undo(C);
        ui.answer(review, true);
        ui.idle();
        assertTrue(ui.runtime.modIntents().pendingChanges().isEmpty());
        assertEquals(2, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry WHERE enabled"));
        assertEquals(2, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry WHERE last_runtime_state='RUNNING'"));
    }

    @Test
    void sameChoicePreparationStillAppearsAndUndoDoesNotDisableTheModOrInvokeAuthorCode() throws Exception {
        ui = new PlatformModManagementTest();
        ui.root = root;
        ui.initial = new PlatformModInitializationTest();
        ui.initial.root = root;
        ui.initial.setup();
        ui.initial.module("valid", false);
        ui.fixture = ui.initial.fixture;
        ui.runtime = ui.fixture.open(true);
        assertEquals("MOD_DATA_NOT_READY", ui.fixture.start(ui.runtime).problems().getFirst().eventCode());
        ui.window();
        ui.select(P);
        ui.click("mods.prepare");
        ui.answer(ui.dialog(), true);
        ui.idle();
        var pending = ui.runtime.modIntents().pendingChanges().getFirst();
        assertEquals(pending.before(), pending.after());
        assertTrue(pending.preparation().startsWith("INITIALIZE · "));
        ui.click("mods.viewChanges");
        var changes = ui.dialog();
        String text = onEdt(() -> ((JTextArea) named(changes, "mods.change." + P)).getText());
        assertTrue(text.contains("准备当前版本数据"));
        assertTrue(text.contains("初始化"));
        assertFalse(text.contains("INITIALIZE"));
        SwingUtilities.invokeLater(() -> ((AbstractButton) named(changes, "mods.undo." + P)).doClick(0));
        ui.answer(ui.dialog(), true);
        ui.idle();
        assertTrue(ui.runtime.modIntents().pendingChanges().isEmpty());
        assertEquals("TRUE", PlatformModIntentsTest.text(ui.runtime, "SELECT enabled FROM mod_registry"));
        assertEquals(0, count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
        assertTrue(ui.fixture.actions().isEmpty());
    }

    @Test
    void failedSaveNeverCallsHostRestartAndPendingOnlyRestartRequiresItsOwnReview() throws Exception {
        twoMods(false, false);
        var restarts = new AtomicInteger();
        onEdt(() -> {
            ((PlatformModsPane) named(ui.frame, "platform.mods")).workspaceActions(() -> {
            }, () -> {
            }, ignored -> {
            }, restarts::incrementAndGet);
            return null;
        });
        ui.select(P);
        ui.click("mods.enabled");
        ui.click("mods.saveRestart");
        var stale = ui.dialog();
        sql(ui.runtime.persistence().database(), "UPDATE mod_registry SET revision=revision+1 WHERE mod_id=?", P);
        ui.answer(stale, true);
        ui.idle();
        assertEquals(0, restarts.get());
        assertTrue(ui.runtime.modIntents().pendingChanges().isEmpty());
        ui.click("mods.save");
        ui.answer(ui.dialog(), true);
        ui.idle();
        ui.click("mods.collapse");
        await(() -> ((JList<?>) named(ui.frame, "mods.inventory")).getSelectedValue() == null);
        ui.click("mods.saveRestart");
        var restartReview = ui.dialog();
        assertTrue(ui.confirmation(restartReview).contains(P));
        ui.answer(restartReview, false);
        ui.idle();
        assertEquals(0, restarts.get());
        ui.click("mods.saveRestart");
        ui.answer(ui.dialog(), true);
        ui.idle();
        assertEquals(1, restarts.get());
        assertEquals(1, ui.runtime.modIntents().pendingChanges().size());
    }

    private void saveEnabled() throws Exception {
        ui.click("mods.enabled");
        ui.click("mods.save");
        ui.answer(ui.dialog(), true);
        ui.idle();
    }

    private String version() throws Exception {
        return onEdt(() -> (String) ((JComboBox<?>) named(ui.frame, "mods.versions")).getSelectedItem());
    }

    private JDialog undo(String owner) throws Exception {
        ui.click("mods.viewChanges");
        var changes = ui.dialog();
        SwingUtilities.invokeLater(() -> ((AbstractButton) named(changes, "mods.undo." + owner)).doClick(0));
        return ui.dialog();
    }
}
