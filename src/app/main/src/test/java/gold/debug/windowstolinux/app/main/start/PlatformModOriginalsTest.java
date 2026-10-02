package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.*;
import java.util.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.mod.*;
import gold.debug.windowstolinux.app.service.mod.PlatformModInstallation.*;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.PlatformModsPane;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModOriginalsTest {
    @TempDir
    Path root;

    PlatformModInstallationTest fixture;

    PlatformRuntime runtime;

    PlatformModInstallation installer;

    PlatformFrame frame;

    @BeforeEach
    void setup() throws Exception {
        fixture = new PlatformModInstallationTest();
        fixture.root = root;
        fixture.compile();
        runtime = fixture.runtime();
        installer = fixture.installer(runtime);
    }

    void window(boolean classMode) throws Exception {
        var intents = runtime.modIntents();
        frame = onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var window = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                FailureReportStore.disabled());
            window.onExit(runtime);
            window.setVisible(true);
            if (!classMode) {
                window.installMods(intents, new PlatformModsPane.SchemaActions() {
                    public PlatformModIntents.RetryReview review(String owner, Optional<SchemaIdentity> source)
                        throws Exception {
                        return runtime.reviewModSchemaRetry(owner, source);
                    }

                    public Outcome retry(PlatformModIntents.RetryReview review) throws Exception {
                        return runtime.retryModSchema(review);
                    }

                    public Outcome reconcile(UUID operation) throws Exception {
                        return runtime.reconcileModSchema(operation);
                    }
                });
                window.installOriginalPackages(Optional.of(installer));
            }
            return window;
        });
        if (classMode)
            DesktopMain.installModManagement(runtime, frame);
        await(() -> !DesktopTaskExecutor.hasActiveTasks());
        onEdt(() -> {
            var outer = (JTabbedPane) named(frame, "platform.pages");
            outer.setSelectedIndex(outer.indexOfTab("Mod"));
            var inner = (JTabbedPane) named(frame, "mods.management");
            inner.setSelectedIndex(inner.indexOfTab("原始包"));
            return null;
        });
        if (!classMode)
            idle();
    }

    void idle() throws Exception {
        await(() -> !DesktopTaskExecutor.hasActiveTasks() && !named(frame, "originals.progress").isVisible());
    }

    void click(String name) throws Exception {
        onEdt(() -> {
            assertTrue(named(frame, name).isEnabled(), name);
            return null;
        });
        SwingUtilities.invokeLater(() -> ((AbstractButton) named(frame, name)).doClick(0));
    }

    JDialog dialog() throws Exception {
        await(() -> Arrays.stream(Window.getWindows()).anyMatch(w -> w instanceof JDialog && w.isShowing()));
        return onEdt(() -> (JDialog) Arrays.stream(Window.getWindows())
            .filter(w -> w instanceof JDialog && w.isShowing()).findFirst().orElseThrow());
    }

    void answer(JDialog dialog, boolean yes) throws Exception {
        onEdt(() -> {
            ((JOptionPane) descendants(dialog).filter(JOptionPane.class::isInstance).findFirst().orElseThrow())
                .setValue(yes ? JOptionPane.OK_OPTION : JOptionPane.CANCEL_OPTION);
            return null;
        });
        await(() -> !dialog.isShowing());
        idle();
    }

    String status() throws Exception {
        return onEdt(() -> ((JLabel) named(frame, "originals.status")).getText());
    }

    int rows() throws Exception {
        return onEdt(() -> ((JList<?>) named(frame, "originals.inventory")).getModel().getSize());
    }

    void search(String text) throws Exception {
        onEdt(() -> {
            ((JTextField) named(frame, "originals.search")).setText(text);
            return null;
        });
    }

    void select(String file) throws Exception {
        idle();
        onEdt(() -> {
            var list = (JList<?>) named(frame, "originals.inventory");
            for (int i = 0; i < list.getModel().getSize(); i++)
                if (((OriginalView) list.getModel().getElementAt(i)).file().getFileName().toString().equals(file))
                    list.setSelectedIndex(i);
            assertEquals(file, ((OriginalView) list.getSelectedValue()).file().getFileName().toString());
            return null;
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        onEdt(() -> {
            for (var window : Window.getWindows())
                if (window instanceof JDialog)
                    window.dispose();
            return null;
        });
        await(() -> !DesktopTaskExecutor.hasActiveTasks());
        if (installer != null)
            installer.close();
        if (runtime != null)
            runtime.close();
        onEdt(() -> {
            if (frame != null)
                frame.dispose();
            return null;
        });
        fixture.noAuthorExecution();
    }

    @Test
    void scansAndSearchesDeclaredFactsAndMalformedFilenamesWithoutInstalling() throws Exception {
        fixture.original("第一版.wtlmod", "1.0.0", "one");
        fixture.original("second.wtlmod", "2.0.0", "two");
        Files.writeString(root.resolve("mod-zip/半包.wtlmod"), "partial");
        window(false);
        assertEquals(3, rows());
        search("Fixture");
        assertEquals(2, rows());
        search("sample.bundle");
        assertEquals(2, rows());
        search("2.0.0");
        assertEquals(1, rows());
        search("半包");
        assertEquals(1, rows());
        assertTrue(onEdt(() -> ((JTextArea) named(frame, "originals.details")).getText()).contains("不完整"));
        assertFalse(onEdt(() -> named(frame, "originals.install").isEnabled()));
        assertTrue(onEdt(() -> named(frame, "originals.delete").isEnabled()));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
        assertFalse(Files.exists(root.resolve(".mod-staging")));
        search("");
        onEdt(() -> {
            capture(frame, "platform-originals-inventory.png");
            return null;
        });
        fixture.original("third.wtlmod", "3.0.0", "three");
        assertEquals(3, rows());
        click("originals.refresh");
        idle();
        assertEquals(4, rows());
        Files.delete(root.resolve("mod-zip/third.wtlmod"));
        onEdt(() -> {
            var tabs = (JTabbedPane) named(frame, "mods.management");
            tabs.setSelectedIndex(0);
            tabs.setSelectedIndex(1);
            return null;
        });
        idle();
        assertEquals(3, rows());
    }

    @Test
    void cancellationConfirmsActualPartialScopeAndPreservesOriginalAndInstalledDraft() throws Exception {
        var first = fixture.original("first.wtlmod", "1.0.0", "first");
        assertEquals(Outcome.SUCCEEDED, installer.install(installer.review(first)).outcome());
        var original = fixture.original("second.wtlmod", "2.0.0", "second");
        PlatformModInstallationTest.trap(runtime, "phase <> 'STAGED'");
        var installation = installer.review(original);
        assertEquals(Outcome.UNKNOWN, installer.install(installation).outcome());
        PlatformModInstallationTest.untrap(runtime);
        var stage = root.resolve(".mod-staging").resolve(installation.id().toString());
        Files.delete(stage.resolve("program/LICENSE"));
        window(false);
        onEdt(() -> {
            ((JCheckBox) named(frame, "mods.enabled")).doClick(0);
            return null;
        });
        click("originals.cancelInstall");
        var confirmation = dialog();
        String text = onEdt(() -> ((JTextArea) named(confirmation, "originals.confirmation")).getText());
        assertTrue(text.contains(stage.resolve("program/README.md").toString()));
        assertTrue(text.contains("不完整的暂存"));
        assertTrue(text.contains("不能恢复安装"));
        onEdt(() -> {
            capture(confirmation, "platform-originals-cancel-confirmation.png");
            return null;
        });
        answer(confirmation, false);
        assertTrue(Files.exists(stage.resolve("program/README.md")));
        click("originals.cancelInstall");
        answer(dialog(), true);
        assertTrue(status().contains("安装已撤销"));
        assertTrue(Files.exists(original));
        assertFalse(Files.exists(stage));
        assertFalse(Files.exists(fixture.target("2.0.0")));
        assertEquals("first", Files.readString(fixture.target("1.0.0").resolve("README.md")));
        assertTrue(onEdt(() -> ((JCheckBox) named(frame, "mods.enabled")).isSelected()));
        assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
        assertEquals(0, onEdt(() -> ((JComboBox<?>) named(frame, "originals.pending")).getItemCount()));
    }

    @Test
    void cancellationConfirmationCannotSurviveLockOrFileChanges() throws Exception {
        var original = fixture.original("original.wtlmod", "1.0.0", "one");
        PlatformModInstallationTest.trap(runtime, "phase <> 'STAGED'");
        var installation = installer.review(original);
        assertEquals(Outcome.UNKNOWN, installer.install(installation).outcome());
        PlatformModInstallationTest.untrap(runtime);
        Path readme = root.resolve(".mod-staging").resolve(installation.id().toString()).resolve("program/README.md");
        window(false);
        click("originals.cancelInstall");
        var confirmation = dialog();
        runtime.security().lock();
        await(() -> !confirmation.isShowing());
        assertTrue(Files.exists(readme));
        runtime.security().unlock(PASSWORD.toCharArray());
        await(() -> named(frame, "originals.cancelInstall").isEnabled());
        idle();
        click("originals.cancelInstall");
        var changed = dialog();
        Files.writeString(readme, "two");
        answer(changed, true);
        assertEquals("two", Files.readString(readme));
        assertEquals("VALIDATED", PlatformModInstallationTest.phase(runtime));
        click("originals.cancelInstall");
        answer(dialog(), true);
        assertFalse(Files.exists(readme));
        assertTrue(Files.exists(original));
    }

    @Test
    void acceptedCancellationUsesOriginalCleanupReconciliationAndNeverRestartsInstallation() throws Exception {
        var original = fixture.original("original.wtlmod", "1.0.0", "one");
        PlatformModInstallationTest.trap(runtime, "phase <> 'STAGED'");
        var installation = installer.review(original);
        assertEquals(Outcome.UNKNOWN, installer.install(installation).outcome());
        PlatformModInstallationTest.untrap(runtime);
        sql(runtime.persistence().database(),
            "ALTER TABLE runtime_log ADD CONSTRAINT ui_cancel_archive_trap CHECK(event_code <> 'MOD_INSTALL_CANCELLED')");
        window(false);
        click("originals.cancelInstall");
        answer(dialog(), true);
        assertTrue(status().contains("撤销尚未确认完成"));
        assertEquals("CANCELLING", PlatformModInstallationTest.phase(runtime));
        assertFalse(onEdt(() -> named(frame, "originals.cancelInstall").isEnabled()));
        assertTrue(onEdt(() -> named(frame, "originals.reconcile").isEnabled()));
        onEdt(() -> {
            capture(frame, "platform-originals-cancelling.png");
            return null;
        });
        sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT ui_cancel_archive_trap");
        click("originals.reconcile");
        var confirmation = dialog();
        assertTrue(
            onEdt(() -> ((JTextArea) named(confirmation, "originals.confirmation")).getText()).contains("不会重新安装"));
        answer(confirmation, true);
        assertTrue(status().contains("安装已撤销"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertTrue(Files.exists(original));
    }

    @Test
    void installationAndDeletionEachRequireTheirOwnConcreteConfirmation() throws Exception {
        var original = fixture.original("original.wtlmod", "1.0.0", "one");
        window(false);
        click("originals.install");
        var first = dialog();
        String text = onEdt(() -> ((JTextArea) named(first, "originals.confirmation")).getText());
        assertTrue(text.contains(original.toString()));
        assertTrue(text.contains("不自动启用"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        answer(first, false);
        assertFalse(Files.exists(root.resolve("mod")));
        click("originals.install");
        answer(dialog(), true);
        assertTrue(status().contains("安装已登记"));
        assertTrue(status().contains("原包已保留"));
        assertEquals(1, rows());
        assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
        assertFalse(onEdt(() -> named(frame, "originals.install").isEnabled()));
        assertEquals(1, onEdt(() -> ((JList<?>) named(frame, "mods.inventory")).getModel().getSize()));
        onEdt(() -> {
            ((JCheckBox) named(frame, "mods.enabled")).doClick(0);
            return null;
        });
        click("originals.delete");
        answer(dialog(), false);
        assertTrue(Files.exists(original));
        click("originals.delete");
        answer(dialog(), true);
        assertFalse(Files.exists(original));
        assertEquals(0, rows());
        assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
        assertEquals("one", Files.readString(fixture.target("1.0.0").resolve("README.md")));
        assertTrue(status().contains("已安装程序和数据保持原样"));
        assertTrue(onEdt(() -> ((JCheckBox) named(frame, "mods.enabled")).isSelected()));
        assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
    }

    @Test
    void retentionPreferenceControlsTheNextReviewedInstallation() throws Exception {
        var original = fixture.original("original.wtlmod", "1.0.0", "one");
        window(false);
        assertTrue(onEdt(() -> ((JCheckBox) named(frame, "originals.keep")).isSelected()));
        click("originals.keep");
        idle();
        assertFalse(installer.keepOriginal());
        click("originals.install");
        var confirm = dialog();
        assertTrue(onEdt(() -> ((JTextArea) named(confirm, "originals.confirmation")).getText()).contains("成功后删除原包"));
        answer(confirm, true);
        assertFalse(Files.exists(original));
        assertEquals(0, rows());
        assertTrue(status().contains("原包已删除"));
    }

    @Test
    void unknownInstallationRemainsVisibleWithoutOriginalAndReconcilesTheSameOperation() throws Exception {
        var original = fixture.original("original.wtlmod", "1.0.0", "one");
        sql(runtime.persistence().database(),
            "ALTER TABLE mod_version ADD CONSTRAINT original_ui_trap CHECK(origin_kind <> 'LOCAL')");
        var review = installer.review(original);
        assertEquals(Outcome.UNKNOWN, installer.install(review).outcome());
        Files.delete(original);
        window(false);
        assertEquals(0, rows());
        var selected = onEdt(() -> (PendingView) ((JComboBox<?>) named(frame, "originals.pending")).getSelectedItem());
        assertEquals(review.id(), selected.id());
        String cause = onEdt(() -> ((JTextArea) named(frame, "originals.operationDetails")).getText());
        assertTrue(cause.contains("结果未知"));
        assertTrue(cause.contains("FILES_PUBLISHED"));
        assertFalse(cause.contains("INSERT INTO"));
        assertTrue(onEdt(() -> named(frame, "originals.reconcile").isEnabled()));
        onEdt(() -> {
            capture(frame, "platform-originals-pending.png");
            return null;
        });
        sql(runtime.persistence().database(), "ALTER TABLE mod_version DROP CONSTRAINT original_ui_trap");
        click("originals.reconcile");
        answer(dialog(), true);
        assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertTrue(status().contains("原包已不在原位置"));
        assertEquals(0, onEdt(() -> ((JComboBox<?>) named(frame, "originals.pending")).getItemCount()));
    }

    @Test
    void lockingDisposesTheConfirmationAndUnlockingCannotSubmitTheOldReview() throws Exception {
        fixture.original("original.wtlmod", "1.0.0", "one");
        window(false);
        click("originals.install");
        var confirm = dialog();
        runtime.security().lock();
        await(() -> !confirm.isShowing());
        assertEquals(0, rows());
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        runtime.security().unlock(PASSWORD.toCharArray());
        await(() -> ((JList<?>) named(frame, "originals.inventory")).getModel().getSize() == 1);
        idle();
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
        click("originals.install");
        answer(dialog(), true);
        assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
    }

    @Test
    void changesDuringConfirmationRejectTheOldActionAndRefreshActualFiles() throws Exception {
        var original = fixture.original("original.wtlmod", "1.0.0", "one");
        window(false);
        click("originals.install");
        var confirm = dialog();
        Files.writeString(original, "incomplete replacement");
        answer(confirm, true);
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertFalse(Files.exists(root.resolve(".mod-staging")));
        assertFalse(onEdt(() -> named(frame, "originals.install").isEnabled()));
        click("originals.delete");
        var deletion = dialog();
        Files.writeString(original, "new copy still arriving with a different size");
        answer(deletion, true);
        assertTrue(Files.exists(original));
        click("originals.delete");
        answer(dialog(), true);
        assertFalse(Files.exists(original));
    }

    @Test
    void failedRefreshKeepsObservationsButDisablesMutationUntilTheRootIsReadableAgain() throws Exception {
        fixture.original("original.wtlmod", "1.0.0", "one");
        window(false);
        Files.move(root.resolve("mod-zip"), root.resolve("held-originals"));
        Files.writeString(root.resolve("mod-zip"), "not a directory");
        click("originals.refresh");
        idle();
        assertFalse(onEdt(() -> named(frame, "originals.install").isEnabled()));
        assertFalse(onEdt(() -> named(frame, "originals.delete").isEnabled()));
        assertFalse(onEdt(() -> named(frame, "originals.keep").isEnabled()));
        assertTrue(onEdt(() -> named(frame, "originals.refresh").isEnabled()));
        assertFalse(status().isBlank());
        assertFalse(Files.exists(root.resolve(".mod-staging")));
        Files.delete(root.resolve("mod-zip"));
        Files.move(root.resolve("held-originals"), root.resolve("mod-zip"));
        click("originals.refresh");
        idle();
        assertEquals(1, rows());
        assertTrue(onEdt(() -> named(frame, "originals.install").isEnabled()));
        assertTrue(onEdt(() -> named(frame, "originals.delete").isEnabled()));
    }

    @Test
    void failedOriginalCleanupIsShownSeparatelyFromSuccessfulInstallation() throws Exception {
        var original = fixture.original("original.wtlmod", "1.0.0", "one");
        installer.keepOriginal(false);
        window(false);
        var nativeApi = com.sun.jna.platform.win32.Kernel32.INSTANCE;
        var handle = nativeApi.CreateFile(original.toString(), com.sun.jna.platform.win32.WinNT.GENERIC_READ,
            com.sun.jna.platform.win32.WinNT.FILE_SHARE_READ | com.sun.jna.platform.win32.WinNT.FILE_SHARE_WRITE,
            null, com.sun.jna.platform.win32.WinNT.OPEN_EXISTING, 0, null);
        assertNotEquals(com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE, handle);
        try {
            assertThrows(java.io.IOException.class, () -> Files.delete(original));
            click("originals.install");
            answer(dialog(), true);
            assertTrue(status().contains("安装已登记"));
            assertTrue(status().contains("原包未能确认清理"));
            assertTrue(Files.exists(original));
            assertEquals(1, rows());
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        } finally {
            assertTrue(nativeApi.CloseHandle(handle));
        }
    }

    @Test
    void defaultClassCompositionExplainsSourceModeWithoutAnInstallationView() throws Exception {
        fixture.original("original.wtlmod", "1.0.0", "one");
        window(true);
        assertTrue(onEdt(() -> ((JTextArea) named(frame, "originals.classMode")).getText()).contains("CLASS"));
        assertTrue(onEdt(() -> descendants(frame).noneMatch(c -> "originals.inventory".equals(c.getName()))));
        for (String action : java.util.List.of("mods.repair", "mods.reconcileRepair", "mods.restoreProgram"))
            assertFalse(onEdt(() -> named(frame, action).isVisible()));
        assertFalse(Files.exists(root.resolve(".mod-staging")));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
    }
}
