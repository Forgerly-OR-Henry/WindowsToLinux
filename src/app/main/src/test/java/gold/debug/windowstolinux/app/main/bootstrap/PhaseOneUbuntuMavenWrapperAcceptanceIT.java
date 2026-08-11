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
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdPhaseOneLinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
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
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in deployment proving that a standard Maven Wrapper is used only on the Ubuntu candidate.
 *
 * <p>用于证明标准 Maven Wrapper 只在 Ubuntu 候选项上使用的可选部署测试。
 */
@EnabledIfSystemProperty(named = "phase1.runtime.wrapper", matches = "true")
class PhaseOneUbuntuMavenWrapperAcceptanceIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void deploysAStandardMavenWrapperProjectThroughTheProductEntryPoint() throws Exception {
        String sourceProperty = System.getProperty("phase1.wrapper.source");
        String accessUrlProperty = System.getProperty("phase1.wrapper.access.url");
        String host = System.getProperty("phase1.ssh.host");
        String username = System.getProperty("phase1.ssh.user", "ubuntu");
        boolean rootBuild = Boolean.getBoolean("phase1.root-build");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertPresent(sourceProperty, "phase1.wrapper.source");
        assertPresent(accessUrlProperty, "phase1.wrapper.access.url");
        assertPresent(host, "phase1.ssh.host");
        assertPresent(username, "phase1.ssh.user");
        assertTrue(!"root".equals(username) || rootBuild,
                "a root SSH session requires the explicit phase1.root-build=true confirmation");
        assertPresent(password, "WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        Path source = Path.of(sourceProperty).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(source), "Maven Wrapper source directory is required");

        HealthCheck.Http health = new HealthCheck.Http(URI.create("http://127.0.0.1:19092/wrapper-health"), 200, 20);
        UserAccessUrl userAccessUrl = new UserAccessUrl(URI.create(accessUrlProperty));
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), new SshdPhaseOneLinuxGateway());
            SourcePreparation preparation = service.prepareSource(source);
            assertTrue(preparation.archive().isPresent(), "fixture must pass static analysis");
            assertTrue(preparation.assessment().facts().orElseThrow().usesMavenWrapper(),
                    "only a root mvnw plus standard wrapper properties may select the Wrapper build path");
            assertArchiveContainsWrapper(preparation);

            ServerProfile profile = new ServerProfile("ubuntu-phase1-wrapper", host, 22, username,
                    "ssh/ubuntu-phase1-wrapper/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "phase1-wrapper-master".toCharArray(), password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "phase1-wrapper-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsPhaseOne(true, health),
                    () -> "Ubuntu target must meet Maven Wrapper preconditions: " + capabilities);

            var server = service.findTrustedServer(profile.id()).orElseThrow();
            DeploymentRequest request = service.createDeploymentRequest(preparation, server, health, Optional.of(userAccessUrl),
                    new BuildLimits(1200, 1024, 4096, 4L * 1024 * 1024, 2L * 1024 * 1024 * 1024, rootBuild), rootBuild);
            DeploymentOutcome result = service.deployWithStoredPassword(request, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "phase1-wrapper-master".toCharArray(), fingerprint -> true);
            assertEquals(DeploymentStatus.SUCCEEDED.name(), result.status(), () -> result.events().toString());
            URI accessUrl = requireHttpAccessUrl(result, userAccessUrl.url());
            assertDesktopCanAccess(accessUrl, "Phase One Wrapper Service");
            assertEvent(result, "source-upload", true);
            assertTrue(result.events().stream().anyMatch(event -> "remote-build".equals(event.step())
                            && event.evidence().contains("Maven Wrapper")),
                    () -> "remote build must prove the fixed Wrapper route: " + result.events());
            assertEvent(result, "snapshot", true);
            assertEvent(result, "publish", true);
            assertEvent(result, "candidate-health", true);

            LifecycleActionResult refresh = service.executePersistedLifecycleResultWithStoredPassword(
                    request.application().id(), LifecycleAction.REFRESH_STATUS,
                    "phase1-wrapper-master".toCharArray());
            assertTrue(refresh.accepted(), refresh::toString);
            assertTrue(refresh.observation().orElseThrow().ownershipVerified());
            assertEquals(RuntimeState.RUNNING, refresh.observation().orElseThrow().runtimeState());
        }
    }

    private static void assertArchiveContainsWrapper(SourcePreparation preparation) throws Exception {
        Set<String> expectedEntries = new LinkedHashSet<>(Set.of(
                "mvnw", ".mvn/wrapper/maven-wrapper.properties", ".mvn/wrapper/maven-wrapper.jar"
        ));
        try (InputStream input = new GZIPInputStream(Files.newInputStream(preparation.archive().orElseThrow().localArchive()))) {
            while (!expectedEntries.isEmpty()) {
                byte[] header = input.readNBytes(512);
                assertEquals(512, header.length, "tar archive ended before required Wrapper entries");
                if (isZeroBlock(header)) {
                    break;
                }
                String name = tarString(header, 0, 100);
                String prefix = tarString(header, 345, 155);
                String entry = prefix.isEmpty() ? name : prefix + "/" + name;
                expectedEntries.remove(entry);
                long size = tarOctal(header, 124, 12);
                input.skipNBytes(size);
                input.skipNBytes((512 - (size % 512)) % 512);
            }
        }
        assertTrue(expectedEntries.isEmpty(), () -> "tar archive is missing Wrapper entries: " + expectedEntries);
    }

    private static boolean isZeroBlock(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String tarString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long tarOctal(byte[] bytes, int offset, int length) {
        long value = 0;
        for (int index = offset; index < offset + length; index++) {
            byte current = bytes[index];
            if (current >= '0' && current <= '7') {
                value = value * 8 + current - '0';
            }
        }
        return value;
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

    private static void assertEvent(DeploymentOutcome result, String step, boolean succeeded) {
        assertTrue(result.events().stream().anyMatch(event -> step.equals(event.step()) && succeeded == event.succeeded()),
                () -> "missing event " + step + "=" + succeeded + ": " + result.events());
    }

    private static void assertPresent(String value, String name) {
        assertTrue(value != null && !value.isBlank(), name + " is required");
    }
}
