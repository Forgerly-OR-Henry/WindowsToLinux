package gold.debug.windowstolinux.app.main.bootstrap;

import gold.debug.windowstolinux.shared.model.project.AdvancedRuntimeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies failure fixtures exercise the service health gate rather than one request process. / 验证故障夹具触发服务健康门，而不是仅结束一次请求进程。 */
class AdvancedAcceptanceFixturesTest {
    @TempDir Path temporaryDirectory;

    @Test
    void phpFailureRespondsUnhealthyWhileTheBuiltInServerRemainsObservable() throws Exception {
        Path root = AdvancedAcceptanceFixtures.create(temporaryDirectory, AdvancedRuntimeKind.PHP,
                "php-fixture", "8.3", "unused", false, null);

        String router = Files.readString(root.resolve("public/index.php"));
        assertTrue(router.contains("http_response_code(503)"));
        assertFalse(router.contains("exit("));
    }

    @Test
    void kotlinFixturePinsTheOfficialDistributionChecksumAndDirectEndpoint() throws Exception {
        Path wrapperJar = temporaryDirectory.resolve("wrapper.jar");
        Files.writeString(wrapperJar, "fixture");
        Path root = AdvancedAcceptanceFixtures.create(temporaryDirectory, AdvancedRuntimeKind.KOTLIN,
                "kotlin-fixture", "21", "healthy", true, wrapperJar);
        AdvancedAcceptanceFixtures.create(temporaryDirectory, AdvancedRuntimeKind.KOTLIN,
                "kotlin-fixture", "21", "unhealthy", false, wrapperJar);

        String properties = Files.readString(root.resolve("gradle/wrapper/gradle-wrapper.properties"));
        assertTrue(properties.contains("distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26"));
        assertTrue(properties.contains("distributionUrl=https\\://downloads.gradle.org/distributions/gradle-8.10.2-bin.zip"));
        assertFalse(properties.contains("services.gradle.org"));
    }
}
