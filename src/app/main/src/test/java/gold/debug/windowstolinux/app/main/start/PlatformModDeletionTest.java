package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModInstallationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.count;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.platform.win32.*;
import gold.debug.windowstolinux.app.service.mod.*;
import gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModDeletionTest {
    @TempDir
    Path root;

    PlatformModInstallationTest fixture;

    @BeforeEach
    void setup() throws Exception {
        fixture = new PlatformModInstallationTest();
        fixture.root = root;
        fixture.compile();
    }

    @AfterEach
    void noAuthor() {
        fixture.noAuthorExecution();
    }

    static void sql(PlatformRuntime runtime, String statement, Object... values) throws SQLException {
        PlatformTaskAdmissionIntegrationTest.sql(runtime.persistence().database(), statement, values);
    }

    static void acknowledged(PlatformRuntime runtime) throws Exception {
        sql(runtime,
            "UPDATE mod_registry SET enabled=FALSE,disable_ack_revision=revision,last_boot_id=?,last_runtime_state='STOPPED',actual_version=NULL WHERE mod_id=?",
            runtime.persistence().database().bootId(), OWNER);
    }

    static String deletionPhase(PlatformRuntime runtime, UUID id) throws Exception {
        return text(runtime, "SELECT state || '/' || phase FROM mod_operation WHERE operation_id='" + id + "'");
    }

    void install(PlatformModInstallation installer, Path original) throws Exception {
        assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(original)).outcome());
    }

    @Test
    void deletesActualInstalledFilesKeepsOriginalsAndSelectionHistoryAndNeverRunsAuthorCode() throws Exception {
        var first = fixture.original("one.wtlmod", "1.0.0", "one");
        var second = fixture.original("two.wtlmod", "2.0.0", "two");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, first);
            install(installer, second);
            acknowledged(runtime);
            var service = installer.deletion();
            var review = service.review(OWNER, "1.0.0");
            assertTrue(review.programs().contains(fixture.target("1.0.0").resolve("main/fixture.jar")));
            assertEquals(List.of(first), review.originals());
            assertTrue(review.bytes() > 0);
            var result = service.delete(review, false);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
            assertFalse(Files.exists(fixture.target("1.0.0")));
            assertTrue(Files.exists(fixture.target("2.0.0")));
            assertTrue(Files.exists(first));
            assertTrue(Files.exists(second));
            assertNull(text(runtime, "SELECT selected_version FROM mod_registry WHERE mod_id='" + OWNER + "'"));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM mod_registry WHERE selection_initialized AND NOT enabled"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(result, service.reconcile(review.id()));
            install(installer, first);
            assertEquals(result, service.reconcile(review.id()));
            assertTrue(Files.exists(fixture.target("1.0.0")));
            assertThrows(IllegalArgumentException.class, () -> service.delete(review, false));
        }
    }

    @Test
    void onlyExplicitlySelectedManifestMatchingOriginalsAreRemoved() throws Exception {
        var original = fixture.original("wrong-name.wtlmod", "1.0.0", "one");
        var duplicate = root.resolve("mod-zip/other-name.wtlmod");
        Files.copy(original, duplicate);
        var other = fixture.original("sample.bundle-1.0.0.wtlmod", "2.0.0", "two");
        var bad = root.resolve("mod-zip/bad.wtlmod");
        Files.writeString(bad, "unproven");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            var review = installer.deletion().review(OWNER, "1.0.0");
            assertEquals(Set.of(original, duplicate), new HashSet<>(review.originals()));
            assertEquals(TaskResult.SUCCEEDED, installer.deletion().delete(review, true).outcome());
            assertFalse(Files.exists(original));
            assertFalse(Files.exists(duplicate));
            assertTrue(Files.exists(other));
            assertEquals("unproven", Files.readString(bad));
        }
    }

    @Test
    void damagedProgramCanBeExplicitlyReviewedButUnexpectedExtraContentIsRetained() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            Path extra = fixture.target("1.0.0").resolve("private.txt");
            Files.writeString(extra, "keep");
            assertThrows(IOException.class, () -> installer.deletion().review(OWNER, "1.0.0"));
            assertEquals("keep", Files.readString(extra));
            Files.move(extra, root.resolve("retained-private.txt"));
            Files.writeString(fixture.target("1.0.0").resolve("main/fixture.jar"), "damaged program bytes");
            assertEquals(TaskResult.SUCCEEDED,
                installer.deletion().delete(installer.deletion().review(OWNER, "1.0.0"), false).outcome());
            assertEquals("keep", Files.readString(root.resolve("retained-private.txt")));
        }
    }

    @Test
    void actualProgramDeletionPreservesSharedSchemaUntilLastRetainedVersion() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        var second = fixture.original("two.wtlmod", "2.0.0", "two");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            install(installer, second);
            acknowledged(runtime);
            var identity = new gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity(OWNER, "1", UUID.randomUUID());
            var names = new gold.debug.windowstolinux.app.db.schema.SchemaCoordinates(identity);
            for (String version : List.of("1.0.0", "2.0.0"))
                sql(runtime, "UPDATE mod_version SET schema_version='1',manifest_json=? WHERE mod_id=? AND version=?",
                    manifest(OWNER, version, Map.of(), "1", List.of()), OWNER, version);
            sql(runtime,
                "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,'1',?,?,?,'PREPARING',1)",
                OWNER, identity.generation(), names.physicalName(), names.databaseUser());
            new gold.debug.windowstolinux.app.db.schema.SchemaProvisioner(runtime.persistence().database())
                .create(identity);
            sql(runtime, "CREATE TABLE " + names.physicalName() + ".items(id INT PRIMARY KEY)");
            sql(runtime, "INSERT INTO " + names.physicalName() + ".items VALUES(7)");
            sql(runtime, "UPDATE mod_schema SET state='READY',ever_used=TRUE,validated_at=1 WHERE generation=?",
                identity.generation());
            var service = installer.deletion();
            var first = service.review(OWNER, "1.0.0");
            assertEquals(List.of("2.0.0"), first.retainedSchemaVersions());
            assertEquals(TaskResult.SUCCEEDED, service.delete(first, false).outcome());
            assertEquals(1,
                count(runtime.persistence().database(), "SELECT COUNT(*) FROM " + names.physicalName() + ".items"));
            var last = service.review(OWNER, "2.0.0");
            assertEquals(Optional.of(identity), last.removedSchema());
            assertEquals(TaskResult.SUCCEEDED, service.delete(last, false).outcome());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(0,
                count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"
                        + names.physicalName() + "'"));
            assertTrue(Files.exists(original));
            assertTrue(Files.exists(second));
        }
    }

    @Test
    void unreadableOriginalFolderDoesNotPreventDefaultProgramOnlyDeletion() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            Files.move(root.resolve("mod-zip"), root.resolve("preserved-originals"));
            Files.writeString(root.resolve("mod-zip"), "not an archive folder");
            var review = installer.deletion().review(OWNER, "1.0.0");
            assertTrue(review.originals().isEmpty());
            assertEquals(TaskResult.SUCCEEDED, installer.deletion().delete(review, false).outcome());
            assertEquals("not an archive folder", Files.readString(root.resolve("mod-zip")));
            assertTrue(Files.exists(root.resolve("preserved-originals/one.wtlmod")));
        }
    }

    @Test
    void lockForeignReviewStaleFilesAndUnacknowledgedStopCannotAdmitDeletion() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime();
             var installer = fixture.installer(runtime);
             var other = fixture.installer(runtime)) {
            install(installer, original);
            assertThrows(SQLException.class, () -> installer.deletion().review(OWNER, "1.0.0"));
            acknowledged(runtime);
            var review = installer.deletion().review(OWNER, "1.0.0");
            assertThrows(IllegalArgumentException.class, () -> other.deletion().delete(review, false));
            Files.writeString(fixture.target("1.0.0").resolve("README.md"), "changed");
            assertThrows(IOException.class, () -> installer.deletion().delete(review, false));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            runtime.security().lock();
            assertThrows(gold.debug.windowstolinux.app.service.error.ApplicationServiceException.class,
                () -> installer.deletion().review(OWNER, "1.0.0"));
            assertTrue(Files.exists(fixture.target("1.0.0").resolve("main/fixture.jar")));
        }
    }

    @Test
    void partiallyDeletedFilesResumeTheirOriginalSubsetAfterDatabaseReopen() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        UUID operation;
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            var review = installer.deletion().review(OWNER, "1.0.0");
            operation = review.id();
            var handle = Kernel32.INSTANCE.CreateFile(fixture.target("1.0.0").resolve("LICENSE").toString(),
                WinNT.GENERIC_READ, WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0,
                null);
            assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
            try {
                var result = installer.deletion().delete(review, false);
                assertEquals(TaskResult.UNKNOWN, result.outcome());
                assertEquals("UNKNOWN/QUIESCENT", deletionPhase(runtime, operation));
                assertFalse(Files.exists(fixture.target("1.0.0").resolve("main/fixture.jar")));
                assertTrue(Files.exists(fixture.target("1.0.0").resolve("LICENSE")));
                assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM mod_version WHERE state='DELETE_PENDING'"));
            } finally {
                assertTrue(Kernel32.INSTANCE.CloseHandle(handle));
            }
        }
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            assertEquals(TaskResult.SUCCEEDED, installer.deletion().reconcile(operation).outcome());
            assertFalse(Files.exists(fixture.target("1.0.0")));
            assertTrue(Files.exists(original));
        }
    }

    @Test
    void interruptedOriginalCleanupIsProtectedFromOtherOriginalDeletionAndKeepsItsScope() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            var review = installer.deletion().review(OWNER, "1.0.0");
            var handle = Kernel32.INSTANCE.CreateFile(original.toString(), WinNT.GENERIC_READ,
                WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0, null);
            assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
            try {
                assertEquals(TaskResult.UNKNOWN, installer.deletion().delete(review, true).outcome());
                assertEquals("UNKNOWN/REGISTRY_REMOVED", deletionPhase(runtime, review.id()));
                assertFalse(Files.exists(fixture.target("1.0.0")));
                assertThrows(SQLException.class, () -> installer.reviewDelete(original));
                assertFalse(installer.scan().originals().getFirst().deletable());
            } finally {
                assertTrue(Kernel32.INSTANCE.CloseHandle(handle));
            }
            var later = root.resolve("mod-zip/later.wtlmod");
            Files.copy(original, later);
            assertEquals(TaskResult.SUCCEEDED, installer.deletion().reconcile(review.id()).outcome());
            assertFalse(Files.exists(original));
            assertTrue(Files.exists(later));
        }
    }

    @Test
    void newFilesAndSameContentReplacementDirectoriesAreNeverAdoptedDuringRecovery() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            var review = installer.deletion().review(OWNER, "1.0.0");
            trap(runtime, "phase <> 'PROGRAM_REMOVED'");
            assertEquals(TaskResult.UNKNOWN, installer.deletion().delete(review, false).outcome());
            untrap(runtime);
            Files.createDirectories(fixture.target("1.0.0"));
            Files.writeString(fixture.target("1.0.0").resolve("README.md"), "one");
            assertEquals(TaskResult.UNKNOWN, installer.deletion().reconcile(review.id()).outcome());
            assertEquals("one", Files.readString(fixture.target("1.0.0").resolve("README.md")));
            assertEquals("UNKNOWN/QUIESCENT", deletionPhase(runtime, review.id()));
            Files.move(fixture.target("1.0.0"), root.resolve("replacement-preserved"));
            assertEquals(TaskResult.SUCCEEDED, installer.deletion().reconcile(review.id()).outcome());
            assertTrue(Files.exists(root.resolve("replacement-preserved/README.md")));
        }
    }

    @Test
    void archiveFailureFinishesOriginalOperationAndMalformedPathsCannotTargetOutsideFiles() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            var review = installer.deletion().review(OWNER, "1.0.0");
            sql(runtime,
                "ALTER TABLE runtime_log ADD CONSTRAINT fixture_delete_archive CHECK(event_code<>'MOD_VERSION_DELETED')");
            assertEquals(TaskResult.UNKNOWN, installer.deletion().delete(review, false).outcome());
            assertEquals("UNKNOWN/CLEANUP", deletionPhase(runtime, review.id()));
            String before = text(runtime,
                "SELECT request_json FROM mod_operation WHERE operation_id='" + review.id() + "'");
            var json = new ObjectMapper();
            var request = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(before);
            var files = (com.fasterxml.jackson.databind.node.ObjectNode) json
                .readTree(request.get("files").textValue());
            var entry = (com.fasterxml.jackson.databind.node.ObjectNode) files.get("program").get(0);
            entry.put("path", original.toString());
            request.put("files", files.toString());
            sql(runtime, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", request.toString(),
                review.id());
            assertThrows(IOException.class, () -> installer.deletion().reconcile(review.id()));
            assertTrue(Files.exists(original));
            sql(runtime, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", before, review.id());
            sql(runtime, "ALTER TABLE runtime_log DROP CONSTRAINT fixture_delete_archive");
            assertEquals(TaskResult.SUCCEEDED, installer.deletion().reconcile(review.id()).outcome());
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_VERSION_DELETED'"));
            assertTrue(Files.exists(original));
        }
    }
}
