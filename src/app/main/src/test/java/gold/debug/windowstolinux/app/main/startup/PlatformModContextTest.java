package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;
import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.main.mod.PlatformModContext;
import gold.debug.windowstolinux.app.mod.development.DevelopmentDescriptor;
import gold.debug.windowstolinux.app.mod.development.DevelopmentModDiscovery;
import gold.debug.windowstolinux.app.mod.execution.lifecycle.ModDependencyPlan;
import gold.debug.windowstolinux.app.mod.execution.lifecycle.ModRuntimeSession;
import gold.debug.windowstolinux.app.mod.loader.ModClassLoader;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModContextTest {
    @TempDir
    Path root;

    static final String SOURCE = """
            package example.admission;
            import java.util.*;
            import java.util.concurrent.*;
            import javax.swing.*;
            import gold.debug.windowstolinux.appapi.mod.*;
            import gold.debug.windowstolinux.appapi.task.*;
            import gold.debug.windowstolinux.appapi.ui.*;
            import gold.debug.windowstolinux.appapi.source.*;
            public final class Mod implements ModEntrypoint, example.admission.api.Echo {
                public static ModContext context;
                public static TaskHandle<String> task;
                public static boolean failStart;
                public static int instances, stops, pageCloses;
                public static String blockedStartup;
                public static final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
                public static java.nio.file.Path snapshotPath;
                private PageRegistration page;
                private ServiceRegistration service;
                private LogTarget logs;
                public Mod(){instances++;}
                public void start(ModContext supplied) throws Exception {
                    context=supplied;
                    List.of(context.identity(),context.launchMode(),context.ui(),context.tasks(),context.interaction(),context.servers(),context.linux(),context.ai(),context.db(),context.secrets(),context.source(),context.logs(),context.processes(),context.services());
                    try { context.tasks().submit(new TaskRequest("wrong","Wrong",List.of(),false), t->new OperationResult<Void>(Outcome.SUCCEEDED,Optional.empty(),Optional.empty()));throw new AssertionError("Startup admitted work"); }
                    catch(ApiException denied) { blockedStartup=denied.problem().code().name(); }
                    context.tasks().registerRecovery("fixture",(before,t)->new OperationResult<Void>(Outcome.SUCCEEDED,Optional.empty(),Optional.empty()));
                    logs=context.logs().register(new LogCategory("runtime","Runtime",Optional.empty()));
                    service=context.services().register(example.admission.api.Echo.class,this);
                    page=context.ui().register(new PageSpec("main","fixture.main","本机 CLASS 验收",false),()->new ModPage(){
                        public JComponent create(UiEnvironment environment) {
                            if(!SwingUtilities.isEventDispatchThread())throw new AssertionError("EDT required");
                            var panel=new JPanel();var button=new JButton("运行本机任务");button.setName("fixture.run");panel.add(button);
                            var result=new JLabel("等待运行");result.setName("fixture.result");panel.add(result);
                            button.addActionListener(event->{try{ task=context.tasks().submit(new TaskRequest("fixture","CLASS capability flow",List.of("server"),true), t->work(t));
                                task.completion().whenComplete((value,error)->SwingUtilities.invokeLater(()->result.setText(error==null?value.outcome()+": "+value.value().orElse(""):"任务失败"))); }
                                catch(ApiException failure){throw new IllegalStateException(failure);}});return panel;
                        }
                        public void environmentChanged(UiEnvironment environment){}
                        public void close(){if(!SwingUtilities.isEventDispatchThread())throw new AssertionError("EDT close required");pageCloses++;}
                    });
                    if(failStart)throw new IllegalStateException("Intentional partial startup failure");
                }
                private OperationResult<String> work(TaskContext task) throws Exception {
                    try(var c=context.db().openConnection(task);var s=c.createStatement()){s.executeUpdate("INSERT INTO items VALUES(42)");}
                    if(context.servers().list(Optional.empty(),10,task).items().size()!=1)throw new AssertionError("Original server admission");
                    var secret=context.secrets().create("fixture",new byte[]{1,2,3},task);
                    if(context.secrets().use(secret,"fixture",task,bytes->bytes.length)!=3)throw new AssertionError("Secret scope");
                    var selection=context.source().selectDirectory("选择验收目录",task);
                    var read=context.source().read(selection,new SourceReadRequest("sample.txt",1024),task);
                    String text=new String(read.content(),java.nio.charset.StandardCharsets.UTF_8);
                    try(var snapshot=context.source().snapshot(selection,new SnapshotRequest(List.of("sample.txt"),1024),task)) {
                        snapshotPath=snapshot.directory();entered.countDown();
                        if(!release.await(12,TimeUnit.SECONDS))throw new AssertionError("Release timed out");
                    }
                    var result=context.logs().write(logs,new LogEvent(LogLevel.INFO,"CONTEXT_FLOW","Local capability flow completed",Optional.empty(),Optional.of(task.id()),Optional.empty(),Optional.of(Outcome.SUCCEEDED)));
                    if(result.storage()!=LogStorage.DATABASE)throw new AssertionError("Real log persistence");
                    return new OperationResult<>(Outcome.SUCCEEDED,Optional.of(text),Optional.empty());
                }
                public String value(){return "actual-provider";}
                public void stop(StopContext reason) throws Exception {stops++;if(page!=null)page.close();if(service!=null)service.close();}
            }
            """;

    ModClassLoader loader(boolean schema) throws Exception {
        var workspace = root.toRealPath().resolve("workspace-" + UUID.randomUUID());
        var code = workspace.resolve("src/mod/fixture");
        var classes = code.resolve("target/classes");
        Files.createDirectories(classes);
        for (var pom : List.of(workspace.resolve("pom.xml"), workspace.resolve("src/app/pom.xml"),
                workspace.resolve("src/mod/pom.xml"), code.resolve("pom.xml"))) {
            Files.createDirectories(pom.getParent());
            Files.writeString(pom, "<project/>\n");
        }
        var entry = code.resolve("Mod.java");
        Files.writeString(entry, SOURCE);
        var contract = code.resolve("Echo.java");
        Files.writeString(contract, "package example.admission.api; public interface Echo { String value(); }");
        String cp = Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0,
                ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none", "-encoding",
                        "UTF-8", "-classpath", cp, "-d", classes.toString(), entry.toString(), contract.toString()));
        String json = """
                {"formatVersion":1,"id":"example.admission","name":"Fixture","version":"1.0.0","platformApiRange":"1.0.0-dev.1","minJavaVersion":21,"mainJar":"main/fixture.jar","entryClass":"example.admission.Mod","exports":["example.admission.api"]%s}
                """
                .formatted(schema ? ",\"schemaVersion\":\"1\"" : "");
        Files.writeString(code.resolve("mod.json"), json, StandardCharsets.UTF_8);
        DevelopmentDescriptor.generate(workspace, code.resolve("mod.json"), classes, List.of(),
                List.of(workspace.resolve("pom.xml"), workspace.resolve("src/mod/pom.xml"), code.resolve("pom.xml")),
                List.of());
        var discovered = DevelopmentModDiscovery.scan(workspace);
        assertTrue(discovered.rejections().isEmpty(), discovered.rejections().toString());
        assertEquals(1, discovered.candidates().size());
        var candidate = discovered.candidates().getFirst();
        var sources = new ArrayList<java.net.URL>();
        for (Path source : candidate.descriptor().sources())
            sources.add(source.toUri().toURL());
        var plan = ModDependencyPlan.resolve(List.of(candidate.manifest()), SemanticVersion.parse("1.0.0-dev.1"),
                Runtime.version().feature());
        assertTrue(plan.failures().isEmpty());
        return plan.createLoader(candidate.manifest().id(), sources, Map.of());
    }

    static void prepareActivation(PlatformRuntime runtime) throws Exception {
        sql(runtime.persistence().database(),
                "UPDATE mod_registry SET last_boot_id=NULL,last_runtime_state='STOPPED',actual_version=NULL WHERE mod_id=?",
                OWNER);
    }

    static void actual(PlatformRuntime runtime, String state, String version) throws Exception {
        try (var c = runtime.persistence().database().openConnection();
                var s = c.prepareStatement(
                        "SELECT last_boot_id,last_runtime_state,actual_version,enabled,selected_version,revision,disable_ack_revision FROM mod_registry WHERE mod_id=?")) {
            s.setString(1, OWNER);
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                assertEquals(runtime.persistence().database().bootId(), r.getObject(1, UUID.class));
                assertEquals(state, r.getString(2));
                assertEquals(version, r.getString(3));
                assertTrue(r.getBoolean(4));
                assertEquals(VERSION, r.getString(5));
                assertEquals(0, r.getLong(6));
                assertNull(r.getObject(7));
            }
        }
    }

    static final class Host implements ModPageRegistry.PageHost {
        final Map<ModPageRegistry.PageKey, JComponent> mounted = new LinkedHashMap<>();

        final AtomicBoolean failRemove = new AtomicBoolean();
        public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            mounted.put(key, component);
        }

        public void focus(ModPageRegistry.PageKey key) {
            assertTrue(SwingUtilities.isEventDispatchThread());
        }

        public void remove(ModPageRegistry.PageKey key) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            if (failRemove.getAndSet(false))
                throw new IllegalStateException("Fixture mount cleanup failure");
            mounted.remove(key);
        }

        public void environment(UiEnvironment environment) {
            assertTrue(SwingUtilities.isEventDispatchThread());
        }
    }

    final class Fixture implements AutoCloseable {
        final PlatformRuntime runtime;

        final Host host = new Host();

        final ModPageRegistry pages = new ModPageRegistry(host,
                new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));

        final ModClassLoader loader;

        final Class<?> entry;

        final PlatformModContext assembly;

        final ModRuntimeSession session;
        Fixture(boolean schema, ModLaunchMode mode) throws Exception {
            runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION);
            SchemaIdentity identity = initialize(runtime, schema);
            if (mode == ModLaunchMode.NORMAL)
                prepareActivation(runtime);
            runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
            Path source = Files.createDirectories(root.resolve("选择的目录"));
            Files.writeString(source.resolve("sample.txt"), "真实源码验收");
            runtime.setSourcePicker((title, task, cancel) -> source);
            loader = loader(schema);
            entry = loader.loadClass("example.admission.Mod");
            entry.getFields();
            session = runtime.modSession(loader, Optional.ofNullable(identity), mode, pages,
                    SemanticVersion.parse("1.0.0-dev.1"));
            assembly = runtime.modContexts().get(OWNER);
        }

        CountDownLatch signal(String name) throws Exception {
            return (CountDownLatch) entry.getField(name).get(null);
        }

        ModContext context() throws Exception {
            return (ModContext) entry.getField("context").get(null);
        }

        @SuppressWarnings("unchecked")
        TaskHandle<String> task() throws Exception {
            return (TaskHandle<String>) entry.getField("task").get(null);
        }

        void click() throws Exception {
            context().ui().open("main");
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) host.mounted.values().iterator().next().getComponent(0)).doClick(0));
        }

        @Override
        public void close() throws Exception {
            signal("release").countDown();
            if (!session.finalized())
                session.stop(StopReason.APPLICATION_EXIT);
            runtime.close();
        }
    }

    @Test
    void classEntrypointUsesEveryRealCapabilityAndPageRunsLocalBusinessFlow() throws Exception {
        try (var f = new Fixture(true, ModLaunchMode.NORMAL)) {
            assertEquals(Set.of(OWNER), f.runtime.modContexts().keySet());
            assertTrue(f.pages.contributions().isEmpty());
            assertThrows(SQLException.class, f.runtime::close);
            f.session.start();
            assertEquals("BUSY", f.entry.getField("blockedStartup").get(null));
            actual(f.runtime, "RUNNING", VERSION);
            assertEquals(Set.of(OWNER), f.runtime.modSessions().keySet());
            assertEquals(1,
                    count(f.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema WHERE ever_used=TRUE"));
            assertEquals(1, f.pages.contributions().size());
            var context = f.context();
            for (var method : ModContext.class.getMethods())
                assertNotNull(method.invoke(context), method.getName());
            assertFalse(java.lang.reflect.Proxy.isProxyClass(context.getClass()));
            f.signal("release").countDown();
            f.click();
            assertEquals("真实源码验收",
                    f.task().completion().toCompletableFuture().get(12, TimeUnit.SECONDS).value().orElseThrow());
            assertEquals(1, count(f.runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='CONTEXT_FLOW'"));
            assertFalse(Files.exists((Path) f.entry.getField("snapshotPath").get(null)));
            assertEquals(0, f.runtime.persistence().database().openConnections());
            f.session.stop(StopReason.APPLICATION_EXIT);
            assertTrue(f.session.resourcesClosed());
            actual(f.runtime, "STOPPED", null);
            assertTrue(f.session.finalized());
            assertTrue(f.runtime.modSessions().isEmpty());
            assertTrue(f.loader.isClosed());
            assertTrue(f.runtime.modContexts().isEmpty());
            assertTrue(f.pages.contributions().isEmpty());
            assertEquals(1, f.entry.getField("pageCloses").getInt(null));
            assertThrows(ApiException.class, () -> context.tasks().submit(request(), t -> success(null)));
            assertThrows(IllegalStateException.class, f.assembly::context);
        }
    }

    @Test
    void stopCannotDiscardRunningTaskOrActualSourceSnapshot() throws Exception {
        try (var f = new Fixture(true, ModLaunchMode.NORMAL)) {
            f.session.start();
            f.click();
            try {
                assertTrue(f.signal("entered").await(8, TimeUnit.SECONDS));
                assertEquals(ProblemCode.BUSY,
                        assertThrows(ApiException.class, () -> f.session.stop(StopReason.APPLICATION_RESTART)).problem()
                                .code());
                assertEquals(0, f.entry.getField("stops").getInt(null));
                actual(f.runtime, "STOPPING", VERSION);
                assertFalse(f.loader.isClosed());
                assertTrue(Files.exists((Path) f.entry.getField("snapshotPath").get(null)));
                assertThrows(SQLException.class, f.runtime::close);
            } finally {
                f.signal("release").countDown();
            }
            assertEquals(Outcome.SUCCEEDED,
                    f.task().completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
            f.session.stop(StopReason.APPLICATION_RESTART);
            assertTrue(f.runtime.modContexts().isEmpty());
        }
    }

    @Test
    void partialAuthorStartupReleasesOnlyItsOriginalRegistrations() throws Exception {
        try (var f = new Fixture(true, ModLaunchMode.NORMAL)) {
            var identity = f.assembly.context().db().identity();
            f.entry.getField("failStart").setBoolean(null, true);
            assertThrows(IllegalStateException.class, f.session::start);
            actual(f.runtime, "FAILED", null);
            assertTrue(f.runtime.modSessions().isEmpty());
            try (var c = f.runtime.persistence().database().openConnection();
                    var s = c.createStatement();
                    var r = s.executeQuery("SELECT failure_code,failure_detail FROM mod_registry")) {
                assertTrue(r.next());
                assertEquals("MOD_START_FAILED", r.getString(1));
                var evidence = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getString(2));
                assertNotNull(UUID.fromString(evidence.path("diagnosticId").asText()));
                assertFalse(r.getString(2).contains("Intentional partial startup failure"));
            }
            assertTrue(f.session.resourcesClosed());
            assertTrue(f.loader.isClosed());
            assertTrue(f.runtime.modContexts().isEmpty());
            assertTrue(f.pages.contributions().isEmpty());
            assertEquals(1, f.entry.getField("stops").getInt(null));
            assertThrows(ApiException.class, () -> f.context().tasks().submit(request(), t -> success(null)));
            assertThrows(ApiException.class, () -> f.context().db().identity());
            try (var next = loader(true);
                    var fresh = f.runtime.services().bind(next, Optional.of(identity), ModLaunchMode.NORMAL)) {
                assertNotNull(fresh);
            }
        }
    }

    @Test
    void failedActualPageUnmountRemainsOwnedUntilRetry() throws Exception {
        try (var f = new Fixture(false, ModLaunchMode.NORMAL)) {
            f.session.start();
            f.context().ui().open("main");
            f.host.failRemove.set(true);
            var failure = assertThrows(ApiException.class, () -> f.session.stop(StopReason.APPLICATION_EXIT));
            assertEquals("PAGE_CLEANUP_PENDING", failure.problem().eventCode());
            actual(f.runtime, "STOPPING", VERSION);
            assertFalse(f.session.resourcesClosed());
            assertFalse(f.loader.isClosed());
            assertEquals(Set.of(OWNER), f.runtime.modContexts().keySet());
            assertThrows(SQLException.class, f.runtime::close);
            assertEquals(1, f.entry.getField("pageCloses").getInt(null));
            f.session.stop(StopReason.APPLICATION_EXIT);
            assertTrue(f.runtime.modContexts().isEmpty());
            actual(f.runtime, "STOPPED", null);
            assertEquals(1, f.entry.getField("pageCloses").getInt(null));
            SwingUtilities.invokeAndWait(() -> assertTrue(f.host.mounted.isEmpty()));
        }
    }

    @Test
    void recoveryContextKeepsPagesHiddenAndStatelessDatabaseExplicitlyUnavailable() throws Exception {
        try (var f = new Fixture(false, ModLaunchMode.RECOVERY)) {
            f.session.start();
            var context = f.context();
            assertTrue(f.pages.contributions().isEmpty());
            assertThrows(ApiException.class, () -> context.ui().open("main"));
            assertThrows(ApiException.class, () -> context.tasks().submit(request(), t -> success(null)));
            assertEquals("MOD_NO_SCHEMA",
                    assertThrows(ApiException.class, context.db()::identity).problem().eventCode());
            assertEquals(0, count(f.runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
            f.session.stop(StopReason.RECOVERY_FINISHED);
            actual(f.runtime, "RUNNING", VERSION);
            assertTrue(f.runtime.modSessions().isEmpty());
            assertTrue(f.runtime.modContexts().isEmpty());
        }
    }

    @Test
    void failedStartingWritePreventsAuthorConstructionAndRollsBackSchemaUse() throws Exception {
        try (var f = new Fixture(true, ModLaunchMode.NORMAL)) {
            assertEquals(0, f.entry.getField("instances").getInt(null));
            var db = f.runtime.persistence().database();
            sql(db, "ALTER TABLE mod_registry ADD CONSTRAINT fixture_start CHECK(last_runtime_state<>'STARTING')");
            try {
                assertThrows(SQLException.class, f.session::start);
                assertEquals(0, f.entry.getField("instances").getInt(null));
                assertNull(f.context());
                assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_schema WHERE ever_used=TRUE"));
                assertEquals(1, count(db,
                        "SELECT COUNT(*) FROM mod_registry WHERE last_boot_id IS NULL AND last_runtime_state='STOPPED'"));
                assertTrue(f.session.finalized());
                assertTrue(f.runtime.modContexts().isEmpty());
                assertTrue(f.runtime.modSessions().isEmpty());
            } finally {
                sql(db, "ALTER TABLE mod_registry DROP CONSTRAINT fixture_start");
            }
        }
    }

    @Test
    void failedRunningWriteRevokesPublishedContributionsAndRecordsStartupFailure() throws Exception {
        try (var f = new Fixture(false, ModLaunchMode.NORMAL)) {
            var db = f.runtime.persistence().database();
            sql(db, "ALTER TABLE mod_registry ADD CONSTRAINT fixture_running CHECK(last_runtime_state<>'RUNNING')");
            try {
                assertThrows(SQLException.class, f.session::start);
                actual(f.runtime, "FAILED", null);
                assertEquals(1, f.entry.getField("instances").getInt(null));
                assertEquals(1, f.entry.getField("stops").getInt(null));
                assertTrue(f.pages.contributions().isEmpty());
                assertTrue(f.session.finalized());
                assertTrue(f.runtime.modSessions().isEmpty());
                assertThrows(ApiException.class, () -> f.context().tasks().submit(request(), t -> success(null)));
            } finally {
                sql(db, "ALTER TABLE mod_registry DROP CONSTRAINT fixture_running");
            }
        }
    }

    @Test
    void failedFinalWriteRetainsOriginalSessionAfterPhysicalCloseAndRetriesOnlyPersistence() throws Exception {
        try (var f = new Fixture(false, ModLaunchMode.NORMAL)) {
            f.session.start();
            var db = f.runtime.persistence().database();
            sql(db, "ALTER TABLE mod_registry ADD CONSTRAINT fixture_final CHECK(last_runtime_state<>'STOPPED')");
            try {
                assertThrows(SQLException.class, () -> f.session.stop(StopReason.APPLICATION_EXIT));
                actual(f.runtime, "UNKNOWN", VERSION);
                assertTrue(f.session.resourcesClosed());
                assertFalse(f.session.finalized());
                assertTrue(f.runtime.modContexts().isEmpty());
                assertSame(f.session, f.runtime.modSessions().get(OWNER));
                assertThrows(SQLException.class, f.runtime::close);
                try (var duplicate = loader(false)) {
                    assertThrows(IllegalStateException.class,
                            () -> f.runtime.capabilities().modContext(duplicate, Optional.empty(), ModLaunchMode.NORMAL, f.pages));
                    assertThrows(IllegalStateException.class, () -> f.runtime.modSession(duplicate, Optional.empty(),
                            ModLaunchMode.NORMAL, f.pages, SemanticVersion.parse("1.0.0-dev.1")));
                }
                assertEquals(1, f.entry.getField("stops").getInt(null));
            } finally {
                sql(db, "ALTER TABLE mod_registry DROP CONSTRAINT fixture_final");
            }
            f.session.stop(StopReason.APPLICATION_EXIT);
            actual(f.runtime, "STOPPED", null);
            assertTrue(f.session.finalized());
            assertTrue(f.runtime.modSessions().isEmpty());
            assertEquals(1, f.entry.getField("stops").getInt(null));
        }
    }

    @Test
    void failedPreparationDoesNotCloseAnotherPageScopeOrKeepUnusedContext() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION);
                var loader = loader(false)) {
            initialize(runtime, false);
            runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
            runtime.setSourcePicker((title, task, cancel) -> root);
            var pages = new ModPageRegistry(new Host(), new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
            try (var original = pages.begin(OWNER, false)) {
                original.register(new PageSpec("prior", "prior", "Prior", false), () -> null);
                original.commit();
                assertThrows(ApiException.class,
                        () -> runtime.capabilities().modContext(loader, Optional.empty(), ModLaunchMode.NORMAL, pages));
                assertEquals(1, pages.contributions().size());
                assertTrue(runtime.modContexts().isEmpty());
                assertFalse(loader.isClosed());
            }
        }
    }

    @Test
    void laterPreparationFailureRemovesItsNewPagesButPreservesExistingServices() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION);
                var loader = loader(false)) {
            initialize(runtime, false);
            runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
            runtime.setSourcePicker((title, task, cancel) -> root);
            var pages = new ModPageRegistry(new Host(), new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
            try (var existing = runtime.services().bind(loader, Optional.empty(), ModLaunchMode.NORMAL)) {
                assertThrows(IllegalStateException.class,
                        () -> runtime.capabilities().modContext(loader, Optional.empty(), ModLaunchMode.NORMAL, pages));
                assertTrue(runtime.modContexts().isEmpty());
                assertTrue(pages.contributions().isEmpty());
                try (var newScope = pages.begin(OWNER, false)) {
                    assertNotNull(newScope);
                }
                @SuppressWarnings("unchecked")
                var contract = (Class<Object>) loader.loadClass("example.admission.api.Echo");
                try (var registration = existing.register(contract,
                        loader.loadClass("example.admission.Mod").getConstructor().newInstance())) {
                    existing.publish();
                    assertNotNull(registration);
                }
                assertFalse(loader.isClosed());
            }
        }
    }

    @Test
    void realPlatformWindowDisplaysClassModTaskResultAndReleasesItsViews() throws Exception {
        assertFalse(java.awt.GraphicsEnvironment.isHeadless());
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION);
                var loader = loader(true)) {
            var schema = initialize(runtime, true);
            var source = Files.createDirectory(root.resolve("source"));
            Files.writeString(source.resolve("sample.txt"), "真实源码验收");
            var frame = PlatformWindowTest.onEdt(() -> {
                gold.debug.windowstolinux.app.ui.display.DesktopThemeService
                        .install(gold.debug.windowstolinux.app.ui.display.ThemeMode.LIGHT);
                var messages = gold.debug.windowstolinux.app.ui.i18n.MessageCatalog.forLanguageTag("zh-CN");
                var window = new gold.debug.windowstolinux.app.ui.shell.PlatformFrame(runtime.security(), messages,
                        gold.debug.windowstolinux.app.ui.display.ThemePalette.light(),
                        new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                        gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore.disabled());
                runtime.setInteractionPresenter(
                        new gold.debug.windowstolinux.app.ui.shell.TaskInteractionPresenter(window, messages));
                runtime.setSourcePicker((title, task, cancel) -> source);
                window.setVisible(true);
                return window;
            });
            ModRuntimeSession session = null;
            try {
                prepareActivation(runtime);
                session = runtime.modSession(loader, Optional.of(schema), ModLaunchMode.NORMAL, frame.pages(),
                        SemanticVersion.parse("1.0.0-dev.1"));
                var assembly = runtime.modContexts().get(OWNER);
                session.start();
                var entry = loader.loadClass("example.admission.Mod");
                ((CountDownLatch) entry.getField("release").get(null)).countDown();
                assembly.context().ui().open("main");
                PlatformWindowTest.onEdt(() -> {
                    var button = (JButton) PlatformWindowTest.named(frame, "fixture.run");
                    assertTrue(button.isShowing());
                    button.doClick(0);
                    return null;
                });
                PlatformWindowTest.await(() -> ((JLabel) PlatformWindowTest.named(frame, "fixture.result")).getText()
                        .equals("SUCCEEDED: 真实源码验收"));
                PlatformWindowTest.onEdt(() -> {
                    PlatformWindowTest.capture(frame, "platform-mod-context.png");
                    return null;
                });
                session.stop(StopReason.APPLICATION_EXIT);
                assertTrue(runtime.modContexts().isEmpty());
                assertTrue(frame.pages().contributions().isEmpty());
            } finally {
                if (session != null && !session.finalized())
                    session.stop(StopReason.APPLICATION_EXIT);
                PlatformWindowTest.onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }
}
