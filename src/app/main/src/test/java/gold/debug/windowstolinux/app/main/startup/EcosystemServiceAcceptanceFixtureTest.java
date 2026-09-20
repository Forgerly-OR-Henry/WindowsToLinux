package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies failure fixtures exercise the service health gate rather than one request process. / 验证故障夹具触发服务健康门，而不是仅结束一次请求进程。 */
class EcosystemServiceAcceptanceFixtureTest {
    @TempDir Path temporaryDirectory;

    @Test
    void phpFailureRespondsUnhealthyWhileTheBuiltInServerRemainsObservable() throws Exception {
        Path root = EcosystemServiceAcceptanceFixture.create(temporaryDirectory, DeploymentProjectType.PHP_SERVICE,
                "php-fixture", "8.3", "unused", false, null);

        String configuration = Files.readString(root.resolve("src/Configuration.php"));
        assertTrue(configuration.contains("503"));
        assertFalse(Files.readString(root.resolve("public/index.php")).contains("exit("));
        assertTrue(Files.readString(root.resolve("src/SummaryService.php")).contains("Opis\\JsonSchema\\Validator"));
        assertTrue(Files.readString(root.resolve("composer.lock")).contains("opis/json-schema"));
    }

    @Test
    void textCustomizationPreservesTheBinaryWrapperAndKeepsCopiedSourcesIndependent() throws Exception {
        Path original = RepositoryServiceFixture.repositoryRoot()
                .resolve("test/single-language/java/gradle/spring-boot/success-deployment-smoke");
        byte[] wrapper = Files.readAllBytes(original.resolve("gradle/wrapper/gradle-wrapper.jar"));
        Path root = RepositoryServiceFixture.copy(temporaryDirectory, "copied-service", "java/gradle/spring-boot", true);
        RepositoryServiceFixture.replaceText(root, Map.of("gradle", "customized", "deployment-smoke-ok", "copied-ok"));
        assertArrayEquals(wrapper, Files.readAllBytes(root.resolve("gradle/wrapper/gradle-wrapper.jar")));
        assertTrue(Files.readString(root.resolve("gradle/wrapper/gradle-wrapper.properties")).contains("customized"));
        assertTrue(Files.readString(original.resolve("gradle/wrapper/gradle-wrapper.properties")).contains("gradle"));
        try (var files = Files.walk(root.resolve("src/main/java"))) {
            assertTrue(files.filter(path -> path.toString().endsWith(".java")).count() >= 5);
        }
    }

    @Test
    void kotlinFixturePinsTheOfficialDistributionChecksumAndDirectEndpoint() throws Exception {
        Path wrapperJar = temporaryDirectory.resolve("wrapper.jar");
        Files.writeString(wrapperJar, "fixture");
        Path root = EcosystemServiceAcceptanceFixture.create(temporaryDirectory, DeploymentProjectType.KOTLIN_SERVICE,
                "kotlin-fixture", "2.0.21", "healthy", true, wrapperJar);
        EcosystemServiceAcceptanceFixture.create(temporaryDirectory, DeploymentProjectType.KOTLIN_SERVICE,
                "kotlin-fixture", "2.0.21", "unhealthy", false, wrapperJar);

        String properties = Files.readString(root.resolve("gradle/wrapper/gradle-wrapper.properties"));
        assertTrue(properties.contains("distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26"));
        assertTrue(properties.contains("distributionUrl=https\\://downloads.gradle.org/distributions/gradle-8.10.2-bin.zip"));
        assertFalse(properties.contains("services.gradle.org"));
    }
}
