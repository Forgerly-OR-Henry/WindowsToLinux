package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.zip.*;

import javax.tools.ToolProvider;

import com.sun.jna.platform.win32.*;
import gold.debug.windowstolinux.app.mod.install.ModPackageReader;
import gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation;
import gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity;
import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModInstallationTest {
    @TempDir
    Path root;

    static final String OWNER = "sample.bundle", MARK = "wtl.installation.executed";

    byte[] mainJar;

    @BeforeEach
    void compile() throws Exception {
        System.clearProperty(MARK);
        var source = root.resolve("Mod.java");
        Files.writeString(source, """
            package sample.bundle;
            import gold.debug.windowstolinux.appapi.mod.ModContext; import gold.debug.windowstolinux.appapi.mod.ModEntrypoint; import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
            public class Mod implements ModEntrypoint {
                static {System.setProperty("wtl.installation.executed","initialized");}
                public Mod() {}
                public void start(ModContext context) {}
                public void stop(StopContext context) {}
            }
            """);
        var cp = Path.of(ModEntrypoint.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21",
            "-classpath", cp.toString(), "-d", root.toString(), source.toString()));
        var bytes = new ByteArrayOutputStream();
        try (var jar = new ZipOutputStream(bytes)) {
            jar.putNextEntry(new ZipEntry("sample/bundle/Mod.class"));
            jar.write(Files.readAllBytes(root.resolve("sample/bundle/Mod.class")));
            jar.closeEntry();
        }
        mainJar = bytes.toByteArray();
        Files.createDirectory(root.resolve("mod-zip"));
    }

    @AfterEach
    void noAuthorExecution() {
        assertNull(System.getProperty(MARK));
        System.clearProperty(MARK);
    }

    PlatformRuntime runtime() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.started();
        return runtime;
    }

    PlatformModInstallation installer(PlatformRuntime runtime) throws Exception {
        return new PlatformModInstallation(runtime.persistence().database(), runtime.security(), root, runtime.logs(),
            gold.debug.windowstolinux.app.mod.version.SemanticVersion.parse("1.0.0-dev.1"));
    }

    Path original(String filename, String version, String readme) throws Exception {
        var values = new LinkedHashMap<String, byte[]>();
        values.put("mod.json", manifest(OWNER, version, Map.of(), null, List.of()).getBytes(StandardCharsets.UTF_8));
        values.put("README.md", readme.getBytes(StandardCharsets.UTF_8));
        values.put("LICENSE", "MIT".getBytes(StandardCharsets.UTF_8));
        values.put("main/fixture.jar", mainJar);
        Path file = root.resolve("mod-zip").resolve(filename);
        try (var zip = new ZipArchiveOutputStream(file)) {
            zip.setMethod(ZipEntry.STORED);
            for (var entry : values.entrySet()) {
                var crc = new CRC32();
                crc.update(entry.getValue());
                var member = new ZipArchiveEntry(entry.getKey());
                member.setSize(entry.getValue().length);
                member.setCrc(crc.getValue());
                zip.putArchiveEntry(member);
                zip.write(entry.getValue());
                zip.closeArchiveEntry();
            }
        }
        return file;
    }

    Path target(String version) {
        return root.resolve("mod").resolve(OWNER).resolve(version);
    }

    static void trap(PlatformRuntime runtime, String expression) throws Exception {
        sql(runtime.persistence().database(),
            "ALTER TABLE mod_operation ADD CONSTRAINT install_test_trap CHECK(" + expression + ")");
    }

    static void untrap(PlatformRuntime runtime) throws Exception {
        sql(runtime.persistence().database(), "ALTER TABLE mod_operation DROP CONSTRAINT install_test_trap");
    }

    static String phase(PlatformRuntime runtime) throws Exception {
        return text(runtime, "SELECT phase FROM mod_operation WHERE operation_type='INSTALL'");
    }

    static void copied(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path))
                    Files.createDirectory(destination);
                else
                    Files.copy(path, destination);
            }
        }
    }

    @Test
    void reviewHasNoFilesystemEffectsAndInstallRegistersWithoutActivation() throws Exception {
        var original = original("用户原包.wtlmod", "1.0.0", "内容");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            var review = installer.review(original);
            assertTrue(review.keepOriginal());
            assertFalse(Files.exists(root.resolve(".mod-staging")));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            var result = installer.install(review);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
            assertEquals("RETAINED", result.value().orElseThrow().originalCleanup());
            assertTrue(Files.exists(original));
            ModPackageReader.verifyDirectory(ModPackageReader.read(review.packageInfo().archive()), target("1.0.0"));
            assertFalse(Files.exists(root.resolve(".mod-staging").resolve(review.id().toString())));
            assertEquals("FALSE/1.0.0/TRUE/0/STOPPED", text(runtime,
                "SELECT enabled || '/' || selected_version || '/' || selection_initialized || '/' || revision || '/' || last_runtime_state FROM mod_registry"));
            assertEquals("LOCAL", text(runtime, "SELECT origin_kind FROM mod_version"));
            assertEquals(review.packageInfo().contentSha256(), text(runtime, "SELECT content_sha256 FROM mod_version"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(result.value(), installer.reconcile(review.id()).value());
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_INSTALLED'"));
            assertThrows(IllegalArgumentException.class, () -> installer.install(review));
            assertThrows(IllegalStateException.class, runtime::modInstallation);
        }
    }

    @Test
    void extraReleasePreservesSelectionRunningFactsAndRefusesAnyDuplicate() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(original)).outcome());
            sql(runtime.persistence().database(),
                "UPDATE mod_registry SET enabled=TRUE,revision=7,last_boot_id=?,last_runtime_state='RUNNING',actual_version='1.0.0',failure_code='preserve'",
                runtime.persistence().database().bootId());
            var second = original("two.wtlmod", "2.0.0", "two");
            assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(second)).outcome());
            assertEquals("TRUE/1.0.0/7/RUNNING/1.0.0/preserve", text(runtime,
                "SELECT enabled || '/' || selected_version || '/' || revision || '/' || last_runtime_state || '/' || actual_version || '/' || failure_code FROM mod_registry"));
            assertThrows(SQLException.class, () -> installer.review(original));
            var different = original("different.wtlmod", "1.0.0", "changed");
            assertThrows(SQLException.class, () -> installer.review(different));
            assertEquals("one", Files.readString(target("1.0.0").resolve("README.md")));
        }
    }

    @Test
    void reviewBindsNativeIdentityBytesAuthorizationAndOriginalService() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime); var other = installer(runtime)) {
            var review = installer.review(original);
            assertThrows(IllegalArgumentException.class, () -> other.install(review));
            var moved = original.resolveSibling("saved.wtlmod");
            Files.move(original, moved);
            Files.copy(moved, original);
            assertThrows(IOException.class, () -> installer.install(review));
            var fresh = installer.review(original);
            original("one.wtlmod", "1.0.0", "changed");
            assertThrows(IOException.class, () -> installer.install(fresh));
            runtime.security().lock();
            assertThrows(gold.debug.windowstolinux.app.service.error.ApplicationServiceException.class,
                () -> installer.review(original));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        }
    }

    @Test
    void retentionIsCapturedAndDeleteRunsOnlyAfterSuccessfulRegistration() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            var review = installer.review(original);
            installer.keepOriginal(false);
            assertEquals("RETAINED", installer.install(review).value().orElseThrow().originalCleanup());
            var second = original("two.wtlmod", "2.0.0", "two");
            assertEquals("DELETED",
                installer.install(installer.review(second)).value().orElseThrow().originalCleanup());
            assertTrue(Files.exists(original));
            assertFalse(Files.exists(second));
        }
        try (var runtime = runtime(); var installer = installer(runtime)) {
            assertFalse(installer.keepOriginal());
        }
    }

    @Test
    void publishedButUnregisteredRecoversAcrossBootWithoutOriginalArchive() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        UUID operation;
        String identity;
        try (var runtime = runtime(); var installer = installer(runtime)) {
            installer.keepOriginal(false);
            sql(runtime.persistence().database(),
                "ALTER TABLE mod_version ADD CONSTRAINT installation_register_trap CHECK(origin_kind <> 'LOCAL')");
            var review = installer.review(original);
            operation = review.id();
            assertEquals(TaskResult.UNKNOWN, installer.install(review).outcome());
            assertEquals("FILES_PUBLISHED", phase(runtime));
            identity = WindowsFileIdentity.read(target("1.0.0"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
            assertTrue(Files.exists(original));
            sql(runtime.persistence().database(), "UPDATE mod_operation SET state='RUNNING'");
            sql(runtime.persistence().database(), "ALTER TABLE mod_version DROP CONSTRAINT installation_register_trap");
        }
        Files.delete(original);
        try (var runtime = runtime(); var installer = installer(runtime)) {
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_operation"));
            var result = installer.reconcile(operation);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
            assertEquals("ABSENT", result.value().orElseThrow().originalCleanup());
            assertEquals(identity, WindowsFileIdentity.read(target("1.0.0")));
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
        }
    }

    @Test
    void nativeRenameBeforeCheckpointIsRecoveredButSameContentReplacementIsRefused() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            trap(runtime, "phase <> 'FILES_PUBLISHED'");
            var review = installer.review(original);
            assertEquals(TaskResult.UNKNOWN, installer.install(review).outcome());
            assertEquals("STAGED", phase(runtime));
            var genuine = root.resolve("genuine");
            Files.move(target("1.0.0"), genuine);
            copied(genuine, target("1.0.0"));
            assertNotEquals(WindowsFileIdentity.read(genuine), WindowsFileIdentity.read(target("1.0.0")));
            untrap(runtime);
            assertEquals(TaskResult.UNKNOWN, installer.reconcile(review.id()).outcome());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
            Files.move(target("1.0.0"), root.resolve("foreign-copy"));
            Files.move(genuine, target("1.0.0"));
            Files.delete(original);
            var recovered = installer.reconcile(review.id());
            assertEquals(TaskResult.SUCCEEDED, recovered.outcome());
            assertEquals("ABSENT", recovered.value().orElseThrow().originalCleanup());
            assertTrue(Files.exists(root.resolve("foreign-copy/README.md")));
        }
    }

    @Test
    void originalDeleteSharingFailureIsAWarningNotInstallationFailure() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            installer.keepOriginal(false);
            var review = installer.review(original);
            var handle = Kernel32.INSTANCE.CreateFile(original.toString(), WinNT.GENERIC_READ,
                WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0, null);
            assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
            try {
                assertThrows(IOException.class, () -> Files.delete(original));
                var result = installer.install(review);
                assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                assertEquals("RETAINED_UNCONFIRMED", result.value().orElseThrow().originalCleanup());
                assertTrue(Files.exists(original));
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
                assertEquals(result.value(), installer.reconcile(review.id()).value());
            } finally {
                assertTrue(Kernel32.INSTANCE.CloseHandle(handle));
            }
            Files.move(original, original.resolveSibling("released.wtlmod"));
        }
    }

    @Test
    void archiveCleanupAndArchiveCommitInterruptionsDoNotRepeatRegistration() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            installer.keepOriginal(false);
            trap(runtime, "phase <> 'CLEANUP'");
            var review = installer.review(original);
            assertEquals(TaskResult.UNKNOWN, installer.install(review).outcome());
            assertEquals("REGISTERED", phase(runtime));
            assertFalse(Files.exists(original));
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
            untrap(runtime);
            sql(runtime.persistence().database(),
                "ALTER TABLE runtime_log ADD CONSTRAINT installation_archive_trap CHECK(event_code <> 'MOD_INSTALLED')");
            assertEquals(TaskResult.UNKNOWN, installer.reconcile(review.id()).outcome());
            assertEquals("CLEANUP", phase(runtime));
            assertFalse(Files.exists(root.resolve(".mod-staging").resolve(review.id().toString())));
            sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT installation_archive_trap");
            assertEquals(TaskResult.SUCCEEDED, installer.reconcile(review.id()).outcome());
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_INSTALLED'"));
        }
    }

    @Test
    void fullStagingRecoversAndNativePublicationNeverReplacesAnExistingTarget() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            trap(runtime, "phase <> 'STAGED'");
            var review = installer.review(original);
            assertEquals(TaskResult.UNKNOWN, installer.install(review).outcome());
            assertEquals("VALIDATED", phase(runtime));
            var program = root.resolve(".mod-staging").resolve(review.id().toString()).resolve("program");
            String originalIdentity = WindowsFileIdentity.read(program);
            Files.createDirectories(target("1.0.0"));
            Files.writeString(target("1.0.0").resolve("keep.txt"), "foreign");
            untrap(runtime);
            assertEquals(TaskResult.UNKNOWN, installer.reconcile(review.id()).outcome());
            assertEquals("foreign", Files.readString(target("1.0.0").resolve("keep.txt")));
            assertEquals(originalIdentity, WindowsFileIdentity.read(program));
            Files.move(target("1.0.0"), root.resolve("foreign"));
            assertEquals(TaskResult.SUCCEEDED, installer.reconcile(review.id()).outcome());
            assertEquals(originalIdentity, WindowsFileIdentity.read(target("1.0.0")));
        }
    }

    @Test
    void changedPublishedBytesAndUnownedStagingAreKeptForExplicitResolution() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            trap(runtime, "phase <> 'FILES_PUBLISHED'");
            var review = installer.review(original);
            assertEquals(TaskResult.UNKNOWN, installer.install(review).outcome());
            untrap(runtime);
            Files.writeString(target("1.0.0").resolve("README.md"), "changed");
            assertEquals(TaskResult.UNKNOWN, installer.reconcile(review.id()).outcome());
            assertEquals("changed", Files.readString(target("1.0.0").resolve("README.md")));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
            assertThrows(SQLException.class, () -> installer.review(original));
            Files.writeString(target("1.0.0").resolve("README.md"), "one");
            var stage = root.resolve(".mod-staging").resolve(review.id().toString());
            Files.writeString(stage.resolve("keep.txt"), "unowned");
            assertEquals(TaskResult.UNKNOWN, installer.reconcile(review.id()).outcome());
            assertEquals("unowned", Files.readString(stage.resolve("keep.txt")));
            Files.move(stage.resolve("keep.txt"), root.resolve("unowned.txt"));
            assertEquals(TaskResult.SUCCEEDED, installer.reconcile(review.id()).outcome());
        }
    }

    @Test
    void missingStagingOwnershipCheckpointCannotAdoptAnExistingDirectory() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            trap(runtime, "recovery_json LIKE '%\"root\":null%'");
            var review = installer.review(original);
            assertEquals(TaskResult.UNKNOWN, installer.install(review).outcome());
            var stage = root.resolve(".mod-staging").resolve(review.id().toString());
            assertTrue(Files.isDirectory(stage));
            Files.writeString(stage.resolve("keep.txt"), "retained");
            untrap(runtime);
            assertEquals(TaskResult.UNKNOWN, installer.reconcile(review.id()).outcome());
            assertEquals("retained", Files.readString(stage.resolve("keep.txt")));
            assertFalse(Files.exists(target("1.0.0")));
        }
    }

    @Test
    void partialStagingIsNotOverwrittenOrPublishedAndCompetingReviewCannotEnter() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime); var other = installer(runtime)) {
            var competing = other.review(original);
            trap(runtime, "phase <> 'STAGED'");
            var review = installer.review(original);
            assertEquals(TaskResult.UNKNOWN, installer.install(review).outcome());
            assertThrows(SQLException.class, () -> other.install(competing));
            var program = root.resolve(".mod-staging").resolve(review.id().toString()).resolve("program");
            Files.delete(program.resolve("LICENSE"));
            String identity = WindowsFileIdentity.read(program);
            untrap(runtime);
            assertEquals(TaskResult.UNKNOWN, installer.reconcile(review.id()).outcome());
            assertFalse(Files.exists(program.resolve("LICENSE")));
            assertFalse(Files.exists(target("1.0.0")));
            assertEquals(identity, WindowsFileIdentity.read(program));
            Files.writeString(program.resolve("LICENSE"), "MIT");
            assertEquals(TaskResult.SUCCEEDED, installer.reconcile(review.id()).outcome());
        }
    }

    @Test
    void targetCaseAliasesAndJunctionsAreRejectedBeforeAdmission() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            Files.createDirectories(root.resolve("mod/SAMPLE.bundle"));
            assertThrows(SQLException.class, () -> installer.review(original));
            assertFalse(Files.exists(root.resolve(".mod-staging")));
            Files.move(root.resolve("mod/SAMPLE.bundle"), root.resolve("other-owner"));
            var external = root.resolve("external");
            Files.createDirectory(external);
            var junction = root.resolve("mod").resolve(OWNER);
            var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), external.toString())
                .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), output);
            try {
                assertThrows(SQLException.class, () -> installer.review(original));
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
                try (var children = Files.list(external)) {
                    assertEquals(0, children.count());
                }
            } finally {
                Files.delete(junction);
            }
        }
    }

    @Test
    void scanIncludesInvalidAndInstalledOriginalsWithoutInstallingOrRecursing() throws Exception {
        try (var runtime = runtime(); var installer = installer(runtime)) {
            Files.delete(root.resolve("mod-zip"));
            assertTrue(installer.scan().originals().isEmpty());
            assertFalse(Files.exists(root.resolve("mod-zip")));
            Files.createDirectory(root.resolve("mod-zip"));
            var original = original("one.wtlmod", "1.0.0", "one");
            Files.writeString(root.resolve("mod-zip/broken.wtlmod"), "incomplete");
            Files.createDirectory(root.resolve("mod-zip/nested"));
            Files.copy(original, root.resolve("mod-zip/nested/ignored.wtlmod"));
            var first = installer.scan();
            assertEquals(List.of("INVALID", "AVAILABLE"),
                first.originals().stream().map(PlatformModInstallation.OriginalView::state).toList());
            assertTrue(first.originals().getFirst().deletable());
            assertTrue(first.originals().getFirst().problem().isPresent());
            assertFalse(Files.exists(root.resolve(".mod-staging")));
            assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(original)).outcome());
            var different = original("different.wtlmod", "1.0.0", "changed");
            var after = installer.scan();
            assertEquals(List.of("INVALID", "CONFLICT", "INSTALLED"),
                after.originals().stream().map(PlatformModInstallation.OriginalView::state).toList());
            assertTrue(after.originals().stream().anyMatch(v -> v.file().equals(different)));
        }
    }

    @Test
    void manualOriginalDeletionDoesNotAffectInstalledCodeAndRejectsChangedReview() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime); var other = installer(runtime)) {
            assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(original)).outcome());
            var review = installer.reviewDelete(original);
            assertThrows(IllegalArgumentException.class, () -> other.deleteOriginal(review));
            var saved = root.resolve("saved.wtlmod");
            Files.move(original, saved);
            Files.copy(saved, original);
            assertThrows(IOException.class, () -> installer.deleteOriginal(review));
            var fresh = installer.reviewDelete(original);
            installer.deleteOriginal(fresh);
            assertFalse(Files.exists(original));
            assertTrue(Files.exists(saved));
            assertEquals("one", Files.readString(target("1.0.0").resolve("README.md")));
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
            assertThrows(IllegalArgumentException.class, () -> installer.deleteOriginal(fresh));
            var broken = root.resolve("mod-zip/broken.wtlmod");
            Files.writeString(broken, "broken");
            var stale = installer.reviewDelete(broken);
            Files.writeString(broken, "new unrelated contents");
            assertThrows(IOException.class, () -> installer.deleteOriginal(stale));
            installer.deleteOriginal(installer.reviewDelete(broken));
            assertFalse(Files.exists(broken));
        }
    }

    @Test
    void rawDeletionRespectsActualSourceIdentityAndUnknownMaintenanceRemainsVisible() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            var deletion = installer.reviewDelete(original);
            trap(runtime, "phase <> 'STAGED'");
            var review = installer.review(original);
            assertEquals(TaskResult.UNKNOWN, installer.install(review).outcome());
            assertThrows(SQLException.class, () -> installer.deleteOriginal(deletion));
            var linked = root.resolve("mod-zip/linked.wtlmod");
            Files.createLink(linked, original);
            assertThrows(SQLException.class, () -> installer.reviewDelete(linked));
            var snapshot = installer.scan();
            assertEquals(1, snapshot.pending().size());
            assertTrue(snapshot.originals().stream().noneMatch(PlatformModInstallation.OriginalView::deletable));
            assertEquals(review.id(), snapshot.pending().getFirst().id());
            var broken = root.resolve("mod-zip/broken.wtlmod");
            Files.writeString(broken, "broken");
            assertNotNull(installer.reviewDelete(broken));
            String json = text(runtime, "SELECT request_json FROM mod_operation");
            sql(runtime.persistence().database(), "UPDATE mod_operation SET request_json='{}'");
            var unknown = installer.scan();
            assertEquals(review.id(), unknown.pending().getFirst().id());
            assertTrue(unknown.pending().getFirst().original().isEmpty());
            assertTrue(unknown.originals().stream().noneMatch(PlatformModInstallation.OriginalView::deletable));
            assertThrows(SQLException.class, () -> installer.reviewDelete(broken));
            sql(runtime.persistence().database(), "UPDATE mod_operation SET request_json=?", json);
            untrap(runtime);
            assertEquals(TaskResult.SUCCEEDED, installer.reconcile(review.id()).outcome());
        }
    }

    @Test
    void rejectedInitialLedgerWriteNeverCreatesStagingOrReusesTheReview() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            var review = installer.review(original);
            trap(runtime, "operation_type <> 'INSTALL'");
            assertThrows(SQLException.class, () -> installer.install(review));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertFalse(Files.exists(root.resolve(".mod-staging")));
            assertTrue(Files.exists(original));
            untrap(runtime);
            assertThrows(IllegalArgumentException.class, () -> installer.install(review));
            assertThrows(SQLException.class, () -> installer.reconcile(review.id()));
            assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(original)).outcome());
        }
    }

    @Test
    void registrationDriftIsNotCalledInstalledAndIncompleteDurableEvidenceIsNeverDefaulted() throws Exception {
        var original = original("one.wtlmod", "1.0.0", "one");
        try (var runtime = runtime(); var installer = installer(runtime)) {
            trap(runtime, "phase <> 'CLEANUP'");
            var review = installer.review(original);
            assertEquals(TaskResult.UNKNOWN, installer.install(review).outcome());
            assertEquals("REGISTERED", phase(runtime));
            untrap(runtime);
            sql(runtime.persistence().database(), "UPDATE mod_version SET archive_sha256=?", "0".repeat(64));
            assertEquals(TaskResult.UNKNOWN, installer.reconcile(review.id()).outcome());
            assertTrue(Files.exists(original));
            sql(runtime.persistence().database(), "UPDATE mod_version SET archive_sha256=?",
                review.packageInfo().archiveSha256());
            String request = text(runtime, "SELECT request_json FROM mod_operation");
            var damaged = JSON.readTree(request);
            ((com.fasterxml.jackson.databind.node.ObjectNode) damaged).remove("keep");
            sql(runtime.persistence().database(), "UPDATE mod_operation SET request_json=?", damaged.toString());
            assertThrows(SQLException.class, () -> installer.reconcile(review.id()));
            assertTrue(Files.exists(original));
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_operation"));
            sql(runtime.persistence().database(), "UPDATE mod_operation SET request_json=?", request);
            assertEquals(TaskResult.SUCCEEDED, installer.reconcile(review.id()).outcome());
        }
    }
}
