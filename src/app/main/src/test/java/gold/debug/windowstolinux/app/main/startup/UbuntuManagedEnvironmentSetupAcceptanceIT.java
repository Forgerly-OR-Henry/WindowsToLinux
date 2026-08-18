package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.service.deployment.*;
import gold.debug.windowstolinux.app.service.lifecycle.*;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.service.source.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live acceptance for the product-owned Ubuntu environment-preparation operation. It deliberately invokes only {@link DesktopApplicationService} and its production SSH gateway; it never opens a raw SSH session itself. <p>SSH and local-test-master secrets are read only from environment variables so Maven command lines and Surefire reports do not receive them. Use {@code WINDOWSTOLINUX_TEST_SSH_PASSWORD} and, optionally, {@code WINDOWSTOLINUX_TEST_MASTER_PASSWORD}.</p>
 *
 * <p>针对产品自有 Ubuntu 环境准备操作的可选实时验收。它刻意只调用 {@link DesktopApplicationService} 及其生产 SSH 网关，自身绝不打开原始 SSH 会话。<p>SSH 和本地测试主密码秘密只从环境变量读取，因此 Maven 命令行和 Surefire 报告不会接收它们。使用 {@code WINDOWSTOLINUX_TEST_SSH_PASSWORD}，并可选使用 {@code WINDOWSTOLINUX_TEST_MASTER_PASSWORD}。</p>
 */
@EnabledIfSystemProperty(named = "managed.runtime.environment-provision", matches = "true")
class UbuntuManagedEnvironmentSetupAcceptanceIT {
    private static final String DEFAULT_TEST_MASTER_PASSWORD = "managed-environment-preparation-master";

    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesTheFixedUbuntuToolsetThroughTheProductEntryPointAndIsIdempotent() throws Exception {
        String host = requiredProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "ubuntu");
        String sshPassword = requiredEnvironment("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        String masterPassword = System.getenv().getOrDefault(
                "WINDOWSTOLINUX_TEST_MASTER_PASSWORD", DEFAULT_TEST_MASTER_PASSWORD
        );
        boolean expectBare = !"false".equalsIgnoreCase(System.getProperty("managed.expect-bare", "true"));
        assertTrue(!username.isBlank(), "managed.ssh.user must not be blank");

        char[] sshPasswordChars = sshPassword.toCharArray();
        char[] masterPasswordChars = masterPassword.toCharArray();
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), new SshdLinuxGateway());
            ServerProfile profile = new ServerProfile(
                    "ubuntu-managed-environment", host, 22, username,
                    "ssh/ubuntu-managed-environment/password", CredentialStorageMode.MASTER_PASSWORD
            );
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    masterPasswordChars.clone(), sshPasswordChars.clone());

            ServerCapabilities baseline = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    masterPasswordChars.clone(), fingerprint -> true);
            assertUbuntu24X8664(baseline);
            System.out.println("MANAGED_ENVIRONMENT_BASELINE " + baselineSummary(baseline));
            if (expectBare) {
                assertCleanRuntimeBaseline(baseline);
            }

            assertTrue(service.listManagedApplications().isEmpty(),
                    "环境准备前不得存在由本验收创建的部署记录");
            EnvironmentSetupResult first = service.prepareEnvironmentWithStoredPassword(
                    profile, CredentialStorageMode.MASTER_PASSWORD, masterPasswordChars.clone(), fingerprint -> true, true
            );
            assertPreparationOnly(first, "首次准备");
            assertSupportsHttpAndTcpHealth(first.capabilities());
            System.out.println("MANAGED_ENVIRONMENT_AFTER_FIRST_PREPARATION " + baselineSummary(first.capabilities()));
            assertTrue(service.listManagedApplications().isEmpty(),
                    "环境准备不得上传源码、构建或发布应用");

            EnvironmentSetupResult second = service.prepareEnvironmentWithStoredPassword(
                    profile, CredentialStorageMode.MASTER_PASSWORD, masterPasswordChars.clone(), fingerprint -> true, true
            );
            assertPreparationOnly(second, "重复准备");
            assertSupportsHttpAndTcpHealth(second.capabilities());
            System.out.println("MANAGED_ENVIRONMENT_AFTER_SECOND_PREPARATION " + baselineSummary(second.capabilities()));
            assertTrue(service.listManagedApplications().isEmpty(),
                    "重复环境准备仍不得上传源码、构建或发布应用");
        } finally {
            Arrays.fill(sshPasswordChars, '\0');
            Arrays.fill(masterPasswordChars, '\0');
        }
    }

    private static void assertUbuntu24X8664(ServerCapabilities capabilities) {
        assertTrue(capabilities.operatingSystem().contains("Ubuntu 24.04"), () ->
                "受管部署环境准备仅接受 Ubuntu 24.04：" + capabilities.operatingSystem());
        assertTrue("x86_64".equals(capabilities.architecture()), () ->
                "受管部署环境准备仅接受 x86_64：" + capabilities.architecture());
    }

    private static void assertCleanRuntimeBaseline(ServerCapabilities capabilities) {
        assertFalse(capabilities.supportsManagedDeployment(false, new HealthCheck.Tcp(18080, 20, 1)), () ->
                "managed.expect-bare=true，但产品基线已满足完整受管部署能力；"
                        + "这不是可用于环境准备验收的干净 Ubuntu 运行时：" + baselineSummary(capabilities));
        assertFalse(capabilities.java21Available(), () ->
                "managed.expect-bare=true，但产品基线已发现 Java 21；"
                        + "这不是可用于环境准备验收的干净 Ubuntu 运行时：" + baselineSummary(capabilities));
        assertFalse(capabilities.mavenAvailable(), () ->
                "managed.expect-bare=true，但产品基线已发现 Maven；"
                        + "这不是可用于环境准备验收的干净 Ubuntu 运行时：" + baselineSummary(capabilities));
    }

    private static void assertPreparationOnly(EnvironmentSetupResult result, String stage) {
        assertTrue(result.evidence().contains("fixed distribution toolset"), () ->
                stage + " 缺少环境准备的受控工具集证据：" + result.evidence());
        String lowerEvidence = result.evidence().toLowerCase(java.util.Locale.ROOT);
        assertFalse(lowerEvidence.contains("upload") || lowerEvidence.contains("上传")
                        || lowerEvidence.contains("build") || lowerEvidence.contains("构建")
                        || lowerEvidence.contains("publish") || lowerEvidence.contains("发布"),
                () -> stage + " 的环境准备证据不应声称进行了源码上传、构建或发布：" + result.evidence());
    }

    private static void assertSupportsHttpAndTcpHealth(ServerCapabilities capabilities) {
        assertTrue(capabilities.supportsManagedDeployment(false, new HealthCheck.Http(
                        java.net.URI.create("http://127.0.0.1:18080/actuator/health"), 200, 20)),
                () -> "环境准备后必须支持受管部署 HTTP 健康检查：" + baselineSummary(capabilities));
        assertTrue(capabilities.supportsManagedDeployment(false, new HealthCheck.Tcp(18080, 20, 1)),
                () -> "环境准备后必须支持受管部署 TCP 健康检查：" + baselineSummary(capabilities));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private static String baselineSummary(ServerCapabilities capabilities) {
        return "os=" + capabilities.operatingSystem()
                + ", arch=" + capabilities.architecture()
                + ", java21=" + capabilities.java21Available()
                + ", maven=" + capabilities.mavenAvailable()
                + ", tar=" + capabilities.tarAvailable()
                + ", curl=" + capabilities.curlAvailable()
                + ", sudo=" + capabilities.nonInteractiveSudoAvailable();
    }
}
