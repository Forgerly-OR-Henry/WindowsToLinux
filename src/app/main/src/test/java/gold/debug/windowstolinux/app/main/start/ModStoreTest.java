package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.count;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

import gold.debug.windowstolinux.app.service.mod.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreGateway;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreService;
import gold.debug.windowstolinux.app.service.mod.store.UnavailableModStoreGateway;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModStoreTest {
    @TempDir
    Path root;

    final URI url = URI.create("https://mods.example.com/api");

    final ModStoreGateway.Release release = new ModStoreGateway.Release("sample.bundle", "1.0.0", "测试包", "模拟服务，仅用于验收");

    @Test
    void unavailableAndEmptyUrlNeverInventResultsOrDownloadAndClassCannotInstall() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var password = "isolated-store-password".toCharArray();
            runtime.security().setupAndUnlock(password, password.clone());
            var gateway = new UnavailableModStoreGateway();
            for (var base : List.of(Optional.<URI>empty(), Optional.of(url))) {
                var store = new ModStoreService(base, gateway, Optional.empty(), runtime.security());
                assertFalse(store.canInstall());
                var failure = assertThrows(ApiException.class, () -> store.query("saved keyword"));
                assertEquals("MOD_STORE_UNAVAILABLE", failure.problem().eventCode());
                assertThrows(IllegalStateException.class, () -> store.install(release, ignored -> {
                }));
            }
            assertFalse(Files.exists(root.resolve("mod-zip")));
        }
    }

    @Test
    void simulatedSearchDownloadVerifyAndRealInstallerPreserveDisabledSelection() throws Exception {
        var fixture = new PlatformModInstallationTest();
        fixture.root = root;
        fixture.compile();
        Path original = fixture.original("test-adapter-source.wtlmod", "1.0.0", "模拟下载");
        var events = new ArrayList<String>();
        var progress = new ArrayList<ModStoreGateway.Progress>();
        ModStoreGateway gateway = new ModStoreGateway() {
            public QueryResult query(URI base, String keyword) {
                assertEquals(url, base);
                assertEquals("测试", keyword);
                events.add("query");
                return new QueryResult(List.of(release));
            }

            public DownloadResult download(URI base, Release selected, Path destination, Consumer<Progress> observer) throws Exception {
                assertEquals(release, selected);
                events.add("download");
                Files.copy(original, destination);
                observer.accept(new Progress(Files.size(destination), Files.size(destination)));
                return new DownloadResult(destination);
            }

            public Verification verify(Release selected, DownloadResult downloaded) {
                events.add("verify");
                return new Verification(true, "TEST ADAPTER verification, no production signature");
            }
        };
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            var store = new ModStoreService(Optional.of(url), gateway, Optional.of(installer), runtime.security());
            assertEquals(release, store.query("测试").releases().getFirst());
            var result = store.install(release, progress::add);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
            assertEquals(List.of("query", "download", "verify"), events);
            assertEquals(1, progress.size());
            assertEquals("FALSE/1.0.0", PlatformModIntentsTest.text(runtime, "SELECT enabled || '/' || selected_version FROM mod_registry"));
            assertTrue(Files.isRegularFile(fixture.target("1.0.0").resolve("mod.json")));
            assertNull(System.getProperty(PlatformModInstallationTest.MARK));
        }
    }

    @Test
    void failedAdapterVerificationNeverRegistersPackage() throws Exception {
        var fixture = new PlatformModInstallationTest();
        fixture.root = root;
        fixture.compile();
        Path original = fixture.original("bad-trust.wtlmod", "1.0.0", "fixture");
        ModStoreGateway gateway = new ModStoreGateway() {
            public QueryResult query(URI base, String keyword) {
                return new QueryResult(List.of(release));
            }

            public DownloadResult download(URI base, Release value, Path destination, Consumer<Progress> progress) throws Exception {
                Files.copy(original, destination);
                return new DownloadResult(destination);
            }

            public Verification verify(Release value, DownloadResult result) {
                return new Verification(false, "rejected test evidence");
            }
        };
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            var store = new ModStoreService(Optional.of(url), gateway, Optional.of(installer), runtime.security());
            assertThrows(java.io.IOException.class, () -> store.install(release, ignored -> {
            }));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_version"));
            assertFalse(Files.exists(fixture.target("1.0.0")));
        }
    }
}
