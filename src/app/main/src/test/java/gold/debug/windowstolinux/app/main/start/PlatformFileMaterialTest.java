package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.completed;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.PlatformStorage;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.file.source.*;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;

import gold.debug.windowstolinux.app.service.file.PlatformFileService;
import gold.debug.windowstolinux.appapi.file.material.*;
import gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest;
import gold.debug.windowstolinux.appapi.file.source.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformFileMaterialTest {
    @TempDir
    Path root;

    @Test
    void originalCustodyHoldsWhitelistLargeContentAndExplicitCleanupUseRealTask() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = new PlatformSourceWorkspaceTest().source(runtime);
            var materials = new PlatformFileService(source).materials();
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var workspace = source.workspace(task);
                Path directory = workspace.directory();
                Files.createDirectory(directory.resolve("src"));
                Files.writeString(directory.resolve("src/allowed.txt"), "original");
                Files.writeString(directory.resolve("secret.txt"), "excluded");
                byte[] bytes = new byte[24 * 1024 * 1024];
                new Random(13).nextBytes(bytes);
                Files.write(directory.resolve("large.bin"), bytes);
                var old = workspace.selection();
                var publication = new MaterialPublication(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(40),
                    List.of("src/allowed.txt", "large.bin"));
                var ref = materials.publish(workspace, publication, task);
                assertTrue(ref.byteCount() > 16L * 1024 * 1024);
                assertEquals(2, ref.fileCount());
                assertEquals(ref, materials.find(ref.id(), ref.operationId(), task));
                assertThrows(ApiException.class, workspace::directory);
                assertThrows(ApiException.class, workspace::selection);
                assertThrows(ApiException.class, () -> source.list(old, "", 10, task));
                workspace.close();
                source.closeTaskResources(task);
                assertTrue(Files.exists(directory));
                var first = materials.acquire(ref, task);
                var second = materials.acquire(ref, task);
                var selection = first.selection();
                assertEquals(List.of("large.bin", "src"), source.list(selection, "", 10, task).stream().map(FileEntry::relativePath).toList());
                assertEquals("original", new String(source.read(selection, new FileReadRequest("src/allowed.txt", 100), task).content()));
                assertThrows(ApiException.class, () -> source.location(selection, task));
                assertThrows(ApiException.class, () -> source.read(selection, new FileReadRequest("secret.txt", 100), task));
                assertThrows(ApiException.class, () -> source.snapshot(selection, new SnapshotRequest(List.of("secret.txt"), 100), task));
                assertThrows(ApiException.class, () -> source.read(new FileSelection(selection.id(), selection.displayName()),
                    new FileReadRequest("src/allowed.txt", 100), task));
                try (var snapshot = source.snapshot(selection, new SnapshotRequest(List.of("large.bin", "src/allowed.txt"), 32L * 1024 * 1024), task)) {
                    assertArrayEquals(bytes, Files.readAllBytes(snapshot.directory().resolve("large.bin")));
                }
                assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, () -> materials.release(ref, task)).problem().code());
                assertThrows(ApiException.class, () -> materials.acquire(ref, task));
                first.close();
                first.close();
                assertThrows(ApiException.class, () -> source.list(selection, "", 10, task));
                assertTrue(Files.exists(directory));
                second.close();
                materials.release(ref, task);
                materials.release(ref, task);
                assertFalse(Files.exists(directory));
                assertEquals(ref, materials.find(ref.id(), ref.operationId(), task));
                assertThrows(ApiException.class, () -> materials.acquire(ref, task));
                return success(null);
            }));
        }
    }

    @Test
    void changedReferenceForeignModAndNewTaskCannotGainMaterialAuthority() throws Exception {
        var original = new AtomicReference<MaterialRef>();
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = new PlatformSourceWorkspaceTest().source(runtime);
            var materials = new PlatformFileService(source).materials();
            var foreign = new PlatformFileService(runtime.capabilities().source("foreign.mod", VERSION)).materials();
            var oldVersion = new PlatformFileService(runtime.capabilities().source(OWNER, "0.9.0")).materials();
            var db = runtime.persistence().database();
            sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES('foreign.mod',1)");
            sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,manifest_json,origin_kind,installed_at,state) VALUES('foreign.mod',?,'fixture-only',?,?,'CLASS',1,'READY')",
                VERSION, "0".repeat(64), PlatformModIntentsTest.manifest("foreign.mod", VERSION, Map.of(), null, List.of()));
            sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version=?,selection_initialized=TRUE,actual_version=?,last_runtime_state='RUNNING',last_boot_id=? WHERE mod_id='foreign.mod'", VERSION, VERSION, db.bootId());
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var workspace = source.workspace(task);
                Files.writeString(workspace.directory().resolve("file.txt"), "original");
                var ref = materials.publish(workspace, new MaterialPublication(UUID.randomUUID(), UUID.randomUUID(), "original", List.of("file.txt")), task);
                original.set(ref);
                var changed = new MaterialRef(ref.id(), ref.operationId(), ref.taskId(), ref.producerId(), ref.producerVersion(),
                    ref.producerProgramSha256(), ref.producerSchema(), "mutated", ref.manifestSha256(), ref.byteCount(), ref.fileCount());
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class, () -> materials.acquire(changed, task)).problem().code());
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class, () -> foreign.acquire(ref, task)).problem().code());
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class, () -> oldVersion.acquire(ref, task)).problem().code());
                for (int field = 0; field < 11; field++) {
                    MaterialRef mutation = changed(ref, field);
                    assertThrows(ApiException.class, () -> materials.acquire(mutation, task), "mutated field " + field);
                }
                assertThrows(ApiException.class, () -> materials.find(ref.id(), UUID.randomUUID(), task));
                assertThrows(ApiException.class, () -> materials.publish(workspace, new MaterialPublication(UUID.randomUUID(), UUID.randomUUID(), "other", List.of("file.txt")), task));
                assertThrows(ApiException.class, () -> materials.recoverHolds(ref, task));
                completed(runtime.tasks().bind("foreign.mod", VERSION, false).submit(request(), other -> {
                    assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class, () -> foreign.acquire(ref, other)).problem().code());
                    assertThrows(ApiException.class, () -> foreign.inspect(ref.id(), ref.operationId(), other));
                    assertThrows(ApiException.class, () -> foreign.releaseUnpublished(ref.id(), ref.operationId(), other));
                    return success(null);
                }));
                materials.release(ref, task);
                return success(null);
            }));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class, () -> materials.acquire(original.get(), task)).problem().code());
                assertEquals(new MaterialInspection(MaterialState.ABSENT, Optional.empty()),
                    materials.inspect(original.get().id(), original.get().operationId(), task));
                assertEquals(new MaterialInspection(MaterialState.ABSENT, Optional.empty()),
                    materials.releaseUnpublished(original.get().id(), original.get().operationId(), task));
                return success(null);
            }));
        }
    }

    @Test
    void originalMultiHoldRecoveryIsAtomicCloseOnlyAndRetainsCorruptEvidenceUntilCorrected() throws Exception {
        Path data = root.resolve("data");
        var ref = seedPublished(data, 2);
        Path container = data.resolve("work/source-workspace").resolve(ref.taskId().toString());
        Path metadata;
        try (var directories = Files.list(container)) {
            metadata = directories.findFirst().orElseThrow().resolve("manifest.bin");
        }
        byte[] correct = Files.readAllBytes(metadata);
        Files.write(metadata, new byte[]{1}, StandardOpenOption.APPEND);
        try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            var source = new PlatformSourceWorkspaceTest().source(runtime);
            var materials = new PlatformFileService(source).materials();
            var binding = runtime.tasks().bind(OWNER, VERSION, true);
            try (var registration = binding.registerRecovery("fixture", (previous, task) -> {
                assertEquals(ref, materials.find(ref.id(), ref.operationId(), task));
                var recovered = materials.recoverHolds(ref, task);
                assertEquals(2, recovered.size());
                assertNotEquals(recovered.getFirst().id(), recovered.getLast().id());
                assertThrows(ApiException.class, () -> materials.acquire(ref, task));
                assertThrows(ApiException.class, recovered.getFirst()::selection);
                assertThrows(ApiException.class, () -> materials.recoverHolds(ref, task));
                assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, () -> materials.release(ref, task)).problem().code());
                for (var hold : recovered) {
                    hold.close();
                    hold.close();
                }
                materials.release(ref, task);
                assertEquals(ref, materials.find(ref.id(), ref.operationId(), task));
                return success(null);
            })) {
                assertEquals(TaskState.UNKNOWN, runtime.tasks().reconcile(ref.taskId()).toCompletableFuture().get(8, java.util.concurrent.TimeUnit.SECONDS).state());
                assertTrue(Files.exists(metadata));
                assertEquals(2, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'material-hold-v1:%' AND released_at IS NULL"));
                Files.write(metadata, correct);
                assertEquals(TaskState.ENDED, runtime.tasks().reconcile(ref.taskId()).toCompletableFuture().get(8, java.util.concurrent.TimeUnit.SECONDS).state());
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                assertFalse(Files.exists(metadata));
            }
        }
    }

    @Test
    void failedHoldAndRootReleaseKeepExactOccupancyUntilOriginalRecoveryConfirmsCleanup() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = new PlatformSourceWorkspaceTest().source(runtime);
            var materials = new PlatformFileService(source).materials();
            var reference = new AtomicReference<MaterialRef>();
            var hold = new AtomicReference<FileMaterialHold>();
            var directory = new AtomicReference<Path>();
            var original = new AtomicReference<TaskContext>();
            var db = runtime.persistence().database();
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var task = binding.submit(request(), context -> {
                original.set(context);
                var workspace = source.workspace(context);
                directory.set(workspace.directory());
                Files.writeString(directory.get().resolve("file.txt"), "original");
                reference.set(materials.publish(workspace, new MaterialPublication(UUID.randomUUID(), UUID.randomUUID(), "original", List.of("file.txt")), context));
                hold.set(materials.acquire(reference.get(), context));
                var token = hold.get().selection();
                sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_hold_release CHECK(resource_id NOT LIKE 'material-hold-v1:%' OR released_at IS NULL)");
                assertEquals(ProblemCode.UNKNOWN, assertThrows(ApiException.class, hold.get()::close).problem().code());
                assertThrows(ApiException.class, () -> source.list(token, "", 10, context));
                assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, () -> materials.release(reference.get(), context)).problem().code());
                assertTrue(Files.exists(directory.get()));
                return success(null);
            });
            state(task, TaskState.UNKNOWN);
            PlatformServerRefTest.callbackReturned(runtime, original.get());
            assertEquals(2, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'material%' AND released_at IS NULL"));
            sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_hold_release");
            sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_root_release CHECK(resource_id NOT LIKE 'material-v1:%' OR released_at IS NULL)");
            try (var registration = binding.registerRecovery("fixture", (previous, context) -> {
                hold.get().close();
                assertEquals(new MaterialInspection(MaterialState.RELEASE_REQUESTED, Optional.of(reference.get())),
                    materials.inspect(reference.get().id(), reference.get().operationId(), context));
                assertTrue(materials.recoverHolds(reference.get(), context).isEmpty());
                materials.release(reference.get(), context);
                assertThrows(ApiException.class, () -> materials.recoverHolds(reference.get(), context));
                assertEquals(reference.get(), materials.find(reference.get().id(), reference.get().operationId(), context));
                return success(null);
            })) {
                assertEquals(TaskState.UNKNOWN, runtime.tasks().reconcile(task.id()).toCompletableFuture().get(8, java.util.concurrent.TimeUnit.SECONDS).state());
                assertFalse(Files.exists(directory.get()));
                assertEquals(1, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'material%' AND released_at IS NULL"));
                sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_root_release");
                assertEquals(TaskState.ENDED, runtime.tasks().reconcile(task.id()).toCompletableFuture().get(8, java.util.concurrent.TimeUnit.SECONDS).state());
                assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
            }
        }
    }

    @Test
    void onlyOriginalProducerProcessOccupancyBlocksPublishingBeforeWorkspaceRevocation() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = new PlatformSourceWorkspaceTest().source(runtime);
            var materials = new PlatformFileService(source).materials();
            var db = runtime.persistence().database();
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var workspace = source.workspace(task);
                Path directory = workspace.directory();
                Files.writeString(directory.resolve("file.txt"), "original");
                var publication = new MaterialPublication(UUID.randomUUID(), UUID.randomUUID(), "original", List.of("file.txt"));
                var producerLease = runtime.tasks().retain(task, new TaskResource("PROCESS", "producer-unconfirmed", OWNER, false));
                assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, () -> materials.publish(workspace, publication, task)).problem().code());
                assertEquals(directory, workspace.directory());
                producerLease.close();
                var ledger = new TaskLedger(db);
                UUID unrelated = ledger.retain(task.id(), new TaskResource("PROCESS", "unrelated-consumer", "example.other-consumer", false));
                try {
                    var ref = materials.publish(workspace, publication, task);
                    assertTrue(Files.exists(directory));
                    materials.release(ref, task);
                } finally {
                    ledger.release(task.id(), unrelated);
                }
                return success(null);
            }));
        }
    }

    @Test
    void cancellationForbidsFurtherReadsAndAcquisitionButAllowsOriginalHoldAndRootCleanup() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = new PlatformSourceWorkspaceTest().source(runtime);
            var materials = new PlatformFileService(source).materials();
            var ready = new java.util.concurrent.CountDownLatch(1);
            var cancelled = new java.util.concurrent.CountDownLatch(1);
            var path = new AtomicReference<Path>();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var workspace = source.workspace(task);
                path.set(workspace.directory());
                Files.writeString(path.get().resolve("file.txt"), "original");
                var ref = materials.publish(workspace, new MaterialPublication(UUID.randomUUID(), UUID.randomUUID(), "original", List.of("file.txt")), task);
                var hold = materials.acquire(ref, task);
                var selected = hold.selection();
                ready.countDown();
                assertTrue(cancelled.await(8, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(ProblemCode.CANCELLED, assertThrows(ApiException.class, () -> materials.acquire(ref, task)).problem().code());
                assertEquals(ProblemCode.CANCELLED, assertThrows(ApiException.class, () -> source.list(selected, "", 10, task)).problem().code());
                hold.close();
                materials.release(ref, task);
                return new OperationResult<Void>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
            });
            try {
                assertTrue(ready.await(8, java.util.concurrent.TimeUnit.SECONDS));
                handle.requestCancel();
            } finally {
                cancelled.countDown();
            }
            assertEquals(TaskResult.CANCELLED, handle.completion().toCompletableFuture().get(8, java.util.concurrent.TimeUnit.SECONDS).outcome());
            assertFalse(Files.exists(path.get()));
        }
    }

    @Test
    void unpublishedTransferFailureKeepsOriginalCustodyUntilRootRecoveryReleasesBothPreciseLeases() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = new PlatformSourceWorkspaceTest().source(runtime);
            var materials = new PlatformFileService(source).materials();
            var foreign = new PlatformFileService(runtime.capabilities().source("foreign.mod", VERSION)).materials();
            var db = runtime.persistence().database();
            var publication = new MaterialPublication(UUID.randomUUID(), UUID.randomUUID(), "original", List.of("file.txt"));
            var directory = new AtomicReference<Path>();
            var original = new AtomicReference<TaskContext>();
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var handle = binding.submit(request(), task -> {
                original.set(task);
                assertEquals(MaterialState.ABSENT, materials.inspect(publication.materialId(), publication.operationId(), task).state());
                assertEquals(MaterialState.ABSENT, materials.releaseUnpublished(publication.materialId(), publication.operationId(), task).state());
                var workspace = source.workspace(task);
                directory.set(workspace.directory());
                Files.writeString(directory.get().resolve("file.txt"), "original");
                sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_publish CHECK(resource_id NOT LIKE 'material-v1:%' OR recovery_json NOT LIKE '%PUBLISHED%')");
                assertEquals(ProblemCode.UNKNOWN, assertThrows(ApiException.class, () -> materials.publish(workspace, publication, task)).problem().code());
                var facts = materials.inspect(publication.materialId(), publication.operationId(), task);
                assertEquals(new MaterialInspection(MaterialState.CAPTURING, Optional.empty()), facts);
                assertThrows(ApiException.class, workspace::directory);
                assertThrows(ApiException.class, workspace::close);
                assertThrows(ApiException.class, () -> source.closeTaskResources(task));
                assertTrue(Files.exists(directory.get()));
                assertThrows(ApiException.class, () -> materials.find(publication.materialId(), publication.operationId(), task));
                assertThrows(ApiException.class, () -> materials.releaseUnpublished(publication.materialId(), UUID.randomUUID(), task));
                assertThrows(ApiException.class, () -> foreign.releaseUnpublished(publication.materialId(), publication.operationId(), task));
                var other = source.workspace(task);
                Files.writeString(other.directory().resolve("file.txt"), "other");
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class, () -> materials.publish(other, publication, task)).problem().code());
                assertTrue(Files.exists(other.directory()));
                other.close();
                return success(null);
            });
            state(handle, TaskState.UNKNOWN);
            PlatformServerRefTest.callbackReturned(runtime, original.get());
            sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_publish");
            assertEquals(2, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
            try (var registration = binding.registerRecovery("fixture", (previous, task) -> {
                var process = new TaskLedger(db).retain(task.id(), new TaskResource("PROCESS", "unpublished-writer", OWNER, false));
                assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class,
                    () -> materials.releaseUnpublished(publication.materialId(), publication.operationId(), task)).problem().code());
                new TaskLedger(db).release(task.id(), process);
                var terminal = materials.releaseUnpublished(publication.materialId(), publication.operationId(), task);
                assertEquals(new MaterialInspection(MaterialState.RELEASED, Optional.empty()), terminal);
                assertEquals(terminal, materials.inspect(publication.materialId(), publication.operationId(), task));
                assertEquals(terminal, materials.releaseUnpublished(publication.materialId(), publication.operationId(), task));
                assertThrows(ApiException.class, () -> materials.find(publication.materialId(), publication.operationId(), task));
                source.closeTaskResources(task);
                return success(null);
            })) {
                sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_unpublished_release CHECK(resource_id NOT LIKE 'material-v1:%' OR released_at IS NULL)");
                assertEquals(TaskState.UNKNOWN, runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(8, java.util.concurrent.TimeUnit.SECONDS).state());
                assertFalse(Files.exists(directory.get()));
                assertEquals(2, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_unpublished_release");
                assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(8, java.util.concurrent.TimeUnit.SECONDS).state());
                assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                assertFalse(Files.exists(directory.get()));
            }
        }
    }

    @Test
    void publishedCustodyCannotUseUnpublishedReleaseAndInspectionCombinationsAreStrict() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new MaterialInspection(MaterialState.PUBLISHED, Optional.empty()));
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = new PlatformSourceWorkspaceTest().source(runtime);
            var materials = new PlatformFileService(source).materials();
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var workspace = source.workspace(task);
                Path directory = workspace.directory();
                Files.writeString(directory.resolve("file.txt"), "original");
                var ref = materials.publish(workspace, new MaterialPublication(UUID.randomUUID(), UUID.randomUUID(), "original", List.of("file.txt")), task);
                assertThrows(IllegalArgumentException.class, () -> new MaterialInspection(MaterialState.CAPTURING, Optional.of(ref)));
                assertThrows(IllegalArgumentException.class, () -> new MaterialInspection(MaterialState.ABSENT, Optional.of(ref)));
                var published = new MaterialInspection(MaterialState.PUBLISHED, Optional.of(ref));
                assertEquals(published, materials.releaseUnpublished(ref.id(), ref.operationId(), task));
                assertTrue(Files.exists(directory));
                try (var hold = materials.acquire(ref, task)) {
                    assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, () -> materials.release(ref, task)).problem().code());
                    assertEquals(new MaterialInspection(MaterialState.RELEASE_REQUESTED, Optional.of(ref)),
                        materials.releaseUnpublished(ref.id(), ref.operationId(), task));
                }
                materials.release(ref, task);
                assertEquals(new MaterialInspection(MaterialState.RELEASED, Optional.of(ref)), materials.inspect(ref.id(), ref.operationId(), task));
                return success(null);
            }));
        }
    }

    @Test
    void absentInspectionUsesActualSynchronousProviderScopeWithoutGivingCleanupAuthority() throws Exception {
        var author = new PlatformModServicesTest();
        author.root = root;
        try (var fixture = author.new Fixture(author.compileProvider(), gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode.NORMAL, null)) {
            fixture.runtime.setSourcePicker((title, task, cancel) -> {
                throw new AssertionError("No material directory selection expected");
            });
            var provider = new PlatformFileService(fixture.runtime.capabilities().source(PlatformModServicesTest.PROVIDER, VERSION)).materials();
            UUID material = UUID.randomUUID(), operation = UUID.randomUUID();
            fixture.publish();
            try (var service = fixture.handle()) {
                completed(fixture.tasks.submit(request(), task -> {
                    assertThrows(ApiException.class, () -> provider.inspect(material, operation, task));
                    fixture.hook.set(() -> {
                        try {
                            assertEquals(new MaterialInspection(MaterialState.ABSENT, Optional.empty()), provider.inspect(material, operation, task));
                            assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class,
                                () -> provider.releaseUnpublished(material, operation, task)).problem().code());
                        } catch (ApiException failure) {
                            throw new AssertionError(failure);
                        }
                    });
                    assertEquals("ready", fixture.call(service, "ready", task));
                    return success(null);
                }));
            }
            assertEquals(0, count(fixture.db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    static MaterialRef seedPublished(Path data, int holdCount) throws Exception {
        UUID task = UUID.randomUUID(), material = UUID.randomUUID(), operation = UUID.randomUUID(), workspace = UUID.randomUUID();
        try (var db = PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
            var storage = new PlatformStorage(db);
            try (var security = new DesktopSecurityService(storage.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            register(db, null);
            var ledger = new TaskLedger(db);
            var release = new TaskRecoveryGraph.Release(OWNER, VERSION, "0".repeat(64), Optional.empty());
            var graph = new TaskRecoveryGraph(OWNER, List.of(release), List.of());
            ledger.enqueue(task, OWNER, VERSION, request(), new TaskLedger.InitialClaims() {
                @Override
                public List<TaskResource> resolve(java.sql.Connection c) {
                    return List.of(new TaskResource("MOD", OWNER, OWNER, false));
                }

                @Override
                public Optional<TaskRecoveryGraph> recoveryGraph(java.sql.Connection c) {
                    return Optional.of(graph);
                }
            });
            assertTrue(ledger.tryStart(task));
            var sourceResource = new TaskResource("FILE", "source-workspace-v1:" + workspace, OWNER, false);
            UUID sourceLease = ledger.retain(task, sourceResource, null, true);
            var work = new SourceWorkDirectory(data.resolve("work/source-workspace"), task, workspace, OWNER);
            work.prepare();
            Files.writeString(work.directory().resolve("file.txt"), "original");
            var fields = new TreeMap<String, String>();
            fields.put("materialId", material.toString());
            fields.put("operationId", operation.toString());
            fields.put("taskId", task.toString());
            for (String prefix : List.of("producer", "consumer")) {
                fields.put(prefix + "Id", OWNER);
                fields.put(prefix + "Version", VERSION);
                fields.put(prefix + "Digest", "0".repeat(64));
                fields.put(prefix + "SchemaVersion", "");
                fields.put(prefix + "SchemaGeneration", "");
            }
            fields.put("workspaceId", workspace.toString());
            fields.put("physicalOwnerId", OWNER);
            fields.put("physicalIdentity", work.identity());
            fields.put("revision", "original");
            fields.put("manifestSha256", "");
            fields.put("byteCount", "0");
            fields.put("fileCount", "0");
            fields.put("state", "CAPTURING");
            fields.put("recordRevision", "0");
            var capturing = new ResourceRecovery("material.v1", fields);
            var resource = new TaskResource("FILE", "material-v1:" + material, OWNER, false);
            UUID rootLease = ledger.retain(task, resource, capturing, true);
            var manifest = SourceMaterialManifest.capture(work, List.of("file.txt"), () -> false);
            fields.put("state", "PUBLISHED");
            fields.put("recordRevision", "1");
            fields.put("manifestSha256", manifest.sha256());
            fields.put("byteCount", Long.toString(manifest.byteCount()));
            fields.put("fileCount", "1");
            var published = new ResourceRecovery("material.v1", fields);
            ledger.materials().transferSourceWorkspace(task, sourceLease, sourceResource, rootLease, resource, capturing, published);
            for (int i = 0; i < holdCount; i++) {
                UUID hold = UUID.randomUUID();
                var recovery = new ResourceRecovery("material-hold.v1", Map.of("materialId", material.toString(), "holdId", hold.toString(),
                    "taskId", task.toString(), "consumerId", OWNER, "consumerVersion", VERSION, "materialDigest", manifest.sha256(),
                    "revision", "original", "bootId", db.bootId().toString(), "state", "ACTIVE"));
                ledger.materials().acquireMaterialHold(task, rootLease, resource, published,
                    new TaskResource("FILE", "material-hold-v1:" + material + "/" + hold, OWNER, false), recovery);
            }
            return new MaterialRef(material, operation, task, OWNER, VERSION, "0".repeat(64), Optional.empty(), "original",
                manifest.sha256(), manifest.byteCount(), 1);
        }
    }

    static MaterialRef changed(MaterialRef ref, int field) {
        return new MaterialRef(field == 0 ? UUID.randomUUID() : ref.id(), field == 1 ? UUID.randomUUID() : ref.operationId(),
            field == 2 ? UUID.randomUUID() : ref.taskId(), field == 3 ? "other.producer" : ref.producerId(),
            field == 4 ? "0.9.0" : ref.producerVersion(), field == 5 ? "1".repeat(64) : ref.producerProgramSha256(),
            field == 6 ? Optional.of(new gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity(ref.producerId(), "1", UUID.randomUUID())) : ref.producerSchema(),
            field == 7 ? "other revision" : ref.revision(), field == 8 ? "1".repeat(64) : ref.manifestSha256(),
            field == 9 ? ref.byteCount() + 1 : ref.byteCount(), field == 10 ? ref.fileCount() + 1 : ref.fileCount());
    }
}
