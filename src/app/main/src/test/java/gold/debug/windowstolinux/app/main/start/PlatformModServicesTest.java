package gold.debug.windowstolinux.app.main.start;

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
import gold.debug.windowstolinux.app.db.task.DependencyRecovery;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.load.ModClassLoader;
import gold.debug.windowstolinux.app.service.db.TaskModDatabase;
import gold.debug.windowstolinux.app.service.mod.PlatformModServices;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.mod.service.ServiceHandle;
import gold.debug.windowstolinux.appapi.mod.service.ServiceRegistration;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.AuthorizationMode;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
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
                    Object execute(String mode, gold.debug.windowstolinux.appapi.task.run.TaskContext task) throws Exception;
                }
                """,
            "example.provider.api.View", """
                package example.provider.api;
                public record View(java.util.List<String> values) {}
                """, "example.provider.Mod",
            """
                package example.provider;


                import gold.debug.windowstolinux.appapi.server.model.ServerId; import gold.debug.windowstolinux.appapi.server.ServerService;
                import gold.debug.windowstolinux.appapi.db.ModDatabase;
                import gold.debug.windowstolinux.appapi.task.result.ApiException; import gold.debug.windowstolinux.appapi.task.result.OperationResult; import gold.debug.windowstolinux.appapi.task.run.TaskContext; import gold.debug.windowstolinux.appapi.task.model.TaskRequest; import gold.debug.windowstolinux.appapi.task.result.TaskResult; import gold.debug.windowstolinux.appapi.task.TaskService;
                import java.util.*;
                public final class Mod implements example.provider.api.Service {
                    private final ModDatabase db;
                    private final ServerService servers;
                    private final TaskService tasks;
                    private final Runnable hook;
                    private java.sql.Connection retained;
                    private gold.debug.windowstolinux.appapi.task.run.ResourceLease retainedCustom;
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
                            case "unadmitted" -> servers.get(new ServerId("other"), task);
                            case "snapshot" -> new example.provider.api.View(values);
                            case "checkpoint" -> {
                                try(var fact=tasks.beginCheckpoint(task,"same","{\\"provider\\":true}")) {
                                    yield fact.snapshot().payload();
                                }
                            }
                            case "asyncCheckpoint" -> {
                                var result = new java.util.concurrent.atomic.AtomicReference<String>();
                                Thread worker=Thread.ofVirtual().start(() -> {
                                    try { tasks.findCheckpoint(task,"same"); result.set("WRONG"); }
                                    catch(ApiException denied) { result.set(denied.problem().code().name()); }
                                }); worker.join(); yield result.get();
                            }
                            case "leak" -> { retained=db.openConnection(task); yield "retained"; }
                            case "close" -> { if(retained!=null) retained.close(); yield "closed"; }
                            case "retainCustom" -> { retainedCustom=task.retain("provider_fixture", "original", "Original provider resource"); yield "retained"; }
                            case "closeCustom" -> { if(retainedCustom!=null) retainedCustom.close(); yield "closed"; }
                            case "future" -> java.util.concurrent.CompletableFuture.completedFuture("done");
                            case "throw" -> throw new IllegalStateException("must-not-leak-secret");
                            case "providerError" -> throw new java.util.ServiceConfigurationError("must-not-leak-secret");
                            case "providerFatal" -> throw new InternalError("synthetic provider VM error");
                            case "providerDeath" -> throw new ThreadDeath();
                            case "async" -> {
                                var result = new java.util.concurrent.atomic.AtomicReference<String>();
                                Thread worker=Thread.ofVirtual().start(() -> {
                                    try(var c=db.openConnection(task)) { result.set("WRONG"); }
                                    catch(ApiException denied) { result.set(denied.problem().code().name()); }
                                    catch(Exception unexpected) { result.set("UNEXPECTED"); }
                                }); worker.join(); yield result.get();
                            }
                            case "reenter" -> {
                                try { tasks.submit(new TaskRequest("nested", "nested", List.of("server"), false), c -> new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty())); yield "WRONG"; }
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
        return loader(owner, classes, dependency, exports, false);
    }

    ModClassLoader loader(String owner, Path classes, boolean dependency, Map<String, ModClassLoader> exports,
                          boolean optional) throws Exception {
        String json = """
            {"formatVersion":1,"id":"%s","name":"fixture","version":"1.0.0","platformApiRange":"1.0.0-dev.1","minJavaVersion":21,"mainJar":"main/fixture.jar","entryClass":"%s.Mod"%s%s}
            """
            .formatted(owner, owner,
                owner.equals(PROVIDER) ? ",\"schemaVersion\":\"1\",\"exports\":[\"example.provider.api\"]" : "",
                dependency
                    ? ",\"dependencies\":[{\"id\":\"example.provider\",\"versionRange\":\"[1.0.0,2.0.0)\"}]"
                    : "");
        if (optional)
            json = json.replace("\"versionRange\":\"[1.0.0,2.0.0)\"", "\"versionRange\":\"[1.0.0,2.0.0)\",\"optional\":true");
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
            this(classes, mode, original, false);
        }

        @SuppressWarnings("unchecked")
        Fixture(Path classes, ModLaunchMode mode, SchemaIdentity original, boolean optional) throws Exception {
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
            callerLoader = loader(OWNER, classes, true, Map.of("example.provider.api", providerLoader), optional);
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
    void optionalUsePersistsExactGraphAndPinsBeforeAuthorHookWhileDiscoveryIsReadOnly() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null, true)) {
            f.publish();
            assertEquals(gold.debug.windowstolinux.appapi.mod.service.AvailabilityState.AVAILABLE,
                f.callerBinding.availability(PROVIDER).state());
            assertThrows(ApiException.class, () -> f.callerBinding.availability("example.undeclared"));
            assertEquals(0, count(f.db, "SELECT COUNT(*) FROM platform_task_resource"));
            try (var handle = f.handle()) {
                var task = f.tasks.submit(request(), context -> {
                    var ledger = new TaskLedger(f.db);
                    assertEquals(1, ledger.queries().recoveryGraph(context.id()).orElseThrow().releases().size());
                    f.hook.set(() -> {
                        try {
                            var graph = ledger.queries().recoveryGraph(context.id()).orElseThrow();
                            assertEquals(Optional.of(f.schema), graph.release(PROVIDER).schema());
                            assertTrue(graph.edges().contains(new gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph.Edge(OWNER, PROVIDER,
                                gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph.EdgeKind.OPTIONAL_USED)));
                            assertTrue(ledger.queries().holds(context.id(), new TaskResource("MOD", PROVIDER, PROVIDER, false)));
                            assertEquals("service.v2", ledger.queries().dependencies(context.id()).keySet().iterator().next().encode().protocol());
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    });
                    assertEquals(1, f.call(handle, "write", context));
                    assertTrue(ledger.queries().holds(context.id(), new TaskResource("MOD", PROVIDER, PROVIDER, false)));
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
            }
            assertEquals(0, count(f.db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void busyOptionalProviderCannotExpandOriginalGraphOrReachAuthorCode() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null, true)) {
            f.publish();
            f.hook.set(() -> fail("Rejected optional call reached provider"));
            var ledger = new TaskLedger(f.db);
            var maintenance = ledger.enqueue(UUID.randomUUID(), PROVIDER, VERSION, request(),
                List.of(new TaskResource("MOD", PROVIDER, PROVIDER, true)));
            assertTrue(ledger.tryStart(maintenance));
            try (var handle = f.handle()) {
                var task = f.tasks.submit(request(), context -> {
                    assertEquals(ProblemCode.BUSY,
                        assertThrows(ApiException.class, () -> f.call(handle, "write", context)).problem().code());
                    assertEquals(List.of(OWNER), ledger.queries().recoveryGraph(context.id()).orElseThrow()
                        .releases().stream().map(gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph.Release::owner)
                        .toList());
                    assertTrue(ledger.queries().dependencies(context.id()).isEmpty());
                    assertFalse(ledger.queries().holds(context.id(), new TaskResource("MOD", PROVIDER, PROVIDER, false)));
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED,
                    task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
            } finally {
                assertTrue(ledger.complete(maintenance, TaskResult.SUCCEEDED, "{}", false));
            }
        }
    }

    @Test
    void availabilityTracksHostStateWithoutLoadingProviderTypesOrRetainingClaims() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null, true)) {
            f.publish();
            var available = gold.debug.windowstolinux.appapi.mod.service.AvailabilityState.AVAILABLE;
            var unavailable = gold.debug.windowstolinux.appapi.mod.service.AvailabilityState.START_FAILED;
            assertEquals(available, f.callerBinding.availability(PROVIDER).state());
            sql(f.db, "UPDATE mod_registry SET enabled=FALSE WHERE mod_id=?", PROVIDER);
            assertEquals(gold.debug.windowstolinux.appapi.mod.service.AvailabilityState.DISABLED,
                f.callerBinding.availability(PROVIDER).state());
            sql(f.db, "UPDATE mod_registry SET enabled=TRUE,last_runtime_state='FAILED' WHERE mod_id=?", PROVIDER);
            assertEquals(unavailable, f.callerBinding.availability(PROVIDER).state());
            f.runtime.tasks().blockOwner(PROVIDER, "Failed startup remains sealed");
            var blocked = gold.debug.windowstolinux.appapi.mod.service.AvailabilityState.RECOVERY_BLOCKED;
            assertEquals(blocked, f.callerBinding.availability(PROVIDER).state(), "A still-owned activation remains blocked");
            sql(f.db, "UPDATE mod_registry SET actual_version=NULL WHERE mod_id=?", PROVIDER);
            assertEquals(unavailable, f.callerBinding.availability(PROVIDER).state());
            assertTrue(f.runtime.tasks().callbacks().ownerAdmissionBlocked(PROVIDER));
            var ledger = new TaskLedger(f.db);
            var original = ledger.enqueue(UUID.randomUUID(), PROVIDER, VERSION,
                new gold.debug.windowstolinux.appapi.task.model.TaskRequest("classification-proof", "Original retained provider work", List.of(), false),
                List.of(new TaskResource("MOD", PROVIDER, PROVIDER, false)));
            assertTrue(ledger.tryStart(original));
            assertEquals(blocked, f.callerBinding.availability(PROVIDER).state(), "An actual retained task overrides failed startup");
            assertTrue(ledger.complete(original, TaskResult.CANCELLED, "{}", false));
            assertEquals(unavailable, f.callerBinding.availability(PROVIDER).state());
            sql(f.db, "UPDATE mod_registry SET last_boot_id=? WHERE mod_id=?", UUID.randomUUID(), PROVIDER);
            assertEquals(blocked, f.callerBinding.availability(PROVIDER).state(), "A previous boot is not confirmed current startup failure");
            f.runtime.tasks().allowOwner(PROVIDER);
            sql(f.db, "UPDATE mod_registry SET last_runtime_state='RUNNING',last_boot_id=? WHERE mod_id=?",
                UUID.randomUUID(), PROVIDER);
            assertEquals(unavailable, f.callerBinding.availability(PROVIDER).state());
            sql(f.db, "UPDATE mod_registry SET last_boot_id=?,actual_version=? WHERE mod_id=?", f.db.bootId(), VERSION, PROVIDER);
            f.runtime.tasks().blockOwner(PROVIDER, "Original retained recovery");
            assertEquals(gold.debug.windowstolinux.appapi.mod.service.AvailabilityState.RECOVERY_BLOCKED,
                f.callerBinding.availability(PROVIDER).state());
            f.runtime.tasks().allowOwner(PROVIDER);
            assertEquals(available, f.callerBinding.availability(PROVIDER).state());
            assertEquals(1, count(f.db, "SELECT COUNT(*) FROM platform_task_resource"));
            assertEquals(0, count(f.db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            assertEquals(1, count(f.db, "SELECT COUNT(*) FROM platform_task"));
            assertEquals(0, count(f.db, "SELECT COUNT(*) FROM platform_task WHERE state<>'ENDED'"));
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
                        callback.set(f.runtime.tasks().callbacks().hostCallback(context, PROVIDER, VERSION, (a, b) -> "allowed"));
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
                    assertFalse(f.runtime.tasks().ownerBlockers(PROVIDER).isEmpty());
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED,
                    task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
                assertEquals(1, count(f.db, "SELECT COUNT(*) FROM mod_schema WHERE ever_used=TRUE"));
                assertEquals(0, count(f.db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }

    @Test
    void providerCheckpointExistsOnlyInCurrentSynchronousScopeAndOwnNamespace() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null)) {
            f.publish();
            try (var handle = f.handle()) {
                var task = f.tasks.submit(request(), context -> {
                    var providerTasks = f.runtime.tasks().bind(PROVIDER, VERSION, false);
                    assertThrows(ApiException.class, () -> providerTasks.findCheckpoint(context, "same"));
                    try (var rootFact = f.tasks.beginCheckpoint(context, "same", "{\"root\":true}")) {
                        assertEquals("{\"provider\":true}", f.call(handle, "checkpoint", context));
                        assertEquals("ACCESS_DENIED", f.call(handle, "asyncCheckpoint", context));
                        assertEquals("{\"root\":true}", rootFact.snapshot().payload());
                        assertThrows(ApiException.class, () -> providerTasks.findCheckpoint(context, "same"));
                    }
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
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
                for (String mode : List.of("throw", "providerError", "future", "unadmitted")) {
                    var task = f.tasks.submit(request("server"), context -> {
                        var failure = assertThrows(ApiException.class, () -> f.call(handle, mode, context));
                        if (mode.equals("throw") || mode.equals("providerError")) {
                            var id = failure.problem().diagnosticId().orElseThrow();
                            var records = new gold.debug.windowstolinux.app.db.log.LogStore(f.db)
                                .read(gold.debug.windowstolinux.app.db.log.LogStore.startupTarget());
                            assertTrue(records.stream().anyMatch(record -> record.id().equals(id)));
                            var saved = records.stream().filter(record -> record.id().equals(id)).findFirst().orElseThrow();
                            assertTrue(saved.details().contains("example.provider.Mod.execute"));
                            assertFalse(saved.details().contains("must-not-leak-secret"));
                        }
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
    void knownRecoveryCannotReleaseOriginalCallGroupWhileProviderCustomResourceRemains() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null, true)) {
            f.publish();
            var closePhysical = new AtomicBoolean();
            var knownCallbacks = new AtomicInteger();
            try (var handle = f.handle(); var recovery = f.recovery((before, context) -> {
                knownCallbacks.incrementAndGet();
                if (closePhysical.get())
                    assertEquals("closed", f.call(handle, "closeCustom", context));
                return success(null);
            })) {
                var task = f.tasks.submit(request(), context -> {
                    var pending = assertThrows(ApiException.class, () -> f.call(handle, "retainCustom", context));
                    assertEquals("SERVICE_RELEASE_PENDING", pending.problem().eventCode());
                    return success(null);
                });
                state(task, TaskState.UNKNOWN);
                var ledger = new TaskLedger(f.db);
                var groups = ledger.queries().dependencies(task.id());
                assertEquals(1, groups.size());
                var original = groups.keySet().iterator().next();
                assertEquals("service.v2", original.encode().protocol());
                assertEquals(OWNER, original.caller());
                assertEquals(PROVIDER, original.provider());
                String physical = "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + task.id()
                    + "' AND owner_id='" + PROVIDER + "' AND resource_kind='CUSTOM' AND released_at IS NULL";
                assertEquals(1, count(f.db, physical));
                try {
                    assertEquals(TaskState.UNKNOWN, f.reconcile(task.id()).state());
                    assertEquals(1, knownCallbacks.get(), "The explicit author recovery actually returned KNOWN");
                    assertEquals(groups, ledger.queries().dependencies(task.id()), "Original call proof and scoped leases remain intact");
                    assertEquals(1, count(f.db, physical));
                    assertTrue(ledger.queries().hasDependencyResources(task.id(), Set.of(PROVIDER)));
                    assertFalse(f.runtime.tasks().ownerBlockers(PROVIDER).isEmpty());
                    assertFalse(task.completion().toCompletableFuture().isDone());
                } finally {
                    closePhysical.set(true);
                    assertEquals(TaskState.ENDED, f.reconcile(task.id()).state());
                }
                assertEquals(2, knownCallbacks.get());
                assertEquals(TaskResult.SUCCEEDED, task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
                assertEquals(0, count(f.db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertTrue(f.runtime.tasks().ownerBlockers(PROVIDER).isEmpty());
            }
        }
    }

    @Test
    void fatalProviderErrorsEscapeWithoutLosingOriginalDependencyRecovery() throws Exception {
        try (var f = new Fixture(compileProvider(), ModLaunchMode.NORMAL, null)) {
            f.publish();
            try (var handle = f.handle(); var recovery = f.recovery((before, context) -> {
                assertEquals(1, new TaskLedger(f.db).queries().dependencies(context.id()).size());
                return success(null);
            })) {
                for (String mode : List.of("providerFatal", "providerDeath")) {
                    var escaped = new AtomicReference<Throwable>();
                    var returned = new java.util.concurrent.CountDownLatch(1);
                    var calls = new AtomicInteger();
                    var task = f.tasks.submit(request("server"), context -> {
                        Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> {
                            escaped.set(failure);
                            returned.countDown();
                        });
                        calls.incrementAndGet();
                        f.call(handle, mode, context);
                        return success(null);
                    });
                    assertTrue(returned.await(8, java.util.concurrent.TimeUnit.SECONDS));
                    if (mode.equals("providerFatal"))
                        assertInstanceOf(InternalError.class, escaped.get());
                    else
                        assertInstanceOf(ThreadDeath.class, escaped.get());
                    state(task, TaskState.UNKNOWN);
                    assertFalse(f.runtime.tasks().ownerBlockers(PROVIDER).isEmpty());
                    assertEquals(TaskState.ENDED, f.reconcile(task.id()).state());
                    assertEquals(1, calls.get());
                    assertTrue(f.runtime.tasks().ownerBlockers(PROVIDER).isEmpty());
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
                assertEquals(TaskResult.SUCCEEDED,
                    task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
                assertTrue(new TaskLedger(f.db).queries().dependencies(task.id()).isEmpty());
                sql(f.db, "UPDATE mod_registry SET enabled=TRUE WHERE mod_id=?", PROVIDER);
                var ledger = new TaskLedger(f.db);
                var maintenanceStarted = new CountDownLatch(1);
                var releaseMaintenance = new CountDownLatch(1);
                f.runtime.started();
                var maintenance = f.runtime.tasks().bind("@platform", f.runtime.version(), false).submit(request(), context -> {
                    try (var lease = f.runtime.tasks().retain(context, new TaskResource("MOD", PROVIDER, PROVIDER, true))) {
                        maintenanceStarted.countDown();
                        assertTrue(releaseMaintenance.await(12, TimeUnit.SECONDS));
                    }
                    return success(null);
                });
                assertTrue(maintenanceStarted.await(8, TimeUnit.SECONDS));
                gold.debug.windowstolinux.appapi.task.run.TaskHandle<Object> blocked;
                try {
                    blocked = f.tasks.submit(request(), context -> {
                        assertEquals(1, f.call(handle, "write", context));
                        return success(null);
                    });
                    assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.QUEUED,
                        blocked.snapshot().state());
                    assertFalse(blocked.completion().toCompletableFuture().isDone());
                    assertTrue(ledger.queries().dependencies(blocked.id()).isEmpty());
                } finally {
                    releaseMaintenance.countDown();
                }
                assertEquals(TaskResult.SUCCEEDED,
                    maintenance.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
                assertEquals(TaskResult.SUCCEEDED,
                    blocked.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
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
                            assertEquals(TaskResult.SUCCEEDED, session.execute(command, grant).outcome());
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
                        assertEquals(TaskResult.SUCCEEDED,
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
                    return gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice.OVERWRITE;
                };
                f.hook.set(() -> {
                    try (var session = provider.linux().connect(PlatformRemoteCapabilitiesTest.SERVER,
                        original.get())) {
                        for (boolean upload : List.of(true, false)) {
                            var request = new gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest(
                                upload ? source : destination, "/artifact", 1024);
                            var digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(source)));
                            var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions
                                .transfer(PlatformRemoteCapabilitiesTest.SERVER, "revision", request, digest,
                                    upload)
                                .withSemantics(new ActionSemantics("application.files",
                                    Set.of(PlatformAuthorizationTest.APP)));
                            var grant = provider.interaction().approve(intent, original.get()).value().orElseThrow();
                            assertEquals(TaskResult.SUCCEEDED,
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
                    assertEquals(TaskResult.SUCCEEDED,
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
                assertEquals(TaskResult.SUCCEEDED,
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
            ledger.dependencies().retainDependency(taskId, old, c -> old.claims());
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

    @Test
    void usedOptionalRecoveryBlocksUnavailableOriginalProviderInsteadOfSelectingNewRelease() throws Exception {
        Path classes = compileProvider();
        SchemaIdentity schema;
        UUID taskId;
        gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph original;
        try (var f = new Fixture(classes, ModLaunchMode.NORMAL, null, true)) {
            f.publish();
            schema = f.schema;
            try (var handle = f.handle(); var recovery = f.recovery((before, context) -> success(null))) {
                var task = f.tasks.submit(request(), context -> {
                    assertEquals(1, f.call(handle, "write", context));
                    return PlatformTaskManagementWindowTest.unknown();
                });
                taskId = task.id();
                state(task, TaskState.UNKNOWN);
                original = new TaskLedger(f.db).queries().recoveryGraph(taskId).orElseThrow();
                assertEquals(TaskState.ENDED, f.reconcile(taskId).state());
            }
        }
        try (var db = PlatformDatabase.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            var ledger = new TaskLedger(db);
            ledger.enqueue(taskId, OWNER, VERSION, request(), new TaskLedger.InitialClaims() {
                public List<TaskResource> resolve(java.sql.Connection c) {
                    return original.claims();
                }

                public Optional<gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph> recoveryGraph(java.sql.Connection c) {
                    return Optional.of(original);
                }
            });
            assertTrue(ledger.tryStart(taskId));
            ledger.uncertain(taskId);
            sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,'2.0.0','next',?,'1','{}','CLASS',1,'READY')",
                PROVIDER, "2".repeat(64));
            sql(db, "UPDATE mod_registry SET selected_version='2.0.0' WHERE mod_id=?", PROVIDER);
            sql(db, "UPDATE mod_version SET state='REINSTALLING' WHERE mod_id=? AND version=?", PROVIDER, VERSION);
        }
        try (var f = new Fixture(classes, ModLaunchMode.RECOVERY, schema, true)) {
            f.publish();
            f.hook.set(() -> fail("Unavailable original provider reached author code"));
            try (var handle = f.handle(); var recovery = f.recovery((before, context) -> {
                assertEquals("SERVICE_ADMISSION_PERSISTENCE",
                    assertThrows(ApiException.class, () -> f.call(handle, "read", context)).problem().eventCode());
                return PlatformTaskManagementWindowTest.unknown();
            })) {
                assertEquals(TaskState.UNKNOWN, f.reconcile(taskId).state());
                assertEquals(original, new TaskLedger(f.db).queries().recoveryGraph(taskId).orElseThrow());
                assertEquals(1, count(f.db, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='MOD' AND owner_id='example.provider' AND released_at IS NULL"));
            }
            sql(f.db, "UPDATE mod_version SET state='READY' WHERE mod_id=? AND version=?", PROVIDER, VERSION);
            f.hook.set(() -> {
            });
            try (var handle = f.handle(); var recovery = f.recovery((before, context) -> {
                assertEquals(1, f.call(handle, "read", context));
                return success(null);
            })) {
                assertEquals(TaskState.ENDED, f.reconcile(taskId).state());
                assertEquals(1, count(f.db, "SELECT COUNT(*) FROM mod_registry WHERE mod_id='example.provider' AND selected_version='2.0.0'"));
            }
        }
    }
}
