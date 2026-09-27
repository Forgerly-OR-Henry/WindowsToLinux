package gold.debug.windowstolinux.app.main.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.*;

import javax.swing.*;
import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.schema.*;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.mod.lifecycle.ModRuntimeSession;
import gold.debug.windowstolinux.app.mod.loader.ModClassLoader;
import gold.debug.windowstolinux.app.mod.manifest.ManifestReader;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.database.TaskModDatabase;
import gold.debug.windowstolinux.app.service.mod.ModHostLease;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.shell.*;
import gold.debug.windowstolinux.appapi.db.*;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModLifecycleIntegrationTest {
    static gold.debug.windowstolinux.app.service.security.DesktopSecurityService security(PlatformDatabase db)
            throws Exception {
        var security = new gold.debug.windowstolinux.app.service.security.DesktopSecurityService(
                new gold.debug.windowstolinux.app.db.persistence.repository.SecurityRepository(
                        new gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections(
                                db::openConnection)));
        if (security.securityState().configured())
            security.unlock("fixture-password".toCharArray());
        else
            security.setupAndUnlock("fixture-password".toCharArray(), "fixture-password".toCharArray());
        return security;
    }

    @TempDir
    Path root;

    static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] input) {
            var copy = input.clone();
            for (int i = 0; i < copy.length; i++)
                copy[i] ^= 0x13;
            return copy;
        }

        public byte[] unprotect(byte[] input) {
            return protect(input);
        }
    };

    static final String SOURCE = """
            package example.fixture;
            import gold.debug.windowstolinux.appapi.mod.*;
            import gold.debug.windowstolinux.appapi.task.*;
            import gold.debug.windowstolinux.appapi.ui.*;
            import java.util.*;
            import java.util.concurrent.*;
            import javax.swing.*;
            public final class Mod implements ModEntrypoint {
              public static boolean failStart, failAfterCommit;
              public static int executions, pageCloses, stops;
              public static final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
              public static TaskHandle<Integer> task;
              private PageRegistration page;
              public void start(ModContext context) throws Exception {
                context.tasks().registerRecovery("insert",(previous,original)->{
                  try(var connection=context.db().openConnection(original);var statement=connection.prepareStatement("SELECT payload FROM records WHERE task_id=?")){
                    statement.setObject(1,original.id());try(var rows=statement.executeQuery()){
                      if(!rows.next())return new OperationResult<Void>(Outcome.UNKNOWN,Optional.empty(),Optional.of(new Problem(ProblemCode.UNKNOWN,"MISSING_RECORD","Business row is absent",Map.of(),Optional.empty())));
                      return new OperationResult<Void>(Outcome.SUCCEEDED,Optional.empty(),Optional.empty());
                    }
                  }
                });
                page=context.ui().register(new PageSpec("main","fixture.main","Fixture",false),()->new ModPage(){
                  public JComponent create(UiEnvironment environment){
                    if(!SwingUtilities.isEventDispatchThread())throw new AssertionError("EDT required");
                    var panel=new JPanel();var button=new JButton("Insert");panel.add(button);
                    button.addActionListener(event->{try{task=context.tasks().submit(new TaskRequest("insert","Local row",List.of(),true),active->{
                      executions++;
                      try(var connection=context.db().openConnection(active);var statement=connection.prepareStatement("INSERT INTO records VALUES(?,42)")){
                        connection.setAutoCommit(false);statement.setObject(1,active.id());statement.executeUpdate();connection.commit();entered.countDown();
                        if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("Test release timed out");
                        if(failAfterCommit)throw new IllegalStateException("Unacknowledged committed effect");
                      }
                      return new OperationResult<Integer>(Outcome.SUCCEEDED,Optional.of(42),Optional.empty());
                    });}catch(ApiException failure){throw new IllegalStateException(failure);}});
                    return panel;
                  }
                  public void environmentChanged(UiEnvironment environment){}
                  public void close(){if(!SwingUtilities.isEventDispatchThread())throw new AssertionError("EDT close required");pageCloses++;}
                });
                if(failStart)throw new IllegalStateException("Partial startup failure");
              }
              public void stop(StopContext context)throws Exception {stops++;if(page!=null)page.close();}
            }
            """;
    ModClassLoader loader(boolean packaged, Path directory) throws Exception {
        Files.createDirectories(directory);
        var source = directory.resolve("Mod.java");
        Files.writeString(source, SOURCE);
        var classes = directory.resolve("classes");
        Files.createDirectories(classes);
        var api = Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none",
                "-encoding", "UTF-8", "-classpath", api.toString(), "-d", classes.toString(), source.toString()));
        Path binary = classes;
        if (packaged) {
            binary = directory.resolve("fixture.jar");
            try (var jar = new JarOutputStream(Files.newOutputStream(binary)); var files = Files.walk(classes)) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, jar);
                    jar.closeEntry();
                }
            }
        }
        var manifest = ManifestReader
                .read("""
                        {"formatVersion":1,"id":"example.stable","name":"fixture","version":"1.0.0","platformApiRange":"1.0.0-dev.1","minJavaVersion":21,"mainJar":"main/fixture.jar","entryClass":"example.fixture.Mod","schemaVersion":"1"}
                        """
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ModClassLoader(manifest, List.of(binary.toUri().toURL()), Map.of());
    }

    SchemaIdentity schema(PlatformDatabase db) throws Exception {
        var identity = new SchemaIdentity("example.stable", "1", UUID.randomUUID());
        var names = new SchemaCoordinates(identity);
        try (var c = db.openConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO mod_registry(mod_id,updated_at) VALUES('example.stable',1)");
        }
        try (var c = db.openConnection();
                var p = c.prepareStatement(
                        "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES('example.stable','1',?,?,?,'PREPARING',1)")) {
            p.setObject(1, identity.generation());
            p.setString(2, names.physicalName());
            p.setString(3, names.databaseUser());
            p.executeUpdate();
        }
        new SchemaProvisioner(db).create(identity);
        try (var access = new SchemaAccessScope(db, identity, UUID.randomUUID(), false);
                var c = access.openConnection();
                var s = c.createStatement()) {
            s.execute("CREATE TABLE records(task_id UUID PRIMARY KEY,payload INT NOT NULL)");
        }
        try (var c = db.openConnection(); var s = c.createStatement()) {
            s.execute("UPDATE mod_schema SET state='READY',validated_at=1 WHERE mod_id='example.stable'");
        }
        return identity;
    }

    List<TaskResource> claims(SchemaIdentity identity) {
        return List.of(new TaskResource("MOD", identity.ownerId(), identity.ownerId(), false),
                new TaskResource("SCHEMA", identity.generation().toString(), identity.ownerId(), false));
    }

    ModContext context(ModPageRegistry.PageScope pages, TaskService tasks, ModDatabase database, ModLaunchMode mode) {
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "identity" -> new ModIdentity("example.stable", "1.0.0", Optional.of("1"));
                    case "launchMode" -> mode;
                    case "ui" -> pages;
                    case "tasks" -> tasks;
                    case "db" -> database;
                    default ->
                        throw new AssertionError("Fixture did not implement unused capability " + method.getName());
                });
    }

    ModHostLease lease(ModContext context, PlatformTaskRuntime tasks, ModPageRegistry.PageScope pages) {
        return new ModHostLease(context, tasks, List.of(new ModHostLease.Contribution() {
            public void publish() throws Exception {
                pages.commit();
            }

            public void close() throws Exception {
                pages.close();
            }
        }));
    }

    static <T extends Component> List<T> components(Component root, Class<T> type) {
        var found = new ArrayList<T>();
        if (type.isInstance(root))
            found.add(type.cast(root));
        if (root instanceof Container container)
            for (var child : container.getComponents())
                found.addAll(components(child, type));
        return found;
    }

    static void state(TaskHandle<?> task, TaskState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (task.snapshot().state() != expected && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertEquals(expected, task.snapshot().state(), task.snapshot().toString());
    }

    @SuppressWarnings("unchecked")
    TaskHandle<Integer> task(Class<?> entry) throws Exception {
        return (TaskHandle<Integer>) entry.getField("task").get(null);
    }

    CountDownLatch signal(Class<?> entry, String name) throws Exception {
        return (CountDownLatch) entry.getField(name).get(null);
    }

    @Test
    void classAndJarPagesExecuteRealTasksAndJdbcBeforeOrderlyModStop() throws Exception {
        for (boolean packaged : List.of(false, true)) {
            var work = root.resolve(packaged ? "jar" : "class");
            var loader = loader(packaged, work);
            var entry = loader.loadClass("example.fixture.Mod");
            try (var db = PlatformDatabase.open(work.resolve("data"), PROTECTION)) {
                var identity = schema(db);
                try (var security = security(db);
                        var tasks = new PlatformTaskRuntime(db,
                                (connection, owner, version, request) -> claims(identity), security)) {
                    var pane = new AtomicReference<ExtensionPagePane>();
                    SwingUtilities.invokeAndWait(() -> pane.set(new ExtensionPagePane(key -> {
                    }, owner -> "Fixture", () -> "Locked", settings -> {
                    })));
                    var pages = new ModPageRegistry(pane.get(), new UiEnvironment(Locale.ENGLISH, "light", false));
                    var scope = pages.begin(identity.ownerId(), false);
                    var authorTasks = tasks.bind(identity.ownerId(), "1.0.0", false);
                    var database = new TaskModDatabase(db, tasks, identity);
                    var session = new ModRuntimeSession(loader,
                            lease(context(scope, authorTasks, database, ModLaunchMode.NORMAL), tasks, scope),
                            SemanticVersion.parse("1.0.0-dev.1"), (state, failure, closed) -> {
                            });
                    assertThrows(ApiException.class, () -> authorTasks.submit(
                            new TaskRequest("prestart", "Denied", List.of(), true),
                            task -> new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty())));
                    session.start();
                    assertEquals(1, pages.contributions().size());
                    scope.open("main");
                    SwingUtilities.invokeAndWait(() -> components(pane.get(), JButton.class).stream()
                            .filter(button -> button.getText().equals("Insert")).findFirst().orElseThrow().doClick());
                    assertTrue(signal(entry, "entered").await(8, TimeUnit.SECONDS));
                    var handle = task(entry);
                    pages.closeView(new ModPageRegistry.PageKey(identity.ownerId(), "main"));
                    assertFalse(handle.completion().toCompletableFuture().isDone());
                    assertThrows(ApiException.class, () -> session.stop(StopReason.APPLICATION_RESTART));
                    assertFalse(loader.isClosed());
                    assertEquals(0, entry.getField("stops").getInt(null));
                    signal(entry, "release").countDown();
                    assertEquals(42,
                            handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).value().orElseThrow());
                    session.stop(StopReason.APPLICATION_RESTART);
                    assertTrue(session.resourcesClosed());
                    assertEquals(1, entry.getField("pageCloses").getInt(null));
                    assertTrue(pages.contributions().isEmpty());
                    assertEquals(0, db.openConnections());
                    try (var c = db.openConnection();
                            var s = c.createStatement();
                            var rows = s.executeQuery("SELECT COUNT(*) FROM "
                                    + new SchemaCoordinates(identity).physicalName() + ".records")) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1));
                    }
                }
            } finally {
                signal(entry, "release").countDown();
            }
            if (packaged)
                Files.delete(work.resolve("fixture.jar"));
        }
    }

    @Test
    void unknownCommittedTaskIsReconciledByTheOriginalModWithoutReplayingItsInsert() throws Exception {
        var work = root.resolve("recovery");
        var loader = loader(true, work);
        var entry = loader.loadClass("example.fixture.Mod");
        entry.getField("failAfterCommit").setBoolean(null, true);
        try (var db = PlatformDatabase.open(work.resolve("data"), PROTECTION)) {
            var identity = schema(db);
            try (var security = security(db);
                    var tasks = new PlatformTaskRuntime(db, (connection, owner, version, request) -> claims(identity),
                            security)) {
                var pane = new AtomicReference<ExtensionPagePane>();
                SwingUtilities.invokeAndWait(() -> pane.set(new ExtensionPagePane(key -> {
                }, owner -> "Fixture", () -> "Locked", settings -> {
                })));
                var pages = new ModPageRegistry(pane.get(), new UiEnvironment(Locale.ENGLISH, "light", false));
                var scope = pages.begin(identity.ownerId(), false);
                var context = context(scope, tasks.bind(identity.ownerId(), "1.0.0", false),
                        new TaskModDatabase(db, tasks, identity), ModLaunchMode.NORMAL);
                var session = new ModRuntimeSession(loader, lease(context, tasks, scope),
                        SemanticVersion.parse("1.0.0-dev.1"), (state, failure, closed) -> {
                        });
                session.start();
                scope.open("main");
                signal(entry, "release").countDown();
                SwingUtilities.invokeAndWait(() -> components(pane.get(), JButton.class).stream()
                        .filter(button -> button.getText().equals("Insert")).findFirst().orElseThrow().doClick());
                var handle = task(entry);
                state(handle, TaskState.UNKNOWN);
                assertThrows(ApiException.class, () -> session.stop(StopReason.APPLICATION_EXIT));
                assertEquals(TaskState.ENDED,
                        tasks.reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                assertTrue(handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).value().isEmpty());
                assertEquals(1, entry.getField("executions").getInt(null));
                session.stop(StopReason.APPLICATION_EXIT);
                assertTrue(loader.isClosed());
            }
        }
        Files.delete(work.resolve("fixture.jar"));
    }

    @Test
    void failedStartupRollsBackRealPageRegistrationsAndKeepsBusinessAdmissionClosed() throws Exception {
        var work = root.resolve("failure");
        var loader = loader(false, work);
        var entry = loader.loadClass("example.fixture.Mod");
        entry.getField("failStart").setBoolean(null, true);
        try (var db = PlatformDatabase.open(work.resolve("data"), PROTECTION)) {
            var identity = schema(db);
            try (var security = security(db);
                    var tasks = new PlatformTaskRuntime(db, (connection, owner, version, request) -> claims(identity),
                            security)) {
                var pane = new AtomicReference<ExtensionPagePane>();
                SwingUtilities.invokeAndWait(() -> pane.set(new ExtensionPagePane(key -> {
                }, owner -> "Fixture", () -> "Locked", settings -> {
                })));
                var pages = new ModPageRegistry(pane.get(), new UiEnvironment(Locale.ENGLISH, "light", false));
                var scope = pages.begin(identity.ownerId(), false);
                var authorTasks = tasks.bind(identity.ownerId(), "1.0.0", false);
                var context = context(scope, authorTasks, new TaskModDatabase(db, tasks, identity),
                        ModLaunchMode.NORMAL);
                var session = new ModRuntimeSession(loader, lease(context, tasks, scope),
                        SemanticVersion.parse("1.0.0-dev.1"), (state, failure, closed) -> {
                        });
                assertThrows(IllegalStateException.class, session::start);
                assertTrue(pages.contributions().isEmpty());
                assertTrue(tasks.snapshots().isEmpty());
                assertTrue(session.resourcesClosed());
                assertThrows(ApiException.class, () -> authorTasks.submit(
                        new TaskRequest("insert", "Denied", List.of(), true),
                        task -> new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty())));
                assertThrows(ApiException.class, () -> authorTasks.registerRecovery("insert", (previous,
                        task) -> new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty())));
            }
        }
    }

    @Test
    void recoveryStartupKeepsPagesHiddenAndNewBusinessClosedWhileOriginalTaskIsReconciled() throws Exception {
        var work = root.resolve("old-boot");
        var loader = loader(true, work);
        UUID original = UUID.randomUUID();
        SchemaIdentity identity;
        try (var db = PlatformDatabase.open(work.resolve("data"), PROTECTION)) {
            identity = schema(db);
            var ledger = new TaskLedger(db);
            ledger.enqueue(original, identity.ownerId(), "1.0.0",
                    new TaskRequest("insert", "Recovered row", List.of(), true), claims(identity));
            assertTrue(ledger.tryStart(original));
            try (var c = db.openConnection();
                    var p = c.prepareStatement("INSERT INTO " + new SchemaCoordinates(identity).physicalName()
                            + ".records VALUES(?,42)")) {
                p.setObject(1, original);
                p.executeUpdate();
            }
            assertFalse(ledger.complete(original, Outcome.UNKNOWN, "{\"outcome\":\"UNKNOWN\"}", false));
        }
        try (var db = PlatformDatabase.open(work.resolve("data"), PROTECTION);
                var security = security(db);
                var tasks = new PlatformTaskRuntime(db, (connection, owner, version, request) -> claims(identity),
                        security)) {
            var pane = new AtomicReference<ExtensionPagePane>();
            SwingUtilities.invokeAndWait(() -> pane.set(new ExtensionPagePane(key -> {
            }, owner -> "Fixture", () -> "Locked", settings -> {
            })));
            var pages = new ModPageRegistry(pane.get(), new UiEnvironment(Locale.ENGLISH, "light", false));
            var scope = pages.begin(identity.ownerId(), true);
            var authorTasks = tasks.bind(identity.ownerId(), "1.0.0", true);
            var session = new ModRuntimeSession(loader,
                    lease(context(scope, authorTasks, new TaskModDatabase(db, tasks, identity), ModLaunchMode.RECOVERY),
                            tasks, scope),
                    SemanticVersion.parse("1.0.0-dev.1"), (state, failure, closed) -> {
                    });
            session.start();
            assertTrue(pages.contributions().isEmpty());
            assertThrows(ApiException.class, () -> scope.open("main"));
            assertThrows(ApiException.class,
                    () -> authorTasks.submit(new TaskRequest("insert", "Denied", List.of(), true),
                            task -> new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty())));
            assertEquals(TaskState.ENDED,
                    tasks.reconcile(original).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            session.stop(StopReason.RECOVERY_FINISHED);
            assertTrue(session.resourcesClosed());
            var normal = tasks.bind(identity.ownerId(), "1.0.0", false);
            assertThrows(ApiException.class,
                    () -> normal.submit(new TaskRequest("insert", "Restart required", List.of(), true),
                            task -> new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty())));
        }
        Files.delete(work.resolve("fixture.jar"));
    }
}
