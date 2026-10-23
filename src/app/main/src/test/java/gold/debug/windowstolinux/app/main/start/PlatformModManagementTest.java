package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.ModView;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModManagementTest {
    @TempDir
    Path root;

    PlatformRuntime runtime;

    PlatformFrame frame;

    PlatformModFlowTest fixture;

    PlatformModInitializationTest initial;

    void development(boolean enabled) throws Exception {
        fixture = new PlatformModFlowTest();
        fixture.root = root;
        fixture.workspace();
        fixture.module(P, Map.of(), false);
        if (enabled)
            fixture.enable(P);
        runtime = fixture.open(true);
        if (!enabled)
            new gold.debug.windowstolinux.app.service.mod.PlatformModCatalog(runtime.persistence().database()).refresh(fixture.workspace);
        fixture.start(runtime);
        window();
    }

    @Test
    void clearedCardSelectionSurvivesRefreshAndDisablesSelectedModActions() throws Exception {
        development(false);
        select(P);
        assertFalse(details().isBlank());
        onEdt(() -> {
            ((JList<?>) named(frame, "mods.inventory")).clearSelection();
            assertEquals("", ((JTextArea) named(frame, "mods.details")).getText());
            for (String name : List.of("mods.enabled", "mods.save", "mods.prepare", "mods.openPage", "mods.cancel", "mods.reconcile", "mods.retry"))
                assertFalse(actionEnabled(name), name);
            return null;
        });
        click("mods.refresh");
        idle();
        onEdt(() -> {
            var list = (JList<?>) named(frame, "mods.inventory");
            assertTrue(list.getModel().getSize() > 0);
            assertNull(list.getSelectedValue());
            assertEquals("", ((JTextArea) named(frame, "mods.details")).getText());
            assertFalse(named(frame, "mods.enabled").isEnabled());
            assertFalse(named(frame, "mods.save").isEnabled());
            assertFalse(named(frame, "mods.openPage").isEnabled());
            return null;
        });
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertTrue(fixture.actions().isEmpty());
    }

    @Test
    void firstDataPreparationUsesVisibleConfirmationAndLockRevokesTheUnsubmittedReview() throws Exception {
        initial = new PlatformModInitializationTest();
        initial.root = root;
        initial.setup();
        initial.module("valid", false);
        fixture = initial.fixture;
        runtime = fixture.open(true);
        assertEquals("MOD_DATA_NOT_READY", fixture.start(runtime).problems().getFirst().eventCode());
        window();
        select(P);
        onEdt(() -> {
            assertTrue(actionVisible("mods.prepare"));
            assertFalse(named(frame, "mods.save").isEnabled());
            return null;
        });
        click("mods.prepare");
        var confirm = dialog();
        assertTrue(confirmation(confirm).contains("首次数据准备"));
        answer(confirm, false);
        idle();
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        click("mods.prepare");
        confirm = dialog();
        var old = confirm;
        var original = onEdt(() -> (JOptionPane) descendants(old).filter(JOptionPane.class::isInstance).findFirst().orElseThrow());
        runtime.security().lock();
        await(() -> !old.isDisplayable());
        runtime.security().unlock(PASSWORD.toCharArray());
        await(() -> named(frame, "mods.refresh").isEnabled());
        onEdt(() -> {
            original.setValue(JOptionPane.OK_OPTION);
            return null;
        });
        idle();
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        click("mods.refresh");
        idle();
        select(P);
        click("mods.prepare");
        answer(dialog(), true);
        idle();
        assertEquals("WAIT_RESTART", text(runtime, "SELECT phase FROM mod_operation"));
        assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
        assertTrue(fixture.actions().isEmpty());
        assertFalse(onEdt(() -> actionVisible("mods.prepare")));
        click("mods.cancel");
        answer(dialog(), true);
        idle();
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
        assertTrue(onEdt(() -> actionVisible("mods.prepare")));
    }

    void window() throws Exception {
        frame = onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var value = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                FailureReportStore.disabled());
            value.onExit(runtime);
            value.setVisible(true);
            return value;
        });
        ApplicationLauncher.installModManagement(runtime, frame);
        await(() -> ((JList<?>) named(frame, "mods.inventory")).getModel().getSize() > 0
            && !DesktopTaskRunner.hasActiveTasks());
        onEdt(() -> {
            var tabs = (JTabbedPane) named(frame, "platform.pages");
            tabs.setSelectedIndex(tabs.indexOfTab("Mod"));
            return null;
        });
    }

    void click(String name) throws Exception {
        if (name.startsWith("mods.version.")) {
            onEdt(() -> {
                ((JComboBox<?>) named(frame, "mods.versions")).setSelectedItem(name.substring("mods.version.".length()));
                return null;
            });
            return;
        }
        var target = onEdt(() -> {
            var button = action(name);
            assertTrue(button.isEnabled(), name);
            return button;
        });
        SwingUtilities.invokeLater(() -> target.doClick(0));
    }

    AbstractButton action(String name) {
        var direct = descendants(frame).filter(c -> name.equals(c.getName()) && c instanceof AbstractButton).findFirst();
        if (direct.isPresent()) return (AbstractButton) direct.get();
        ((AbstractButton) named(frame, "mods.more")).doClick(0);
        return (AbstractButton) Arrays.stream(Window.getWindows()).filter(Window::isShowing).flatMap(PlatformWindowTest::descendants)
            .filter(c -> name.equals(c.getName()) && c instanceof AbstractButton && c.isShowing()).findFirst().orElseThrow();
    }

    boolean actionEnabled(String name) {
        var direct = descendants(frame).filter(c -> name.equals(c.getName()) && c instanceof AbstractButton).findFirst();
        if (direct.isPresent()) return direct.get().isEnabled();
        if (!named(frame, "mods.more").isEnabled()) return false;
        ((AbstractButton) named(frame, "mods.more")).doClick(0);
        boolean enabled = Arrays.stream(Window.getWindows()).filter(Window::isShowing).flatMap(PlatformWindowTest::descendants)
            .anyMatch(c -> name.equals(c.getName()) && c instanceof AbstractButton && c.isShowing() && c.isEnabled());
        MenuSelectionManager.defaultManager().clearSelectedPath();
        return enabled;
    }

    boolean actionVisible(String name) {
        if (!named(frame, "mods.more").isEnabled()) return false;
        ((AbstractButton) named(frame, "mods.more")).doClick(0);
        boolean visible = Arrays.stream(Window.getWindows()).filter(Window::isShowing).flatMap(PlatformWindowTest::descendants)
            .anyMatch(c -> name.equals(c.getName()) && c instanceof AbstractButton && c.isShowing());
        MenuSelectionManager.defaultManager().clearSelectedPath();
        return visible;
    }

    void idle() throws Exception {
        await(() -> !DesktopTaskRunner.hasActiveTasks() && !named(frame, "mods.progress").isVisible());
    }

    void select(String owner) throws Exception {
        idle();
        onEdt(() -> {
            var list = (JList<?>) named(frame, "mods.inventory");
            for (int i = 0; i < list.getModel().getSize(); i++)
                if (((ModView) list.getModel().getElementAt(i)).owner().equals(owner))
                    list.setSelectedIndex(i);
            return null;
        });
    }

    JDialog dialog() throws Exception {
        await(() -> Arrays.stream(Window.getWindows()).anyMatch(w -> w instanceof JDialog && w.isShowing()));
        return onEdt(() -> (JDialog) Arrays.stream(Window.getWindows())
            .filter(w -> w instanceof JDialog && w.isShowing()).findFirst().orElseThrow());
    }

    void answer(JDialog dialog, boolean yes) throws Exception {
        onEdt(() -> {
            var option = (JOptionPane) descendants(dialog).filter(JOptionPane.class::isInstance).findFirst()
                .orElseThrow();
            option.setValue(yes ? JOptionPane.OK_OPTION : JOptionPane.CANCEL_OPTION);
            return null;
        });
        await(() -> !dialog.isShowing());
    }

    String details() throws Exception {
        return onEdt(() -> (((JTextArea) named(frame, "mods.savedFacts")).getText() + "\n"
            + ((JTextArea) named(frame, "mods.details")).getText()).strip());
    }

    String confirmation(JDialog dialog) throws Exception {
        return onEdt(() -> ((JTextArea) named(dialog, "mods.confirmation")).getText());
    }

    @AfterEach
    void cleanup() throws Exception {
        onEdt(() -> {
            for (var window : Window.getWindows())
                if (window instanceof JDialog)
                    window.dispose();
            return null;
        });
        await(() -> !DesktopTaskRunner.hasActiveTasks());
        if (initial != null)
            initial.cleanup();
        if (runtime != null)
            runtime.close();
        onEdt(() -> {
            if (frame != null)
                frame.dispose();
            return null;
        });
    }

    @Test
    void realDefaultPageReviewsWithoutWritingThenSavesAndWithdrawsOriginalRequest() throws Exception {
        development(false);
        select(P);
        assertTrue(details().contains("未运行"));
        click("mods.enabled");
        click("mods.save");
        var confirm = dialog();
        assertTrue(confirmation(confirm).contains(P));
        assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
        answer(confirm, false);
        idle();
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        click("mods.save");
        answer(dialog(), true);
        idle();
        assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
        assertTrue(details().contains("已启用"));
        assertTrue(details().contains("待重启"));
        assertTrue(details().contains("未运行"));
        assertTrue(fixture.actions().isEmpty());
        onEdt(() -> {
            capture(frame, "platform-mods-pending.png");
            return null;
        });
        click("mods.cancel");
        answer(dialog(), true);
        idle();
        assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertFalse(details().contains("待重启"));
    }

    @Test
    void dependencyDisableListsCascadeAndCancellationRestoresWholeGroupWithoutStoppingBusiness() throws Exception {
        fixture = new PlatformModFlowTest();
        fixture.root = root;
        fixture.workspace();
        fixture.module(P, Map.of(), false);
        fixture.module(C, Map.of(P, VERSION), false);
        fixture.enable(P, C);
        runtime = fixture.open(true);
        assertEquals(Set.of(P, C), fixture.start(runtime).running());
        window();
        select(P);
        click("mods.enabled");
        click("mods.save");
        var confirm = dialog();
        assertTrue(confirmation(confirm).contains("将同时关闭这些 Mod：" + C));
        answer(confirm, true);
        idle();
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry WHERE enabled"));
        assertEquals(2, count(runtime.persistence().database(),
            "SELECT COUNT(*) FROM mod_registry WHERE last_runtime_state='RUNNING'"));
        select(C);
        click("mods.cancel");
        confirm = dialog();
        assertTrue(confirmation(confirm).contains(P));
        answer(confirm, true);
        idle();
        assertEquals(2, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry WHERE enabled"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertFalse(fixture.actions().stream().anyMatch(a -> a.endsWith(":stop")));
    }

    @Test
    void lockClearsVisibleFactsAndInvalidatesAlreadyOpenConfirmationEvenAfterUnlock() throws Exception {
        development(false);
        select(P);
        click("mods.enabled");
        click("mods.save");
        var confirm = dialog();
        var oldOption = onEdt(() -> (JOptionPane) descendants(confirm).filter(JOptionPane.class::isInstance).findFirst()
            .orElseThrow());
        runtime.security().lock();
        await(() -> ((JList<?>) named(frame, "mods.inventory")).getModel().getSize() == 0);
        await(() -> !confirm.isDisplayable());
        assertEquals("", details());
        runtime.security().unlock(PASSWORD.toCharArray());
        await(() -> named(frame, "mods.refresh").isEnabled());
        onEdt(() -> {
            oldOption.setValue(JOptionPane.OK_OPTION);
            return null;
        });
        idle();
        assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
    }

    @Test
    void changedDecisionDuringConfirmationIsRejectedAndReloadedWithoutQueuedRetry() throws Exception {
        development(false);
        select(P);
        click("mods.enabled");
        click("mods.save");
        var confirm = dialog();
        sql(runtime.persistence().database(), "UPDATE mod_registry SET revision=revision+1 WHERE mod_id=?", P);
        answer(confirm, true);
        idle();
        assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertTrue(onEdt(() -> ((JLabel) named(frame, "mods.status")).getText().contains("MOD_REVIEW_STALE")));
    }

    @Test
    void visibleFailedTargetRetriesThroughOriginalClassGraphAndWaitsForNextStartup() throws Exception {
        initial = new PlatformModInitializationTest();
        initial.root = root;
        initial.setup();
        fixture = initial.fixture;
        initial.module("invalid", false);
        fixture.enable(P);
        runtime = fixture.open(true);
        fixture.start(runtime);
        window();
        select(P);
        assertTrue(onEdt(() -> ((JCheckBox) named(frame, "mods.enabled")).isSelected()));
        assertEquals(VERSION, onEdt(() -> ((JComboBox<?>) named(frame, "mods.versions")).getSelectedItem()));
        assertTrue(details().contains("数据库未迁移成功"));
        onEdt(() -> {
            capture(frame, "platform-mods-failed.png");
            return null;
        });
        initial.module("valid", false);
        Files.writeString(fixture.trace, "");
        click("mods.retry");
        answer(dialog(), true);
        idle();
        assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
        assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
        assertEquals("FAILED", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
        assertEquals(List.of("schema:initialize", "schema:validate"), fixture.actions());
        assertFalse(onEdt(() -> actionVisible("mods.retry")));
        assertTrue(onEdt(() -> ((JLabel) named(frame, "mods.status")).getText().contains("下次正常启动")));
        onEdt(() -> {
            capture(frame, "platform-mods-data-ready.png");
            return null;
        });
    }

    @Test
    void explicitUnknownReconciliationValidatesExistingTargetWithoutReinitialization() throws Exception {
        var interrupted = new PlatformModReconciliationTest();
        interrupted.root = root;
        interrupted.interrupted("valid", true, true);
        initial = interrupted.initial;
        fixture = interrupted.fixture;
        runtime = fixture.open(true);
        fixture.start(runtime);
        window();
        select(P);
        assertTrue(onEdt(() -> actionVisible("mods.reconcile")));
        assertFalse(onEdt(() -> actionVisible("mods.retry")));
        click("mods.reconcile");
        answer(dialog(), true);
        idle();
        assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
        assertEquals(List.of("schema:validate"), fixture.actions());
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
    }

    @Test
    void retryArchiveFailureKeepsTheOriginalHandleAndNeverReplaysAuthorCode() throws Exception {
        initial = new PlatformModInitializationTest();
        initial.root = root;
        initial.setup();
        fixture = initial.fixture;
        initial.module("invalid", false);
        fixture.enable(P);
        runtime = fixture.open(true);
        fixture.start(runtime);
        window();
        select(P);
        initial.module("valid", false);
        sql(runtime.persistence().database(),
            "ALTER TABLE runtime_log ADD CONSTRAINT management_archive_failure CHECK(event_code<>'MOD_SCHEMA_INITIALIZED')");
        Files.writeString(fixture.trace, "");
        click("mods.retry");
        answer(dialog(), true);
        idle();
        assertEquals("PREPARING", text(runtime, "SELECT state FROM mod_schema"));
        assertEquals("继续原操作收尾", onEdt(() -> action("mods.retry").getText()));
        sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT management_archive_failure");
        click("mods.retry");
        idle();
        assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertEquals(List.of("schema:initialize", "schema:validate"), fixture.actions());
        assertFalse(onEdt(() -> actionVisible("mods.retry")));
    }

    @Test
    void sourceDefaultsToActualThenSemanticLatestButEachSourceAndChangeNeedsConfirmation() throws Exception {
        var setup = new PlatformModIntentsTest();
        setup.root = root;
        runtime = setup.runtime();
        add(runtime, P, true, Map.of(), "1");
        schema(runtime, P, "1");
        var newer = schema(runtime, P, "2");
        release(runtime, P, "2.9.0", Map.of(), "2", List.of());
        release(runtime, P, "2.10.0", Map.of(), "2", List.of());
        release(runtime, P, "3.0.0", Map.of(), "3", List.of(edge("1", "3"), edge("2", "3")));
        window();
        select(P);
        click("mods.version.3.0.0");
        click("mods.save");
        var source = dialog();
        assertEquals(2, onEdt(() -> ((JComboBox<?>) named(source, "mods.source")).getSelectedIndex()));
        answer(source, false);
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        sql(runtime.persistence().database(),
            "UPDATE mod_registry SET actual_version=NULL,last_runtime_state='FAILED' WHERE mod_id=?", P);
        click("mods.refresh");
        idle();
        click("mods.version.3.0.0");
        click("mods.save");
        var latest = dialog();
        assertEquals(0, onEdt(() -> ((JComboBox<?>) named(latest, "mods.source")).getSelectedIndex()));
        answer(latest, true);
        var confirm = dialog();
        assertTrue(confirmation(confirm).contains("Schema 2"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        answer(confirm, true);
        idle();
        assertEquals("3.0.0", text(runtime, "SELECT selected_version FROM mod_registry"));
        assertTrue(text(runtime, "SELECT request_json FROM mod_operation").contains(newer.generation().toString()));
        assertTrue(details().contains("待重启"));
    }
}
