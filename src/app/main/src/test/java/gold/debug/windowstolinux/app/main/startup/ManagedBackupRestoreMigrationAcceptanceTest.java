package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.app.service.backup.CreatedBackupArchive;
import gold.debug.windowstolinux.app.service.backup.ManagedOfflineMigrationOutcome;
import gold.debug.windowstolinux.app.service.backup.ManagedRestoreControlState;
import gold.debug.windowstolinux.app.service.backup.ManagedRestoreOutcome;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.backup.execution.migration.OfflineMigrationStatus;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreStatus;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in product-entrypoint acceptance for managed backup, restore and two-server migration. / 受管备份、恢复及双服务器迁移产品入口的可选验收。 */
@EnabledIfSystemProperty(named = "managed.backup-restore-migration-acceptance", matches = "true")
class ManagedBackupRestoreMigrationAcceptanceTest {
    private static final int PORT_BASE = 40000 + (int) ((System.currentTimeMillis() / 1000) % 8000);

    private static final String RUN_ID = Long.toUnsignedString(System.nanoTime(), 36);

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsCompleteArchiveAndRestoresItToSecondServer() throws Exception {
        String applicationId = applicationId("restore");
        int port = PORT_BASE + 1;
        char[] backupPassword = requiredBackupPassword();
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(
                temporaryDirectory.resolve("restore-context"))) {
            ServerProfile target = context.registerTargetServer();
            deployStaticSite(context, applicationId, port);

            Path requestedArchive = temporaryDirectory.resolve("archives").resolve(applicationId + ".wtlbak");
            CreatedBackupArchive created = context.createManagedBackup(applicationId, requestedArchive, backupPassword);
            assertEquals(requestedArchive.toAbsolutePath().normalize(), created.archive());
            assertTrue(Files.isRegularFile(created.archive()));
            assertTrue(created.inspection().archiveSha256().matches("[0-9a-f]{64}"));

            ManagedRestoreOutcome restored = context.restoreManagedBackup(created.archive(), target, backupPassword);
            assertEquals(BackupRestoreStatus.SUCCEEDED, restored.restore().status(),
                    () -> restored.restore().events().toString());
            assertEquals(ManagedRestoreControlState.DEFERRED_SOURCE_RETAINED, restored.controlState());
            assertTrue(restored.localFailure().isEmpty());
            assertHttp(requiredProperty("managed.target.ssh.host"), port, "static-live-ok");
        } finally {
            Arrays.fill(backupPassword, '\0');
        }
    }

    @Test
    void stopsSourceAndPreparesVerifiedTargetWithoutSwitchingTraffic() throws Exception {
        String applicationId = applicationId("migration");
        int port = PORT_BASE + 2;
        char[] backupPassword = requiredBackupPassword();
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(
                temporaryDirectory.resolve("migration-context"))) {
            ServerProfile target = context.registerTargetServer();
            deployStaticSite(context, applicationId, port);

            ManagedOfflineMigrationOutcome outcome = context.prepareManagedOfflineMigration(applicationId, target,
                    backupPassword);
            assertEquals(OfflineMigrationStatus.READY_FOR_MANUAL_TRAFFIC_SWITCH, outcome.migration().status(),
                    () -> outcome.migration().events().toString());
            assertTrue(outcome.migration().sourceWritesStopped());
            assertTrue(outcome.migration().targetCandidateReady());
            assertTrue(outcome.migration().sourceRetained());
            assertFalse(outcome.migration().externalTrafficSwitched());
            Path finalArchive = outcome.retainedFinalArchive().orElseThrow();
            assertTrue(Files.isRegularFile(finalArchive));
            assertTrue(finalArchive.startsWith(
                    temporaryDirectory.resolve("migration-context").resolve("backups").toAbsolutePath().normalize()));
            assertEquals(RuntimeState.STOPPED,
                    context.lifecycle(applicationId, LifecycleAction.REFRESH_STATUS).runtimeState());
            assertHttp(requiredProperty("managed.target.ssh.host"), port, "static-live-ok");
        } finally {
            Arrays.fill(backupPassword, '\0');
        }
    }

    private void deployStaticSite(LiveTypedDeploymentContext context, String applicationId, int port) throws Exception {
        ReviewedSourcePreparation source = context.prepare(
                TypedAcceptanceFixture.staticSite(temporaryDirectory.resolve("sources"), applicationId),
                DeploymentProjectType.STATIC_SITE);
        DeploymentResult result = context.deploy(source, 1, List.of(
                new ConfigurationEntry("ACCEPTANCE_RUN_ID", ConfigurationScope.RUNTIME,
                        new ConfigurationValue.Text(RUN_ID)),
                new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(port))),
                List.of(), new DeploymentRuntimeSpecification.StaticSite("public", health(port)), access(port));
        assertEquals(DeploymentStatus.SUCCEEDED, result.status(), () -> result.events().toString());
        assertHttp(requiredProperty("managed.ssh.host"), port, "static-live-ok");
    }

    private static HealthCheck.Http health(int port) {
        return new HealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/"), 200, 30);
    }

    private static Optional<UserAccessUrl> access(int port) {
        return Optional
                .of(new UserAccessUrl(URI.create("http://" + requiredProperty("managed.ssh.host") + ":" + port + "/")));
    }

    private static void assertHttp(String host, int port, String marker) throws Exception {
        URI uri = URI.create("http://" + host + ":" + port + "/");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        try {
            assertEquals(200, connection.getResponseCode(), () -> "desktop HTTP access failed: " + uri);
            try (InputStream input = connection.getInputStream()) {
                String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains(marker), () -> "response lacked expected marker: " + uri);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static char[] requiredBackupPassword() {
        String value = System.getenv("WINDOWSTOLINUX_TEST_BACKUP_PASSWORD");
        assertTrue(value != null && value.length() >= 12,
                "WINDOWSTOLINUX_TEST_BACKUP_PASSWORD with at least 12 characters is required");
        return value.toCharArray();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private static String applicationId(String purpose) {
        return "wtl-managed-" + purpose + "-" + RUN_ID;
    }
}
