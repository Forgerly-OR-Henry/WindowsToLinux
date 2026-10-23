package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModFlowTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.choice;
import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.text;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.main.mod.PlatformModFlow;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlatformInstalledModGraphTest {
    @TempDir
    Path root;

    PlatformModFlowTest fixture;

    Path home;

    @BeforeEach
    void setup() throws Exception {
        fixture = new PlatformModFlowTest();
        fixture.root = root;
        fixture.workspace();
        home = Files.createDirectories(root.resolve("安装 程序"));
        Files.createDirectory(home.resolve("mod-zip"));
    }

    PlatformModInstallation installer(PlatformRuntime runtime) throws Exception {
        return new PlatformModInstallation(runtime.persistence().database(), runtime.security(), home, runtime.logs(),
            SemanticVersion.parse("1.0.0-dev.1"));
    }

    Path archive(String owner, String version) throws Exception {
        Path module = fixture.workspace.resolve("src/mod/" + owner), classes = module.resolve("target/classes");
        var bytes = new ByteArrayOutputStream();
        try (var jar = new ZipOutputStream(bytes); var paths = Files.walk(classes)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new ZipEntry(classes.relativize(path).toString().replace('\\', '/')));
                jar.write(Files.readAllBytes(path));
                jar.closeEntry();
            }
        }
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper()
            .readTree(Files.readString(module.resolve("mod.json")));
        manifest.put("version", version);
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("mod.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        entries.put("README.md", "本机安装图夹具".getBytes(StandardCharsets.UTF_8));
        entries.put("LICENSE", "MIT".getBytes(StandardCharsets.UTF_8));
        entries.put(manifest.path("mainJar").asText(), bytes.toByteArray());
        Path archive = home.resolve("mod-zip/" + owner + "-" + version + ".wtlmod");
        try (var zip = new ZipArchiveOutputStream(archive)) {
            zip.setMethod(ZipEntry.STORED);
            for (var entry : entries.entrySet()) {
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
        return archive;
    }

    PlatformModFlow.Report start(PlatformRuntime runtime, boolean app) {
        return ApplicationLauncher.startMods(runtime,
            new RunModeResolver.RuntimeLayout(
                app ? RunModeResolver.RunMode.RUN_APP : RunModeResolver.RunMode.RUN_JAR, home,
                runtime.persistence().database().root()),
            fixture.pages(!runtime.security().securityState().unlocked()));
    }

    void installAndEnable(String... owners) throws Exception {
        try (var runtime = fixture.open(true); var installer = installer(runtime)) {
            for (var owner : owners) {
                var original = archive(owner, VERSION);
                assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(original)).outcome());
                Files.delete(original);
            }
            var report = start(runtime, false);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertTrue(report.running().isEmpty());
            assertTrue(fixture.actions().isEmpty());
            for (var owner : owners)
                runtime.modIntents().apply(runtime.modIntents().review(owner, choice(true, VERSION), Optional.empty()),
                    false);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void installedGraphStartsWithoutOriginalOrWorkspaceAndStopsInReverseOrder(boolean app) throws Exception {
        fixture.module(P, Map.of(), false);
        fixture.module(C, Map.of(P, VERSION), false);
        fixture.module(G, Map.of(C, VERSION), false);
        installAndEnable(P, C, G);
        try (var runtime = fixture.open(false)) {
            var report = start(runtime, app);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(Set.of(P, C, G), report.running());
            assertEquals(List.of(P + ":construct", P + ":start", C + ":construct", C + ":start", G + ":construct",
                G + ":start"), fixture.actions());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertFalse(start(runtime, app).problems().isEmpty());
        }
        assertEquals(List.of(G + ":stop", C + ":stop", P + ":stop"), fixture.actions().subList(6, 9));
        Path jar = home.resolve("mod/" + P + "/" + VERSION + "/main/fixture.jar");
        Files.delete(jar);
        assertFalse(Files.exists(jar));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes", "extra", "path", "origin", "missing", "state"})
    void alteredSelectedProgramNeverExecutesAndIndependentModStillRuns(String alteration) throws Exception {
        fixture.module(P, Map.of(), false);
        fixture.module(F, Map.of(), false);
        installAndEnable(P, F);
        Path program = home.resolve("mod/" + P + "/" + VERSION);
        if (alteration.equals("bytes"))
            Files.writeString(program.resolve("README.md"), "changed");
        if (alteration.equals("extra"))
            Files.writeString(program.resolve("extra.txt"), "unexpected");
        if (alteration.equals("missing"))
            Files.delete(program.resolve("main/fixture.jar"));
        try (var runtime = fixture.open(false)) {
            if (alteration.equals("path"))
                sql(runtime.persistence().database(), "UPDATE mod_version SET install_path=? WHERE mod_id=?",
                    program.resolve("..").toString(), P);
            if (alteration.equals("origin"))
                sql(runtime.persistence().database(), "UPDATE mod_version SET origin_kind='CLASS' WHERE mod_id=?", P);
            if (alteration.equals("state"))
                sql(runtime.persistence().database(), "UPDATE mod_version SET state='REINSTALLING' WHERE mod_id=?", P);
            var report = start(runtime, false);
            assertEquals(Set.of(F), report.running());
            assertFalse(report.problems().isEmpty());
            assertEquals(alteration.equals("state") ? "STOPPED" : "FAILED", state(runtime, P));
            if (alteration.equals("state"))
                assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM mod_operation WHERE mod_id='" + P + "'"));
            assertFalse(fixture.actions().stream().anyMatch(s -> s.startsWith(P)));
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry WHERE mod_id='" + P + "'"));
        }
    }

    @Test
    void savedOlderVersionRunsAndExplicitDisableRestartAllowsActualDeletion() throws Exception {
        fixture.module(P, Map.of(), false);
        installAndEnable(P);
        try (var runtime = fixture.open(true); var installer = installer(runtime)) {
            assertEquals(Set.of(P), start(runtime, false).running());
            assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(archive(P, "2.0.0"))).outcome());
            assertEquals(VERSION, text(runtime, "SELECT actual_version FROM mod_registry"));
            var task = runtime.tasks().bind(P, VERSION, false).submit(request(), context -> success("completed"));
            assertEquals("completed", task.completion().toCompletableFuture()
                .get(8, java.util.concurrent.TimeUnit.SECONDS).value().orElseThrow());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task' AND subject_mod_id='" + P + "'"));
            runtime.modIntents().apply(runtime.modIntents().review(P, choice(false, VERSION), Optional.empty()), false);
            assertFalse(runtime.modIntents().list().getFirst().stopAcknowledged());
        }
        try (var runtime = fixture.open(true); var installer = installer(runtime)) {
            var report = start(runtime, false);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertTrue(report.running().isEmpty());
            assertTrue(runtime.modIntents().list().getFirst().stopAcknowledged());
            assertEquals(TaskResult.SUCCEEDED,
                installer.deletion().delete(installer.deletion().review(P, VERSION), false).outcome());
            assertFalse(Files.exists(home.resolve("mod/" + P + "/" + VERSION)));
            assertTrue(Files.exists(home.resolve("mod/" + P + "/2.0.0/main/fixture.jar")));
        }
    }

    @Test
    void installedSchemaUsesFreshPreparationLoaderAndReusesItsReadyGeneration() throws Exception {
        var schema = new PlatformModInitializationTest();
        schema.root = root;
        schema.fixture = fixture;
        schema.module("valid", false);
        installAndEnable(P);
        String generation;
        try (var runtime = fixture.open(false)) {
            var report = start(runtime, false);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(Set.of(P), report.running());
            assertEquals(List.of("schema:initialize", "schema:validate", P + ":construct", P + ":start"),
                fixture.actions());
            generation = text(runtime, "SELECT generation FROM mod_schema");
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
        }
        try (var runtime = fixture.open(false)) {
            assertEquals(Set.of(P), start(runtime, true).running());
            assertEquals(generation, text(runtime, "SELECT generation FROM mod_schema"));
        }
        assertEquals(1, fixture.actions().stream().filter("schema:initialize"::equals).count());
    }

    @Test
    void failedProviderDoesNotConstructConsumerAndUnregisteredDirectoryIsNotAdopted() throws Exception {
        fixture.module(P, Map.of(), true);
        fixture.module(C, Map.of(P, VERSION), false);
        fixture.module(F, Map.of(), false);
        installAndEnable(P, C, F);
        Files.createDirectories(home.resolve("mod/unregistered/1.0.0"));
        try (var runtime = fixture.open(false)) {
            var report = start(runtime, false);
            assertEquals(Set.of(F), report.running());
            assertFalse(fixture.actions().stream().anyMatch(s -> s.startsWith(C)));
            assertEquals(3, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
        }
    }

    @Test
    void neverEnabledInstallationNeedsNextBootObservationBeforeMaintenance() throws Exception {
        fixture.module(P, Map.of(), false);
        try (var runtime = fixture.open(true); var installer = installer(runtime)) {
            assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(archive(P, VERSION))).outcome());
            assertTrue(start(runtime, false).running().isEmpty());
            assertFalse(runtime.modIntents().list().getFirst().stopAcknowledged());
            assertThrows(Exception.class, () -> installer.deletion().review(P, VERSION));
        }
        try (var runtime = fixture.open(true); var installer = installer(runtime)) {
            var report = start(runtime, false);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertTrue(runtime.modIntents().list().getFirst().stopAcknowledged());
            assertEquals(TaskResult.SUCCEEDED,
                installer.deletion().delete(installer.deletion().review(P, VERSION), false).outcome());
        }
        assertTrue(fixture.actions().isEmpty());
    }

    @Test
    void installedFailedDataRetryUsesOriginalProgramWithoutAutomaticStartup() throws Exception {
        var schema = new PlatformModInitializationTest();
        schema.root = root;
        schema.fixture = fixture;
        schema.module("invalid", false);
        installAndEnable(P);
        try (var runtime = fixture.open(true)) {
            assertTrue(start(runtime, false).running().isEmpty());
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema"));
            var review = runtime.modRecovery().review(P, Optional.empty());
            assertEquals(TaskResult.FAILED, runtime.modRecovery().retry(review));
            assertFalse(fixture.actions().stream().anyMatch(a -> a.endsWith(":construct")));
        }
        assertEquals(2, fixture.actions().stream().filter("schema:initialize"::equals).count());
        try (var runtime = fixture.open(false)) {
            assertTrue(start(runtime, false).running().isEmpty());
        }
        assertEquals(2, fixture.actions().stream().filter("schema:initialize"::equals).count());
    }

    @Test
    void changedDisabledRevisionCannotUseTheOldBootSnapshot() throws Exception {
        fixture.module(P, Map.of(), false);
        try (var runtime = fixture.open(true); var installer = installer(runtime)) {
            assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(archive(P, VERSION))).outcome());
        }
        try (var runtime = fixture.open(true)) {
            sql(runtime.persistence().database(), "UPDATE mod_registry SET revision=revision+1 WHERE mod_id=?", P);
            var report = start(runtime, false);
            assertFalse(report.problems().isEmpty());
            assertFalse(runtime.modIntents().list().getFirst().stopAcknowledged());
        }
        assertTrue(fixture.actions().isEmpty());
    }

    @Test
    void reinstalledOwnerCannotBorrowTheDeletedOwnersDisabledSnapshot() throws Exception {
        fixture.module(P, Map.of(), false);
        Path original = archive(P, VERSION);
        try (var runtime = fixture.open(true); var installer = installer(runtime)) {
            assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(original)).outcome());
        }
        try (var runtime = fixture.open(true)) {
            assertTrue(start(runtime, false).problems().isEmpty());
        }
        try (var runtime = fixture.open(true); var installer = installer(runtime)) {
            assertEquals(TaskResult.SUCCEEDED,
                installer.deletion().delete(installer.deletion().review(P), false).outcome());
            assertEquals(TaskResult.SUCCEEDED, installer.install(installer.review(original)).outcome());
            assertFalse(start(runtime, false).problems().isEmpty());
            assertFalse(runtime.modIntents().list().getFirst().stopAcknowledged());
        }
        assertTrue(fixture.actions().isEmpty());
    }
}
