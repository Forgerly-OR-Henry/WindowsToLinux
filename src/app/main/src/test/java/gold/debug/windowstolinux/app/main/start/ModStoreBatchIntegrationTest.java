package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import gold.debug.windowstolinux.app.service.mod.store.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreBatchResult.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real HTTP and installed-filesystem batch counterexamples. / 真实 HTTP 及安装文件系统批次反例。 */
class ModStoreBatchIntegrationTest {
    @TempDir
    Path home;

    @AfterEach
    void authorCodeNeverRuns() {
        assertNull(System.getProperty(ModStoreFixture.AUTHOR_MARK));
    }

    static ModStorePlan plan(ModStoreService service, ReleaseKey root, Set<OptionalKey> optional) throws Exception {
        var catalog = service.catalog(() -> false);
        return service.plan(catalog.revision(), new ModStoreSelection(root, optional, Map.of()), () -> false);
    }

    static ModStoreBatchResult install(ModStoreFixture fixture, ReleaseKey root, Set<OptionalKey> optional) throws Exception {
        var plan = plan(fixture.service(), root, optional);
        assertTrue(plan.valid(), plan.toString());
        return fixture.service().install(plan.revision(), () -> false, ignored -> {
        });
    }

    static PackageResult report(ModStoreBatchResult result, ReleaseKey key) {
        return result.packages().stream().filter(value -> value.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void actualRequiredClosureDedupeOptionalSelectionAndReadyReusePreserveChoices() throws Exception {
        try (var fixture = new ModStoreFixture(home)) {
            var base = fixture.publish("sample.base", "1.0.0", Map.of(), Map.of(), Map.of());
            var provider1 = fixture.publish("sample.provider", "1.0.0", Map.of("sample.base", "1.0.0"), Map.of(), Map.of());
            var provider2 = fixture.publish("sample.provider", "2.0.0", Map.of("sample.base", "1.0.0"), Map.of(), Map.of("sample.base", "Shared base"));
            var extra = fixture.publish("sample.extra", "1.0.0", Map.of("sample.base", "1.0.0"), Map.of(), Map.of());
            var root = fixture.publish("sample.root", "1.0.0", Map.of("sample.provider", "[1.0.0,3.0.0)"),
                Map.of("sample.extra", "1.0.0"), Map.of("sample.extra", "Extra functionality"));
            var first = install(fixture, root, Set.of());
            assertEquals(TaskResult.SUCCEEDED, first.outcome());
            assertEquals(3, first.packages().size());
            assertEquals(1, fixture.requests(base));
            assertEquals(0, fixture.requests(provider1));
            assertEquals(1, fixture.requests(provider2));
            assertEquals(0, fixture.requests(extra));
            assertEquals(0, fixture.count("SELECT COUNT(*) FROM mod_registry WHERE enabled"));
            var selected = plan(fixture.service(), root, Set.of(new OptionalKey(root.owner(), extra.owner())));
            assertTrue(selected.valid());
            var observations = new ArrayList<List<PackageResult>>();
            var second = fixture.service().install(selected.revision(), () -> false, value -> {
                if (value.stage() == ModStoreProgress.Kind.FINISHED) {
                    var actual = fixture.service().packageResults(value.batchId());
                    assertEquals(value.key(), actual.getLast().key());
                    observations.add(actual);
                }
            });
            assertEquals(TaskResult.SUCCEEDED, second.outcome());
            assertEquals(State.REUSED, report(second, root).state());
            assertEquals(State.INSTALLED, report(second, extra).state());
            assertEquals(1, fixture.requests(extra));
            assertEquals(1, fixture.requests(base));
            assertEquals(1, fixture.requests(root));
            assertEquals(4, observations.size());
            var snapshot = fixture.service().catalog(() -> false);
            assertTrue(snapshot.installed().stream().allMatch(value -> !value.enabled() && value.selected()));
        }
    }

    @Test
    void compatibleActualInstalledProviderCanBeReusedWithoutRemoteVersion() throws Exception {
        try (var fixture = new ModStoreFixture(home)) {
            var provider = fixture.publish("sample.provider", "1.0.0", Map.of(), Map.of(), Map.of());
            assertEquals(TaskResult.SUCCEEDED, install(fixture, provider, Set.of()).outcome());
            fixture.remove(provider);
            var root = fixture.publish("sample.root", "1.0.0", Map.of(provider.owner(), provider.version()), Map.of(), Map.of());
            var result = install(fixture, root, Set.of());
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertEquals(State.REUSED, report(result, provider).state());
            assertEquals(1, fixture.requests(provider));
            assertTrue(result.requiredReady());
            assertTrue(Files.isRegularFile(home.resolve("mod/sample.provider/1.0.0/main/fixture.jar")));
        }
    }

    @Test
    void requiredFailurePreservesIndependentCompletedOptionalAndRetryOnlyFillsMissing() throws Exception {
        try (var fixture = new ModStoreFixture(home)) {
            var extra = fixture.publish("sample.extra", "1.0.0", Map.of(), Map.of(), Map.of());
            var provider = fixture.publish("sample.provider", "1.0.0", Map.of(), Map.of(), Map.of());
            var root = fixture.publish("sample.root", "1.0.0", Map.of(provider.owner(), provider.version()), Map.of(extra.owner(), extra.version()), Map.of());
            fixture.failure(provider, 503);
            var optional = Set.of(new OptionalKey(root.owner(), extra.owner()));
            var first = install(fixture, root, optional);
            assertEquals(TaskResult.FAILED, first.outcome());
            assertFalse(first.requiredReady());
            assertFalse(first.allSelectedReady());
            assertEquals(State.INSTALLED, report(first, extra).state());
            assertTrue(report(first, extra).receipt().isPresent());
            assertEquals(State.FAILED, report(first, provider).state());
            assertEquals(State.UNATTEMPTED, report(first, root).state());
            assertEquals(0, fixture.requests(root));
            assertTrue(Files.exists(home.resolve("mod/sample.extra/1.0.0")));
            fixture.failure(provider, 0);
            var second = install(fixture, root, optional);
            assertEquals(TaskResult.SUCCEEDED, second.outcome());
            assertEquals(State.REUSED, report(second, extra).state());
            assertEquals(1, fixture.requests(extra));
            assertEquals(2, fixture.requests(provider));
            assertEquals(1, fixture.requests(root));
            assertEquals(0, fixture.count("SELECT COUNT(*) FROM mod_registry WHERE enabled"));
        }
    }

    @Test
    void selectedOptionalFailureDoesNotClaimAllSuccessButRootBaseCanBeReady() throws Exception {
        try (var fixture = new ModStoreFixture(home)) {
            var extra = fixture.publish("sample.extra", "1.0.0", Map.of(), Map.of(), Map.of());
            var root = fixture.publish("sample.root", "1.0.0", Map.of(), Map.of(extra.owner(), extra.version()), Map.of());
            fixture.failure(extra, 503);
            var optional = Set.of(new OptionalKey(root.owner(), extra.owner()));
            var first = install(fixture, root, optional);
            assertEquals(TaskResult.FAILED, first.outcome());
            assertTrue(first.requiredReady());
            assertFalse(first.allSelectedReady());
            assertEquals(State.FAILED, report(first, extra).state());
            assertEquals(State.INSTALLED, report(first, root).state());
            fixture.failure(extra, 0);
            var retried = install(fixture, root, optional);
            assertEquals(TaskResult.SUCCEEDED, retried.outcome());
            assertEquals(State.REUSED, report(retried, root).state());
            assertEquals(1, fixture.requests(root));
            assertEquals(2, fixture.requests(extra));
        }
    }

    @Test
    void cooperativeCancelDuringSecondRealStreamKeepsFirstReceiptAndHidesHalfOriginal() throws Exception {
        try (var fixture = new ModStoreFixture(home)) {
            var first = fixture.publish("sample.first", "1.0.0", Map.of(), Map.of(), Map.of());
            var second = fixture.publish("sample.second", "1.0.0", Map.of(), Map.of(), Map.of());
            var root = fixture.publish("sample.root", "1.0.0", Map.of(first.owner(), first.version(), second.owner(), second.version()), Map.of(), Map.of());
            fixture.hold(second);
            var proposal = plan(fixture.service(), root, Set.of());
            assertTrue(proposal.valid());
            var cancelled = new AtomicBoolean();
            var worker = Executors.newSingleThreadExecutor();
            try {
                var task = worker.submit(() -> fixture.service().install(proposal.revision(), cancelled::get, ignored -> {
                }));
                fixture.awaitStarted(second);
                var originals = fixture.runtime().modInstallation().scan();
                assertTrue(originals.originals().stream().allMatch(value -> value.packageInfo().isPresent()));
                assertTrue(originals.originals().stream().noneMatch(value -> value.packageInfo().orElseThrow().id().equals(second.owner())));
                cancelled.set(true);
                var result = task.get(20, TimeUnit.SECONDS);
                assertEquals(TaskResult.CANCELLED, result.outcome());
                assertEquals(State.INSTALLED, report(result, first).state());
                assertTrue(report(result, first).receipt().isPresent());
                assertEquals(State.CANCELLED, report(result, second).state());
                assertEquals(State.UNATTEMPTED, report(result, root).state());
                assertEquals(0, fixture.requests(root));
                try (var files = Files.list(home.resolve("mod-zip"))) {
                    assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".download")));
                }
                fixture.release(second);
                var retried = install(fixture, root, Set.of());
                assertEquals(TaskResult.SUCCEEDED, retried.outcome());
                assertEquals(State.REUSED, report(retried, first).state());
                assertEquals(1, fixture.requests(first));
                assertEquals(2, fixture.requests(second));
            } finally {
                cancelled.set(true);
                fixture.release(second);
                worker.close();
            }
        }
    }

    @Test
    void actualReadyUnknownBlocksNewServiceAndReopenedRuntimeUntilOriginalUuidReconciles() throws Exception {
        try (var fixture = new ModStoreFixture(home)) {
            var first = fixture.publish("sample.first", "1.0.0", Map.of(), Map.of(), Map.of());
            var second = fixture.publish("sample.second", "1.0.0", Map.of(), Map.of(), Map.of());
            var root = fixture.publish("sample.root", "1.0.0", Map.of(first.owner(), first.version(), second.owner(), second.version()), Map.of(), Map.of());
            fixture.sql("ALTER TABLE mod_operation ADD CONSTRAINT store_unknown CHECK(phase <> 'CLEANUP')");
            var original = install(fixture, root, Set.of());
            assertEquals(TaskResult.UNKNOWN, original.outcome());
            assertEquals(State.UNKNOWN, report(original, first).state());
            UUID operation = report(original, first).operationId().orElseThrow();
            assertEquals(1, fixture.count("SELECT COUNT(*) FROM mod_version WHERE state='READY'"));
            assertEquals(operation.toString(), fixture.text("SELECT operation_id FROM mod_operation"));
            assertEquals(0, fixture.requests(second));
            assertEquals(0, fixture.requests(root));
            var nativeIdentity = gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity.read(home.resolve("mod/sample.first/1.0.0"));
            var view = fixture.newService();
            var catalog = view.catalog(() -> false);
            assertEquals(1, catalog.installed().size());
            assertEquals(operation.toString(), catalog.installedIssues().stream().filter(issue -> issue.problem().eventCode().equals("MOD_STORE_ORIGINAL_PENDING"))
                .findFirst().orElseThrow().problem().context().get("operationId"));
            var blocked = view.plan(catalog.revision(), new ModStoreSelection(root, Set.of(), Map.of()), () -> false);
            assertFalse(blocked.valid());
            assertThrows(ApiException.class, () -> view.install(blocked.revision(), () -> false, ignored -> {
            }));
            assertEquals(1, fixture.requests(first));
            fixture.sql("ALTER TABLE mod_operation DROP CONSTRAINT store_unknown");
            fixture.reopen();
            var restarted = plan(fixture.service(), root, Set.of());
            assertFalse(restarted.valid());
            assertEquals(1, fixture.requests(first));
            assertEquals(0, fixture.requests(second));
            var reconciled = fixture.service().reconcile(operation);
            assertEquals(TaskResult.SUCCEEDED, reconciled.outcome());
            assertEquals(operation, reconciled.value().orElseThrow().operationId());
            assertEquals(nativeIdentity, gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity.read(home.resolve("mod/sample.first/1.0.0")));
            var completed = install(fixture, root, Set.of());
            assertEquals(TaskResult.SUCCEEDED, completed.outcome());
            assertEquals(State.REUSED, report(completed, first).state());
            assertEquals(1, fixture.requests(first));
            assertEquals(1, fixture.requests(second));
            assertEquals(1, fixture.requests(root));
            assertEquals(0, fixture.count("SELECT COUNT(*) FROM mod_operation"));
        }
    }

    @Test
    void completePackageDeclarationMismatchKeepsEarlierProviderButNeverRegistersRoot() throws Exception {
        try (var fixture = new ModStoreFixture(home)) {
            var provider = fixture.publish("sample.provider", "1.0.0", Map.of(), Map.of(), Map.of());
            var root = fixture.publish("sample.root", "1.0.0", Map.of(), Map.of(), Map.of());
            fixture.differentDeclaration(root, Map.of(provider.owner(), provider.version()));
            var actual = install(fixture, root, Set.of());
            assertEquals(TaskResult.FAILED, actual.outcome());
            assertFalse(actual.requiredReady());
            assertEquals(State.INSTALLED, report(actual, provider).state());
            assertEquals(State.FAILED, report(actual, root).state());
            assertEquals(0, fixture.count("SELECT COUNT(*) FROM mod_version WHERE mod_id='sample.root'"));
            assertFalse(Files.exists(home.resolve("mod/sample.root/1.0.0")));
            assertEquals(1, fixture.requests(root));
        }
    }

    @Test
    void sameInstalledIdentityWithChangedDirectoryApiIsConflictWithoutOverwriteOrDownload() throws Exception {
        try (var fixture = new ModStoreFixture(home)) {
            var root = fixture.publish("sample.root", "1.0.0", Map.of(), Map.of(), Map.of());
            assertEquals(TaskResult.SUCCEEDED, install(fixture, root, Set.of()).outcome());
            String original = Files.readString(home.resolve("mod/sample.root/1.0.0/mod.json"));
            fixture.differentDeclaration(root, Map.of(), Map.of(), 21, "[1.0.0-dev.4,3.0.0)");
            var rejected = plan(fixture.service(), root, Set.of());
            assertFalse(rejected.valid());
            assertEquals("MOD_STORE_DECLARATION_CONFLICT", rejected.conflicts().getFirst().code());
            assertThrows(ApiException.class, () -> fixture.service().install(rejected.revision(), () -> false, ignored -> {
            }));
            assertEquals(1, fixture.requests(root));
            assertEquals(original, Files.readString(home.resolve("mod/sample.root/1.0.0/mod.json")));
        }
    }

    @Test
    void catalogReloadInvalidatesConfirmationAndZeroPackagesAreRequested() throws Exception {
        try (var fixture = new ModStoreFixture(home)) {
            var root = fixture.publish("sample.root", "1.0.0", Map.of(), Map.of(), Map.of());
            var original = plan(fixture.service(), root, Set.of());
            assertTrue(original.valid());
            fixture.service().catalog(() -> false);
            assertEquals("MOD_STORE_PLAN_STALE", assertThrows(ApiException.class,
                () -> fixture.service().install(original.revision(), () -> false, ignored -> {
                })).problem().eventCode());
            assertEquals(0, fixture.requests(root));
            assertEquals(0, fixture.count("SELECT COUNT(*) FROM mod_version"));
        }
    }

    @Test
    void actualInstalledBytesChangedAfterPlanFailBeforeAnyAdditionalHttpOrOverwrite() throws Exception {
        try (var fixture = new ModStoreFixture(home)) {
            var provider = fixture.publish("sample.provider", "1.0.0", Map.of(), Map.of(), Map.of());
            assertEquals(TaskResult.SUCCEEDED, install(fixture, provider, Set.of()).outcome());
            var root = fixture.publish("sample.root", "1.0.0", Map.of(provider.owner(), provider.version()), Map.of(), Map.of());
            var original = plan(fixture.service(), root, Set.of());
            assertTrue(original.valid());
            assertEquals(ModStorePlan.Source.INSTALLED, original.items().stream().filter(item -> item.key().equals(provider)).findFirst().orElseThrow().source());
            Path readme = home.resolve("mod/sample.provider/1.0.0/README.md");
            Files.writeString(readme, "Actually changed after confirmation");
            var rejected = fixture.service().install(original.revision(), () -> false, ignored -> {
            });
            assertEquals(TaskResult.FAILED, rejected.outcome());
            assertFalse(rejected.requiredReady());
            assertFalse(rejected.allSelectedReady());
            assertTrue(rejected.packages().stream().allMatch(report -> report.state() == State.UNATTEMPTED && report.problem().isPresent()));
            assertEquals(1, fixture.requests(provider));
            assertEquals(0, fixture.requests(root));
            assertEquals("Actually changed after confirmation", Files.readString(readme));
            assertEquals(1, fixture.count("SELECT COUNT(*) FROM mod_version"));
            assertFalse(Files.exists(home.resolve("mod/sample.root/1.0.0")));
            var actual = fixture.service().catalog(() -> false);
            assertTrue(actual.installed().isEmpty());
            assertEquals("MOD_STORE_INSTALLED_INVALID", actual.installedIssues().getFirst().problem().eventCode());
        }
    }
}
