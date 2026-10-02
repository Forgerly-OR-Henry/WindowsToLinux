package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import javax.swing.SwingUtilities;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.mod.dev.DevelopmentDescriptor;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.mod.ModContext;

import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModCoordinatorTest {
    @TempDir
    Path root;

    Path workspace, trace;

    static final String P = "example.provider", C = "example.consumer", G = "example.grandchild", F = "example.free";

    @BeforeEach
    void workspace() throws Exception {
        workspace = Files.createDirectories(root.resolve("workspace")).toRealPath();
        trace = workspace.resolve("trace.txt");
        for (var relative : List.of("pom.xml", "src/app/pom.xml", "src/mod/pom.xml")) {
            var pom = workspace.resolve(relative);
            Files.createDirectories(pom.getParent());
            Files.writeString(pom, "<project/>\n");
        }
    }

    void module(String owner, Map<String, String> dependencies, boolean failStart) throws Exception {
        Path module = workspace.resolve("src/mod/" + owner);
        Path classes = Files.createDirectories(module.resolve("target/classes"));
        Path source = module.resolve("Mod.java");
        String prefix = workspace.toString().replace("\\", "\\\\");
        Files.writeString(source,
            """
                package %s;
                import java.nio.file.*;
                import gold.debug.windowstolinux.appapi.mod.*;
                public final class Mod implements ModEntrypoint {
                    private static final Path ROOT=Path.of("%s");
                    private static void record(String action) throws Exception {
                        Files.writeString(ROOT.resolve("trace.txt"),"%s:"+action+"\\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    }
                    public Mod() throws Exception {record("construct");}
                    public void start(ModContext context) throws Exception {
                        record("start");
                        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
                        while(Files.exists(ROOT.resolve("%s.start.hold"))){
                            if(System.nanoTime()>deadline)throw new IllegalStateException("fixture deadline");
                            Thread.sleep(5);
                        }
                        if(%s)throw new IllegalStateException("fixture startup failure");
                    }
                    public void stop(StopContext context) throws Exception {
                        record("stop");
                        if(Files.exists(ROOT.resolve("%s.stop.fail")))throw new IllegalStateException("fixture stop failure");
                    }
                }
                """
                .formatted(owner, prefix, owner, owner, failStart, owner));
        String cp = Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none",
            "-encoding", "UTF-8", "-classpath", cp, "-d", classes.toString(), source.toString()));
        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("mod.json"), manifest(owner, VERSION, dependencies, null, List.of()));
        DevelopmentDescriptor.generate(workspace, module.resolve("mod.json"), classes, List.of(),
            List.of(workspace.resolve("pom.xml"), workspace.resolve("src/mod/pom.xml"), module.resolve("pom.xml")),
            List.of());
    }

    PlatformRuntime open(boolean unlock) throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION);
        if (unlock) {
            if (runtime.security().securityState().configured())
                runtime.security().unlock(PASSWORD.toCharArray());
            else
                runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        }
        runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
        runtime.setSourcePicker((title, task, cancel) -> workspace);
        runtime.started();
        return runtime;
    }

    ModPageRegistry pages(boolean locked) {
        return new ModPageRegistry(new PlatformModContextTest.Host(),
            new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", locked));
    }

    PlatformModCoordinator.Report start(PlatformRuntime runtime) {
        return DesktopMain.startMods(runtime,
            new RunModeResolver.RuntimeLayout(RunModeResolver.RunMode.RUN_CLASS, workspace,
                runtime.persistence().database().root()),
            workspace.toString(), pages(!runtime.security().securityState().unlocked()));
    }

    void enable(String... owners) throws Exception {
        try (var runtime = open(true)) {
            var initial = start(runtime);
            assertTrue(initial.running().isEmpty());
            assertTrue(initial.problems().isEmpty(), initial.problems().toString());
            assertFalse(Files.exists(trace));
            var intents = runtime.modIntents();
            for (var owner : owners)
                intents.apply(intents.review(owner, choice(true, VERSION), Optional.empty()), false);
        }
    }

    List<String> actions() throws Exception {
        return Files.exists(trace) ? Files.readString(trace).lines().toList() : List.of();
    }

    static String state(PlatformRuntime runtime, String owner) throws Exception {
        return text(runtime, "SELECT last_runtime_state FROM mod_registry WHERE mod_id='" + owner + "'");
    }

    @Test
    void defaultDiscoveryKeepsNewModsDisabledAndExplicitWorkspaceCannotBeReplacedByWorkingDirectory() throws Exception {
        module(P, Map.of(), false);
        try (var runtime = open(false)) {
            var layout = new RunModeResolver.RuntimeLayout(RunModeResolver.RunMode.RUN_CLASS, workspace,
                runtime.persistence().database().root());
            assertFalse(DesktopMain.startMods(runtime, layout, null, pages(true)).problems().isEmpty());
            assertFalse(DesktopMain.startMods(runtime, layout, root.toString(), pages(true)).problems().isEmpty());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
            var report = start(runtime);
            assertTrue(report.running().isEmpty());
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertFalse(Files.exists(trace));
            assertFalse(start(runtime).problems().isEmpty());
        }
    }

    @Test
    void restartStartsDependencyFirstWhileLockedAndRuntimeExitStopsInReverseOrder() throws Exception {
        module(P, Map.of(), false);
        module(C, Map.of(P, VERSION), false);
        module(G, Map.of(C, VERSION), false);
        module(F, Map.of(), false);
        enable(P, C, G);
        var runtime = open(false);
        try {
            var report = start(runtime);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(Set.of(P, C, G), report.running());
            assertEquals(List.of(P + ":construct", P + ":start", C + ":construct", C + ":start", G + ":construct",
                G + ":start"), actions());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(3, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE category='mod_operation' AND outcome='SUCCEEDED'"));
            assertThrows(ApiException.class,
                () -> runtime.tasks().bind(G, VERSION, false).submit(work(), t -> success(null)));
            runtime.security().unlock(PASSWORD.toCharArray());
            assertEquals(Outcome.SUCCEEDED, runtime.tasks().bind(G, VERSION, false).submit(work(), t -> success(null))
                .completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
        } finally {
            runtime.close();
        }
        assertEquals(List.of(G + ":stop", C + ":stop", P + ":stop"), actions().subList(6, 9));
        try (var reopened = open(false)) {
            assertEquals(3, count(reopened.persistence().database(),
                "SELECT COUNT(*) FROM mod_registry WHERE enabled AND last_runtime_state='STOPPED' AND actual_version IS NULL"));
        }
    }

    @Test
    void failedProviderPreventsConsumerConstructionButIndependentModRunsAndFailuresStayEnabled() throws Exception {
        module(P, Map.of(), true);
        module(C, Map.of(P, VERSION), false);
        module(F, Map.of(), false);
        enable(P, C, F);
        try (var runtime = open(false)) {
            var report = start(runtime);
            assertEquals(Set.of(F), report.running());
            assertEquals(2, report.problems().size(), report.problems().toString());
            assertFalse(actions().contains(C + ":construct"));
            assertTrue(actions().contains(P + ":stop"));
            assertEquals("FAILED", state(runtime, P));
            assertEquals("FAILED", state(runtime, C));
            assertEquals(3, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry WHERE enabled"));
            assertEquals(2, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE outcome='FAILED' AND category='mod_operation'"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        }
        assertEquals(1, actions().stream().filter(x -> x.equals(P + ":construct")).count());
    }

    @Test
    void lateArchiveFailureRetainsRunningSessionAndExitRetriesSummaryBeforeStoppingAuthor() throws Exception {
        module(P, Map.of(), false);
        enable(P);
        var runtime = open(false);
        try {
            var db = runtime.persistence().database();
            var original = text(runtime, "SELECT archive_log_id FROM mod_operation");
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT fixture_archive CHECK(category<>'mod_operation')");
            var report = start(runtime);
            assertEquals(Set.of(P), report.running());
            assertFalse(report.problems().isEmpty());
            assertThrows(SQLException.class, runtime::close);
            assertFalse(actions().contains(P + ":stop"));
            assertEquals("RUNNING", state(runtime, P));
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT fixture_archive");
            runtime.close();
            assertEquals(1, actions().stream().filter(x -> x.equals(P + ":construct")).count());
            assertEquals(1, actions().stream().filter(x -> x.equals(P + ":stop")).count());
            try (var reopened = open(false)) {
                assertEquals(original, text(reopened, "SELECT log_id FROM runtime_log WHERE category='mod_operation'"));
            }
        } finally {
            runtime.close();
        }
    }

    @Test
    void consumerStopFailureKeepsProviderAliveAndSecondExitFinishesOriginalObjects() throws Exception {
        module(P, Map.of(), false);
        module(C, Map.of(P, VERSION), false);
        enable(P, C);
        var runtime = open(true);
        Path flag = workspace.resolve(C + ".stop.fail");
        try {
            assertTrue(start(runtime).problems().isEmpty());
            Files.writeString(flag, "fail once until removed");
            assertThrows(IllegalStateException.class, runtime::close);
            assertFalse(actions().contains(P + ":stop"));
            assertEquals("RUNNING", state(runtime, P));
            assertEquals("UNKNOWN", state(runtime, C));
            assertEquals(Outcome.SUCCEEDED, runtime.tasks().bind(P, VERSION, false).submit(work(), t -> success(null))
                .completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
            Files.delete(flag);
            runtime.close();
            assertEquals(2, actions().stream().filter(x -> x.equals(C + ":stop")).count());
            assertEquals(1, actions().stream().filter(x -> x.equals(P + ":stop")).count());
        } finally {
            Files.deleteIfExists(flag);
            runtime.close();
        }
    }

    @Test
    void missingSelectedBuildFailsDurablyWithoutLoadingAnOlderRegisteredSource() throws Exception {
        module(P, Map.of(), false);
        enable(P);
        Files.delete(workspace.resolve("src/mod/" + P + "/target/wtlmod-development.json"));
        try (var runtime = open(false)) {
            var report = start(runtime);
            assertTrue(report.running().isEmpty());
            assertFalse(report.problems().isEmpty());
            assertFalse(Files.exists(trace));
            assertEquals("FAILED", state(runtime, P));
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        }
    }

    @Test
    void providerFailureSealsNewTransitiveConsumerWorkWithoutCancellingAnAcceptedTask() throws Exception {
        module(P, Map.of(), false);
        module(C, Map.of(P, VERSION), false);
        module(G, Map.of(C, VERSION), false);
        enable(P, C, G);
        var runtime = open(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            assertTrue(start(runtime).problems().isEmpty());
            var binding = runtime.tasks().bind(G, VERSION, false);
            var original = binding.submit(work(), task -> {
                entered.countDown();
                assertTrue(release.await(8, TimeUnit.SECONDS));
                return success("original");
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Path flag = workspace.resolve(P + ".stop.fail");
            Files.writeString(flag, "retain actual original provider");
            assertThrows(IllegalStateException.class, () -> runtime.modSessions().get(P)
                .stop(gold.debug.windowstolinux.appapi.mod.StopReason.APPLICATION_EXIT));
            assertEquals("MOD_PROVIDER_NOT_RUNNING",
                assertThrows(ApiException.class, () -> binding.submit(work(), t -> success(null))).problem()
                    .eventCode());
            release.countDown();
            assertEquals("original",
                original.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).value().orElseThrow());
            Files.delete(flag);
        } finally {
            release.countDown();
            Files.deleteIfExists(workspace.resolve(P + ".stop.fail"));
            runtime.close();
        }
    }

    @Test
    void concurrentExitReportsBusyWithoutInterruptingOriginalAuthorStartup() throws Exception {
        module(P, Map.of(), false);
        enable(P);
        var runtime = open(false);
        Path gate = workspace.resolve(P + ".start.hold");
        Files.writeString(gate, "hold author startup");
        var starting = CompletableFuture.supplyAsync(() -> start(runtime));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!actions().contains(P + ":start") && System.nanoTime() < deadline)
                Thread.sleep(5);
            assertTrue(actions().contains(P + ":start"));
            assertEquals("MOD_GRAPH_BUSY", assertThrows(ApiException.class, runtime::close).problem().eventCode());
            Files.delete(gate);
            assertEquals(Set.of(P), starting.get(8, TimeUnit.SECONDS).running());
        } finally {
            Files.deleteIfExists(gate);
            starting.get(8, TimeUnit.SECONDS);
            runtime.close();
        }
        assertEquals(1, actions().stream().filter(x -> x.equals(P + ":construct")).count());
    }

    @Test
    void retainedPreviousBootStateIsNotOverwrittenAndUnclaimedNewContextDoesNotPreventExit() throws Exception {
        module(P, Map.of(), false);
        enable(P);
        try (var runtime = open(false)) {
            assertTrue(start(runtime).problems().isEmpty());
        }
        try (var runtime = open(false)) {
            sql(runtime.persistence().database(),
                "UPDATE mod_registry SET last_runtime_state='UNKNOWN',actual_version=?", VERSION);
        }
        var before = actions();
        try (var runtime = open(false)) {
            var report = start(runtime);
            assertTrue(report.running().isEmpty());
            assertFalse(report.problems().isEmpty());
            assertEquals("UNKNOWN", state(runtime, P));
            assertEquals(before, actions());
            assertTrue(runtime.modSessions().isEmpty());
            assertTrue(runtime.modContexts().isEmpty());
        }
    }

    @Test
    void visibleDefaultWindowShowsStartupProblemsWithoutBlockingPlatformSecurity() throws Exception {
        module(P, Map.of(), true);
        enable(P);
        var runtime = open(false);
        var frame = PlatformWindowTest.onEdt(() -> {
            var catalog = gold.debug.windowstolinux.app.ui.language.MessageCatalog.forLanguageTag("zh-CN");
            var window = new PlatformFrame(runtime.security(), catalog,
                gold.debug.windowstolinux.app.ui.display.ThemePalette.light(),
                new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true),
                gold.debug.windowstolinux.app.ui.error.FailureReportStore.disabled());
            window.onExit(runtime);
            window.setVisible(true);
            return window;
        });
        try {
            var report = DesktopMain.startMods(runtime,
                new RunModeResolver.RuntimeLayout(RunModeResolver.RunMode.RUN_CLASS, workspace,
                    runtime.persistence().database().root()),
                workspace.toString(), frame.pages());
            PlatformWindowTest.onEdt(() -> {
                frame.showModStartup(report.running().size(), report.problems());
                assertFalse(
                    ((javax.swing.JButton) PlatformWindowTest.named(frame, "platform.modProblems")).isEnabled());
                return null;
            });
            runtime.security().unlock(PASSWORD.toCharArray());
            PlatformWindowTest.await(
                () -> ((javax.swing.JButton) PlatformWindowTest.named(frame, "platform.modProblems")).isEnabled());
            PlatformWindowTest.onEdt(() -> {
                assertEquals("运行中的 Mod：0；启动问题：1",
                    ((javax.swing.JLabel) PlatformWindowTest.named(frame, "platform.modStatus")).getText());
                PlatformWindowTest.capture(frame, "platform-mod-startup.png");
                return null;
            });
        } finally {
            runtime.close();
            PlatformWindowTest.onEdt(() -> {
                frame.dispose();
                return null;
            });
        }
    }
}
