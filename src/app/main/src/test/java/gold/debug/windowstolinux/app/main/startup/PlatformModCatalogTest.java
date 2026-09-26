package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.mod.development.DevelopmentDescriptor;
import gold.debug.windowstolinux.app.service.mod.PlatformModCatalog;
import gold.debug.windowstolinux.appapi.task.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModCatalogTest {
    @TempDir
    Path root;

    Path workspace() throws Exception {
        var helper = new PlatformModContextTest();
        helper.root = root;
        try (var loader = helper.loader(false)) {
            assertFalse(loader.isClosed());
        }
        try (var paths = Files.list(root)) {
            return paths.filter(p -> p.getFileName().toString().startsWith("workspace-")).findFirst().orElseThrow();
        }
    }

    PlatformRuntime runtime() throws Exception {
        var helper = new PlatformModIntentsTest();
        helper.root = root;
        return helper.runtime();
    }

    static Path module(Path workspace) {
        return workspace.resolve("src/mod/fixture");
    }

    static void generate(Path workspace, Path module) throws IOException {
        DevelopmentDescriptor.generate(workspace, module.resolve("mod.json"), module.resolve("target/classes"),
                List.of(),
                List.of(workspace.resolve("pom.xml"), workspace.resolve("src/mod/pom.xml"), module.resolve("pom.xml")),
                List.of());
    }

    static PlatformModCatalog.Build registered(PlatformModCatalog catalog, Path workspace) throws IOException {
        var result = catalog.refresh(workspace);
        assertTrue(result.rejections().isEmpty(), result.rejections().toString());
        assertEquals(1, result.builds().size());
        return result.builds().getFirst();
    }

    static void resource(Path workspace, String value) throws IOException {
        Files.writeString(module(workspace).resolve("target/classes/local.txt"), value);
    }

    @Test
    void discoveryRegistersDisabledSelectionAndActualBytesAreCheckedBeforeLoading() throws Exception {
        Path workspace = workspace();
        try (var runtime = runtime()) {
            var catalog = new PlatformModCatalog(runtime.persistence().database());
            var original = registered(catalog, workspace);
            assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals(VERSION, text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals("TRUE", text(runtime, "SELECT selection_initialized FROM mod_registry"));
            assertEquals("0", text(runtime, "SELECT revision FROM mod_registry"));
            assertEquals("STOPPED", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(original.contentDigest(), text(runtime, "SELECT content_sha256 FROM mod_version"));
            PlatformModCatalog.verify(workspace, original);
            resource(workspace, "changed actual processed output");
            assertThrows(IOException.class, () -> PlatformModCatalog.verify(workspace, original));
            var next = registered(catalog, workspace);
            assertNotEquals(original.contentDigest(), next.contentDigest());
            assertEquals(original.candidate().descriptor().inputDigest(), next.candidate().descriptor().inputDigest());
            PlatformModCatalog.verify(workspace, next);
            assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals("0", text(runtime, "SELECT revision FROM mod_registry"));
            var classFile = module(workspace).resolve("target/classes/example/admission/Mod.class");
            byte[] bytes = Files.readAllBytes(classFile);
            bytes[bytes.length - 1] ^= 1;
            Files.write(classFile, bytes);
            assertThrows(IOException.class, () -> PlatformModCatalog.verify(workspace, next));
        }
    }

    @Test
    void newVersionDoesNotReplaceExistingSelectionOrRestoreExplicitlyClearedSelection() throws Exception {
        Path workspace = workspace();
        try (var runtime = runtime()) {
            var db = runtime.persistence().database();
            var catalog = new PlatformModCatalog(db);
            registered(catalog, workspace);
            sql(db, "UPDATE mod_registry SET enabled=TRUE,revision=4,last_runtime_state='FAILED',failure_code='ORIGINAL_FAILURE',disable_ack_revision=2,last_boot_id=?",
                    UUID.randomUUID());
            Path manifest = module(workspace).resolve("mod.json");
            Files.writeString(manifest,
                    Files.readString(manifest).replace("\"version\":\"1.0.0\"", "\"version\":\"2.0.0\""));
            generate(workspace, module(workspace));
            assertEquals("2.0.0", registered(catalog, workspace).candidate().manifest().version().toString());
            assertEquals(2, count(db, "SELECT COUNT(*) FROM mod_version"));
            assertEquals(VERSION, text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals("4", text(runtime, "SELECT revision FROM mod_registry"));
            assertEquals("ORIGINAL_FAILURE", text(runtime, "SELECT failure_code FROM mod_registry"));
            assertEquals("2", text(runtime, "SELECT disable_ack_revision FROM mod_registry"));
            sql(db, "UPDATE mod_registry SET enabled=FALSE,selected_version=NULL,revision=5");
            registered(catalog, workspace);
            assertNull(text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals("5", text(runtime, "SELECT revision FROM mod_registry"));
        }
    }

    @Test
    void pendingIntentKeepsItsOriginalContentWhileUnchangedRescanRemainsAvailable() throws Exception {
        Path workspace = workspace();
        try (var runtime = runtime()) {
            var db = runtime.persistence().database();
            var catalog = new PlatformModCatalog(db);
            var original = registered(catalog, workspace);
            var intents = runtime.modIntents();
            var group = intents.apply(intents.review(OWNER, choice(true, VERSION), Optional.empty()), false);
            var operation = text(runtime, "SELECT operation_id FROM mod_operation");
            assertEquals(original.contentDigest(), registered(catalog, workspace).contentDigest());
            resource(workspace, "recompiled while a request is waiting");
            var blocked = catalog.refresh(workspace);
            assertTrue(blocked.builds().isEmpty());
            assertEquals(1, blocked.rejections().size());
            assertEquals("MOD_CATALOG_BUILD_IN_USE", blocked.rejections().getFirst().problem().eventCode());
            assertEquals(original.contentDigest(), text(runtime, "SELECT content_sha256 FROM mod_version"));
            assertEquals(operation, text(runtime, "SELECT operation_id FROM mod_operation"));
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals("1", text(runtime, "SELECT revision FROM mod_registry"));
            intents.apply(intents.reviewCancellation(group), false);
            assertNotEquals(original.contentDigest(), registered(catalog, workspace).contentDigest());
            assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals("2", text(runtime, "SELECT revision FROM mod_registry"));
        }
    }

    @Test
    void retainedRuntimeAndCrossOwnerClaimsPreventReplacement() throws Exception {
        Path workspace = workspace();
        try (var runtime = runtime()) {
            var db = runtime.persistence().database();
            var catalog = new PlatformModCatalog(db);
            var original = registered(catalog, workspace);
            resource(workspace, "new compiled resource");
            sql(db, "UPDATE mod_registry SET last_runtime_state='UNKNOWN',actual_version=?", VERSION);
            assertTrue(catalog.refresh(workspace).builds().isEmpty());
            assertEquals("UNKNOWN", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
            sql(db, "UPDATE mod_registry SET last_runtime_state='STOPPED',actual_version=NULL");
            var tasks = new TaskLedger(db);
            var task = tasks.enqueue(UUID.randomUUID(), "other.mod", VERSION, work(),
                    List.of(new TaskResource("MOD", "other.mod", "other.mod", false),
                            new TaskResource("MOD", OWNER, "other.mod", false)));
            assertTrue(tasks.tryStart(task));
            try {
                assertTrue(catalog.refresh(workspace).builds().isEmpty());
                assertEquals(original.contentDigest(), text(runtime, "SELECT content_sha256 FROM mod_version"));
            } finally {
                assertTrue(tasks.complete(task, Outcome.SUCCEEDED, "{}", false));
            }
            assertNotEquals(original.contentDigest(), registered(catalog, workspace).contentDigest());
        }
    }

    @Test
    void installedOriginCannotBeTakenOverButUnoccupiedDevelopmentSourceCanMove() throws Exception {
        Path workspace = workspace();
        try (var runtime = runtime()) {
            var db = runtime.persistence().database();
            var catalog = new PlatformModCatalog(db);
            var original = registered(catalog, workspace);
            sql(db, "UPDATE mod_version SET origin_kind='LOCAL'");
            assertTrue(catalog.refresh(workspace).builds().isEmpty());
            assertEquals("LOCAL", text(runtime, "SELECT origin_kind FROM mod_version"));
            sql(db, "UPDATE mod_version SET origin_kind='CLASS'");
            Path moved = workspace.resolve("src/mod/renamed");
            assertTrue(module(workspace).startsWith(root.toRealPath()));
            assertTrue(moved.startsWith(root.toRealPath()));
            Files.move(module(workspace), moved);
            generate(workspace, moved);
            var relocated = registered(catalog, workspace);
            assertNotEquals(original.contentDigest(), relocated.contentDigest());
            assertEquals(moved.toString(), text(runtime, "SELECT install_path FROM mod_version"));
            assertEquals(VERSION, text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals("0", text(runtime, "SELECT revision FROM mod_registry"));
        }
    }

    @Test
    void lateWriteFailureRollsBackOwnerAndVersionButIndependentSourceStillRegisters() throws Exception {
        Path workspace = workspace();
        Path independent = workspace.resolve("src/mod/independent");
        try (var paths = Files.walk(module(workspace))) {
            for (var source : paths.toList()) {
                var target = independent.resolve(module(workspace).relativize(source));
                if (Files.isDirectory(source))
                    Files.createDirectories(target);
                else
                    Files.copy(source, target);
            }
        }
        Path manifest = independent.resolve("mod.json");
        Files.writeString(manifest,
                Files.readString(manifest).replace("\"id\":\"" + OWNER + "\"", "\"id\":\"example.independent\""));
        generate(workspace, independent);
        try (var runtime = runtime()) {
            var db = runtime.persistence().database();
            sql(db, "ALTER TABLE mod_version ADD CONSTRAINT fixture_catalog CHECK(mod_id<>'" + OWNER + "')");
            var catalog = new PlatformModCatalog(db);
            var result = catalog.refresh(workspace);
            assertEquals(1, result.builds().size());
            assertEquals("example.independent", result.builds().getFirst().candidate().manifest().id());
            assertEquals(1, result.rejections().size());
            assertEquals(gold.debug.windowstolinux.appapi.task.ProblemCode.UNKNOWN,
                    result.rejections().getFirst().problem().code());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_registry WHERE mod_id='" + OWNER + "'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mod_version"));
            sql(db, "ALTER TABLE mod_version DROP CONSTRAINT fixture_catalog");
            assertEquals(2, catalog.refresh(workspace).builds().size());
            assertEquals(2, count(db, "SELECT COUNT(*) FROM mod_registry WHERE enabled=FALSE"));
        }
    }

    @Test
    void duplicateUncompiledSourceRejectsEveryCandidateWithoutChangingSavedChoice() throws Exception {
        Path workspace = workspace();
        try (var runtime = runtime()) {
            var catalog = new PlatformModCatalog(runtime.persistence().database());
            var original = registered(catalog, workspace);
            Path duplicate = Files.createDirectories(workspace.resolve("src/mod/duplicate"));
            Files.copy(module(workspace).resolve("mod.json"), duplicate.resolve("mod.json"));
            var result = catalog.refresh(workspace);
            assertTrue(result.builds().isEmpty());
            assertEquals(2, result.rejections().size());
            assertEquals(original.contentDigest(), text(runtime, "SELECT content_sha256 FROM mod_version"));
            assertEquals(VERSION, text(runtime, "SELECT selected_version FROM mod_registry"));
        }
    }

    @Test
    void discoveredBuildCompletesRealRestartActivationWhileLockedBusinessRemainsBlocked() throws Exception {
        Path workspace = workspace();
        UUID operation;
        try (var runtime = runtime()) {
            registered(new PlatformModCatalog(runtime.persistence().database()), workspace);
            runtime.modIntents().apply(runtime.modIntents().review(OWNER, choice(true, VERSION), Optional.empty()),
                    false);
            operation = UUID.fromString(text(runtime, "SELECT operation_id FROM mod_operation"));
        }
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            runtime.started();
            assertFalse(runtime.security().securityState().unlocked());
            var build = registered(new PlatformModCatalog(runtime.persistence().database()), workspace);
            PlatformModCatalog.verify(workspace, build);
            var ledger = new gold.debug.windowstolinux.app.db.mod.ModRestartLedger(runtime.persistence().database());
            var prepared = ledger.begin(operation);
            var api = gold.debug.windowstolinux.app.mod.version.SemanticVersion.parse("1.0.0-dev.1");
            var plan = gold.debug.windowstolinux.app.mod.lifecycle.ModDependencyPlan
                    .resolve(List.of(build.candidate().manifest()), api, Runtime.version().feature());
            var urls = new ArrayList<java.net.URL>();
            for (var source : build.candidate().descriptor().sources())
                urls.add(source.toUri().toURL());
            try (var loader = plan.createLoader(OWNER, urls, Map.of())) {
                runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
                runtime.setSourcePicker((title, task, cancel) -> root);
                var pages = new gold.debug.windowstolinux.app.ui.shell.ModPageRegistry(
                        new PlatformModContextTest.Host(), new gold.debug.windowstolinux.appapi.ui.UiEnvironment(
                                Locale.SIMPLIFIED_CHINESE, "LIGHT", true));
                var session = runtime.modSession(loader, prepared.schema(),
                        gold.debug.windowstolinux.appapi.mod.ModLaunchMode.NORMAL, pages, api);
                var entry = loader.loadClass("example.admission.Mod");
                entry.getFields();
                try {
                    session.start();
                    assertEquals("RUNNING", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
                    assertTrue(ledger.applied(operation));
                    var context = (gold.debug.windowstolinux.appapi.mod.ModContext) entry.getField("context").get(null);
                    assertThrows(gold.debug.windowstolinux.appapi.task.ApiException.class,
                            () -> context.tasks().submit(work(), t -> success(null)));
                    runtime.security().unlock(PASSWORD.toCharArray());
                    assertEquals(Outcome.SUCCEEDED, context.tasks().submit(work(), t -> success(null)).completion()
                            .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS).outcome());
                } finally {
                    session.stop(gold.debug.windowstolinux.appapi.mod.StopReason.APPLICATION_EXIT);
                }
                assertEquals(1, entry.getField("instances").getInt(null));
                assertEquals(1, entry.getField("stops").getInt(null));
            }
            assertTrue(runtime.modSessions().isEmpty());
        }
    }
}
