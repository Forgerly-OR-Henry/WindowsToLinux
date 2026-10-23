package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.mod.dev.DevelopmentInfo;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.*;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.PageRegistration;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModNavigationTest {
    @TempDir
    Path root;

    PlatformModFlowTest fixture;

    PlatformRuntime runtime;

    PlatformFrame frame;

    @BeforeEach
    void setup() throws Exception {
        fixture = new PlatformModFlowTest();
        fixture.root = root;
        fixture.workspace();
        module(P);
        module(FREE);
        fixture.enable(P, FREE);
        start();
    }

    void module(String owner) throws Exception {
        fixture.module(owner, Map.of(), false);
        var module = fixture.workspace.resolve("src/mod/" + owner);
        var source = module.resolve("Mod.java");
        Files.writeString(source,
            """
                package %s;
                import java.nio.file.*;
                import javax.swing.*;
                import gold.debug.windowstolinux.appapi.mod.ModContext; import gold.debug.windowstolinux.appapi.mod.ModEntrypoint; import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
                import gold.debug.windowstolinux.appapi.ui.page.ModPage; import gold.debug.windowstolinux.appapi.ui.page.PageSpec; import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
                public final class Mod implements ModEntrypoint {
                    static void trace(String text) throws Exception {
                        Files.writeString(Path.of("%s"),"%s:"+text+"\\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    }
                    public void start(ModContext context) throws Exception {
                        System.getProperties().put("%s.ui",context.ui());
                        for(String id:new String[]{"main","settings"}){
                            var registration=context.ui().register(new PageSpec(id,"fixture."+id,id.equals("main")?"本机业务页":"本机设置页",id.equals("settings")),()->new ModPage(){
                                public JComponent create(UiEnvironment environment) throws Exception {
                                    trace("create:"+id);
                                    var label=new JTextField("%s / "+id);
                                    label.setName("%s."+id);
                                    return label;
                                }
                                public void environmentChanged(UiEnvironment value){}
                                public void close() throws Exception {trace("close:"+id);}
                            });
                            System.getProperties().put("%s."+id,registration);
                        }
                    }
                    public void stop(StopContext context) {
                        System.getProperties().remove("%s.ui");
                        for(String id:new String[]{"main","settings"})System.getProperties().remove("%s."+id);
                    }
                }
                """
                .formatted(owner, fixture.trace.toString().replace("\\", "\\\\"), owner, owner, owner, owner,
                    owner, owner, owner));
        var classes = module.resolve("target/classes");
        String cp = Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none",
            "-encoding", "UTF-8", "-classpath", cp, "-d", classes.toString(), source.toString()));
        DevelopmentInfo.generate(fixture.workspace, module.resolve("mod.json"), classes, List.of(),
            List.of(fixture.workspace.resolve("pom.xml"), fixture.workspace.resolve("src/mod/pom.xml"),
                module.resolve("pom.xml")),
            List.of());
    }

    void start() throws Exception {
        runtime = fixture.open(true);
        frame = onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var value = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                FailureReportStore.disabled());
            value.onExit(runtime);
            value.setVisible(true);
            return value;
        });
        ApplicationLauncher.startMods(runtime, new RunModeResolver.RuntimeLayout(RunModeResolver.RunMode.RUN_CLASS,
                fixture.workspace, runtime.persistence().database().root()),
            frame.pages());
        ApplicationLauncher.installModManagement(runtime, frame);
        idle();
        select(P);
    }

    void idle() throws Exception {
        await(() -> !DesktopTaskRunner.hasActiveTasks());
    }

    void select(String owner) throws Exception {
        onEdt(() -> {
            var tabs = (JTabbedPane) named(frame, "platform.pages");
            tabs.setSelectedIndex(tabs.indexOfTab("Mod"));
            var list = (JList<?>) named(frame, "mods.inventory");
            for (int i = 0; i < list.getModel().getSize(); i++)
                if (((ModView) list.getModel().getElementAt(i)).owner().equals(owner))
                    list.setSelectedIndex(i);
            assertEquals(owner, ((ModView) list.getSelectedValue()).owner());
            return null;
        });
    }

    void click(String name) throws Exception {
        onEdt(() -> {
            var button = (AbstractButton) named(frame, name);
            assertTrue(button.isEnabled(), name);
            button.doClick(0);
            return null;
        });
        idle();
    }

    JButton rootButton(String owner) throws Exception {
        return (JButton) descendants(named(frame, "mods.navigation")).filter(c -> c instanceof JButton button
                && button.getClientProperty("mod.pageKey") instanceof PageKey key && key.ownerId().equals(owner))
            .findFirst().orElseThrow();
    }

    void clickRoot(String owner) throws Exception {
        onEdt(() -> {
            rootButton(owner).doClick(0);
            return null;
        });
        idle();
    }

    int pins() throws Exception {
        return onEdt(() -> (int) descendants(named(frame, "mods.navigation"))
            .filter(c -> c instanceof JButton && c.getName() != null && c.getName().startsWith("mods.pinned."))
            .count());
    }

    void stop() throws Exception {
        idle();
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        onEdt(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
            return null;
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        stop();
    }

    @Test
    void publishedModOpensActualPlatformDestinationsAndReturnsToItsOriginalDraft() throws Exception {
        var models = runtime.models();
        onEdt(() -> {
            frame.installServers(runtime.servers());
            frame.installModels(models);
            frame.installLogs(runtime.logs());
            frame.installTasks(new gold.debug.windowstolinux.app.service.task.PlatformTaskManagement(
                runtime.tasks(), runtime.security(), id -> runtime.recovery().reconcile(id, true), runtime.modRecovery()::release));
            return null;
        });
        idle();
        clickRoot(P);
        var ui = (gold.debug.windowstolinux.appapi.ui.UiService) System.getProperties().get(P + ".ui");
        var field = onEdt(() -> (JTextField) named(frame, P + ".main"));
        onEdt(() -> {
            field.setText("retained deployment draft");
            return null;
        });
        var destinations = Map.of(
            gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind.SERVERS, "platform.servers",
            gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind.AI_MODELS, "platform.models",
            gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind.TASKS, "tasks.title");
        for (var destination : destinations.entrySet()) {
            ui.openPlatform(destination.getKey());
            assertEquals(destination.getValue(), onEdt(frame::currentPage).platformId());
            clickRoot(P);
            assertSame(field, onEdt(() -> named(frame, P + ".main")));
            assertEquals("retained deployment draft", onEdt(field::getText));
        }
        runtime.security().lock();
        await(() -> ui.environment().locked());
        assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,
            () -> ui.openPlatform(gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind.SERVERS));
        assertEquals("retained deployment draft", onEdt(field::getText));
    }

    @Test
    void rootOrderMergesOldPagesPreservesUnpublishedOwnersAndRejectsStaleWrites() throws Exception {
        var service = runtime.modIntents();
        var first = new PagePin(P, "main");
        var second = new PagePin(FREE, "main");
        var absent = new PagePin("example.absent", "legacy");
        runtime.persistence().preferences().save("mod_navigation_pins", "{\"formatVersion\":1,\"pages\":["
            + "{\"ownerId\":\"" + FREE + "\",\"pageId\":\"old-applications\"},"
            + "{\"ownerId\":\"example.absent\",\"pageId\":\"legacy\"},"
            + "{\"ownerId\":\"" + P + "\",\"pageId\":\"old-static\"},"
            + "{\"ownerId\":\"" + FREE + "\",\"pageId\":\"old-agent\"}]}");
        var before = service.workspacePins(List.of(first, second));
        assertEquals(List.of(second, absent, first), before);
        assertEquals(List.of(first, absent, second), service.reorderPins(before, List.of(first, second)));
        assertThrows(ApiException.class, () -> service.reorderPins(before, List.of(second, first)));
        var current = service.pins();
        assertThrows(ApiException.class, () -> service.reorderPins(current, List.of(first, first)));
        assertEquals(current, service.pins());
        assertEquals(current, service.workspacePins(List.of(first, second)));
    }

    @Test
    void appearanceReordersAutomaticRootsWithoutRecreatingViews() throws Exception {
        var service = runtime.modIntents();
        var first = service.pins().get(0);
        var second = service.pins().get(1);
        var order = new gold.debug.windowstolinux.app.service.setting.NavigationOrderService(runtime.persistence().navigation(), runtime.security());
        onEdt(() -> {
            frame.installAppearance(new DesktopDisplayConfig("zh-CN", ThemeMode.LIGHT), ignored -> {
            });
            frame.installNavigationOrder(order);
            ((JTabbedPane) named(frame, "platform.pages")).setSelectedComponent(named(frame, "platform.appearance"));
            return null;
        });
        idle();
        onEdt(() -> {
            var list = (JList<?>) named(frame, "navigation.mods.list");
            assertEquals(2, list.getModel().getSize());
            list.setSelectedIndex(1);
            ((JButton) named(frame, "navigation.mods.up")).doClick(0);
            return null;
        });
        idle();
        assertEquals(List.of(second, first), service.pins());
        onEdt(() -> {
            capture(frame, "configured-pinned-order.png");
            return null;
        });
        assertFalse(Files.exists(fixture.trace) && Files.readString(fixture.trace).contains("create:main"));
    }

    @Test
    void automaticRootsOpenLazilyRetainDraftsAndCloseThenRecreate() throws Exception {
        assertEquals(2, pins());
        assertTrue(fixture.actions().isEmpty());
        assertEquals(2, runtime.modIntents().pins().size());
        click("mods.openPage");
        var field = onEdt(() -> (JTextField) named(frame, P + ".main"));
        onEdt(() -> {
            field.setText("retained draft");
            return null;
        });
        clickRoot(FREE);
        assertTrue(onEdt(() -> named(frame, FREE + ".main").isShowing()));
        assertFalse(onEdt(field::isShowing));
        clickRoot(P);
        assertSame(field, onEdt(() -> named(frame, P + ".main")));
        assertEquals("retained draft", onEdt(field::getText));
        assertEquals(1, fixture.actions().stream().filter((P + ":create:main")::equals).count());
        frame.pages().closeView(new PageKey(P, "main"));
        assertEquals(2, pins());
        clickRoot(P);
        assertNotSame(field, onEdt(() -> named(frame, P + ".main")));
        assertEquals(2, fixture.actions().stream().filter((P + ":create:main")::equals).count());
        select(P);
        onEdt(() -> {
            ((JComboBox<?>) named(frame, "mods.pages")).setSelectedIndex(1);
            return null;
        });
        click("mods.openPage");
        assertTrue(onEdt(() -> named(frame, P + ".settings").isShowing()));
        assertEquals(2, pins());
    }

    @Test
    void rootsKeepOrderAcrossRestartAndDisappearOnlyAfterActualDisable() throws Exception {
        var before = runtime.modIntents().pins();
        stop();
        Files.writeString(fixture.trace, "");
        start();
        assertEquals(2, pins());
        assertTrue(fixture.actions().isEmpty());
        var service = runtime.modIntents();
        service.apply(service.review(P, choice(false, VERSION), Optional.empty()), true);
        idle();
        assertEquals(frame.pages().contributions().stream().filter(page -> !page.spec().settingsPage()).count(), pins());
        stop();
        start();
        assertEquals(1, pins());
        assertEquals(0, onEdt(() -> ((JComboBox<?>) named(frame, "mods.pages")).getItemCount()));
        assertFalse(onEdt(() -> named(frame, "mods.openPage").isEnabled()));
        assertEquals(before, runtime.modIntents().pins());
        service = runtime.modIntents();
        service.apply(service.review(P, choice(true, VERSION), Optional.empty()), false);
        stop();
        start();
        assertEquals(2, pins());
        assertTrue(fixture.actions().isEmpty());
    }

    @Test
    void identicalRootIdsInDifferentOwnersRemainIndependent() throws Exception {
        assertEquals(2, pins());
        clickRoot(FREE);
        assertTrue(onEdt(() -> named(frame, FREE + ".main").isShowing()));
        assertEquals(List.of(FREE + ":create:main"), fixture.actions());
        clickRoot(P);
        assertTrue(onEdt(() -> named(frame, P + ".main").isShowing()));
        assertFalse(onEdt(() -> named(frame, FREE + ".main").isShowing()));
        assertEquals(2, pins());
    }

    @Test
    void removingARegistrationRevokesPinAndStaleButtonsCannotRecreateIt() throws Exception {
        var stale = onEdt(() -> rootButton(P));
        ((PageRegistration) System.getProperties().get(P + ".main")).close();
        await(() -> !stale.isShowing());
        assertEquals(1, pins());
        onEdt(() -> {
            stale.doClick(0);
            return null;
        });
        assertTrue(fixture.actions().isEmpty());
        assertEquals(2, runtime.modIntents().pins().size());
        assertEquals(1, onEdt(() -> ((JComboBox<?>) named(frame, "mods.pages")).getItemCount()));
        assertThrows(ApiException.class, () -> frame.pages().openView(new PageKey(P, "main")));
        var created = new java.util.concurrent.atomic.AtomicInteger();
        var ui = (UiService) System.getProperties().get(P + ".ui");
        try (var replacement = ui.register(new PageSpec("main", "fixture.replacement", "重新发布的页面", false),
            () -> new ModPage() {
                public JComponent create(UiEnvironment environment) {
                    created.incrementAndGet();
                    var label = new JLabel("replacement");
                    label.setName("replacement.main");
                    return label;
                }

                public void environmentChanged(UiEnvironment environment) {
                }

                public void close() {
                }
            })) {
            await(() -> descendants(named(frame, "mods.navigation")).filter(c -> c instanceof JButton
                && c.getName() != null && c.getName().startsWith("mods.pinned.")).count() == 2);
            assertEquals(2, pins());
            assertEquals(0, created.get());
            clickRoot(P);
            assertEquals(1, created.get());
            assertTrue(onEdt(() -> named(frame, "replacement.main").isShowing()));
        }
        await(() -> descendants(named(frame, "mods.navigation")).filter(c -> c instanceof JButton
            && c.getName() != null && c.getName().startsWith("mods.pinned.")).count() == 1);
    }

    @Test
    void lockedNavigationRemainsVisibleAndOriginalScopeStillRejectsOpening() throws Exception {
        runtime.security().lock();
        await(() -> frame.getGlassPane().isVisible());
        assertEquals(2, pins());
        assertEquals(0, onEdt(() -> ((JComboBox<?>) named(frame, "mods.pages")).getItemCount()));
        assertThrows(Exception.class, () -> runtime.modIntents().pins());
        assertThrows(ApiException.class, () -> frame.pages().openView(new PageKey(P, "main")));
        runtime.security().unlock(PASSWORD.toCharArray());
        await(() -> !frame.getGlassPane().isVisible());
        idle();
        assertEquals(2, pins());
        assertTrue(fixture.actions().isEmpty());
        ((UiService) System.getProperties().get(P + ".ui")).open("settings");
        assertTrue(onEdt(() -> named(frame, P + ".settings").isShowing()));
    }

    @Test
    void pendingWholeDeletionPreservesItsOwnedPinsUntilTheOriginalCleanupFinishes() throws Exception {
        var original = runtime.modIntents().pins();
        UUID operation = UUID.randomUUID();
        var db = runtime.persistence().database();
        sql(db, "INSERT INTO mod_operation(operation_id,mod_id,operation_type,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,'DELETE_MOD','UNKNOWN','DATA_REMOVED','{}','{}',?,1,1)",
            operation, P, UUID.randomUUID().toString());
        try {
            assertEquals("MOD_DELETE_PENDING",
                assertThrows(ApiException.class, () -> runtime.modIntents().pin(new PagePin(P, "main"), false))
                    .problem().eventCode());
            assertEquals("MOD_DELETE_PENDING",
                assertThrows(ApiException.class, () -> runtime.modIntents().pin(new PagePin(P, "settings"), true))
                    .problem().eventCode());
            assertEquals(original, runtime.modIntents().pins());
            assertEquals("MOD_DELETE_PENDING", assertThrows(ApiException.class,
                () -> runtime.modIntents().workspacePins(List.of(new PagePin(P, "replacement"), new PagePin(FREE, "main"))))
                .problem().eventCode());
            assertEquals(original, runtime.modIntents().pins());
            runtime.modIntents().pin(new PagePin(FREE, "main"), true);
            assertTrue(runtime.modIntents().pins().contains(new PagePin(FREE, "main")));
        } finally {
            sql(db, "DELETE FROM mod_operation WHERE operation_id=?", operation);
        }
        runtime.modIntents().pin(new PagePin(P, "main"), false);
        assertFalse(runtime.modIntents().pins().contains(new PagePin(P, "main")));
    }

    @Test
    void corruptedOrderIsReportedAndRetainedWithoutRemovingPublishedRoots() throws Exception {
        for (String corrupted : List.of("", "null", "{\"formatVersion\":1,\"formatVersion\":1,\"pages\":[]}")) {
            sql(runtime.persistence().database(),
                "UPDATE desktop_preference SET preference_value=? WHERE preference_key='mod_navigation_pins'", corrupted);
            onEdt(() -> {
                ((gold.debug.windowstolinux.app.ui.mod.ModNavigationPane) named(frame, "mods.navigation"))
                    .refreshPreferences();
                return null;
            });
            idle();
            assertEquals(2, pins());
            assertEquals("MOD_NAVIGATION_INVALID", assertThrows(ApiException.class,
                () -> runtime.modIntents().workspacePins(List.of(new PagePin(P, "main"), new PagePin(FREE, "main"))))
                .problem().eventCode());
            assertEquals(corrupted, runtime.persistence().preferences().find("mod_navigation_pins").orElseThrow());
            assertTrue(onEdt(() -> !((JLabel) named(frame, "mods.navigationStatus")).getText().isEmpty()));
            assertTrue(fixture.actions().isEmpty());
        }
    }

    @Test
    void explicitCloseDefaultsToCancelAndReopeningCreatesAFreshRoot() throws Exception {
        clickRoot(P);
        var field = onEdt(() -> (JTextField) named(frame, P + ".main"));
        onEdt(() -> {
            field.setText("unsubmitted draft");
            return null;
        });
        SwingUtilities.invokeLater(() -> {
            try {
                ((JButton) named(frame, "mods.workspace.close")).doClick(0);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        await(() -> java.util.Arrays.stream(frame.getOwnedWindows()).anyMatch(java.awt.Window::isShowing));
        var dialog = onEdt(() -> java.util.Arrays.stream(frame.getOwnedWindows()).filter(java.awt.Window::isShowing)
            .findFirst().orElseThrow());
        onEdt(() -> {
            var buttons = descendants(dialog).filter(JButton.class::isInstance).map(JButton.class::cast).toList();
            var cancel = buttons.stream().filter(button -> button.getText().equals("取消")).findFirst().orElseThrow();
            assertSame(cancel, ((JDialog) dialog).getRootPane().getDefaultButton());
            cancel.doClick(0);
            return null;
        });
        assertSame(field, onEdt(() -> named(frame, P + ".main")));
        assertEquals("unsubmitted draft", onEdt(field::getText));
        SwingUtilities.invokeLater(() -> {
            try {
                ((JButton) named(frame, "mods.workspace.close")).doClick(0);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        await(() -> java.util.Arrays.stream(frame.getOwnedWindows()).anyMatch(java.awt.Window::isShowing));
        onEdt(() -> {
            var prompt = java.util.Arrays.stream(frame.getOwnedWindows()).filter(java.awt.Window::isShowing)
                .findFirst().orElseThrow();
            descendants(prompt).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> button.getText().equals("关闭工作区")).findFirst().orElseThrow().doClick(0);
            return null;
        });
        assertEquals("shell.home", onEdt(frame::currentPage).platformId());
        assertEquals(2, pins());
        clickRoot(P);
        assertNotSame(field, onEdt(() -> named(frame, P + ".main")));
    }

}
