package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.appapi.task.Outcome;

import static gold.debug.windowstolinux.app.main.start.PlatformModDeletionTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformModInstallationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.text;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.count;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.platform.win32.*;
import gold.debug.windowstolinux.app.db.log.LogRepository;
import gold.debug.windowstolinux.app.db.mod.ModNavigationPreferences;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;
import gold.debug.windowstolinux.app.service.mod.PlatformModInstallation;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModWholeDeletionTest {
    @TempDir
    Path root;

    PlatformModInstallationTest fixture;

    final ObjectMapper json = new ObjectMapper();

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

    void install(PlatformModInstallation installer, Path original) throws Exception {
        assertEquals(Outcome.SUCCEEDED, installer.install(installer.review(original)).outcome());
    }

    Path write(String relative, String bytes) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, bytes);
    }

    Path privateFile(String name) {
        return root.resolve("data/mod").resolve(OWNER).resolve(name);
    }

    LogRepository.Target category(PlatformRuntime runtime) throws Exception {
        var logs = new PlatformLogDeletionTest();
        return logs.category(runtime);
    }

    SchemaIdentity schema(PlatformRuntime runtime) throws Exception {
        var identity = new SchemaIdentity(OWNER, "7", UUID.randomUUID());
        var names = new SchemaCoordinates(identity);
        sql(runtime,
            "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,'7',?,?,?,'PREPARING',1)",
            OWNER, identity.generation(), names.physicalName(), names.databaseUser());
        new SchemaProvisioner(runtime.persistence().database()).create(identity);
        sql(runtime, "CREATE TABLE " + names.physicalName() + ".items(id INT PRIMARY KEY)");
        sql(runtime, "INSERT INTO " + names.physicalName() + ".items VALUES(42)");
        sql(runtime, "UPDATE mod_schema SET state='READY',ever_used=TRUE,validated_at=1 WHERE generation=?",
            identity.generation());
        return identity;
    }

    WinNT.HANDLE hold(Path file) {
        var handle = Kernel32.INSTANCE.CreateFile(file.toString(), WinNT.GENERIC_READ,
            WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0, null);
        assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
        return handle;
    }

    String request(PlatformRuntime runtime, UUID operation) throws Exception {
        return text(runtime, "SELECT request_json FROM mod_operation WHERE operation_id='" + operation + "'");
    }

    @Test
    void removesAllReviewedProgramsPrivateFilesDataAndLogsButKeepsOtherOwnersAndIndependentBackups() throws Exception {
        var first = fixture.original("one.wtlmod", "1.0.0", "one");
        var second = fixture.original("two.wtlmod", "2.0.0", "two");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, first);
            install(installer, second);
            acknowledged(runtime);
            var data = schema(runtime);
            var target = category(runtime);
            Path cache = write("data/mod/" + OWNER + "/nested/cache.bin", "private cache");
            Path log = write("data/log/emergency/" + target.key() + ".log", "damaged but attributed log");
            Path pending = write("data/log/emergency/" + target.key() + ".pending", "pending bytes");
            Path backup = write("data/backups/export.zip", "independent backup");
            Path other = write("data/mod/other.owner/cache.bin", "other private data");
            Path unknown = write("data/log/emergency/" + UUID.randomUUID() + ".log", "unknown category");
            try (var c = runtime.persistence().database().openConnection()) {
                c.setAutoCommit(false);
                ModNavigationPreferences.write(c, List.of(new ModNavigationPreferences.PagePin(OWNER, "main"),
                    new ModNavigationPreferences.PagePin("other.owner", "main")));
                c.commit();
            }
            var review = installer.deletion().review(OWNER);
            assertTrue(review.whole());
            assertNull(review.version());
            assertEquals(List.of("1.0.0", "2.0.0"), review.versions());
            assertEquals(List.of(data), review.removedSchemas());
            assertTrue(review.ownedFiles().containsAll(List.of(cache, log, pending)));
            assertFalse(review.ownedFiles().contains(backup));
            assertTrue(review.bytes() > 0);
            assertTrue(review.ownedBytes() > 0);
            var result = installer.deletion().delete(review, false);
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            assertNull(result.value().orElseThrow().version());
            assertFalse(Files.exists(root.resolve("mod").resolve(OWNER)));
            assertFalse(Files.exists(privateFile("")));
            assertFalse(Files.exists(log));
            assertFalse(Files.exists(pending));
            for (Path retained : List.of(first, second, backup, other, unknown))
                assertTrue(Files.exists(retained), retained.toString());
            assertEquals(0, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM mod_registry WHERE mod_id='" + OWNER + "'"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(0,
                count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"
                        + new SchemaCoordinates(data).physicalName() + "'"));
            assertEquals(0, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM log_category WHERE owner_id='" + OWNER + "'"));
            assertEquals(0, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE owner_id='" + OWNER + "'"));
            try (var c = runtime.persistence().database().openConnection()) {
                assertEquals(List.of(new ModNavigationPreferences.PagePin("other.owner", "main")),
                    ModNavigationPreferences.read(c));
            }
            assertEquals(result, installer.deletion().reconcile(review.id()));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_DELETED'"));
        }
    }

    @Test
    void optionalOriginalsCoverExactOwnerAcrossUninstalledVersionsAndDoNotAdoptLaterArchives() throws Exception {
        var first = fixture.original("one.wtlmod", "1.0.0", "one");
        var uninstalled = fixture.original("three.wtlmod", "3.0.0", "three");
        Path damaged = write("mod-zip/damaged.wtlmod", "not a readable package");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, first);
            acknowledged(runtime);
            var review = installer.deletion().review(OWNER);
            assertEquals(Set.of(first, uninstalled), Set.copyOf(review.originals()));
            var later = fixture.original("later.wtlmod", "4.0.0", "later");
            assertEquals(Outcome.SUCCEEDED, installer.deletion().delete(review, true).outcome());
            assertFalse(Files.exists(first));
            assertFalse(Files.exists(uninstalled));
            assertTrue(Files.exists(damaged));
            assertTrue(Files.exists(later));
        }
    }

    @Test
    void unknownProgramVersionsAndChangedPrivateFilesCannotBeAccepted() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            Path unknown = write("mod/" + OWNER + "/foreign/README.md", "unregistered");
            assertThrows(IOException.class, () -> installer.deletion().review(OWNER));
            Files.move(unknown.getParent(), root.resolve("preserved-program"));
            Path cache = write("data/mod/" + OWNER + "/cache.bin", "before");
            var review = installer.deletion().review(OWNER);
            Files.writeString(cache, "after");
            assertThrows(IOException.class, () -> installer.deletion().delete(review, false));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertTrue(Files.exists(fixture.target("1.0.0").resolve("main/fixture.jar")));
            assertEquals(Outcome.SUCCEEDED,
                installer.deletion().delete(installer.deletion().review(OWNER), false).outcome());
            assertEquals("unregistered", Files.readString(root.resolve("preserved-program/README.md")));
        }
    }

    @Test
    void lockedPrivateFileKeepsReferencesAndCrossBootRecoveryUsesOnlyRemainingOriginalScope() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        UUID operation;
        Path pending;
        String accepted;
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            schema(runtime);
            var target = category(runtime);
            pending = write("data/log/emergency/" + target.key() + ".pending", "original pending bytes");
            write("data/mod/" + OWNER + "/a.bin", "first removed");
            Path locked = write("data/mod/" + OWNER + "/z.bin", "locked");
            var review = installer.deletion().review(OWNER);
            operation = review.id();
            var handle = hold(locked);
            try {
                assertThrows(IOException.class, () -> Files.delete(locked));
                assertEquals(Outcome.UNKNOWN, installer.deletion().delete(review, false).outcome());
                assertEquals("UNKNOWN/DATA_REMOVED", deletionPhase(runtime, operation));
                assertFalse(Files.exists(privateFile("a.bin")));
                assertTrue(Files.exists(locked));
                assertFalse(Files.exists(fixture.target("1.0.0")));
                assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM log_category WHERE owner_id='" + OWNER + "'"));
                accepted = request(runtime, operation);
                assertThrows(IllegalStateException.class, () -> runtime.capabilities().logs(OWNER, "1.0.0", Optional.empty()));
            } finally {
                assertTrue(Kernel32.INSTANCE.CloseHandle(handle));
            }
        }
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            assertEquals(accepted, request(runtime, operation));
            assertEquals(Outcome.SUCCEEDED, installer.deletion().reconcile(operation).outcome());
            assertFalse(Files.exists(privateFile("")));
            assertFalse(Files.exists(pending));
            assertTrue(Files.exists(original));
        }
    }

    @Test
    void recoveryRejectsAddedPrivateFilesAndSameBytesReplacementRoots() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            Path cache = write("data/mod/" + OWNER + "/cache.bin", "retained");
            var review = installer.deletion().review(OWNER);
            trap(runtime, "phase <> 'DATA_REMOVED'");
            assertEquals(Outcome.UNKNOWN, installer.deletion().delete(review, false).outcome());
            untrap(runtime);
            assertEquals("UNKNOWN/PROGRAM_REMOVED", deletionPhase(runtime, review.id()));
            Path extra = write("data/mod/" + OWNER + "/added.bin", "not confirmed");
            assertEquals(Outcome.UNKNOWN, installer.deletion().reconcile(review.id()).outcome());
            assertTrue(Files.exists(cache));
            assertTrue(Files.exists(extra));
            Files.move(extra, root.resolve("preserved-extra.bin"));
            Files.move(privateFile(""), root.resolve("original-private-tree"));
            write("data/mod/" + OWNER + "/cache.bin", "retained");
            assertEquals(Outcome.UNKNOWN, installer.deletion().reconcile(review.id()).outcome());
            assertEquals("retained", Files.readString(cache));
            Files.move(privateFile(""), root.resolve("preserved-replacement-tree"));
            Files.move(root.resolve("original-private-tree"), privateFile(""));
            assertEquals(Outcome.SUCCEEDED, installer.deletion().reconcile(review.id()).outcome());
            assertTrue(Files.exists(root.resolve("preserved-extra.bin")));
            assertTrue(Files.exists(root.resolve("preserved-replacement-tree/cache.bin")));
        }
    }

    @Test
    void registryRemovalDoesNotLoseProtectedOriginalsOrCrossBootRecovery() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        UUID operation;
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            category(runtime);
            var review = installer.deletion().review(OWNER);
            operation = review.id();
            var handle = hold(original);
            try {
                assertEquals(Outcome.UNKNOWN, installer.deletion().delete(review, true).outcome());
                assertEquals("UNKNOWN/REGISTRY_REMOVED", deletionPhase(runtime, operation));
                assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM mod_registry WHERE mod_id='" + OWNER + "'"));
                assertThrows(SQLException.class, () -> installer.reviewDelete(original));
                assertFalse(installer.scan().originals().getFirst().deletable());
            } finally {
                assertTrue(Kernel32.INSTANCE.CloseHandle(handle));
            }
        }
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            var later = fixture.original("later.wtlmod", "3.0.0", "later");
            assertEquals(Outcome.SUCCEEDED, installer.deletion().reconcile(operation).outcome());
            assertFalse(Files.exists(original));
            assertTrue(Files.exists(later));
        }
    }

    @Test
    void tamperedPrivateAndEmergencyPathsAreRejectedBeforeAnyRecoveryEffect() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            var target = category(runtime);
            write("data/mod/" + OWNER + "/cache.bin", "retained");
            write("data/log/emergency/" + target.key() + ".log", "original log");
            Path outside = write("exported-backup.bin", "preserve");
            var review = installer.deletion().review(OWNER);
            trap(runtime, "phase <> 'PROGRAM_REMOVED'");
            assertEquals(Outcome.UNKNOWN, installer.deletion().delete(review, false).outcome());
            untrap(runtime);
            String before = request(runtime, review.id());
            for (String area : List.of("auxiliary", "emergency")) {
                var request = (ObjectNode) json.readTree(before);
                var files = (ObjectNode) json.readTree(request.get("files").textValue());
                ((ObjectNode) files.get("owned").get(area).get(0)).put("path", outside.toString());
                request.put("files", files.toString());
                sql(runtime, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", request.toString(),
                    review.id());
                assertThrows(IOException.class, () -> installer.deletion().reconcile(review.id()));
                assertEquals("UNKNOWN/QUIESCENT", deletionPhase(runtime, review.id()));
                assertEquals("preserve", Files.readString(outside));
            }
            sql(runtime, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", before, review.id());
            assertEquals(Outcome.SUCCEEDED, installer.deletion().reconcile(review.id()).outcome());
            assertTrue(Files.exists(outside));
        }
    }

    @Test
    void emptyOwnerDeletionKeepsOneReceiptAndDoesNotConsumeLaterReinstallation() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            assertEquals(Outcome.SUCCEEDED,
                installer.deletion().delete(installer.deletion().review(OWNER, "1.0.0"), false).outcome());
            var review = installer.deletion().review(OWNER);
            assertTrue(review.versions().isEmpty());
            sql(runtime,
                "ALTER TABLE runtime_log ADD CONSTRAINT reject_whole_archive CHECK(event_code<>'MOD_DELETED')");
            assertEquals(Outcome.UNKNOWN, installer.deletion().delete(review, false).outcome());
            assertEquals("UNKNOWN/CLEANUP", deletionPhase(runtime, review.id()));
            sql(runtime, "ALTER TABLE runtime_log DROP CONSTRAINT reject_whole_archive");
            var complete = installer.deletion().reconcile(review.id());
            assertEquals(Outcome.SUCCEEDED, complete.outcome());
            install(installer, original);
            assertEquals(complete, installer.deletion().reconcile(review.id()));
            assertTrue(Files.exists(fixture.target("1.0.0")));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_DELETED'"));
        }
    }

    @Test
    void previousPhysicalEvidenceFormatRetainsItsVersionOnlyMeaningWithoutRewrite() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            Path cache = write("data/mod/" + OWNER + "/cache.bin", "retained");
            var review = installer.deletion().review(OWNER, "1.0.0");
            trap(runtime, "phase <> 'PROGRAM_REMOVED'");
            assertEquals(Outcome.UNKNOWN, installer.deletion().delete(review, false).outcome());
            untrap(runtime);
            var request = (ObjectNode) json.readTree(request(runtime, review.id()));
            var files = (ObjectNode) json.readTree(request.get("files").textValue());
            files.put("format", 1);
            files.remove("owned");
            request.put("files", files.toString());
            String originalEvidence = request.toString();
            sql(runtime, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", originalEvidence, review.id());
            trap(runtime, "phase <> 'CLEANUP'");
            assertEquals(Outcome.UNKNOWN, installer.deletion().reconcile(review.id()).outcome());
            assertEquals(originalEvidence, request(runtime, review.id()));
            untrap(runtime);
            assertEquals(Outcome.SUCCEEDED, installer.deletion().reconcile(review.id()).outcome());
            assertEquals("retained", Files.readString(cache));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM mod_registry WHERE mod_id='" + OWNER + "'"));
        }
    }

    @Test
    void privateDirectoryJunctionCannotAuthorizeDeletionOfItsExternalTarget() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(installer, original);
            acknowledged(runtime);
            Path outside = write("independent-backup/keep.bin", "exported backup");
            Files.createDirectories(privateFile(""));
            Path junction = privateFile("redirected");
            var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(),
                outside.getParent().toString()).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
                assertThrows(IOException.class, () -> installer.deletion().review(OWNER));
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
                assertEquals("exported backup", Files.readString(outside));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                }
                Files.deleteIfExists(junction);
            }
        }
    }
}
