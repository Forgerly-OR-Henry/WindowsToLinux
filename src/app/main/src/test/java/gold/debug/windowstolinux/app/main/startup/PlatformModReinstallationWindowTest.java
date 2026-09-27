package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformModDeletionTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformModInstallationTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformModIntentsTest.text;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.count;
import static gold.debug.windowstolinux.app.main.startup.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

import javax.swing.*;

import com.sun.jna.platform.win32.*;
import gold.debug.windowstolinux.app.windows.filesystem.WindowsFileIdentity;
import gold.debug.windowstolinux.appapi.task.Outcome;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModReinstallationWindowTest {
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

    String status() throws Exception {
        return fixture.status();
    }

    long operations() throws Exception {
        return count(ui.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation");
    }

    void answerStep(JDialog dialog, boolean yes) throws Exception {
        onEdt(() -> {
            ((JOptionPane) descendants(dialog).filter(JOptionPane.class::isInstance).findFirst().orElseThrow())
                    .setValue(yes ? JOptionPane.OK_OPTION : JOptionPane.CANCEL_OPTION);
            return null;
        });
        await(() -> !dialog.isShowing());
    }

    JDialog localConfirmation() throws Exception {
        ui.click("mods.repair");
        var source = ui.dialog();
        assertNotNull(onEdt(() -> named(source, "mods.repairSources")));
        answerStep(source, true);
        return ui.dialog();
    }

    void selectVersion(String version) throws Exception {
        ui.click("mods.version." + version);
    }

    UUID pending(String phase) throws Exception {
        var operation = ui.runtime.modIntents().list().getFirst().operations().getFirst();
        assertEquals("REINSTALL", operation.type());
        assertEquals("UNKNOWN", operation.state());
        assertEquals(phase, operation.phase());
        return operation.id();
    }

    void originalsTab() throws Exception {
        onEdt(() -> {
            var tabs = (JTabbedPane) named(ui.frame, "mods.management");
            tabs.setSelectedIndex(tabs.indexOfTab("原始包"));
            return null;
        });
        ui.idle();
    }

    @Test
    void localSourceAndFinalConfirmationCanBeCancelledBeforeRepairKeepsDataAndChoice() throws Exception {
        Path original = fixture.installed();
        ui.fixture.original("other-version.wtlmod", "2.0.0", "other");
        ui.fixture.original("conflicting-content.wtlmod", "1.0.0", "conflicting");
        String old = WindowsFileIdentity.read(ui.fixture.target("1.0.0"));
        fixture.window();
        ui.click("mods.repair");
        var source = ui.dialog();
        onEdt(() -> {
            var choices = (JComboBox<?>) named(source, "mods.repairSources");
            assertEquals(2, choices.getItemCount());
            assertEquals(original, choices.getSelectedItem());
            return null;
        });
        answerStep(source, false);
        ui.idle();
        assertEquals(0, operations());
        var confirmation = localConfirmation();
        String scope = onEdt(() -> ((JTextArea) named(confirmation, "mods.confirmation")).getText());
        assertTrue(scope.contains(original.toString()));
        assertTrue(scope.contains("sample.bundle 1.0.0"));
        assertTrue(scope.contains("不重新迁移数据"));
        assertTrue(scope.contains(ui.fixture.target("1.0.0").resolve("main/fixture.jar").toString()));
        onEdt(() -> {
            capture(confirmation, "platform-reinstallation-confirmation.png");
            return null;
        });
        ui.answer(confirmation, false);
        assertEquals(old, WindowsFileIdentity.read(ui.fixture.target("1.0.0")));
        assertEquals(0, operations());
        ui.answer(localConfirmation(), true);
        assertTrue(status().startsWith("程序修复完成"), status());
        assertNotEquals(old, WindowsFileIdentity.read(ui.fixture.target("1.0.0")));
        assertEquals("FALSE/1.0.0", text(ui.runtime, "SELECT enabled||'/'||selected_version FROM mod_registry"));
        assertTrue(Files.exists(original));
        assertEquals(0, operations());
    }

    @Test
    void acknowledgedRestartIsRequiredAndRepairingADraftVersionDoesNotChangeTheSavedSelection() throws Exception {
        fixture.installed();
        var second = ui.fixture.original("two.wtlmod", "2.0.0", "two");
        assertEquals(Outcome.SUCCEEDED, ui.installer.install(ui.installer.review(second)).outcome());
        sql(ui.runtime, "UPDATE mod_registry SET disable_ack_revision=NULL");
        fixture.window();
        assertFalse(ui.runtime.modIntents().list().getFirst().stopAcknowledged());
        onEdt(() -> {
            assertFalse(named(ui.frame, "mods.repair").isEnabled());
            assertFalse(named(ui.frame, "mods.deleteVersion").isEnabled());
            return null;
        });
        acknowledged(ui.runtime);
        ui.click("mods.refresh");
        ui.idle();
        assertTrue(ui.runtime.modIntents().list().getFirst().stopAcknowledged());
        selectVersion("2.0.0");
        var confirmation = localConfirmation();
        assertTrue(onEdt(() -> ((JTextArea) named(confirmation, "mods.confirmation")).getText())
                .contains("sample.bundle 2.0.0"));
        ui.answer(confirmation, true);
        assertTrue(status().startsWith("程序修复完成"));
        assertEquals("1.0.0", text(ui.runtime, "SELECT selected_version FROM mod_registry"));
    }

    @Test
    void missingManagedPackageOffersARealFilePickerAndPreservesAnExternalInput() throws Exception {
        var original = fixture.installed();
        Path external = root.resolve("external-input.wtlmod");
        Files.move(original, external);
        ui.installer.keepOriginal(false);
        fixture.window();
        ui.click("mods.repair");
        var picker = ui.dialog();
        onEdt(() -> {
            var chooser = (JFileChooser) named(picker, "mods.repairFile");
            chooser.setSelectedFile(external.toFile());
            chooser.approveSelection();
            return null;
        });
        await(() -> !picker.isShowing());
        var confirmation = ui.dialog();
        String scope = onEdt(() -> ((JTextArea) named(confirmation, "mods.confirmation")).getText());
        assertTrue(scope.contains(external.toString()));
        assertTrue(scope.contains("保留原包"));
        ui.answer(confirmation, true);
        assertTrue(status().startsWith("程序修复完成"), status());
        assertTrue(Files.exists(external));
    }

    @Test
    void unknownRepairBlocksDraftChangesAndContinuesAcrossWindowAndRuntimeReopen() throws Exception {
        fixture.installed();
        fixture.window();
        trap(ui.runtime, "phase<>'REGISTERED'");
        ui.answer(localConfirmation(), true);
        UUID id = pending("FILES_PUBLISHED");
        untrap(ui.runtime);
        assertTrue(status().startsWith("重装尚未确认完成"), status());
        onEdt(() -> {
            assertFalse(named(ui.frame, "mods.enabled").isEnabled());
            assertFalse(named(ui.frame, "mods.version.1.0.0").isEnabled());
            assertFalse(named(ui.frame, "mods.repair").isEnabled());
            assertTrue(named(ui.frame, "mods.reconcileRepair").isEnabled());
            assertTrue(named(ui.frame, "mods.restoreProgram").isEnabled());
            capture(ui.frame, "platform-reinstallation-pending.png");
            return null;
        });
        ui.cleanup();
        ui.frame = null;
        ui.runtime = ui.fixture.runtime();
        ui.installer = ui.fixture.installer(ui.runtime);
        fixture.window();
        assertEquals(id, pending("FILES_PUBLISHED"));
        ui.click("mods.reconcileRepair");
        ui.answer(ui.dialog(), false);
        assertEquals(1, operations());
        ui.click("mods.reconcileRepair");
        var confirmation = ui.dialog();
        assertTrue(
                onEdt(() -> ((JTextArea) named(confirmation, "mods.confirmation")).getText()).contains(id.toString()));
        ui.answer(confirmation, true);
        assertTrue(status().startsWith("程序修复完成"), status());
        assertEquals(0, operations());
    }

    @Test
    void explicitRestorationUsesOriginalRepairVersionAndReportsFailedRepair() throws Exception {
        fixture.installed();
        var second = ui.fixture.original("two.wtlmod", "2.0.0", "two");
        assertEquals(Outcome.SUCCEEDED, ui.installer.install(ui.installer.review(second)).outcome());
        String old = WindowsFileIdentity.read(ui.fixture.target("2.0.0"));
        trap(ui.runtime, "phase<>'REGISTERED'");
        var review = ui.installer.reinstallation().review(OWNER, "2.0.0", second);
        assertEquals(Outcome.UNKNOWN, ui.installer.reinstallation().reinstall(review).outcome());
        untrap(ui.runtime);
        Files.writeString(ui.fixture.target("2.0.0").resolve("main/fixture.jar"), "damaged new bytes");
        fixture.window();
        assertTrue(onEdt(() -> ((JRadioButton) named(ui.frame, "mods.version.1.0.0")).isSelected()));
        ui.click("mods.restoreProgram");
        var confirmation = ui.dialog();
        String scope = onEdt(() -> ((JTextArea) named(confirmation, "mods.confirmation")).getText());
        assertTrue(scope.contains("sample.bundle 2.0.0"));
        assertTrue(scope.contains("不表示修复成功"));
        assertTrue(scope.contains(ui.fixture.target("2.0.0").resolve("main/fixture.jar").toString()));
        onEdt(() -> {
            capture(confirmation, "platform-reinstallation-restore.png");
            return null;
        });
        ui.answer(confirmation, false);
        assertEquals(1, operations());
        ui.click("mods.restoreProgram");
        ui.answer(ui.dialog(), true);
        assertEquals("重装未成功，旧程序已恢复，数据和选择已保留。", status());
        assertEquals(old, WindowsFileIdentity.read(ui.fixture.target("2.0.0")));
        assertEquals("1.0.0", text(ui.runtime, "SELECT selected_version FROM mod_registry"));
        assertEquals(0, operations());
    }

    @Test
    void lockRevokesBothRepairConfirmationAndFilePickerAndChangedArchivesCannotReuseApproval() throws Exception {
        Path original = fixture.installed();
        fixture.window();
        var confirmation = localConfirmation();
        var option = onEdt(() -> (JOptionPane) descendants(confirmation).filter(JOptionPane.class::isInstance)
                .findFirst().orElseThrow());
        ui.runtime.security().lock();
        await(() -> !confirmation.isShowing());
        onEdt(() -> {
            option.setValue(JOptionPane.OK_OPTION);
            return null;
        });
        assertEquals(0, operations());
        ui.runtime.security().unlock(PlatformTaskAdmissionIntegrationTest.PASSWORD.toCharArray());
        ui.idle();
        await(() -> named(ui.frame, "mods.repair").isEnabled());
        var changed = localConfirmation();
        Files.writeString(original, "changed archive");
        ui.answer(changed, true);
        assertEquals(0, operations());
        assertFalse(status().startsWith("程序修复完成"));
        ui.click("mods.repair");
        var picker = ui.dialog();
        var chooser = onEdt(() -> (JFileChooser) named(picker, "mods.repairFile"));
        ui.runtime.security().lock();
        await(() -> !picker.isShowing());
        onEdt(() -> {
            chooser.setSelectedFile(original.toFile());
            chooser.approveSelection();
            return null;
        });
        assertEquals(0, operations());
        assertTrue(Files.exists(ui.fixture.target("1.0.0")));
    }

    @Test
    void originalsPageContinuesOriginalRestorationWithoutRoutingItToInstallation() throws Exception {
        Path original = fixture.installed();
        trap(ui.runtime, "phase<>'REGISTERED'");
        var review = ui.installer.reinstallation().review(OWNER, "1.0.0", original);
        assertEquals(Outcome.UNKNOWN, ui.installer.reinstallation().reinstall(review).outcome());
        untrap(ui.runtime);
        trap(ui.runtime, "phase<>'RESTORED'");
        assertEquals(Outcome.UNKNOWN, ui.installer.reinstallation()
                .restore(ui.installer.reinstallation().reviewRestore(review.id())).outcome());
        untrap(ui.runtime);
        fixture.window();
        pending("RESTORING");
        assertFalse(onEdt(() -> named(ui.frame, "mods.restoreProgram").isEnabled()));
        originalsTab();
        assertTrue(onEdt(() -> named(ui.frame, "originals.reconcile").isEnabled()));
        assertFalse(onEdt(() -> named(ui.frame, "originals.cancelInstall").isEnabled()));
        ui.click("originals.reconcile");
        var confirmation = ui.dialog();
        assertTrue(
                onEdt(() -> ((JTextArea) named(confirmation, "originals.confirmation")).getText()).contains("继续原恢复"));
        ui.answer(confirmation, true);
        assertEquals("重装未成功，旧程序已恢复，数据和选择已保留。", ui.status());
        assertEquals(0, operations());
    }

    @Test
    void lockedManagedOriginalCleanupRemainsAWarningAfterSuccessfulProgramRepair() throws Exception {
        Path original = fixture.installed();
        ui.installer.keepOriginal(false);
        fixture.window();
        var confirmation = localConfirmation();
        var handle = Kernel32.INSTANCE.CreateFile(original.toString(), WinNT.GENERIC_READ,
                WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0, null);
        assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
        try {
            ui.answer(confirmation, true);
            assertTrue(status().startsWith("程序修复完成"), status());
            assertTrue(status().contains("原包"));
            assertTrue(Files.exists(original));
            assertEquals(0, operations());
        } finally {
            assertTrue(Kernel32.INSTANCE.CloseHandle(handle));
        }
    }
}
