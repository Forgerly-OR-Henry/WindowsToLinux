package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.app.db.task.DependencyRecovery;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.AuthorizationMode;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;

import gold.debug.windowstolinux.app.mod.load.ModClassLoader;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.service.db.TaskModDatabase;
import gold.debug.windowstolinux.app.service.mod.PlatformModServices;

import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.server.ServerService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModServicesTest {
    static final String PROVIDER = "example.provider";

    @TempDir
    Path root;

    Path compileProvider() throws Exception {
        var source = root.resolve("provider-src");
        var classes = root.resolve("provider-classes");
        Files.createDirectories(classes);
        var files = Map.of("example.provider.api.Service",
            """
                package example.provider.api;
                public interface Service {
                    Object execute(String mode, gold.debug.windowstolinux.appapi.task.TaskContext task) throws Exception;
                }
                """,
            "example.provider.api.View", """
                package example.provider.api;
                public record View(java.util.List<String> values) {}
                """, "example.provider.Mod",
            """
                package example.provider;
                
                
                import gold.debug.windowstolinux.appapi.server.*;
                import java.util.*;
                public final class Mod implements example.provider.api.Service {
                    private final ModDatabase db;
                    private final ServerService servers;
                    private final TaskService tasks;
                    private final Runnable hook;
                    private java.sql.Connection retained;
                    public final List<String> values = new ArrayList<>(List.of("original"));
                    public Mod(ModDatabase db, ServerService servers, TaskService tasks, Runnable hook) {
                        this.db=db;this.servers=servers;this.tasks=tasks;this.hook=hook;
                    }
                    public void cleanup() throws Exception { if(retained!=null) retained.close(); }
                    public Object execute(String mode, TaskContext task) throws Exception {
                        hook.run();
                        return switch(mode) {
                            case "write" -> {
                                try(var c=db.openConnection(task);var s=c.createStatement()) {
                                    yield s.executeUpdate("INSERT INTO items VALUES(42)");
                                }
                            }
                            case "read" -> {
                                try(var c=db.openConnection(task);var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM items")) {
                                    r.next(); yield r.getInt(1);
                                }
                            }
                            case "servers" -> servers.list(Optional.empty(), 10, task).items().stream().map(s -> s.ref().id()).toList();
                            case "unadmitted" -> servers.get(new ServerRef("other"), task);
                            case "snapshot" -> new example.provider.api.View(values);
                            case "leak" -> { retained=db.openConnection(task); yield "retained"; }
                            case "close" -> { if(retained!=null) retained.close(); yield "closed"; }
                            case "future" -> java.util.concurrent.CompletableFuture.completedFuture("done");
                            case "throw" -> throw new IllegalStateException("must-not-leak-secret");
                            case "async" -> {
                                var result = new java.util.concurrent.atomic.AtomicReference<String>();
                                Thread worker=Thread.ofVirtual().start(() -> {
                                    try(var c=db.openConnection(task)) { result.set("WRONG"); }
                                    catch(ApiException denied) { result.set(denied.problem().code().name()); }
                                    catch(Exception unexpected) { result.set("UNEXPECTED"); }
                                }); worker.join(); yield result.get();
                            }
                            case "reenter" -> {
                                try { tasks.submit(new TaskRequest("nested", "nested", List.of("server"), false), c -> new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty())); yield "WRONG"; }
                                catch(ApiException denied) { yield denied.problem().eventCode(); }
                            }
                            default -> "ready";
                        };
                    }
                }
                """);
        var args = new ArrayList<>(List.of("--release", "21", "-proc:none", "-encoding", "UTF-8", "-d",
            classes.toString(), "-classpath",
            Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()));
        for (var entry : files.entrySet()) {
            var path = source.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, entry.getValue());
            args.add(path.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return classes;
    }

    ModClassLoader loader(String owner, Path classes, boolean dependency, Map<String, ModClassLoader> exports)
        throws Exception {
        String json = """
            {"formatVersion":1,"id":"%s","name":"fixture","version":"1.0.0","platformApiRange":"1.0.0-dev.1","minJavaVersion":21,"mainJar":"main/fixture.jar","entryClass":"%s.Mod"%s%s}
            """
            .formatted(owner, owner,
                owner.equals(PROVIDER) ? ",\"schemaVersion\":\"1\",\"exports\":[\"example.provider.api\"]" : "",
                dependency
                    ? ",\"dependencies\":[{\"id\":\"example.provider\",\"versionRange\":\"[1.0.0,2.0.0)\"}]"
                    : "");
        return new ModClassLoader(ManifestReader.read(json.getBytes(StandardCharsets.UTF_8)),
            List.of(classes.toUri().toURL()), exports);
    }

    static SchemaIdentity provider(PlatformDatabase db) throws Exception {
        sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)", PROVIDER);
        sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,?,'fixture',?,'1',?,'CLASS',1,'READY')",
            PROVIDER, VERSION, "0".repeat(64),
            PlatformModIntentsTest.manifest(PROVIDER, VERSION, Map.of(), "1", List.of()));
        sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version=?,selection_initialized=TRUE,actual_version=?,last_runtime_state='RUNNING',last_boot_id=? WHERE mod_id=?",
            VERSION, VERSION, db.bootId(), PROVIDER);
        var identity = new SchemaIdentity(PROVIDER, "1", UUID.randomUUID());
        var coordinates = new SchemaCoordinates(identity);
        sql(db, "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,'1',?,?,?,'PREPARING',1)",
            PROVIDER, identity.generation(), coordinates.physicalName(), coordinates.databaseUser());
        new SchemaProvisioner(db).create(identity);
        try (var scope = new SchemaAccessScope(db, identity, UUID.randomUUID(), false);
             var c = scope.openConnection();
             var s = c.createStatement()) {
            s.execute("CREATE TABLE items(id INT PRIMARY KEY)");
        }
        sql(db, "UPDATE mod_schema SET state='READY',validated_at=1 WHERE mod_id=?", PROVIDER);
        return identity;
    }

    final class Fixture implements AutoCloseable {
        final PlatformRuntime runtime;

        final PlatformDatabase db;

        final SchemaIdentity schema;

        final ModClassLoader providerLoader, callerLoader;

        final PlatformModServices.Binding providerBinding, callerBinding;

        final Class<Object> contract;

        final Object implementation;

        final AtomicReference<Runnable> hook = new AtomicReference<>(() -> {
        });

        final TaskModDatabase business;

        final TaskService tasks;

        final ServiceRegistration registration;

        final PlatformRemoteCapabilitiesTest.Reviews reviews = new PlatformRemoteCapabilitiesTest.Reviews();

        final PlatformRuntime.ModCapabilities.RemoteCapabilities providerRemote;

        final AtomicReference<Throwable> recoveryFailure = new AtomicReference<>();

        @SuppressWarnings("unchecked")
        Fixture(Path classes, ModLaunchMode mode, SchemaIdentity original) throws Exception {
            runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION);
            db = runtime.persistence().database();
            if (original == null) {
                initialize(runtime, false);
                schema = provider(db);
            } else {
                runtime.security().unlock(PASSWORD.toCharArray());
                schema = original;
            }
            runtime.setInteractionPresenter(reviews);
            business = new TaskModDatabase(db, runtime.tasks(), schema);
            providerLoader = loader(PROVIDER, classes, false, Map.of());
            callerLoader = loader(OWNER, classes, true, Map.of("example.provider.api", providerLoader));
            sql(db, "UPDATE mod_version SET manifest_json=? WHERE mod_id=? AND version=?",
                providerLoader.manifest().json(), PROVIDER, VERSION);
            sql(db, "UPDATE mod_version SET manifest_json=? WHERE mod_id=? AND version=?",
                callerLoader.manifest().json(), OWNER, VERSION);
            contract = (Class<Object>) providerLoader.loadClass("example.provider.api.Service");
            assertSame(contract, callerLoader.loadClass(contract.getName()));
            tasks = runtime.tasks().bind(OWNER, VERSION, mode == ModLaunchMode.RECOVERY);
            implementation = providerLoader.loadClass("example.provider.Mod")
                .getConstructor(ModDatabase.class, ServerService.class, TaskService.class, Runnable.class)
                .newInstance(business, runtime.capabilities().serverAccess(PROVIDER, VERSION, Optional.of(schema)),
                    runtime.tasks().bind(PROVIDER, VERSION, mode == ModLaunchMode.RECOVERY),
                    (Runnable) () -> hook.get().run());
            providerBinding = runtime.services().bind(providerLoader, Optional.of(schema), mode);
            callerBinding = runtime.services().bind(callerLoader, Optional.empty(), mode);
            providerRemote = runtime.capabilities().remoteCapabilities(PROVIDER, VERSION);
            providerBinding.bindRecoveryCleanup(providerRemote::closeTaskResources);
            registration = providerBinding.register(contract, implementation);
        }

        void publish() throws Exception {
            providerBinding.publish();
            callerBinding.publish();
        }

        ServiceHandle<Object> handle() throws Exception {
            return callerBinding.require(PROVIDER, contract);
        }

        Object invoke(Object service, String mode, TaskContext task) throws Exception {
            try {
                return contract.getMethod("execute", String.class, TaskContext.class).invoke(service, mode, task);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception e)
                    throw e;
                throw (Error) failure.getCause();
            }
        }

        Object call(ServiceHandle<Object> handle, String mode, TaskContext task) throws Exception {
            return handle.call(task, p -> invoke(p, mode, task));
        }

        AutoCloseable recovery(TaskRecovery callback) throws Exception {
            return tasks.registerRecovery("fixture", (before, context) -> {
                try {
                    return callback.reconcile(before, context);
                } catch (Exception | AssertionError failure) {
                    recoveryFailure.set(failure);
                    throw failure;
                }
            });
        }

        TaskSnapshot reconcile(UUID id) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (true) {
                try {
                    var result = runtime.tasks().reconcile(id).toCompletableFuture().get(12, TimeUnit.SECONDS);
                    if (recoveryFailure.get() != null)
                        throw new AssertionError("Recovery callback failed", recoveryFailure.get());
                    return result;
                } catch (ApiException busy) {
                    if (!busy.problem().eventCode().equals("TASK_RECOVERY_BUSY") || System.nanoTime() >= until)
                        throw busy;
                    Thread.sleep(5);
                }
            }
        }

        @Override
        public void close() throws Exception {
            providerRemote.close();
            runtime.close();
            callerLoader.close();
            providerLoader.close();
        }
    }

    @Test
    void actualProviderUsesOwnJdbcAndOriginalServersThenReleasesAllClaims() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null)) {
            assertThrows(ApiException.class, f::handle);
            f.publish();
            try (var handle = f.handle()) {
                assertEquals(0, count(f.db, "SELECT COUNT(*) FROM platform_task_resource"));
                var escaped = new AtomicReference<Object>();
                var callback = new AtomicReference<gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime.HostCallback<Void, Void, String>>();
                var task = f.tasks.submit(request("server"), context -> {
                    assertThrows(ApiException.class, () -> f.business.openConnection(context));
                    f.hook.set(() -> {
                        try {
                            assertEquals(1, new TaskLedger(f.db).queries().dependencies(context.id()).size());
                            assertFalse(f.runtime.tasks().ownerBlockers(PROVIDER).isEmpty());
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    });
                    assertEquals(1, f.call(handle, "write", context));
                    assertEquals(List.of("server"), f.call(handle, "servers", context));
                    assertEquals("ACCESS_DENIED", f.call(handle, "async", context));
                    assertEquals("SERVICE_TASK_REENTRY", f.call(handle, "reenter", context));
                    Object snapshot = handle.call(context, p -> {
                        escaped.set(p);
                        return f.invoke(p, "snapshot", context);
                    });
                    handle.call(context, p -> {
                        callback.set(f.runtime.tasks().hostCallback(context, PROVIDER, VERSION, (a, b) -> "allowed"));
                        return "ready";
                    });
                    assertEquals("SERVICE_CALLBACK_EXPIRED",
                        assertThrows(ApiException.class, () -> callback.get().apply(null, null)).problem()
                            .eventCode());
                    @SuppressWarnings("unchecked")
                    var values = (List<String>) snapshot.getClass().getMethod("values").invoke(snapshot);
                    @SuppressWarnings("unchecked")
                    var original = (List<String>) f.implementation.getClass().getField("values").get(f.implementation);
                    original.add("later");
                    assertEquals(List.of("original"), values);
                    assertThrows(UnsupportedOperationException.class, () -> values.add("changed"));
                    assertThrows(ApiException.class, () -> f.invoke(escaped.get(), "write", context));
                    assertThrows(ApiException.class, () -> f.business.openConnection(context));
                    assertTrue(new TaskLedger(f.db).queries().dependencies(context.id()).isEmpty());
                    assertTrue(f.runtime.tasks().ownerBlockers(PROVIDER).isEmpty());
                    return success(null);
                });
                assertEquals(Outcome.SUCCEEDED,
                    task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
                assertEquals(1, count(f.db, "SELECT COUNT(*) FROM mod_schema WHERE ever_used=TRUE"));
                assertEquals(0, count(f.db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }

    @Test
    void providerFailureAndLiveValuesRetainOriginalFactsUntilExplicitRecovery() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null)) {
            f.publish();
            try (var handle = f.handle(); var recovery = f.recovery((before, context) -> {
                assertEquals(1, new TaskLedger(f.db).queries().dependencies(context.id()).size());
                return success(null);
            })) {
                for (String mode : List.of("throw", "future", "unadmitted")) {
                    var task = f.tasks.submit(request("server"), context -> {
                        assertThrows(ApiException.class, () -> f.call(handle, mode, context));
                        return success(null);
                    });
                    state(task, TaskState.UNKNOWN);
                    assertFalse(f.runtime.tasks().ownerBlockers(PROVIDER).isEmpty());
                    assertEquals(TaskState.ENDED, f.reconcile(task.id()).state());
                }
            }
        }
    }

    @Test
    void physicalJdbcLeakRetainsProviderUntilItsOriginalThreadClosesConnection() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null)) {
            f.publish();
            try (var handle = f.handle(); var recovery = f.recovery((before, context) -> {
                return success(null);
            })) {
                var leaked = new CountDownLatch(1);
                var close = new CountDownLatch(1);
                var task = f.tasks.submit(request(), context -> {
                    assertThrows(ApiException.class, () -> f.call(handle, "leak", context));
                    leaked.countDown();
                    try {
                        assertTrue(close.await(12, TimeUnit.SECONDS));
                    } finally {
                        f.implementation.getClass().getMethod("cleanup").invoke(f.implementation);
                    }
                    return success(null);
                });
                try {
                    assertTrue(leaked.await(8, TimeUnit.SECONDS));
                    state(task, TaskState.UNKNOWN);
                    assertEquals(1, new TaskLedger(f.db).queries().dependencies(task.id()).size());
                    assertTrue(new TaskLedger(f.db).queries().hasDependencyResources(task.id(), Set.of(PROVIDER)));
                    assertEquals("TASK_RECOVERY_BUSY",
                        assertThrows(ApiException.class, () -> f.runtime.tasks().reconcile(task.id())).problem()
                            .eventCode());
                } finally {
                    close.countDown();
                }
                assertEquals(TaskState.ENDED, f.reconcile(task.id()).state());
            }
        }
    }

    @Test
    void declaredDependencyAndOriginalClassIdentityAreRequiredBeforeDiscovery() throws Exception {
        Path classes = compileProvider();
        try (var f = new Fixture(classes, ModLaunchMode.NORMAL, null);
             var unrelated = loader("example.unrelated", classes, false,
                 Map.of("example.provider.api", f.providerLoader));
             var duplicate = loader(PROVIDER, classes, false, Map.of());
             var binding = f.runtime.services().bind(unrelated, Optional.empty(), ModLaunchMode.NORMAL)) {
            f.publish();
            binding.publish();
            assertThrows(ApiException.class, () -> binding.require(PROVIDER, f.contract));
            assertThrows(ApiException.class,
                () -> f.callerBinding.require(PROVIDER, duplicate.loadClass(f.contract.getName())));
            assertThrows(ApiException.class, () -> f.providerBinding.register(Runnable.class, () -> {
            }));
            assertEquals(0, count(f.db, "SELECT COUNT(*) FROM platform_task_resource"));
            try (var handle = f.handle()) {
                var admitted = new CountDownLatch(1);
                var providerChanged = new CountDownLatch(1);
                var task = f.tasks.submit(request(), context -> {
                    admitted.countDown();
                    assertTrue(providerChanged.await(12, TimeUnit.SECONDS));
                    assertThrows(ApiException.class, () -> f.call(handle, "write", context));
                    return success(null);
                });
                try {
                    assertTrue(admitted.await(8, TimeUnit.SECONDS));
                    sql(f.db, "UPDATE mod_registry SET enabled=FALSE WHERE mod_id=?", PROVIDER);
                    assertEquals("MOD_PROVIDER_NOT_RUNNING",
                        assertThrows(ApiException.class, () -> f.tasks.submit(request(), context -> success(null)))
                            .problem().eventCode());
                } finally {
                    providerChanged.countDown();
                }
                assertEquals(Outcome.SUCCEEDED,
                    task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
                assertTrue(new TaskLedger(f.db).queries().dependencies(task.id()).isEmpty());
                sql(f.db, "UPDATE mod_registry SET enabled=TRUE WHERE mod_id=?", PROVIDER);
                var ledger = new TaskLedger(f.db);
                var maintenance = ledger.enqueue(UUID.randomUUID(), PROVIDER, VERSION, request(),
                    List.of(new TaskResource("MOD", PROVIDER, PROVIDER, true)));
                assertTrue(ledger.tryStart(maintenance));
                try {
                    var blocked = f.tasks.submit(request(), context -> {
                        assertEquals(ProblemCode.BUSY,
                            assertThrows(ApiException.class, () -> f.call(handle, "write", context)).problem()
                                .code());
                        assertTrue(ledger.queries().dependencies(context.id()).isEmpty());
                        return success(null);
                    });
                    assertEquals(Outcome.SUCCEEDED,
                        blocked.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
                } finally {
                    assertTrue(ledger.complete(maintenance, Outcome.SUCCEEDED, "{}", false));
                }
            }
        }
    }

    @Test
    void providerRemoteActionsUseOriginalWorkflowModesWithoutTargetExpansion() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null)) {
            f.runtime.persistence().servers()
                .saveServerProfile(new gold.debug.windowstolinux.app.db.server.StoredServerProfile("server",
                    "127.0.0.1", remote.listener.server().getPort(), "alice", "credential", "本机回环"));
            var reviews = f.reviews;
            f.publish();
            try (var handle = f.handle();
                 var caller = f.runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var provider = f.runtime.capabilities().remoteCapabilities(PROVIDER, VERSION)) {
                var original = new AtomicReference<TaskContext>();
                var failed = new AtomicReference<Throwable>();
                f.hook.set(() -> {
                    try {
                        var task = original.get();
                        var command = PlatformRemoteCapabilitiesTest.command("builtin printf 'cross-mod'");
                        assertThrows(ApiException.class, () -> provider.interaction().approve(PlatformAuthorizationTest
                            .action(command, "application.restart", PlatformAuthorizationTest.OTHER), task));
                        try (var session = provider.linux().connect(PlatformRemoteCapabilitiesTest.SERVER, task)) {
                            var grant = provider
                                .interaction().approve(PlatformAuthorizationTest.action(command,
                                    "application.restart", PlatformAuthorizationTest.APP), task)
                                .value().orElseThrow();
                            assertEquals(Outcome.SUCCEEDED, session.execute(command, grant).outcome());
                        }
                    } catch (Exception | AssertionError e) {
                        failed.set(e);
                        throw new AssertionError(e);
                    }
                });
                for (var mode : AuthorizationMode.values()) {
                    f.runtime.authorization().saveMode(mode);
                    long before = PlatformAuthorizationTest.prompts(reviews);
                    var task = caller.launcher().submit(
                        new gold.debug.windowstolinux.app.service.task.PlatformAuthorizationService.Launch<>(
                            request("server"), Set.of(PlatformAuthorizationTest.APP), context -> {
                            original.set(context);
                            for (int i = 0; i < 3; i++)
                                assertEquals("ready", f.call(handle, "ready", context));
                            assertThrows(ApiException.class, () -> provider.linux()
                                .connect(PlatformRemoteCapabilitiesTest.SERVER, context));
                            return success(null);
                        }));
                    try {
                        assertEquals(Outcome.SUCCEEDED,
                            task.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
                    } catch (TimeoutException timeout) {
                        throw new AssertionError(task.snapshot().toString(),
                            failed.get() == null ? timeout : failed.get());
                    }
                    assertEquals(mode == AuthorizationMode.ONCE ? 3 : mode == AuthorizationMode.GENERAL ? 1 : 0,
                        PlatformAuthorizationTest.prompts(reviews) - before);
                }
                assertEquals(9, remote.commands.get());
                f.runtime.authorization().saveMode(AuthorizationMode.FULL);
                var source = root.resolve("source.txt");
                var destination = root.resolve("destination.txt");
                Files.writeString(source, "service-file");
                Files.writeString(destination, "old-local");
                Files.writeString(remote.files.resolve("artifact"), "old-remote");
                var choices = new AtomicInteger();
                reviews.conflictPolicy = conflict -> {
                    choices.incrementAndGet();
                    return gold.debug.windowstolinux.appapi.linux.TransferConflictChoice.OVERWRITE;
                };
                f.hook.set(() -> {
                    try (var session = provider.linux().connect(PlatformRemoteCapabilitiesTest.SERVER,
                        original.get())) {
                        for (boolean upload : List.of(true, false)) {
                            var request = new gold.debug.windowstolinux.appapi.linux.TransferRequest(
                                upload ? source : destination, "/artifact", 1024);
                            var digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(source)));
                            var intent = gold.debug.windowstolinux.appapi.linux.RemoteActions
                                .transfer(PlatformRemoteCapabilitiesTest.SERVER, "revision", request, digest,
                                    upload)
                                .withSemantics(new ActionSemantics("application.files",
                                    Set.of(PlatformAuthorizationTest.APP)));
                            var grant = provider.interaction().approve(intent, original.get()).value().orElseThrow();
                            assertEquals(Outcome.SUCCEEDED,
                                (upload ? session.upload(request, grant) : session.download(request, grant))
                                    .outcome());
                        }
                    } catch (Exception | AssertionError e) {
                        failed.set(e);
                        throw new AssertionError(e);
                    }
                });
                var transfer = caller.launcher()
                    .submit(new gold.debug.windowstolinux.app.service.task.PlatformAuthorizationService.Launch<>(
                        request("server"), Set.of(PlatformAuthorizationTest.APP), context -> {
                        original.set(context);
                        return success(f.call(handle, "ready", context));
                    }));
                try {
                    assertEquals(Outcome.SUCCEEDED,
                        transfer.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
                } catch (TimeoutException timeout) {
                    throw new AssertionError(transfer.snapshot().toString(),
                        failed.get() == null ? timeout : failed.get());
                }
                assertEquals(2, choices.get());
                assertEquals("service-file", Files.readString(destination));
                assertEquals("service-file", Files.readString(remote.files.resolve("artifact")));
            }
        }
    }

    @Test
    void acceptedCallOwnsProviderWhileStopSealsNewCalls() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null)) {
            f.publish();
            try (var handle = f.handle()) {
                var entered = new CountDownLatch(1);
                var proceed = new CountDownLatch(1);
                f.hook.set(() -> {
                    entered.countDown();
                    try {
                        if (!proceed.await(12, TimeUnit.SECONDS))
                            throw new AssertionError("Timed out");
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                });
                var task = f.tasks.submit(request(), context -> success(f.call(handle, "ready", context)));
                try {
                    assertTrue(entered.await(8, TimeUnit.SECONDS));
                    assertEquals(ProblemCode.BUSY,
                        assertThrows(ApiException.class, f.registration::close).problem().code());
                    assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, handle::close).problem().code());
                    f.providerBinding.seal();
                    assertFalse(f.runtime.tasks().ownerBlockers(PROVIDER).isEmpty());
                    assertThrows(ApiException.class, f::handle);
                } finally {
                    proceed.countDown();
                }
                assertEquals(Outcome.SUCCEEDED,
                    task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
                f.registration.close();
                f.providerBinding.close();
                assertTrue(f.runtime.tasks().ownerBlockers(PROVIDER).isEmpty());
            }
        }
    }

    @Test
    void failedAtomicReleaseRemainsUnknownAndDoesNotRepeatProviderSql() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null)) {
            f.publish();
            try (var handle = f.handle(); var recovery = f.recovery((before, context) -> {
                assertEquals(1, f.call(handle, "read", context));
                return success(null);
            })) {
                sql(f.db,
                    "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_service_release CHECK(resource_kind<>'SCHEMA' OR released_at IS NULL)");
                var task = f.tasks.submit(request(), context -> {
                    assertThrows(ApiException.class, () -> f.call(handle, "write", context));
                    return success(null);
                });
                state(task, TaskState.UNKNOWN);
                assertEquals(1, new TaskLedger(f.db).queries().dependencies(task.id()).size());
                sql(f.db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_service_release");
                assertEquals(TaskState.ENDED, f.reconcile(task.id()).state());
            }
        }
    }

    @Test
    void recoveryGraphUsesRetainedInstalledVersionAndDoesNotPublishToNormalGraph() throws Exception {
        Path classes = compileProvider();
        SchemaIdentity schema;
        UUID taskId;
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            schema = provider(runtime.persistence().database());
        }
        try (var db = PlatformDatabase.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            var ledger = new TaskLedger(db);
            taskId = ledger.enqueue(UUID.randomUUID(), OWNER, VERSION, request(),
                List.of(new TaskResource("MOD", OWNER, OWNER, false)));
            assertTrue(ledger.tryStart(taskId));
            var old = new DependencyRecovery(db.bootId(), UUID.randomUUID(), PROVIDER, VERSION, Optional.of(schema));
            ledger.retainDependency(taskId, old, c -> old.claims());
            sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,'2.0.0','next',?,'1','{}','CLASS',1,'READY')",
                PROVIDER, "1".repeat(64));
            sql(db, "UPDATE mod_registry SET selected_version='2.0.0' WHERE mod_id=?", PROVIDER);
        }
        try (var f = new Fixture(classes, ModLaunchMode.RECOVERY, schema)) {
            f.publish();
            try (var normalLoader = loader("example.normal", classes, true,
                Map.of("example.provider.api", f.providerLoader));
                 var normal = f.runtime.services().bind(normalLoader, Optional.empty(), ModLaunchMode.NORMAL)) {
                normal.publish();
                assertThrows(ApiException.class, () -> normal.require(PROVIDER, f.contract));
            }
            try (var handle = f.handle(); var recovery = f.recovery((before, context) -> {
                assertEquals(0, f.call(handle, "read", context));
                return success(null);
            })) {
                assertEquals(TaskState.ENDED, f.reconcile(taskId).state());
                assertEquals(1, count(f.db,
                    "SELECT COUNT(*) FROM mod_registry WHERE mod_id='example.provider' AND selected_version='2.0.0'"));
            }
        }
    }
}
