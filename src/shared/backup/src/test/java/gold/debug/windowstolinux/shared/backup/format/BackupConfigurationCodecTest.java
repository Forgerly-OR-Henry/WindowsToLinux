package gold.debug.windowstolinux.shared.backup.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseEngineType;
import gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import org.junit.jupiter.api.Test;

/** Verifies exact non-secret activation state without weakening historical inspection. / 验证精确无秘密激活状态且不削弱历史检查能力。 */
class BackupConfigurationCodecTest {
    private final BackupConfigurationCodec codec = new BackupConfigurationCodec();

    @Test
    void roundTripsConfigurationResourcesRuntimeAndUserAccessUrl() throws Exception {
        BackupConfigurationDocument document = document();

        assertEquals(document, codec.readActivation(codec.writeActivation(document)));
        assertEquals(document.configuration(), codec.read(codec.writeActivation(document)));
    }

    @Test
    void keepsHistoricalConfigurationInspectableButRejectsAutomaticActivation() throws Exception {
        byte[] historical = codec.write(document().configuration());

        assertEquals(document().configuration(), codec.read(historical));
        assertThrows(IOException.class, () -> codec.readActivation(historical));
    }

    @Test
    void emptyConfigurationForAnOnDemandToolSurvivesBackupActivation() throws Exception {
        var command = new gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand("main.py",
                List.of("--help"));
        var workload = new gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload(
                gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.ON_DEMAND,
                true, command, "", List.of(), Optional.of(command), "usage", Optional.empty(), List.of(), List.of());
        var runtime = new ManagedApplicationRuntimeConfiguration(new HealthCheck.Command(command, "usage", 10),
                Optional.empty(), gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode.SYSTEMD_STATIC,
                workload);
        var document = new BackupConfigurationDocument(
                ConfigurationSnapshot.create("cli", 1, "runtime-v1", Instant.now(), List.of()),
                new ManagedComponentResourceBindings(List.of(), Optional.of(List.of())), runtime);
        assertEquals(document, codec.readActivation(codec.writeActivation(document)));
    }

    @Test
    void rejectsTruncationAndTrailingBytes() throws Exception {
        byte[] valid = codec.writeActivation(document());

        assertThrows(IOException.class, () -> codec.readActivation(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IOException.class, () -> codec.readActivation(Arrays.copyOf(valid, valid.length + 1)));
    }

    private static BackupConfigurationDocument document() {
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create("shop-api", 7, "v2",
                Instant.parse("2026-08-22T00:00:00Z"),
                List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                        new ConfigurationValue.Number(18081)),
                        new ConfigurationEntry("PUBLIC_NAME", ConfigurationScope.BUILD,
                                new ConfigurationValue.Text("shop"))));
        ManagedComponentResourceBindings resources = new ManagedComponentResourceBindings(
                List.of(new ManagedFileBinding("file-uploads",
                        new ComponentDataPath("uploads", ComponentDataPath.AccessMode.READ_WRITE, "uploads-v1",
                                false))),
                Optional.of(List.of(new ManagedDatabaseBinding("orders",
                        new ManagedDatabaseConnection.Server(ManagedDatabaseEngineType.POSTGRESQL, "db.internal", 5432,
                                "orders", "application", new SecretReference("database-password", 7), true)))));
        ManagedApplicationRuntimeConfiguration runtime = new ManagedApplicationRuntimeConfiguration(
                new HealthCheck.Http(URI.create("http://127.0.0.1:18081/health"), 200, 15),
                Optional.of(new UserAccessUrl(URI.create("https://shop.example.test/"))));
        return new BackupConfigurationDocument(configuration, resources, runtime);
    }
}
