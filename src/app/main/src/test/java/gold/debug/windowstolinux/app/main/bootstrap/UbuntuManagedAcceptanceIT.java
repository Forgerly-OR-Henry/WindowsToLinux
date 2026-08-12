package gold.debug.windowstolinux.app.main.bootstrap;

import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.service.deployment.*;
import gold.debug.windowstolinux.app.service.lifecycle.*;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.service.source.*;

import gold.debug.windowstolinux.app.db.DesktopDatabase;
import gold.debug.windowstolinux.app.service.deployment.DeploymentHandoff.HttpAccessUrl;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.LifecycleActionResult;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in real-host acceptance test. It reads the SSH password only from WINDOWSTOLINUX_TEST_SSH_PASSWORD and is never part of ordinary Maven gates.
 *
 * <p>可选真实主机验收测试。它只从 WINDOWSTOLINUX_TEST_SSH_PASSWORD 读取 SSH 密码，并且绝不属于常规 Maven 门禁。
 */
@EnabledIfSystemProperty(named = "managed.runtime", matches = "true")
class UbuntuManagedAcceptanceIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void deploysHelloWorldAndVerifiesTheManagedLifecycleOnUbuntu() throws Exception {
        String sourceProperty = System.getProperty("managed.hello.source");
        String accessUrlProperty = System.getProperty("managed.access.url");
        String host = System.getProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "ubuntu");
        boolean rootBuild = Boolean.getBoolean("managed.root-build");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        HealthCheck.Http health = httpHealth(System.getProperty("managed.health.url", "http://127.0.0.1:18080/actuator/health"));
        assertTrue(sourceProperty != null && !sourceProperty.isBlank(), "managed.hello.source is required");
        assertTrue(accessUrlProperty != null && !accessUrlProperty.isBlank(), "managed.access.url is required");
        assertTrue(host != null && !host.isBlank(), "managed.ssh.host is required");
        assertTrue(username != null && !username.isBlank(), "managed.ssh.user is required");
        assertTrue(!"root".equals(username) || rootBuild,
                "a root SSH session requires the explicit managed.root-build=true confirmation");
        assertTrue(password != null && !password.isBlank(), "WINDOWSTOLINUX_TEST_SSH_PASSWORD is required");
        Path source = Path.of(sourceProperty).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(source), "Hello World source directory is required");
        UserAccessUrl userAccessUrl = new UserAccessUrl(URI.create(accessUrlProperty));
        assertNotEquals(health.endpoint(), userAccessUrl.url(), "用户访问 URL 不能是目标机健康端点");

        char[] sshPassword = password.toCharArray();
        char[] masterPassword = "managed-acceptance-master".toCharArray();
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), new SshdLinuxGateway());
            SourcePreparation preparation = service.prepareSource(source);
            assertTrue(preparation.archive().isPresent(), "Hello World must pass the managed-deployment static analysis");
            String applicationId = preparation.assessment().facts().orElseThrow().applicationName();

            ServerProfile profile = new ServerProfile("ubuntu-managed", host, 22, username,
                    "ssh/ubuntu-managed/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD, masterPassword, sshPassword);
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-acceptance-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsManagedDeployment(preparation.assessment().facts().orElseThrow().usesMavenWrapper(),
                    health), "Ubuntu target must meet all managed-deployment preconditions: " + capabilities);

            var server = service.findTrustedServer(profile.id()).orElseThrow();
            DeploymentRequest request = service.createDeploymentRequest(preparation, server, health, Optional.of(userAccessUrl),
                    new BuildLimits(1200, 1024, 4096, 4L * 1024 * 1024, 2L * 1024 * 1024 * 1024, rootBuild), rootBuild);
            DeploymentOutcome deployed = service.deployWithStoredPassword(request, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-acceptance-master".toCharArray(), fingerprint -> true);
            assertEquals(DeploymentStatus.SUCCEEDED.name(), deployed.status(), () -> deployed.events().toString());
            URI accessUrl = requireHttpAccessUrl(deployed, userAccessUrl.url());
            assertDesktopCanAccess(accessUrl, "managed service Quote Service");

            LifecycleObservation refreshed = lifecycle(service, applicationId, LifecycleAction.REFRESH_STATUS);
            assertState(refreshed, RuntimeState.RUNNING, "初始状态查询必须确认已运行");
            AutostartState initialAutostart = refreshed.autostartState();
            assertTrue(initialAutostart == AutostartState.ENABLED || initialAutostart == AutostartState.DISABLED,
                    () -> "初始自启状态必须可复核: " + refreshed);

            LifecycleObservation stopped = lifecycle(service, applicationId, LifecycleAction.STOP);
            assertState(stopped, RuntimeState.STOPPED, "STOP 必须停止服务");
            assertEquals(initialAutostart, stopped.autostartState(), "STOP 不得静默改变自启状态");

            LifecycleObservation started = lifecycle(service, applicationId, LifecycleAction.START);
            assertState(started, RuntimeState.RUNNING, "START 必须启动服务");
            assertEquals(initialAutostart, started.autostartState(), "START 不得静默改变自启状态");

            LifecycleObservation restarted = lifecycle(service, applicationId, LifecycleAction.RESTART);
            assertState(restarted, RuntimeState.RUNNING, "RESTART 必须令服务恢复运行");
            assertEquals(initialAutostart, restarted.autostartState(), "RESTART 不得静默改变自启状态");

            LifecycleObservation enabled = lifecycle(service, applicationId, LifecycleAction.ENABLE_AUTOSTART);
            assertState(enabled, RuntimeState.RUNNING, "启用自启不得停止运行中的服务");
            assertEquals(AutostartState.ENABLED, enabled.autostartState(), "ENABLE_AUTOSTART 必须启用自启");

            LifecycleObservation stoppedWithAutostart = lifecycle(service, applicationId, LifecycleAction.STOP);
            assertState(stoppedWithAutostart, RuntimeState.STOPPED, "STOP 必须停止已启用自启的服务");
            assertEquals(AutostartState.ENABLED, stoppedWithAutostart.autostartState(),
                    "STOP 不得因停止服务而禁用自启");

            LifecycleObservation startedWithAutostart = lifecycle(service, applicationId, LifecycleAction.START);
            assertState(startedWithAutostart, RuntimeState.RUNNING, "START 必须启动已启用自启的服务");
            assertEquals(AutostartState.ENABLED, startedWithAutostart.autostartState(),
                    "START 不得因启动服务而改变自启");

            LifecycleObservation disabled = lifecycle(service, applicationId, LifecycleAction.DISABLE_AUTOSTART);
            assertState(disabled, RuntimeState.RUNNING, "禁用自启不得停止运行中的服务");
            assertEquals(AutostartState.DISABLED, disabled.autostartState(), "DISABLE_AUTOSTART 必须禁用自启");
        }
    }

    private static LifecycleObservation lifecycle(
            DesktopApplicationService service,
            String applicationId,
            LifecycleAction action
    ) throws Exception {
        LifecycleActionResult result = service.executePersistedLifecycleResultWithStoredPassword(applicationId, action,
                "managed-acceptance-master".toCharArray());
        assertTrue(result.accepted(), () -> action + " 未被受理: " + result);
        return result.observation().orElseThrow(() -> new AssertionError(action + " 缺少远端观测"));
    }

    private static void assertState(LifecycleObservation observation, RuntimeState expectedRuntime, String message) {
        assertTrue(observation.ownershipVerified(), () -> message + "；受管身份未通过复核: " + observation);
        assertEquals(expectedRuntime, observation.runtimeState(), () -> message + "；观测=" + observation);
    }

    private static URI requireHttpAccessUrl(DeploymentOutcome outcome, URI expectedBusinessUrl) {
        DeploymentHandoff handoff = outcome.handoff().orElseThrow(
                () -> new AssertionError("部署成功必须向使用者返回访问网址或启动指令: " + outcome));
        assertTrue(handoff instanceof HttpAccessUrl,
                () -> "HTTP 健康检查部署成功后必须返回可访问的网址: " + handoff);
        URI accessUrl = ((HttpAccessUrl) handoff).url();
        assertEquals(expectedBusinessUrl, accessUrl, "返回网址必须是用户明确声明的业务入口");
        return accessUrl;
    }

    private static void assertDesktopCanAccess(URI accessUrl, String expectedBusinessMarker) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) accessUrl.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        try {
            int status = connection.getResponseCode();
            String body;
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                body = input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals(200, status,
                    () -> "桌面侧访问部署返回的业务网址未得到 200: " + accessUrl);
            assertTrue(body.contains(expectedBusinessMarker),
                    () -> "桌面侧 GET 返回的不是业务入口页面: " + accessUrl);
        } finally {
            connection.disconnect();
        }
    }

    private static HealthCheck.Http httpHealth(String endpoint) {
        return new HealthCheck.Http(URI.create(endpoint), 200, 20);
    }
}
