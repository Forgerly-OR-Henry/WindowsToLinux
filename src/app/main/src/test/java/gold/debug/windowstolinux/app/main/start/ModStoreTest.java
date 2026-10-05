package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.app.service.mod.store.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual store availability, HTTP package integrity and original installer checks. / 实际商店可用性、HTTP 包完整性及原安装器核验。 */
class ModStoreTest {
    @TempDir
    Path root;

    @Test
    void absentSourceAndClassNeverInventCatalogOrInstallPackages() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), FilePlatformFixture.PROTECTION)) {
            runtime.security().setupAndUnlock(ModStoreFixture.PASSWORD.toCharArray(), ModStoreFixture.PASSWORD.toCharArray());
            var store = new ModStoreService(Optional.empty(), new UnavailableModStoreGateway(), Optional.empty(), runtime.security(),
                runtime.persistence().database(), root, PlatformBuildIdentity.readApiVersion());
            assertFalse(store.canInstall());
            var failure = assertThrows(ApiException.class, () -> store.catalog(() -> false));
            assertEquals("MOD_STORE_UNAVAILABLE", failure.problem().eventCode());
            assertThrows(IllegalStateException.class, () -> store.install(1, () -> false, ignored -> {
            }));
            assertFalse(Files.exists(root.resolve("mod-zip")));
        }
    }

    @Test
    void actualHttpCatalogDownloadAndInstallerPreserveDisabledSelectionAndNoAuthorExecution() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var key = fixture.publish("sample.bundle", "1.0.0", Map.of(), Map.of(), Map.of());
            var catalog = fixture.service().catalog(() -> false);
            assertEquals(key.owner(), catalog.releases().getFirst().owner());
            var plan = fixture.service().plan(catalog.revision(), new ModStoreSelection(key, Set.of(), Map.of()), () -> false);
            assertTrue(plan.valid(), plan.toString());
            var progress = new ArrayList<ModStoreProgress>();
            var actual = fixture.service().install(plan.revision(), () -> false, progress::add);
            assertEquals(TaskResult.SUCCEEDED, actual.outcome(), actual.toString());
            assertTrue(actual.requiredReady());
            assertTrue(actual.allSelectedReady());
            assertEquals(ModStoreBatchResult.State.INSTALLED, actual.packages().getFirst().state());
            assertEquals("FALSE/1.0.0", fixture.text("SELECT enabled || '/' || selected_version FROM mod_registry"));
            assertTrue(Files.isRegularFile(root.resolve("mod/sample.bundle/1.0.0/mod.json")));
            assertEquals(1, fixture.requests(key));
            assertFalse(progress.isEmpty());
            assertNull(System.getProperty(ModStoreFixture.AUTHOR_MARK));
        }
    }

    @Test
    void actualDigestFailureNeverRegistersOrExposesPartialOriginal() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var key = fixture.publish("sample.bundle", "1.0.0", Map.of(), Map.of(), Map.of());
            fixture.badDigest(key);
            var catalog = fixture.service().catalog(() -> false);
            var plan = fixture.service().plan(catalog.revision(), new ModStoreSelection(key, Set.of(), Map.of()), () -> false);
            var result = fixture.service().install(plan.revision(), () -> false, ignored -> {
            });
            assertEquals(TaskResult.FAILED, result.outcome());
            assertFalse(result.requiredReady());
            assertEquals(0, fixture.count("SELECT COUNT(*) FROM mod_version"));
            assertFalse(Files.exists(root.resolve("mod/sample.bundle/1.0.0")));
            try (var paths = Files.list(root.resolve("mod-zip"))) {
                assertEquals(0, paths.count());
            }
            assertEquals(1, fixture.requests(key));
        }
    }
}
