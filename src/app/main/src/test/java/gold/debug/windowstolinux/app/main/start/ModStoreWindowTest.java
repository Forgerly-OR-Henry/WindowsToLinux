package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.mod.store.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreBatchResult.State;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.*;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** Real desktop store interaction over local HTTP and the original installation owner. / 经本机 HTTP 及原安装所有者的真实桌面商店交互。 */
class ModStoreWindowTest {
    @TempDir
    Path root;

    @AfterEach
    void acquisitionNeverInitializesAuthorCode() {
        assertNull(System.getProperty(ModStoreFixture.AUTHOR_MARK));
    }

    @Test
    void groupedCardsKeepExactRootAcrossRefreshAndLeaveDropsOnlyUnsubmittedDependencyIntent() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var addon = fixture.publish("sample.group.addon", "1.0.0", Map.of(), Map.of(), Map.of());
            var older = fixture.publish("sample.group.root", "1.9.0", Map.of(), Map.of(addon.owner(), "1.0.0"), Map.of());
            var latest = fixture.publish(older.owner(), "1.10.0", Map.of(), Map.of(addon.owner(), "1.0.0"), Map.of());
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                var list = (JList<?>) named(window.frame, "store.results");
                assertEquals(2, onEdt(() -> list.getModel().getSize()));
                assertNull(onEdt(list::getSelectedValue));
                assertFalse(onEdt(() -> named(window.frame, "store.detailScroll").isShowing()));
                selectMain(window, latest);
                assertTrue(onEdt(() -> named(window.frame, "store.detailScroll").isShowing()));
                assertEquals("1.10.0", onEdt(() -> ((JComboBox<?>) named(window.frame, "store.rootVersions")).getSelectedItem()));
                combo(window.robot, (JComboBox<?>) named(window.frame, "store.rootVersions"), "1.9.0");
                idle();
                optional(window, older.owner(), addon.owner());
                click(window.robot, (JComponent) named(window.frame, "store.retry"));
                idle();
                assertEquals("1.9.0", onEdt(() -> ((JComboBox<?>) named(window.frame, "store.rootVersions")).getSelectedItem()));
                assertTrue(onEdt(() -> checkbox(window, older.owner(), addon.owner()).isSelected()));
                click(window.robot, (JComponent) named(window.frame, "store.collapse"));
                assertNull(onEdt(list::getSelectedValue));
                assertFalse(onEdt(() -> named(window.frame, "store.detailScroll").isShowing()));
                selectMain(window, latest);
                assertFalse(onEdt(() -> checkbox(window, latest.owner(), addon.owner()).isSelected()));
                noDownloads(fixture, older, latest, addon);
            }
        }
    }

    @Test
    void nativeSecondClickClearsPlanAndRefreshDoesNotRestoreSelectionOrApproval() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var main = fixture.publish("sample.deselect.root", "1.0.0", Map.of(), Map.of(), Map.of());
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                selectMain(window, main);
                var list = (JList<?>) named(window.frame, "store.results");
                clickArea(window.robot, list, onEdt(() -> list.getCellBounds(0, 0)));
                idle();
                assertNull(onEdt(list::getSelectedValue));
                assertEquals(0, onEdt(() -> ((JTable) named(window.frame, "store.plan")).getRowCount()));
                assertFalse(onEdt(() -> named(window.frame, "store.install").isEnabled()));
                click(window.robot, (JComponent) named(window.frame, "store.retry"));
                idle();
                assertNull(onEdt(list::getSelectedValue));
                assertEquals(0, onEdt(() -> ((JTable) named(window.frame, "store.plan")).getRowCount()));
                selectMain(window, main);
                openConfirmation(window);
                var approval = dialog(window.frame);
                onEdt(() -> {
                    list.clearSelection();
                    return null;
                });
                await(() -> !approval.isDisplayable());
                idle();
                assertFalse(onEdt(() -> named(window.frame, "store.install").isEnabled()));
                noDownloads(fixture, main);
            }
        }
    }

    @Test
    void clearedSelectionRejectsItsAlreadyQueuedResolutionWithoutRevivingPlan() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var main = fixture.publish("sample.stale.selection", "1.0.0", Map.of(), Map.of(), Map.of());
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                var list = (JList<?>) named(window.frame, "store.results");
                onEdt(() -> {
                    list.setSelectedIndex(0);
                    list.clearSelection();
                    return null;
                });
                idle();
                assertNull(onEdt(list::getSelectedValue));
                assertEquals(0, onEdt(() -> ((JTable) named(window.frame, "store.plan")).getRowCount()));
                assertFalse(onEdt(() -> named(window.frame, "store.install").isEnabled()));
                assertTrue(onEdt(() -> named(window.frame, "store.retry").isEnabled()));
                selectMain(window, main);
                assertTrue(onEdt(() -> named(window.frame, "store.install").isEnabled()));
                noDownloads(fixture, main);
            }
        }
    }

    @Test
    void nativeCompatibleVersionOptionalBranchesConfirmationAndInstalledReuseUseRealWire() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var core = fixture.publish("sample.store.core", "1.9.0", Map.of(), Map.of(), Map.of());
            var latest = fixture.publish(core.owner(), "2.0.0", Map.of(), Map.of(), Map.of());
            var leaf = fixture.publish("sample.store.leaf", "1.0.0", Map.of(), Map.of(), Map.of());
            var extra = fixture.publish("sample.store.extra", "1.0.0", Map.of(), Map.of(), Map.of());
            var addon = fixture.publish("sample.store.addon", "1.0.0", Map.of(leaf.owner(), "1.0.0"),
                Map.of(extra.owner(), "1.0.0"), Map.of(leaf.owner(), "Required addon support", extra.owner(), "Extra addon tool"));
            var main = fixture.publish("sample.store.root", "1.0.0", Map.of(core.owner(), "1.9.0"),
                Map.of(addon.owner(), "1.0.0"), Map.of(core.owner(), "Core function", addon.owner(), "Extra user tool"));
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                filter(window, main.owner());
                selectMain(window, main);
                selectOwner(window, core.owner());
                assertEquals("1.9.0", onEdt(() -> ((JComboBox<?>) named(window.frame, "store.versions")).getSelectedItem()));
                assertTrue(onEdt(() -> ((JTextArea) named(window.frame, "store.details")).getText().contains(fixture.source().toString())));
                chooseVersion(window, "2.0.0");
                assertFalse(onEdt(() -> named(window.frame, "store.install").isEnabled()));
                assertTrue(onEdt(() -> ((JTextArea) named(window.frame, "store.conflicts")).getText().contains(core.owner())));
                noDownloads(fixture, main, core, latest, addon, leaf, extra);
                chooseVersion(window, "1.9.0");
                optional(window, main.owner(), addon.owner());
                assertTrue(hasOwner(window, leaf.owner()));
                assertFalse(onEdt(() -> checkbox(window, addon.owner(), extra.owner()).isSelected()));
                openConfirmation(window);
                assertEquals(4, onEdt(() -> includedOwners(dialog(window.frame), "store.confirm.plan").size()));
                noDownloads(fixture, main, core, latest, addon, leaf, extra);
                click(window.robot, (JComponent) named(dialog(window.frame), "store.confirm.cancel"));
                idle();
                noDownloads(fixture, main, core, latest, addon, leaf, extra);
                approve(window);
                idle();
                var actual = fixture.service().lastResult().orElseThrow();
                assertTrue(actual.requiredReady());
                assertTrue(actual.allSelectedReady());
                assertEquals(Set.of(main, core, addon, leaf), actual.packages().stream().map(ModStoreBatchResult.PackageResult::key).collect(java.util.stream.Collectors.toSet()));
                actual.packages().forEach(value -> assertEquals(State.INSTALLED, value.state()));
                assertInstalled(fixture, main, core, addon, leaf);
                assertEquals(0, fixture.requests(latest));
                assertEquals(0, fixture.requests(extra));
                assertEquals(0, FilePlatformFixture.count(fixture.runtime().persistence().database(), "SELECT COUNT(*) FROM mod_registry WHERE enabled=TRUE"));
                click(window.robot, (JComponent) named(window.frame, "store.retry"));
                idle();
                assertTrue(onEdt(() -> ((JTable) named(window.frame, "store.plan")).getValueAt(0, 4).toString().contains("Mod disabled")));
                approve(window);
                idle();
                fixture.service().lastResult().orElseThrow().packages().forEach(value -> assertEquals(State.REUSED, value.state()));
                for (var key : List.of(main, core, addon, leaf)) assertEquals(1, fixture.requests(key));
                assertTrue(onEdt(() -> ((JTable) named(window.frame, "store.packageResults")).getRowCount() == 4));
            }
        }
    }

    @Test
    void nativeConflictingOptionalChoiceCanBeRemovedWithoutAnyPrematureDownload() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var core = fixture.publish("sample.conflict.core", "1.9.0", Map.of(), Map.of(), Map.of());
            var newer = fixture.publish(core.owner(), "2.0.0", Map.of(), Map.of(), Map.of());
            var addon = fixture.publish("sample.conflict.addon", "1.0.0", Map.of(core.owner(), "2.0.0"), Map.of(), Map.of());
            var main = fixture.publish("sample.conflict.root", "1.0.0", Map.of(core.owner(), "1.9.0"), Map.of(addon.owner(), "1.0.0"), Map.of());
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                filter(window, main.owner());
                selectMain(window, main);
                optional(window, main.owner(), addon.owner());
                assertFalse(onEdt(() -> named(window.frame, "store.install").isEnabled()));
                assertTrue(onEdt(() -> checkbox(window, main.owner(), addon.owner()).isSelected()));
                String evidence = onEdt(() -> ((JTextArea) named(window.frame, "store.conflicts")).getText());
                assertTrue(evidence.contains(main.owner()) && evidence.contains(addon.owner()) && evidence.contains("1.9.0") && evidence.contains("2.0.0"));
                assertTrue(onEdt(() -> descendants(window.frame).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .anyMatch(label -> "Purpose not declared".equals(label.getText()))));
                noDownloads(fixture, main, core, newer, addon);
                optional(window, main.owner(), addon.owner());
                assertTrue(onEdt(() -> named(window.frame, "store.install").isEnabled()));
                assertFalse(onEdt(() -> checkbox(window, main.owner(), addon.owner()).isSelected()));
                approve(window);
                idle();
                assertEquals(Set.of(main, core), fixture.service().lastResult().orElseThrow().packages().stream()
                    .map(ModStoreBatchResult.PackageResult::key).collect(java.util.stream.Collectors.toSet()));
                assertInstalled(fixture, main, core);
                assertEquals(0, fixture.requests(addon));
                assertEquals(0, fixture.requests(newer));
            }
        }
    }

    @Test
    void nativeCooperativeCancelKeepsCompletedPackageAndRetryReusesIt() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var first = fixture.publish("sample.cancel.first", "1.0.0", Map.of(), Map.of(), Map.of());
            var slow = fixture.publish("sample.cancel.slow", "1.0.0", Map.of(first.owner(), "1.0.0"), Map.of(), Map.of());
            var main = fixture.publish("sample.cancel.root", "1.0.0", Map.of(slow.owner(), "1.0.0"), Map.of(), Map.of());
            fixture.hold(slow);
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                filter(window, main.owner());
                selectMain(window, main);
                approve(window);
                fixture.awaitStarted(slow);
                await(() -> {
                    var table = (JTable) named(window.frame, "store.packageResults");
                    for (int row = 0; row < table.getRowCount(); row++)
                        if ((first.owner() + "@" + first.version()).equals(table.getValueAt(row, 0))
                            && "Installation confirmed".equals(table.getValueAt(row, 1))) return true;
                    return false;
                });
                assertInstalled(fixture, first);
                assertTrue(onEdt(() -> ((JTable) named(window.frame, "store.packageResults")).getRowCount() >= 1));
                click(window.robot, (JComponent) named(window.frame, "store.cancel"));
                fixture.release(slow);
                idle();
                var actual = fixture.service().lastResult().orElseThrow();
                assertEquals(TaskResult.CANCELLED, actual.outcome());
                assertState(actual, first, State.INSTALLED);
                assertState(actual, slow, State.CANCELLED);
                assertState(actual, main, State.UNATTEMPTED);
                assertFalse(actual.requiredReady());
                assertInstalled(fixture, first);
                assertFalse(Files.exists(installed(fixture, main)));
                click(window.robot, (JComponent) named(window.frame, "store.retry"));
                idle();
                approve(window);
                idle();
                var retried = fixture.service().lastResult().orElseThrow();
                assertTrue(retried.allSelectedReady());
                assertState(retried, first, State.REUSED);
                assertState(retried, slow, State.INSTALLED);
                assertState(retried, main, State.INSTALLED);
                assertEquals(1, fixture.requests(first));
                assertEquals(2, fixture.requests(slow));
                assertInstalled(fixture, first, slow, main);
            } finally {
                fixture.release(slow);
            }
        }
    }

    @Test
    void realOptionalHttpFailureShowsRequiredReadinessAndRetryPreservesInstalledPrograms() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var core = fixture.publish("sample.partial.core", "1.0.0", Map.of(), Map.of(), Map.of());
            var addon = fixture.publish("sample.partial.addon", "1.0.0", Map.of(), Map.of(), Map.of());
            var main = fixture.publish("sample.partial.root", "1.0.0", Map.of(core.owner(), "1.0.0"), Map.of(addon.owner(), "1.0.0"), Map.of());
            fixture.failure(addon, 503);
            try (var window = open(fixture, "en", ThemeMode.DARK, 150)) {
                onEdt(() -> {
                    window.frame.setSize(1280, 720);
                    return null;
                });
                idle();
                filter(window, main.owner());
                selectMain(window, main);
                optional(window, main.owner(), addon.owner());
                approve(window);
                idle();
                var actual = fixture.service().lastResult().orElseThrow();
                assertTrue(actual.requiredReady());
                assertFalse(actual.allSelectedReady());
                assertState(actual, addon, State.FAILED);
                assertState(actual, core, State.INSTALLED);
                assertState(actual, main, State.INSTALLED);
                assertTrue(onEdt(() -> ((JLabel) named(window.frame, "store.resultSummary")).getText().contains("Required packages are ready")));
                assertTaskResultsStayBounded(window);
                fixture.failure(addon, 0);
                click(window.robot, (JComponent) named(window.frame, "store.retry"));
                idle();
                approve(window);
                idle();
                var retried = fixture.service().lastResult().orElseThrow();
                assertTrue(retried.allSelectedReady());
                assertState(retried, core, State.REUSED);
                assertState(retried, main, State.REUSED);
                assertState(retried, addon, State.INSTALLED);
                assertEquals(1, fixture.requests(core));
                assertEquals(1, fixture.requests(main));
                assertEquals(2, fixture.requests(addon));
                assertInstalled(fixture, core, main, addon);
            }
        }
    }

    @Test
    void realLockRevokesAnUnsubmittedFullPlanApprovalAndNativeUnlockRequiresNewReview() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var main = fixture.publish("sample.lock.root", "1.0.0", Map.of(), Map.of(), Map.of());
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                filter(window, main.owner());
                selectMain(window, main);
                openConfirmation(window);
                var original = dialog(window.frame);
                fixture.runtime().security().lock();
                await(() -> !original.isDisplayable() && !named(window.frame, "store.search").isEnabled());
                assertEquals(0, fixture.requests(main));
                assertEquals(0, onEdt(() -> ((JList<?>) named(window.frame, "store.results")).getModel().getSize()));
                unlock(window);
                idle();
                selectMain(window, main);
                approve(window);
                idle();
                assertInstalled(fixture, main);
                assertEquals(1, fixture.requests(main));
            }
        }
    }

    @Test
    void realLateCatalogCallbackAfterLockUnlockMakesExactlyOneFreshQuery() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var main = fixture.publish("sample.late.root", "1.0.0", Map.of(), Map.of(), Map.of());
            fixture.holdCatalog();
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                fixture.awaitCatalogStarted();
                click(window.robot, (JComponent) named(window.frame, "security.lock"));
                await(() -> !fixture.runtime().security().securityState().unlocked());
                assertEquals(0, onEdt(() -> ((JList<?>) named(window.frame, "store.results")).getModel().getSize()));
                unlock(window);
                fixture.releaseCatalog();
                idle();
                assertEquals(2, fixture.catalogRequests());
                assertTrue(onEdt(() -> named(window.frame, "store.retry").isEnabled()));
                selectMain(window, main);
                idle();
                assertEquals(2, fixture.catalogRequests());
                assertEquals(0, fixture.requests(main));
            } finally {
                fixture.releaseCatalog();
            }
        }
    }

    @Test
    void closeViewDuringAcceptedBatchKeepsRealReceiptsAndNeverRevivesClosedControls() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var first = fixture.publish("sample.close.first", "1.0.0", Map.of(), Map.of(), Map.of());
            var slow = fixture.publish("sample.close.slow", "1.0.0", Map.of(first.owner(), "1.0.0"), Map.of(), Map.of());
            var main = fixture.publish("sample.close.root", "1.0.0", Map.of(slow.owner(), "1.0.0"), Map.of(), Map.of());
            fixture.hold(slow);
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                filter(window, main.owner());
                selectMain(window, main);
                approve(window);
                fixture.awaitStarted(slow);
                assertInstalled(fixture, first);
                onEdt(() -> {
                    window.pane().close();
                    return null;
                });
                fixture.release(slow);
                idle();
                fixture.service().lastResult().orElseThrow().packages().forEach(value -> assertEquals(State.INSTALLED, value.state()));
                assertInstalled(fixture, first, slow, main);
                assertEquals(0, onEdt(() -> ((JList<?>) named(window.frame, "store.results")).getModel().getSize()));
                assertEquals(0, onEdt(() -> ((JTable) named(window.frame, "store.packageResults")).getRowCount()));
                assertFalse(onEdt(() -> named(window.frame, "store.install").isEnabled()));
            } finally {
                fixture.release(slow);
            }
        }
    }

    @Test
    void nativeRapidVersionDraftsAndRefreshRetainOnlyLatestResolvedPin() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var versions = new ArrayList<ReleaseKey>();
            for (int version = 0; version < 32; version++)
                versions.add(fixture.publish("sample.rapid.core", "1.0." + version, Map.of(), Map.of(), Map.of()));
            var main = fixture.publish("sample.rapid.root", "1.0.0", Map.of("sample.rapid.core", "[1.0.0,2.0.0)"), Map.of(), Map.of());
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                filter(window, main.owner());
                selectMain(window, main);
                selectOwner(window, "sample.rapid.core");
                var combo = (JComboBox<?>) named(window.frame, "store.versions");
                click(window.robot, combo);
                window.robot.keyPress(KeyEvent.VK_ESCAPE);
                window.robot.keyRelease(KeyEvent.VK_ESCAPE);
                await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == combo);
                window.robot.setAutoDelay(0);
                try {
                    window.robot.keyPress(KeyEvent.VK_HOME);
                    window.robot.keyRelease(KeyEvent.VK_HOME);
                    for (int index = 0; index < 15; index++) {
                        window.robot.keyPress(KeyEvent.VK_DOWN);
                        window.robot.keyRelease(KeyEvent.VK_DOWN);
                    }
                    window.robot.waitForIdle();
                    click(window.robot, (JComponent) named(window.frame, "store.retry"));
                } finally {
                    window.robot.setAutoDelay(35);
                }
                idle();
                assertEquals("1.0.16", onEdt(combo::getSelectedItem));
                var table = (JTable) named(window.frame, "store.plan");
                assertEquals("1.0.16", onEdt(() -> {
                    for (int row = 0; row < table.getRowCount(); row++)
                        if ("sample.rapid.core".equals(table.getValueAt(row, 0))) return table.getValueAt(row, 2);
                    throw new AssertionError("Latest explicitly pinned owner missing");
                }));
                assertTrue(onEdt(() -> named(window.frame, "store.install").isEnabled()));
                noDownloads(fixture, versions.toArray(ReleaseKey[]::new));
                assertEquals(0, fixture.requests(main));
                approve(window);
                idle();
                assertInstalled(fixture, main, versions.get(16));
                for (var key : versions) assertEquals(key.equals(versions.get(16)) ? 1 : 0, fixture.requests(key));
            }
        }
    }

    @Test
    void realHostConflictRetainsExactRequestedReleaseAndPerformsZeroDownloads() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var main = fixture.publish("sample.host.root", "1.0.0", Map.of(), Map.of(), Map.of(), 100, "[90.0.0,91.0.0)");
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                var list = (JList<?>) named(window.frame, "store.results");
                clickArea(window.robot, list, onEdt(() -> list.getCellBounds(0, 0)));
                idle();
                assertFalse(onEdt(() -> named(window.frame, "store.install").isEnabled()));
                assertEquals(main.owner(), onEdt(() -> ((ModStoreGateway.Release) list.getSelectedValue()).owner()));
                assertTrue(onEdt(() -> ((JTextArea) named(window.frame, "store.details")).getText().contains("Java ≥ 100")));
                String evidence = onEdt(() -> ((JTextArea) named(window.frame, "store.conflicts")).getText());
                assertTrue(evidence.contains("MOD_STORE_REQUIREMENT_CONFLICT") && evidence.contains("host API") && evidence.contains("Java"));
                noDownloads(fixture, main);
            }
        }
    }

    @Test
    void realUnknownReceiptCanBeCheckedWithoutReplayingOriginalPackageDownload() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var main = fixture.publish("sample.unknown.root", "1.0.0", Map.of(), Map.of(), Map.of());
            PlatformModInstallationTest.trap(fixture.runtime(), "phase <> 'CLEANUP'");
            boolean trapped = true;
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                selectMain(window, main);
                approve(window);
                idle();
                var actual = fixture.service().lastResult().orElseThrow();
                assertEquals(TaskResult.UNKNOWN, actual.outcome());
                assertState(actual, main, State.UNKNOWN);
                var original = actual.packages().getFirst().operationId().orElseThrow();
                var identity = gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity.read(installed(fixture, main).getParent());
                assertEquals("REGISTERED", fixture.text("SELECT phase FROM mod_operation WHERE operation_id='" + original + "'"));
                assertTrue(onEdt(() -> ((JTextArea) named(window.frame, "store.receiptDetails")).getText().contains(original.toString())));
                assertTrue(onEdt(() -> ((JTable) named(window.frame, "store.packageResults")).getValueAt(0, 1).toString().contains("unconfirmed")));
                assertFalse(onEdt(() -> named(window.frame, "store.install").isEnabled()));
                assertTaskResultsStayBounded(window);
                PlatformModInstallationTest.untrap(fixture.runtime());
                trapped = false;
                click(window.robot, (JComponent) named(window.frame, "store.check"));
                idle();
                assertEquals(1, fixture.requests(main));
                assertEquals(0, fixture.count("SELECT COUNT(*) FROM mod_operation WHERE operation_id='" + original + "'"));
                assertEquals(identity, gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity.read(installed(fixture, main).getParent()));
                assertState(fixture.service().lastResult().orElseThrow(), main, State.UNKNOWN);
                assertTrue(onEdt(() -> {
                    String summary = ((JLabel) named(window.frame, "store.resultSummary")).getText();
                    return summary.contains(original.toString()) && summary.contains("Batch completed");
                }));
                assertInstalled(fixture, main);
            } finally {
                if (trapped) PlatformModInstallationTest.untrap(fixture.runtime());
            }
        }
    }

    @Test
    void remotePurposeMarkupRemainsLiteralInActualDraftAndConfirmation() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var addon = fixture.publish("sample.plain.addon", "1.0.0", Map.of(), Map.of(), Map.of());
            String purpose = "<html><img src='http://127.0.0.1:1/unrequested'>Literal purpose";
            var main = fixture.publish("sample.plain.root", "1.0.0", Map.of(), Map.of(addon.owner(), "1.0.0"), Map.of(addon.owner(), purpose));
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                filter(window, main.owner());
                selectMain(window, main);
                optional(window, main.owner(), addon.owner());
                assertPlainPurpose(window.frame, purpose);
                openConfirmation(window);
                assertPlainPurpose(dialog(window.frame), purpose);
                var table = (JTable) named(dialog(window.frame), "store.confirm.plan");
                assertTrue(onEdt(() -> Boolean.TRUE.equals(((JComponent) table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0)).getClientProperty("html.disable"))));
                click(window.robot, (JComponent) named(dialog(window.frame), "store.confirm.cancel"));
                idle();
                noDownloads(fixture, main, addon);
            }
        }
    }

    static void assertPlainPurpose(Component root, String purpose) throws Exception {
        onEdt(() -> {
            var label = descendants(root).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                .filter(value -> purpose.equals(value.getText())).findFirst().orElseThrow();
            assertEquals(Boolean.TRUE, label.getClientProperty("html.disable"));
            assertNull(label.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey));
            return null;
        });
    }

    @Test
    void nativePersistedAppearanceDiscardsLeftDetailDraftAndSmallWindowPlanControlsRemainReachable() throws Exception {
        float originalScale = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try (var fixture = new ModStoreFixture(root)) {
            var addon = fixture.publish("sample.appearance.addon", "1.0.0", Map.of(), Map.of(), Map.of());
            var main = fixture.publish("sample.appearance.root", "1.0.0", Map.of(), Map.of(addon.owner(), "1.0.0"), Map.of());
            try (var window = open(fixture, "en", ThemeMode.LIGHT, 100)) {
                idle();
                filter(window, main.owner());
                selectMain(window, main);
                optional(window, main.owner(), addon.owner());
                var original = window.pane();
                for (int scale : new int[]{100, 150})
                    for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                        for (String language : new String[]{"zh-CN", "en"}) {
                            appearance(window);
                            combo(window.robot, (JComboBox<?>) named(window.frame, "appearance.language"), language);
                            combo(window.robot, (JComboBox<?>) named(window.frame, "appearance.theme"), theme);
                            combo(window.robot, (JComboBox<?>) named(window.frame, "appearance.scale"), scale);
                            click(window.robot, (JComponent) named(window.frame, "appearance.save"));
                            idle();
                            assertEquals(Integer.toString(scale), fixture.runtime().persistence().preferences().find("ui_scale").orElseThrow());
                            store(window);
                            assertSame(original, window.pane());
                            assertEquals(main.owner(), onEdt(() -> ((JTextField) named(window.frame, "store.search")).getText()));
                            assertNull(onEdt(() -> ((JList<?>) named(window.frame, "store.results")).getSelectedValue()));
                            assertEquals(0, onEdt(() -> ((JTable) named(window.frame, "store.plan")).getRowCount()));
                            selectMain(window, main);
                            assertFalse(onEdt(() -> checkbox(window, main.owner(), addon.owner()).isSelected()));
                            optional(window, main.owner(), addon.owner());
                            String variant = theme + "-" + language + "-" + scale;
                            onEdt(() -> {
                                window.frame.setSize(1120, 820);
                                capture(window.frame, "store-" + variant + ".png");
                                window.frame.setSize(800, 560);
                                window.frame.validate();
                                return null;
                            });
                            var table = (JTable) named(window.frame, "store.plan");
                            clickArea(window.robot, table, onEdt(() -> table.getCellRect(0, 0, true)));
                            assertTrue(onEdt(() -> table.getVisibleRect().contains(table.getCellRect(0, 0, true))));
                            onEdt(() -> {
                                capture(window.frame, "store-" + variant + "-minimum-plan.png");
                                return null;
                            });
                            openConfirmation(window);
                            var accept = (JButton) named(dialog(window.frame), "store.confirm.accept");
                            onEdt(() -> {
                                reveal(accept, new Rectangle(0, 0, accept.getWidth(), accept.getHeight()));
                                assertEquals(accept.getHeight(), accept.getVisibleRect().height);
                                capture(dialog(window.frame), "store-" + variant + "-confirmation.png");
                                return null;
                            });
                            click(window.robot, (JComponent) named(dialog(window.frame), "store.confirm.cancel"));
                            onEdt(() -> {
                                var download = (JButton) named(window.frame, "store.install");
                                reveal(download, new Rectangle(0, 0, download.getWidth(), download.getHeight()));
                                assertEquals(download.getHeight(), download.getVisibleRect().height);
                                capture(window.frame, "store-" + variant + "-minimum-actions.png");
                                window.frame.setSize(1120, 820);
                                return null;
                            });
                            noDownloads(fixture, main, addon);
                        }
            }
        } finally {
            com.formdev.flatlaf.util.UIScale.setZoomFactor(originalScale);
        }
    }

    static WindowFixture open(ModStoreFixture fixture, String language, ThemeMode theme, int scale) throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        var robot = new Robot();
        robot.setAutoDelay(35);
        var messages = MessageCatalog.forLanguageTag(language);
        var intents = fixture.runtime().modIntents();
        var frame = onEdt(() -> {
            DesktopThemeService.apply(theme, scale);
            var previous = Set.copyOf(Arrays.asList(Window.getWindows()));
            try {
                var created = new PlatformFrame(fixture.runtime().security(), messages,
                    theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light(),
                    new UiEnvironment(messages.locale(), theme.name(), false), FailureReportStore.disabled());
                created.installAppearance(new DesktopDisplayConfig(language, theme, scale, false), requested -> {
                    try (var authorized = fixture.runtime().security().beginTask()) {
                        authorized.execute(() -> {
                            fixture.runtime().persistence().preferences().saveAll(Map.of("ui_locale", requested.localeTag(),
                                "ui_theme", requested.themeMode().name(), "ui_scale", Integer.toString(requested.scalePercent()),
                                "ui_restore_last_page", Boolean.toString(requested.restoreLastPage())));
                            return null;
                        });
                    }
                });
                created.installMods(intents, new PlatformModsPane.SchemaActions() {
                    public PlatformModIntents.RetryReview review(String owner, Optional<SchemaIdentity> source) throws Exception {
                        return fixture.runtime().modRecovery().review(owner, source);
                    }

                    public TaskResult retry(PlatformModIntents.RetryReview review) throws Exception {
                        return fixture.runtime().modRecovery().retry(review);
                    }

                    public TaskResult reconcile(UUID operation) throws Exception {
                        return fixture.runtime().modRecovery().reconcile(operation);
                    }
                });
                created.installStore(fixture.service());
                created.setSize(1120, 820);
                created.setLocation(20, 20);
                created.setVisible(true);
                return created;
            } catch (Exception | Error failure) {
                for (var window : Window.getWindows())
                    if (!previous.contains(window) && window instanceof PlatformFrame) dispose(window);
                throw failure;
            }
        });
        var window = new WindowFixture(fixture, frame, robot);
        try {
            store(window);
            return window;
        } catch (Exception | Error failure) {
            onEdt(() -> {
                var navigation = (JComponent) named(frame, "navigation.shell.mods");
                var selected = ((JTabbedPane) named(frame, "platform.pages")).getSelectedComponent();
                System.err.println("Store navigation failure: active=" + frame.isActive()
                    + ", navigationEnabled=" + navigation.isEnabled() + ", navigationShowing=" + navigation.isShowing()
                    + ", navigationBounds=" + SwingUtilities.convertRectangle(navigation.getParent(), navigation.getBounds(), frame)
                    + ", root=" + (selected == null ? "null" : selected.getName())
                    + ", glass=" + frame.getGlassPane().isVisible());
                capture(frame, "store-navigation-failure.png");
                return null;
            });
            window.close();
            throw failure;
        }
    }

    static void store(WindowFixture window) throws Exception {
        click(window.robot, (JComponent) named(window.frame, "navigation.shell.mods"));
        await(() -> named(window.frame, "mods.management").isShowing());
        if (!onEdt(() -> window.pane().isShowing())) {
            var button = onEdt(() -> descendants(window.frame).filter(JComponent.class::isInstance).map(JComponent.class::cast)
                .filter(value -> "modui.store".equals(value.getName()) && value.isShowing()).findFirst().orElseThrow());
            click(window.robot, button);
        }
        await(() -> window.pane().isShowing());
    }

    static void assertTaskResultsStayBounded(WindowFixture window) throws Exception {
        var toggle = (JToggleButton) named(window.frame, "store.taskToggle");
        var results = (JScrollPane) named(window.frame, "store.taskScroll");
        var details = (JScrollPane) named(window.frame, "store.detailScroll");
        assertFalse(onEdt(toggle::isSelected));
        assertFalse(onEdt(results::isShowing));
        assertTrue(onEdt(() -> details.getViewport().getHeight() > 0));
        click(window.robot, toggle);
        await(() -> results.isShowing() && results.getViewport().getHeight() > 0);
        assertTrue(onEdt(() -> named(window.frame, "store.taskArea").getHeight() <= window.pane().getHeight() / 3));
        assertTrue(onEdt(() -> details.getViewport().getHeight() > 0));
        assertTrue(onEdt(() -> ((JTable) named(window.frame, "store.packageResults")).getRowCount() > 0));
        assertFalse(onEdt(() -> ((JTextArea) named(window.frame, "store.receiptDetails")).getText().isBlank()));
        click(window.robot, toggle);
        assertFalse(onEdt(results::isShowing));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void appearance(WindowFixture window) throws Exception {
        click(window.robot, (JComponent) named(window.frame, "navigation.settings"));
        var button = (JButton) named(window.frame, "navigation.settings.appearance.title");
        var picker = (JComboBox<?>) named(window.frame, "settings.category");
        await(() -> button.isShowing() || picker.isShowing());
        if (onEdt(button::isShowing)) click(window.robot, button);
        else {
            Object target = onEdt(() -> {
                for (int index = 0; index < picker.getItemCount(); index++) {
                    var value = picker.getItemAt(index);
                    var label = (JLabel) ((ListCellRenderer) picker.getRenderer()).getListCellRendererComponent(new JList<>(), value, index, false, false);
                    if (button.getText().equals(label.getText())) return value;
                }
                throw new AssertionError("Actual compact appearance category not displayed");
            });
            combo(window.robot, picker, target);
        }
        await(() -> named(window.frame, "appearance.language").isShowing());
    }

    static void idle() throws Exception {
        await(() -> !DesktopTaskRunner.hasActiveTasks());
    }

    static void filter(WindowFixture window, String value) throws Exception {
        type(window.robot, window.frame, (JTextField) named(window.frame, "store.search"), value);
        window.robot.keyPress(KeyEvent.VK_ENTER);
        window.robot.keyRelease(KeyEvent.VK_ENTER);
        idle();
        assertEquals(value, onEdt(() -> ((JTextField) named(window.frame, "store.search")).getText()));
    }

    static void selectMain(WindowFixture window, ReleaseKey key) throws Exception {
        var list = (JList<?>) named(window.frame, "store.results");
        int index = onEdt(() -> {
            for (int row = 0; row < list.getModel().getSize(); row++) {
                var release = (ModStoreGateway.Release) list.getModel().getElementAt(row);
                if (key.owner().equals(release.owner())) return row;
            }
            throw new AssertionError("Actual main release not displayed: " + key);
        });
        if (onEdt(list::getSelectedIndex) != index)
            clickArea(window.robot, list, onEdt(() -> list.getCellBounds(index, index)));
        idle();
        var versions = (JComboBox<?>) named(window.frame, "store.rootVersions");
        if (!key.version().equals(onEdt(versions::getSelectedItem))) {
            combo(window.robot, versions, key.version());
            idle();
        }
        assertTrue(onEdt(() -> named(window.frame, "store.install").isEnabled()));
    }

    static void selectOwner(WindowFixture window, String owner) throws Exception {
        var table = (JTable) named(window.frame, "store.plan");
        int row = onEdt(() -> {
            for (int index = 0; index < table.getRowCount(); index++)
                if (owner.equals(table.getValueAt(index, 0))) return index;
            throw new AssertionError("Actual owner not displayed: " + owner);
        });
        clickArea(window.robot, table, onEdt(() -> table.getCellRect(row, 0, true)));
    }

    static void chooseVersion(WindowFixture window, String version) throws Exception {
        combo(window.robot, (JComboBox<?>) named(window.frame, "store.versions"), version);
        idle();
        assertEquals(version, onEdt(() -> ((JComboBox<?>) named(window.frame, "store.versions")).getSelectedItem()));
    }

    static void optional(WindowFixture window, String consumer, String dependency) throws Exception {
        click(window.robot, checkbox(window, consumer, dependency));
        idle();
    }

    static JCheckBox checkbox(WindowFixture window, String consumer, String dependency) {
        return (JCheckBox) named(window.frame, "store.optional." + consumer + "." + dependency);
    }

    static boolean hasOwner(WindowFixture window, String owner) throws Exception {
        return onEdt(() -> includedOwners(window.frame, "store.plan").contains(owner));
    }

    static Set<String> includedOwners(Component component, String name) {
        var table = (JTable) named(component, name);
        var owners = new HashSet<String>();
        for (int row = 0; row < table.getRowCount(); row++)
            if (!table.getValueAt(row, 3).equals("—")) owners.add(table.getValueAt(row, 0).toString());
        return owners;
    }

    static void openConfirmation(WindowFixture window) throws Exception {
        click(window.robot, (JComponent) named(window.frame, "store.install"));
        await(() -> dialog(window.frame) != null);
    }

    static void approve(WindowFixture window) throws Exception {
        openConfirmation(window);
        click(window.robot, (JComponent) named(dialog(window.frame), "store.confirm.accept"));
        await(() -> dialog(window.frame) == null);
    }

    static JDialog dialog(Window frame) {
        return Arrays.stream(frame.getOwnedWindows()).filter(window -> window.isShowing()
                && window instanceof JDialog && "store.confirmation".equals(window.getName()))
            .map(JDialog.class::cast).findFirst().orElse(null);
    }

    static void unlock(WindowFixture window) throws Exception {
        click(window.robot, (JComponent) named(window.frame, "security.lock"));
        await(() -> Arrays.stream(window.frame.getOwnedWindows()).anyMatch(owned -> owned.isShowing()
            && "security.unlockDialog".equals(owned.getName())));
        var dialog = onEdt(() -> Arrays.stream(window.frame.getOwnedWindows()).filter(owned -> owned.isShowing()
            && "security.unlockDialog".equals(owned.getName())).findFirst().orElseThrow());
        type(window.robot, dialog, (JTextField) named(dialog, "security.password"), ModStoreFixture.PASSWORD);
        click(window.robot, (JComponent) named(dialog, "security.submit"));
        await(() -> window.fixture.runtime().security().securityState().unlocked()
            && !dialog.isDisplayable() && !window.frame.getGlassPane().isVisible());
    }

    static void tab(Robot robot, JTabbedPane tabs, Component component) throws Exception {
        clickArea(robot, tabs, onEdt(() -> tabs.getBoundsAt(tabs.indexOfComponent(component))));
    }

    static void combo(Robot robot, JComboBox<?> combo, Object value) throws Exception {
        int index = onEdt(() -> {
            for (int row = 0; row < combo.getItemCount(); row++)
                if (value.equals(combo.getItemAt(row))) return row;
            throw new AssertionError("Actual version or preference candidate not displayed: " + value);
        });
        click(robot, combo);
        robot.keyPress(KeyEvent.VK_HOME);
        robot.keyRelease(KeyEvent.VK_HOME);
        for (int row = 0; row < index; row++) {
            robot.keyPress(KeyEvent.VK_DOWN);
            robot.keyRelease(KeyEvent.VK_DOWN);
        }
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        robot.waitForIdle();
    }

    static void click(Robot robot, JComponent component) throws Exception {
        clickArea(robot, component, onEdt(() -> new Rectangle(0, 0, component.getWidth(), component.getHeight())));
    }

    static void clickArea(Robot robot, JComponent component, Rectangle area) throws Exception {
        var owner = onEdt(() -> SwingUtilities.getWindowAncestor(component));
        onEdt(() -> {
            if (!owner.isActive()) {
                owner.toFront();
                owner.requestFocus();
            }
            return null;
        });
        await(owner::isActive);
        Point point = onEdt(() -> {
            assertTrue(component.isEnabled(), component.getName());
            reveal(component, area);
            assertTrue(component.getVisibleRect().intersects(area), component.getName() + " actual click area is not visible");
            Point actual = component.getLocationOnScreen();
            actual.translate(area.x + area.width / 2, area.y + area.height / 2);
            return actual;
        });
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    static void reveal(JComponent component, Rectangle area) {
        component.scrollRectToVisible(area);
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof JViewport viewport && viewport.getView() instanceof JComponent content)
                content.scrollRectToVisible(SwingUtilities.convertRectangle(component, area, content));
        var window = SwingUtilities.getWindowAncestor(component);
        if (window != null) window.validate();
    }

    static void type(Robot robot, Window owner, JTextField field, String text) throws Exception {
        click(robot, field);
        await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == field);
        var input = onEdt(owner::getInputContext);
        boolean composition = onEdt(input::isCompositionEnabled);
        try {
            onEdt(() -> {
                input.endComposition();
                input.setCompositionEnabled(false);
                return null;
            });
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_A);
            robot.keyRelease(KeyEvent.VK_A);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            for (char character : text.toCharArray()) {
                int key = KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(character));
                robot.keyPress(key);
                robot.keyRelease(key);
            }
            robot.waitForIdle();
            assertEquals(text, onEdt(field::getText));
        } finally {
            onEdt(() -> {
                input.setCompositionEnabled(composition);
                return null;
            });
        }
    }

    static void noDownloads(ModStoreFixture fixture, ReleaseKey... keys) {
        for (var key : keys) assertEquals(0, fixture.requests(key), key.toString());
    }

    static Path installed(ModStoreFixture fixture, ReleaseKey key) {
        return fixture.home().resolve("mod").resolve(key.owner()).resolve(key.version()).resolve("mod.json");
    }

    static void assertInstalled(ModStoreFixture fixture, ReleaseKey... keys) throws Exception {
        for (var key : keys) {
            var manifest = gold.debug.windowstolinux.app.mod.config.ManifestReader.read(Files.readAllBytes(installed(fixture, key)));
            assertEquals(key.owner(), manifest.id());
            assertEquals(key.version(), manifest.version().toString());
        }
    }

    static void assertState(ModStoreBatchResult result, ReleaseKey key, State state) {
        assertEquals(state, result.packages().stream().filter(value -> value.key().equals(key)).findFirst().orElseThrow().state(), key.toString());
    }

    static void dispose(Window window) {
        for (var owned : window.getOwnedWindows()) dispose(owned);
        window.dispose();
    }

    record WindowFixture(ModStoreFixture fixture, PlatformFrame frame, Robot robot) implements AutoCloseable {
        ModStorePane pane() {
            return (ModStorePane) named(frame, "mods.store");
        }

        @Override
        public void close() throws Exception {
            onEdt(() -> {
                pane().close();
                dispose(frame);
                return null;
            });
        }
    }
}
