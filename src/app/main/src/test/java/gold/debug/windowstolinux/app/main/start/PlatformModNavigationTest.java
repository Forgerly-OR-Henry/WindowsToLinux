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
import gold.debug.windowstolinux.app.mod.dev.DevelopmentDescriptor;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.*;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.ui.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModNavigationTest {
    @TempDir
    Path root;

    PlatformModCoordinatorTest fixture;

    PlatformRuntime runtime;

    PlatformFrame frame;

    @BeforeEach
    void setup() throws Exception {
        fixture = new PlatformModCoordinatorTest();
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
                import gold.debug.windowstolinux.appapi.mod.*;
                import gold.debug.windowstolinux.appapi.ui.*;
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
                                    var label=new JLabel("%s / "+id);
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
        DevelopmentDescriptor.generate(fixture.workspace, module.resolve("mod.json"), classes, List.of(),
            List.of(fixture.workspace.resolve("pom.xml"), fixture.workspace.resolve("src/mod/pom.xml"),
                module.resolve("pom.xml")),
            List.of());
    }

    void start() throws Exception {
        runtime = fixture.open(true);
        frame = onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var value = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                FailureReportStore.disabled());
            value.onExit(runtime);
            value.setVisible(true);
            return value;
        });
        DesktopMain.startMods(runtime, new RunModeResolver.RuntimeLayout(RunModeResolver.RunMode.RUN_CLASS,
                fixture.workspace, runtime.persistence().database().root()), fixture.workspace.toString(),
            frame.pages());
        DesktopMain.installModManagement(runtime, frame);
        idle();
        select(P);
    }

    void idle() throws Exception {
        await(() -> !DesktopTaskExecutor.hasActiveTasks());
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
    void publishedPagesOpenLazilyCloseAndRecreateWhilePinsNeverAutoOpen() throws Exception {
        assertEquals(0, pins());
        assertTrue(fixture.actions().isEmpty());
        click("mods.pinPage");
        assertEquals(1, pins());
        assertTrue(fixture.actions().isEmpty());
        assertEquals(List.of(new PagePin(P, "main")), runtime.modIntents().pins());
        click("mods.openPage");
        assertTrue(onEdt(() -> named(frame, P + ".main").isShowing()));
        click("mods.pinned.0");
        assertEquals(List.of(P + ":create:main"), fixture.actions());
        frame.pages().closeView(new PageKey(P, "main"));
        assertEquals(List.of(P + ":create:main", P + ":close:main"), fixture.actions());
        click("mods.pinned.0");
        assertEquals(2, fixture.actions().stream().filter((P + ":create:main")::equals).count());
        select(P);
        onEdt(() -> {
            ((JComboBox<?>) named(frame, "mods.pages")).setSelectedIndex(1);
            return null;
        });
        click("mods.openPage");
        assertTrue(onEdt(() -> named(frame, P + ".settings").isShowing()));
        assertEquals(1, pins());
        onEdt(() -> {
            capture(frame, "platform-mod-navigation-settings.png");
            return null;
        });
    }

    @Test
    void pinsSurviveRestartHideWhenDisabledAndReappearWithoutCreatingViews() throws Exception {
        click("mods.pinPage");
        stop();
        Files.writeString(fixture.trace, "");
        start();
        assertEquals(1, pins());
        assertTrue(fixture.actions().isEmpty());
        var service = runtime.modIntents();
        service.apply(service.review(P, choice(false, VERSION), Optional.empty()), true);
        assertEquals(1, pins());
        stop();
        start();
        assertEquals(0, pins());
        assertEquals(0, onEdt(() -> ((JComboBox<?>) named(frame, "mods.pages")).getItemCount()));
        assertFalse(onEdt(() -> named(frame, "mods.openPage").isEnabled()));
        assertEquals(List.of(new PagePin(P, "main")), runtime.modIntents().pins());
        service = runtime.modIntents();
        service.apply(service.review(P, choice(true, VERSION), Optional.empty()), false);
        stop();
        start();
        assertEquals(1, pins());
        assertTrue(fixture.actions().isEmpty());
        onEdt(() -> {
            capture(frame, "platform-mod-navigation-restored.png");
            return null;
        });
    }

    @Test
    void sameLocalPageIdInTwoModsKeepsExactIndependentPins() throws Exception {
        click("mods.pinPage");
        select(FREE);
        click("mods.pinPage");
        assertEquals(2, pins());
        click("mods.pinned.1");
        assertTrue(onEdt(() -> named(frame, FREE + ".main").isShowing()));
        assertEquals(List.of(FREE + ":create:main"), fixture.actions());
        select(P);
        click("mods.pinPage");
        assertEquals(List.of(new PagePin(FREE, "main")), runtime.modIntents().pins());
        assertEquals(1, pins());
        assertTrue(onEdt(() -> named(frame, FREE + ".main") != null));
    }

    @Test
    void removingARegistrationRevokesPinAndStaleButtonsCannotRecreateIt() throws Exception {
        click("mods.pinPage");
        var stale = onEdt(() -> (JButton) named(frame, "mods.pinned.0"));
        ((PageRegistration) System.getProperties().get(P + ".main")).close();
        await(() -> !stale.isShowing());
        assertEquals(0, pins());
        onEdt(() -> {
            stale.doClick(0);
            return null;
        });
        assertTrue(fixture.actions().isEmpty());
        assertEquals(1, runtime.modIntents().pins().size());
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
            await(() -> descendants(frame).anyMatch(c -> "mods.pinned.0".equals(c.getName())));
            assertEquals(1, pins());
            assertEquals(0, created.get());
            click("mods.pinned.0");
            assertEquals(1, created.get());
            assertTrue(onEdt(() -> named(frame, "replacement.main").isShowing()));
        }
        await(() -> descendants(frame).noneMatch(c -> "mods.pinned.0".equals(c.getName())));
    }

    @Test
    void lockedNavigationIsHiddenAndOriginalScopeStillRejectsOpening() throws Exception {
        click("mods.pinPage");
        runtime.security().lock();
        await(() -> !frame.getContentPane().isVisible());
        assertEquals(0, pins());
        assertEquals(0, onEdt(() -> ((JComboBox<?>) named(frame, "mods.pages")).getItemCount()));
        assertThrows(Exception.class, () -> runtime.modIntents().pins());
        assertThrows(ApiException.class, () -> frame.pages().openView(new PageKey(P, "main")));
        runtime.security().unlock(PASSWORD.toCharArray());
        await(() -> frame.getContentPane().isVisible());
        idle();
        assertEquals(1, pins());
        assertTrue(fixture.actions().isEmpty());
        ((UiService) System.getProperties().get(P + ".ui")).open("settings");
        assertTrue(onEdt(() -> named(frame, P + ".settings").isShowing()));
    }

    @Test
    void pendingWholeDeletionPreservesItsOwnedPinsUntilTheOriginalCleanupFinishes() throws Exception {
        click("mods.pinPage");
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
            runtime.modIntents().pin(new PagePin(FREE, "main"), true);
            assertTrue(runtime.modIntents().pins().contains(new PagePin(FREE, "main")));
        } finally {
            sql(db, "DELETE FROM mod_operation WHERE operation_id=?", operation);
        }
        runtime.modIntents().pin(new PagePin(P, "main"), false);
        assertFalse(runtime.modIntents().pins().contains(new PagePin(P, "main")));
    }

    @Test
    void failedPinStorageIsNotShownAsSavedAndOriginalPreferencesAreNotReplaced() throws Exception {
        sql(runtime.persistence().database(),
            "ALTER TABLE desktop_preference ADD CONSTRAINT navigation_write_failure CHECK(preference_key<>'mod_navigation_pins')");
        try {
            click("mods.pinPage");
            assertEquals(0, pins());
            assertTrue(runtime.modIntents().pins().isEmpty());
            assertTrue(onEdt(() -> !((JLabel) named(frame, "mods.navigationStatus")).getText().isEmpty()));
        } finally {
            sql(runtime.persistence().database(),
                "ALTER TABLE desktop_preference DROP CONSTRAINT navigation_write_failure");
        }
        click("mods.pinPage");
        assertEquals(1, pins());
        assertEquals("", onEdt(() -> ((JLabel) named(frame, "mods.navigationStatus")).getText()));
        runtime.persistence().preferences().save("mod_navigation_pins",
            "{\"formatVersion\":1,\"pages\":[{\"ownerId\":\"example.provider\",\"pageId\":\"main\"},{\"ownerId\":\"example.provider\",\"pageId\":\"main\"}]}");
        String invalid = runtime.persistence().preferences().find("mod_navigation_pins").orElseThrow();
        assertEquals("MOD_NAVIGATION_INVALID",
            assertThrows(ApiException.class, () -> runtime.modIntents().pins()).problem().eventCode());
        assertThrows(ApiException.class, () -> runtime.modIntents().pin(new PagePin(P, "settings"), true));
        assertEquals(invalid, runtime.persistence().preferences().find("mod_navigation_pins").orElseThrow());
        for (String corrupted : List.of("", "null", "{\"formatVersion\":1,\"formatVersion\":1,\"pages\":[]}")) {
            sql(runtime.persistence().database(),
                "UPDATE desktop_preference SET preference_value=? WHERE preference_key='mod_navigation_pins'",
                corrupted);
            assertEquals("MOD_NAVIGATION_INVALID",
                assertThrows(ApiException.class, () -> runtime.modIntents().pins()).problem().eventCode());
            assertThrows(ApiException.class, () -> runtime.modIntents().pin(new PagePin(P, "settings"), true));
            assertEquals(corrupted, runtime.persistence().preferences().find("mod_navigation_pins").orElseThrow());
        }
    }
}
