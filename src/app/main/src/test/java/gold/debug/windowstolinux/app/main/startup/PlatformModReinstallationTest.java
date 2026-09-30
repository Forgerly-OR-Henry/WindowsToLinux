package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformModDeletionTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformModInstallationTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformModIntentsTest.text;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.count;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.platform.win32.*;
import gold.debug.windowstolinux.app.mod.installation.ModPackageReader;
import gold.debug.windowstolinux.app.service.mod.*;
import gold.debug.windowstolinux.app.windows.filesystem.WindowsFileIdentity;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlatformModReinstallationTest {
    @TempDir
    Path root;

    PlatformModInstallationTest fixture;

    static final String VERSION = "1.0.0";

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

    Path target() {
        return fixture.target(VERSION);
    }

    Path stage(UUID id) {
        return root.resolve(".mod-staging").resolve(id.toString());
    }

    void install(PlatformRuntime runtime, PlatformModInstallation installer, Path original) throws Exception {
        assertEquals(Outcome.SUCCEEDED, installer.install(installer.review(original)).outcome());
        acknowledged(runtime);
    }

    String choice(PlatformRuntime runtime) throws Exception {
        return text(runtime,
                "SELECT enabled||'/'||selected_version||'/'||revision||'/'||disable_ack_revision||'/'||last_boot_id||'/'||last_runtime_state FROM mod_registry WHERE mod_id='"
                        + OWNER + "'");
    }

    PlatformModReinstallation.Review review(PlatformModInstallation installer, Path original) throws Exception {
        return installer.reinstallation().review(OWNER, VERSION, original);
    }

    void state(PlatformRuntime runtime, UUID id, String expected) throws Exception {
        assertEquals(expected, deletionPhase(runtime, id));
    }

    @Test
    void repairsDamagedProgramWithExactSameReleaseAndRetainsDataChoiceOtherVersionsAndArchives() throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "same release");
        var second = fixture.original("second.wtlmod", "2.0.0", "other release");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            assertEquals(Outcome.SUCCEEDED, installer.install(installer.review(second)).outcome());
            String choice = choice(runtime), oldId = WindowsFileIdentity.read(target());
            Path data = Files.createDirectories(root.resolve("data/mod/" + OWNER)).resolve("retained.txt");
            Files.writeString(data, "data stays");
            Files.writeString(target().resolve("main/fixture.jar"), "damaged old program");
            var service = installer.reinstallation();
            var review = review(installer, original);
            assertTrue(review.oldFiles().contains(target().resolve("main/fixture.jar")));
            assertFalse(Files.exists(stage(review.id())));
            var result = service.reinstall(review);
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            ModPackageReader.verifyDirectory(review.inspection(), target());
            assertNotEquals(oldId, WindowsFileIdentity.read(target()));
            assertEquals(choice, choice(runtime));
            assertEquals("data stays", Files.readString(data));
            assertTrue(Files.exists(fixture.target("2.0.0")));
            assertTrue(Files.exists(original));
            assertTrue(Files.exists(second));
            assertFalse(Files.exists(stage(review.id())));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(result, service.reconcile(review.id()));
            assertThrows(IllegalArgumentException.class, () -> service.reinstall(review));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAGED", "OLD_SAVED", "FILES_PUBLISHED", "REGISTERED", "CLEANUP"})
    void phaseCommitFailuresResumeOriginalNativeDirectoriesAcrossRuntimeReopen(String rejected) throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "unchanged");
        UUID id;
        String request;
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            var review = review(installer, original);
            id = review.id();
            trap(runtime, "phase<>'" + rejected + "'");
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().reinstall(review).outcome());
            request = text(runtime, "SELECT request_json FROM mod_operation WHERE operation_type='REINSTALL'");
            assertTrue(Files.exists(stage(id)));
            assertThrows(SQLException.class, () -> installer.reviewDelete(original));
            untrap(runtime);
            // Completed extraction no longer depends on the original archive being present.
            Files.move(original, root.resolve("retained-original.wtlmod"));
        }
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            assertEquals(request,
                    text(runtime, "SELECT request_json FROM mod_operation WHERE operation_type='REINSTALL'"));
            var result = installer.reinstallation().reconcile(id);
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            ModPackageReader.verifyDirectory(ModPackageReader.read(root.resolve("retained-original.wtlmod")), target());
            assertFalse(Files.exists(stage(id)));
            assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
        }
    }

    @Test
    void repairRejectsChangedReleaseAndUnacknowledgedStopBeforeMutatingAnyProgram() throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "original");
        var conflict = fixture.original("conflict.wtlmod", VERSION, "different content");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            String identity = WindowsFileIdentity.read(target());
            assertThrows(SQLException.class, () -> review(installer, conflict));
            sql(runtime, "UPDATE mod_registry SET disable_ack_revision=NULL");
            assertThrows(SQLException.class, () -> review(installer, original));
            acknowledged(runtime);
            var review = review(installer, original);
            Files.writeString(target().resolve("README.md"), "changed after approval");
            assertThrows(IOException.class, () -> installer.reinstallation().reinstall(review));
            assertEquals(identity, WindowsFileIdentity.read(target()));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            var fresh = review(installer, original);
            runtime.security().lock();
            assertThrows(gold.debug.windowstolinux.app.service.contract.result.ApplicationServiceException.class,
                    () -> installer.reinstallation().reinstall(fresh));
            assertFalse(Files.exists(stage(fresh.id())));
        }
    }

    @Test
    void missingInstalledDirectoryCanBeRepairedWithoutInventingAnOldBackup() throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "original");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            Files.move(target(), root.resolve("separately-retained-program"));
            var review = review(installer, original);
            assertTrue(review.oldFiles().isEmpty());
            assertEquals(Outcome.SUCCEEDED, installer.reinstallation().reinstall(review).outcome());
            ModPackageReader.verifyDirectory(review.inspection(), target());
            assertTrue(Files.exists(root.resolve("separately-retained-program/main/fixture.jar")));
        }
    }

    @Test
    void nativeFileLockPreservesOldAndNewFilesUntilExplicitContinuation() throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "original");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            var review = review(installer, original);
            String oldId = WindowsFileIdentity.read(target());
            var handle = Kernel32.INSTANCE.CreateFile(target().resolve("main/fixture.jar").toString(),
                    WinNT.GENERIC_READ, WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0,
                    null);
            assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
            try {
                assertEquals(Outcome.UNKNOWN, installer.reinstallation().reinstall(review).outcome());
                assertTrue(Files.exists(stage(review.id()).resolve("program"))
                        || Files.exists(stage(review.id()).resolve("old")));
            } finally {
                assertTrue(Kernel32.INSTANCE.CloseHandle(handle));
            }
            assertEquals(Outcome.SUCCEEDED, installer.reinstallation().reconcile(review.id()).outcome());
            assertNotEquals(oldId, WindowsFileIdentity.read(target()));
        }
    }

    @Test
    void damagedNewProgramCanBeExplicitlyRestoredUsingUnchangedOldBackupWithoutFalselyReportingRepairSuccess()
            throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "original");
        UUID id;
        String oldId;
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            oldId = WindowsFileIdentity.read(target());
            trap(runtime, "phase<>'REGISTERED'");
            var review = review(installer, original);
            id = review.id();
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().reinstall(review).outcome());
            untrap(runtime);
            state(runtime, id, "UNKNOWN/FILES_PUBLISHED");
            Files.writeString(target().resolve("main/fixture.jar"), "invalid new bytes");
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().reconcile(id).outcome());
            var restore = installer.reinstallation().reviewRestore(id);
            assertTrue(restore.discardedFiles().contains(target().resolve("main/fixture.jar")));
            trap(runtime, "phase<>'RESTORED'");
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().restore(restore).outcome());
            assertEquals(oldId, WindowsFileIdentity.read(target()));
            state(runtime, id, "UNKNOWN/RESTORING");
            untrap(runtime);
        }
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            var result = installer.reinstallation().reconcile(id);
            assertEquals(Outcome.FAILED, result.outcome(), result.toString());
            assertTrue(result.value().orElseThrow().restored());
            assertEquals(oldId, WindowsFileIdentity.read(target()));
            assertFalse(Files.exists(stage(id)));
            ModPackageReader.verifyDirectory(ModPackageReader.read(original), target());
            assertEquals(result, installer.reinstallation().reconcile(id));
            assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_REINSTALL_RESTORED' AND outcome='FAILED'"));
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_REINSTALLED'"));
        }
    }

    @Test
    void earlyRestorationRetainsOriginalProgramAndRejectsStaleDiscardApproval() throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "original");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            String oldId = WindowsFileIdentity.read(target());
            trap(runtime, "phase<>'STAGED'");
            var review = review(installer, original);
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().reinstall(review).outcome());
            untrap(runtime);
            state(runtime, review.id(), "UNKNOWN/VALIDATED");
            Files.delete(stage(review.id()).resolve("program/LICENSE"));
            var restore = installer.reinstallation().reviewRestore(review.id());
            Files.writeString(stage(review.id()).resolve("program/README.md"), "changed new bytes");
            assertThrows(IOException.class, () -> installer.reinstallation().restore(restore));
            var fresh = installer.reinstallation().reviewRestore(review.id());
            assertEquals(Outcome.FAILED, installer.reinstallation().restore(fresh).outcome());
            assertEquals(oldId, WindowsFileIdentity.read(target()));
            assertFalse(Files.exists(stage(review.id())));
        }
    }

    @Test
    void foreignStageChildrenChangedOldBackupAndReplacementDirectoryBlockEffects() throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "original");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            trap(runtime, "phase<>'OLD_SAVED'");
            var review = review(installer, original);
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().reinstall(review).outcome());
            untrap(runtime);
            Path stage = stage(review.id()), program = stage.resolve("program"), old = stage.resolve("old");
            Files.writeString(stage.resolve("foreign.txt"), "keep");
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().reconcile(review.id()).outcome());
            assertThrows(IOException.class, () -> installer.reinstallation().reviewRestore(review.id()));
            Files.move(stage.resolve("foreign.txt"), root.resolve("foreign-retained.txt"));
            String oldReadme = Files.readString(old.resolve("README.md"));
            Files.writeString(old.resolve("README.md"), "changed old");
            assertThrows(IOException.class, () -> installer.reinstallation().reviewRestore(review.id()));
            Files.writeString(old.resolve("README.md"), oldReadme);
            Files.move(program, stage.resolve("saved-new"));
            copied(stage.resolve("saved-new"), program);
            Files.move(stage.resolve("saved-new"), root.resolve("original-new-directory"));
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().reconcile(review.id()).outcome());
            assertTrue(Files.exists(old));
            assertFalse(Files.exists(target()));
            assertTrue(Files.exists(program));
            assertEquals("keep", Files.readString(root.resolve("foreign-retained.txt")));
        }
    }

    @Test
    void archiveFailureKeepsOriginalReceiptAndNeverReappliesAfterCompletion() throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "original");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            var review = review(installer, original);
            sql(runtime,
                    "ALTER TABLE runtime_log ADD CONSTRAINT repair_archive_trap CHECK(event_code<>'MOD_REINSTALLED')");
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().reinstall(review).outcome());
            state(runtime, review.id(), "UNKNOWN/CLEANUP");
            assertFalse(Files.exists(stage(review.id())));
            String archive = text(runtime, "SELECT archive_log_id FROM mod_operation WHERE operation_type='REINSTALL'");
            sql(runtime, "ALTER TABLE runtime_log DROP CONSTRAINT repair_archive_trap");
            assertEquals(Outcome.SUCCEEDED, installer.reinstallation().reconcile(review.id()).outcome());
            assertEquals(archive,
                    text(runtime, "SELECT log_id FROM runtime_log WHERE operation_id='" + review.id() + "'"));
            Files.writeString(target().resolve("README.md"), "later state");
            assertEquals(Outcome.SUCCEEDED, installer.reinstallation().reconcile(review.id()).outcome());
            assertEquals("later state", Files.readString(target().resolve("README.md")));
        }
    }

    @Test
    void tamperedPersistedOldScopeNeverDeletesUnrelatedFiles() throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "original");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            trap(runtime, "phase<>'STAGED'");
            var review = review(installer, original);
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().reinstall(review).outcome());
            untrap(runtime);
            var outside = Files.writeString(root.resolve("outside.txt"), "keep");
            var json = new ObjectMapper();
            var request = (ObjectNode) json
                    .readTree(text(runtime, "SELECT request_json FROM mod_operation WHERE operation_type='REINSTALL'"));
            var scope = (ObjectNode) json.readTree(request.path("files").asText());
            ((ObjectNode) scope.path("old").get(0)).put("path", outside.toString());
            request.put("files", scope.toString());
            sql(runtime, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", request.toString(),
                    review.id());
            assertThrows(IOException.class, () -> installer.reinstallation().reconcile(review.id()));
            assertEquals("keep", Files.readString(outside));
            assertTrue(Files.exists(target()));
        }
    }

    @Test
    void managedOriginalUsesCapturedRetentionButExternalRepairInputIsAlwaysPreserved() throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "original");
        Path external = root.resolve("external-source.wtlmod");
        Files.copy(original, external);
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            installer.keepOriginal(false);
            var review = review(installer, original);
            assertFalse(review.keepOriginal());
            installer.keepOriginal(true);
            var result = installer.reinstallation().reinstall(review);
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            assertEquals("DELETED", result.value().orElseThrow().originalCleanup());
            assertFalse(Files.exists(original));
            assertEquals(result, installer.reinstallation().reconcile(review.id()));
            installer.keepOriginal(false);
            var externalReview = review(installer, external);
            assertTrue(externalReview.keepOriginal());
            var second = installer.reinstallation().reinstall(externalReview);
            assertEquals(Outcome.SUCCEEDED, second.outcome());
            assertEquals("RETAINED", second.value().orElseThrow().originalCleanup());
            assertTrue(Files.exists(external));
        }
    }

    @Test
    void lockedOriginalCleanupIsReportedSeparatelyAndRepairDoesNotBlockUnrelatedOriginals() throws Exception {
        var original = fixture.original("first.wtlmod", VERSION, "original");
        var other = fixture.original("other.wtlmod", "2.0.0", "other");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            install(runtime, installer, original);
            installer.keepOriginal(false);
            trap(runtime, "phase<>'REGISTERED'");
            var review = review(installer, original);
            assertEquals(Outcome.UNKNOWN, installer.reinstallation().reinstall(review).outcome());
            untrap(runtime);
            assertThrows(SQLException.class, () -> installer.reviewDelete(original));
            assertNotNull(installer.reviewDelete(other));
            assertEquals(Optional.of(original), installer.scan().pending().getFirst().original());
            var handle = Kernel32.INSTANCE.CreateFile(original.toString(), WinNT.GENERIC_READ,
                    WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0, null);
            assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
            try {
                var result = installer.reinstallation().reconcile(review.id());
                assertEquals(Outcome.SUCCEEDED, result.outcome());
                assertEquals("RETAINED_UNCONFIRMED", result.value().orElseThrow().originalCleanup());
                assertEquals(result, installer.reinstallation().reconcile(review.id()));
                assertTrue(Files.exists(original));
            } finally {
                assertTrue(Kernel32.INSTANCE.CloseHandle(handle));
            }
        }
    }
}
