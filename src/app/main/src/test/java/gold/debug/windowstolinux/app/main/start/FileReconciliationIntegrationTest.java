package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformServerReferenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity;
import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.h2.api.Trigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileReconciliationIntegrationTest {
    @TempDir
    Path root;

    FilePath path(String name) {
        return FilePath.local(root.resolve(name));
    }

    void presenters(PlatformRuntime runtime) {
        runtime.setSourcePicker((a, b, c) -> root);
        runtime.setInteractionPresenter(new PlatformInteractionTest.Prompts());
    }

    static OperationResult<Void> abandoned() {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, "FIXTURE_OBSERVED_PENDING_LINK", "Fixture preserved original link evidence for a newly authorized continuation", Map.of(), Optional.empty())));
    }

    void createOriginal(gold.debug.windowstolinux.app.service.file.PlatformFileAccess file, PlatformRuntime runtime) throws Exception {
        completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
            file.createDirectory(path("original"), task);
            file.writeFile(path("original/content"), new byte[]{1, 2, 3}, task);
            file.createLink(path("managed"), root.resolve("original").toString(), task);
            return success(null);
        }));
    }

    @Test
    void completedPublicationEvidenceSurvivesOuterSummaryAndChangedObjectsRemainUnknown() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime);
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    Files.writeString(root.resolve("source"), "original");
                    var report = file.copyFile(path("source"), path("copied"), task);
                    assertEquals(Outcome.SUCCEEDED, file.checkOperation(report.operationId(), task).outcome());
                    Files.writeString(root.resolve("copied"), "externally replaced content");
                    assertEquals(Outcome.UNKNOWN, file.checkOperation(report.operationId(), task).outcome());
                    assertEquals("original", Files.readString(root.resolve("source")));
                    return success(null);
                }));
            }
        }
    }

    @Test
    void sourceAbsenceAndOuterDeleteSummaryCannotSubstituteForAConfirmedEffectReceipt() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime);
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    var repository = new gold.debug.windowstolinux.app.db.file.FileRepository(runtime.persistence().database(), OWNER, VERSION);
                    for (String summary : List.of("UNKNOWN", "FAILED", "RECYCLE", "PERMANENT")) {
                        var source = path("external-" + summary);
                        Files.writeString(Path.of(source.path()), "external");
                        UUID operation = repository.begin(task.id(), "DELETE_FILE", source, Optional.empty(), "RECYCLE fixture original plan");
                        repository.record(operation, task.id(), "BEFORE_DELETE", source, Optional.empty(), "RECYCLE");
                        Files.delete(Path.of(source.path()));
                        repository.record(operation, task.id(), "COMPLETE", source, Optional.empty(), summary);
                        assertEquals(Outcome.UNKNOWN, file.checkOperation(operation, task).outcome());
                    }
                    var permanent = path("confirmed-permanent");
                    Files.writeString(Path.of(permanent.path()), "fixture");
                    UUID operation = repository.begin(task.id(), "DELETE_FILE", permanent, Optional.empty(), "PERMANENT fixture plan");
                    repository.record(operation, task.id(), "BEFORE_DELETE_ITEM", permanent, Optional.empty(), file.getFile(permanent, task).identity());
                    Files.delete(Path.of(permanent.path()));
                    repository.record(operation, task.id(), "DELETED_ITEM", permanent, Optional.empty(), "confirmed");
                    assertEquals(Outcome.SUCCEEDED, file.checkOperation(operation, task).outcome());
                    Files.writeString(Path.of(permanent.path()), "new object");
                    assertEquals(Outcome.UNKNOWN, file.checkOperation(operation, task).outcome());
                    return success(null);
                }));
            }
        }
    }

    @Test
    void recoveryChecksActualReceiptIdentityAndOriginalPathMetadataWithoutReplayingEffects() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime);
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    var repository = new gold.debug.windowstolinux.app.db.file.FileRepository(runtime.persistence().database(), OWNER, VERSION);
                    // These owned files model persisted receipt fault windows; native recycling is tested separately.
                    for (String fault : List.of("missing-object", "missing-information", "replaced-information", "wrong-original-path", "source-reappeared")) {
                        FilePath source = path(fault + "-source"), recycled = path(fault + "-recycled"), information = path(fault + "-information");
                        Files.writeString(Path.of(source.path()), "original content");
                        UUID operation = repository.begin(task.id(), "DELETE_FILE", source, Optional.empty(), "RECYCLE fixture original plan");
                        repository.record(operation, task.id(), "BEFORE_DELETE", source, Optional.empty(), "RECYCLE");
                        Files.move(Path.of(source.path()), Path.of(recycled.path()));
                        String original = fault.equals("wrong-original-path") ? path("different-original").path() : source.path();
                        Files.writeString(Path.of(information.path()), "[Trash Info]\nPath=" + java.net.URLEncoder.encode(original, java.nio.charset.StandardCharsets.UTF_8) + "\nDeletionDate=2026-10-03T00:00:00\n");
                        repository.record(operation, task.id(), "RECYCLED_ITEM", source, Optional.of(recycled), file.getFile(recycled, task).identity());
                        repository.record(operation, task.id(), "RECYCLE_METADATA", source, Optional.of(information), file.getFile(information, task).identity());
                        if (!fault.equals("wrong-original-path"))
                            assertEquals(Outcome.SUCCEEDED, file.checkOperation(operation, task).outcome());
                        switch (fault) {
                            case "missing-object" -> Files.delete(Path.of(recycled.path()));
                            case "missing-information" -> Files.delete(Path.of(information.path()));
                            case "replaced-information" -> {
                                Files.delete(Path.of(information.path()));
                                Files.writeString(Path.of(information.path()), "unrelated new metadata");
                            }
                            case "source-reappeared" -> Files.writeString(Path.of(source.path()), "new source");
                            default -> {
                            }
                        }
                        assertEquals(Outcome.UNKNOWN, file.checkOperation(operation, task).outcome());
                        if (!fault.equals("missing-object"))
                            assertEquals("original content", Files.readString(Path.of(recycled.path())));
                        assertEquals(fault.equals("source-reappeared"), Files.exists(Path.of(source.path())));
                    }
                    return success(null);
                }));
            }
        }
    }

    @Test
    void physicalLinkUpdateWithLostRegistryCommitRepairsOnlyMetadataDuringRecovery() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime);
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                createOriginal(file, runtime);
                sql(runtime.persistence().database(), "CREATE TRIGGER interrupt_link BEFORE UPDATE ON file_link FOR EACH ROW CALL '" + InterruptLinkRegistration.class.getName() + "'");
                var report = new AtomicReference<FileReport>();
                var context = new AtomicReference<TaskContext>();
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    context.set(task);
                    report.set(file.moveFile(path("original"), path("moved"), task));
                    return abandoned();
                });
                state(handle, TaskState.UNKNOWN);
                callbackReturned(runtime, context.get());
                assertEquals(Outcome.UNKNOWN, report.get().outcome());
                String identity = WindowsFileIdentity.read(root.resolve("managed"));
                try (var registration = runtime.tasks().bind(OWNER, VERSION, false).registerRecovery("fixture", (previous, task) -> {
                    assertEquals("TASK_RECOVERY_NEW_WORK", assertThrows(ApiException.class, () -> file.continueOperation(report.get().operationId(), task)).problem().eventCode());
                    assertEquals(Outcome.SUCCEEDED, file.checkOperation(report.get().operationId(), task).outcome());
                    assertEquals(identity, WindowsFileIdentity.read(root.resolve("managed")));
                    assertEquals(path("moved"), file.getFile(path("managed"), task).actual());
                    return success(null);
                })) {
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                }
                assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_link WHERE last_state='CONFIRMED' AND target_path LIKE '%moved'"));
                assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(root.resolve("moved/content")));
            } finally {
                Files.deleteIfExists(root.resolve("managed"));
            }
        }
    }

    @Test
    void rebootPreservesPendingLinkAndNewOrdinaryTaskContinuesOnlyTheUnfinishedLink() throws Exception {
        UUID originalOperation;
        UUID originalTask;
        String contentIdentity;
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime);
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                createOriginal(file, runtime);
                sql(runtime.persistence().database(), "CREATE TRIGGER interrupt_link BEFORE INSERT ON file_operation_step FOR EACH ROW CALL '" + InterruptLinkStep.class.getName() + "'");
                var report = new AtomicReference<FileReport>();
                var context = new AtomicReference<TaskContext>();
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    context.set(task);
                    report.set(file.moveFile(path("original"), path("moved"), task));
                    return abandoned();
                });
                state(handle, TaskState.UNKNOWN);
                callbackReturned(runtime, context.get());
                originalOperation = report.get().operationId();
                originalTask = handle.id();
                contentIdentity = WindowsFileIdentity.read(root.resolve("moved/content"));
                assertFalse(Files.exists(root.resolve("original")));
                try (var recovery = runtime.tasks().bind(OWNER, VERSION, false).registerRecovery("fixture", (previous, task) -> {
                    assertEquals(Outcome.UNKNOWN, file.checkOperation(report.get().operationId(), task).outcome());
                    assertEquals("TASK_RECOVERY_NEW_WORK", assertThrows(ApiException.class, () -> file.continueOperation(report.get().operationId(), task)).problem().eventCode());
                    return abandoned();
                })) {
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                }
                sql(runtime.persistence().database(), "DROP TRIGGER interrupt_link");
            }
        }
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            presenters(runtime);
            sql(runtime.persistence().database(), "UPDATE mod_registry SET last_boot_id=? WHERE mod_id=?", runtime.persistence().database().bootId(), OWNER);
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                assertFalse(Files.exists(root.resolve("original")));
                assertEquals(contentIdentity, WindowsFileIdentity.read(root.resolve("moved/content")));
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    assertEquals(Outcome.UNKNOWN, file.checkOperation(originalOperation, task).outcome());
                    var continued = file.continueOperation(originalOperation, task);
                    assertEquals(Outcome.SUCCEEDED, continued.outcome());
                    assertEquals(path("moved"), file.getFile(path("managed"), task).actual());
                    assertEquals(contentIdentity, WindowsFileIdentity.read(root.resolve("moved/content")));
                    assertFalse(Files.exists(root.resolve("original")));
                    assertEquals(Outcome.SUCCEEDED, file.checkOperation(originalOperation, task).outcome());
                    assertThrows(ApiException.class, () -> file.continueOperation(originalOperation, task));
                    try (var connection = runtime.persistence().database().openConnection(); var statement = connection.prepareStatement("SELECT parent_operation_id,original_task_id,task_id,owner_version FROM file_operation WHERE operation_id=?")) {
                        statement.setObject(1, continued.operationId());
                        try (var rows = statement.executeQuery()) {
                            assertTrue(rows.next());
                            assertEquals(originalOperation, rows.getObject(1, UUID.class));
                            assertEquals(originalTask, rows.getObject(2, UUID.class));
                            assertEquals(task.id(), rows.getObject(3, UUID.class));
                            assertEquals(VERSION, rows.getString(4));
                        }
                    }
                    return success(null);
                }));
            } finally {
                Files.deleteIfExists(root.resolve("managed"));
            }
        }
    }

    public static final class InterruptLinkStep implements Trigger {
        boolean first = true;

        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            if (first && "BEFORE_LINK_UPDATE".equals(newRow[2])) {
                first = false;
                throw new SQLException("Controlled original link-stage commit failure");
            }
        }
    }

    public static final class InterruptLinkRegistration implements Trigger {
        boolean first = true;

        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            if (first && newRow[5] instanceof String path && path.endsWith("moved")) {
                first = false;
                throw new SQLException("Controlled updated link registration commit failure");
            }
        }
    }
}
