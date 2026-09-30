package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformModInstallationTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

import com.sun.jna.platform.win32.*;
import gold.debug.windowstolinux.app.service.mod.PlatformModInstallation;
import gold.debug.windowstolinux.app.windows.filesystem.WindowsFileIdentity;
import gold.debug.windowstolinux.appapi.task.Outcome;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModInstallationCancellationTest {
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
    void noAuthors() {
        fixture.noAuthorExecution();
    }

    UUID staged(PlatformRuntime runtime, PlatformModInstallation installer, Path original) throws Exception {
        trap(runtime, "phase <> 'STAGED'");
        var review = installer.review(original);
        assertEquals(Outcome.UNKNOWN, installer.install(review).outcome());
        untrap(runtime);
        return review.id();
    }

    Path stage(UUID operation) {
        return root.resolve(".mod-staging").resolve(operation.toString());
    }

    Path program(UUID operation) {
        return stage(operation).resolve("program");
    }

    @Test
    void explicitlyCancelsPartialStagingAndAllowsFreshInstallWithoutDeletingOriginal() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            installer.keepOriginal(false);
            var operation = staged(runtime, installer, original);
            Files.delete(program(operation).resolve("LICENSE"));
            Files.writeString(program(operation).resolve("README.md"), "o");
            var review = installer.reviewCancellation(operation);
            assertTrue(review.paths().contains(program(operation).resolve("README.md")));
            assertFalse(review.paths().contains(original));
            assertTrue(Files.exists(stage(operation)));
            var result = installer.cancel(review);
            assertEquals(Outcome.CANCELLED, result.outcome(), result.toString());
            assertTrue(Files.exists(original));
            assertFalse(Files.exists(stage(operation)));
            assertFalse(Files.exists(fixture.target("1.0.0")));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
            assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_INSTALL_CANCELLED' AND outcome='CANCELLED'"));
            var archived = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    text(runtime, "SELECT details FROM runtime_log WHERE event_code='MOD_INSTALL_CANCELLED'"));
            assertEquals("UNTOUCHED", archived.get("originalArchive").textValue());
            assertEquals(review.paths().size(), archived.get("reviewedEntries").intValue());
            assertEquals(review.bytes(), archived.get("reviewedBytes").longValue());
            assertThrows(IllegalArgumentException.class, () -> installer.cancel(review));
            assertEquals(result, installer.reconcileCancellation(operation));
            assertEquals(Outcome.SUCCEEDED, installer.install(installer.review(original)).outcome());
        }
    }

    @Test
    void cancellationRefusesChangedConfirmationAndForeignServiceReview() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime();
                var installer = fixture.installer(runtime);
                var other = fixture.installer(runtime)) {
            var operation = staged(runtime, installer, original);
            var review = installer.reviewCancellation(operation);
            assertThrows(IllegalArgumentException.class, () -> other.cancel(review));
            Files.writeString(program(operation).resolve("README.md"), "two");
            assertThrows(IOException.class, () -> installer.cancel(review));
            assertEquals("two", Files.readString(program(operation).resolve("README.md")));
            assertEquals("VALIDATED", phase(runtime));
            runtime.security().lock();
            assertThrows(gold.debug.windowstolinux.app.service.contract.result.ApplicationServiceException.class,
                    () -> installer.reviewCancellation(operation));
        }
    }

    @Test
    void unknownExtraFilesAndUnprovenRootIdentityRemainUntouched() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            var operation = staged(runtime, installer, original);
            Path extra = program(operation).resolve("keep.txt");
            Files.writeString(extra, "foreign");
            assertThrows(IOException.class, () -> installer.reviewCancellation(operation));
            assertEquals("foreign", Files.readString(extra));
            Files.move(extra, root.resolve("preserved.txt"));
            var genuine = root.resolve("genuine");
            Files.move(stage(operation), genuine);
            copied(genuine, stage(operation));
            assertThrows(IOException.class, () -> installer.reviewCancellation(operation));
            assertTrue(Files.exists(program(operation).resolve("README.md")));
            Files.move(stage(operation), root.resolve("replacement"));
            Files.move(genuine, stage(operation));
            assertEquals(Outcome.CANCELLED, installer.cancel(installer.reviewCancellation(operation)).outcome());
            assertTrue(Files.exists(root.resolve("replacement/program/README.md")));
        }
    }

    @Test
    void cancelledPublishedFilesNeverRemoveOlderInstalledVersionOrItsIntent() throws Exception {
        var first = fixture.original("first.wtlmod", "1.0.0", "first");
        var second = fixture.original("second.wtlmod", "2.0.0", "second");
        UUID operation;
        String oldIdentity;
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            assertEquals(Outcome.SUCCEEDED, installer.install(installer.review(first)).outcome());
            oldIdentity = WindowsFileIdentity.read(fixture.target("1.0.0"));
            sql(runtime.persistence().database(),
                    "ALTER TABLE mod_version ADD CONSTRAINT cancel_register_trap CHECK(version <> '2.0.0')");
            var review = installer.review(second);
            operation = review.id();
            assertEquals(Outcome.UNKNOWN, installer.install(review).outcome());
            assertEquals("FILES_PUBLISHED", phase(runtime));
        }
        Files.delete(second);
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            var review = installer.reviewCancellation(operation);
            assertTrue(review.paths().contains(fixture.target("2.0.0")));
            assertEquals(Outcome.CANCELLED, installer.cancel(review).outcome());
            assertFalse(Files.exists(fixture.target("2.0.0")));
            assertEquals(oldIdentity, WindowsFileIdentity.read(fixture.target("1.0.0")));
            assertEquals("1.0.0", text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
        }
    }

    @Test
    void failedCancellationAdmissionCannotDeleteFiles() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            var operation = staged(runtime, installer, original);
            var review = installer.reviewCancellation(operation);
            trap(runtime, "phase <> 'CANCELLING'");
            assertThrows(SQLException.class, () -> installer.cancel(review));
            assertTrue(Files.exists(program(operation).resolve("main/fixture.jar")));
            assertEquals("VALIDATED", phase(runtime));
            untrap(runtime);
            assertEquals(Outcome.CANCELLED, installer.cancel(installer.reviewCancellation(operation)).outcome());
        }
    }

    @Test
    void partialDeletionContinuesAcrossBootWithoutReinstallingOrBroadeningScope() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        UUID operation;
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            operation = staged(runtime, installer, original);
            var review = installer.reviewCancellation(operation);
            Path license = program(operation).resolve("LICENSE");
            var handle = Kernel32.INSTANCE.CreateFile(license.toString(), WinNT.GENERIC_READ,
                    WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0, null);
            assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
            try {
                assertEquals(Outcome.UNKNOWN, installer.cancel(review).outcome());
                assertEquals("CANCELLING", phase(runtime));
                assertFalse(Files.exists(program(operation).resolve("main/fixture.jar")));
                assertTrue(Files.exists(license));
                assertThrows(SQLException.class, () -> installer.reconcile(operation));
                assertThrows(SQLException.class, () -> installer.reviewCancellation(operation));
            } finally {
                assertTrue(Kernel32.INSTANCE.CloseHandle(handle));
            }
        }
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            Path extra = program(operation).resolve("extra.txt");
            Files.writeString(extra, "preserve");
            assertEquals(Outcome.UNKNOWN, installer.reconcileCancellation(operation).outcome());
            assertEquals("preserve", Files.readString(extra));
            Files.move(extra, root.resolve("preserved.txt"));
            assertEquals(Outcome.CANCELLED, installer.reconcileCancellation(operation).outcome());
            assertTrue(Files.exists(original));
            assertFalse(Files.exists(stage(operation)));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
        }
    }

    @Test
    void archiveFailureRetainsOriginalCancellationAndDoesNotDeleteLaterForeignFiles() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        UUID operation;
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            operation = staged(runtime, installer, original);
            sql(runtime.persistence().database(),
                    "ALTER TABLE runtime_log ADD CONSTRAINT cancel_archive_trap CHECK(event_code <> 'MOD_INSTALL_CANCELLED')");
            assertEquals(Outcome.UNKNOWN, installer.cancel(installer.reviewCancellation(operation)).outcome());
            assertFalse(Files.exists(stage(operation)));
            assertEquals("CANCELLING", phase(runtime));
        }
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT cancel_archive_trap");
            assertEquals(Outcome.CANCELLED, installer.reconcileCancellation(operation).outcome());
            Files.createDirectories(stage(operation));
            Files.writeString(stage(operation).resolve("keep.txt"), "later");
            assertEquals(Outcome.CANCELLED, installer.reconcileCancellation(operation).outcome());
            assertEquals("later", Files.readString(stage(operation).resolve("keep.txt")));
            assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_INSTALL_CANCELLED'"));
        }
    }

    @Test
    void registeredVersionsAndMissingOriginalOwnershipCannotBeCancelled() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            trap(runtime, "recovery_json LIKE '%\"root\":null%'");
            var review = installer.review(original);
            assertEquals(Outcome.UNKNOWN, installer.install(review).outcome());
            untrap(runtime);
            assertThrows(IOException.class, () -> installer.reviewCancellation(review.id()));
            Files.delete(stage(review.id()));
            assertEquals(Outcome.CANCELLED, installer.cancel(installer.reviewCancellation(review.id())).outcome());
            trap(runtime, "phase <> 'CLEANUP'");
            var registered = installer.review(original);
            assertEquals(Outcome.UNKNOWN, installer.install(registered).outcome());
            assertEquals("REGISTERED", phase(runtime));
            assertThrows(SQLException.class, () -> installer.reviewCancellation(registered.id()));
            assertTrue(Files.exists(fixture.target("1.0.0")));
            untrap(runtime);
            assertEquals(Outcome.SUCCEEDED, installer.reconcile(registered.id()).outcome());
        }
    }

    @Test
    void corruptRetainedCleanupCannotDeleteAnOutOfScopePath() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            var operation = staged(runtime, installer, original);
            sql(runtime.persistence().database(),
                    "ALTER TABLE runtime_log ADD CONSTRAINT corrupt_cancel_archive_trap CHECK(event_code <> 'MOD_INSTALL_CANCELLED')");
            assertEquals(Outcome.UNKNOWN, installer.cancel(installer.reviewCancellation(operation)).outcome());
            String recovery = text(runtime, "SELECT recovery_json FROM mod_operation");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var corrupt = mapper.readTree(recovery);
            ((com.fasterxml.jackson.databind.node.ObjectNode) corrupt.get("entries").get(0)).put("path",
                    original.toString());
            try (var connection = runtime.persistence().database().openConnection();
                    var statement = connection.prepareStatement("UPDATE mod_operation SET recovery_json=?")) {
                statement.setString(1, mapper.writeValueAsString(corrupt));
                statement.executeUpdate();
            }
            assertThrows(SQLException.class, () -> installer.reconcileCancellation(operation));
            assertTrue(Files.exists(original));
            try (var connection = runtime.persistence().database().openConnection();
                    var statement = connection.prepareStatement("UPDATE mod_operation SET recovery_json=?")) {
                statement.setString(1, recovery);
                statement.executeUpdate();
            }
            sql(runtime.persistence().database(),
                    "ALTER TABLE runtime_log DROP CONSTRAINT corrupt_cancel_archive_trap");
            assertEquals(Outcome.CANCELLED, installer.reconcileCancellation(operation).outcome());
        }
    }
}
