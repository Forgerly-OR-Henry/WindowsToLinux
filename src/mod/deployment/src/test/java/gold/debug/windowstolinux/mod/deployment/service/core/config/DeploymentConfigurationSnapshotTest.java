package gold.debug.windowstolinux.mod.deployment.service.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentConfigurationException;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference;
import org.junit.jupiter.api.Test;

class DeploymentConfigurationSnapshotTest {
    @Test
    void migratedDigestMatchesOriginalAlgorithmAndIgnoresOnlyOrderingAndTimestamp() {
        var entries = DeploymentConfigurationInput.parse("PORT=8080;BUILD:OPTIMIZE=true;LABEL=production");
        var now = Instant.parse("2026-09-27T00:00:00.123456Z");
        var snapshot = DeploymentConfigurationSnapshot.create("demo", 2, "v1", now, entries);
        var legacy = gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot.create("demo", 2, "v1", now,
                gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser
                        .parse("PORT=8080;BUILD:OPTIMIZE=true;LABEL=production"));
        assertEquals(legacy.sha256(), snapshot.sha256());
        assertEquals(snapshot.sha256(), DeploymentConfigurationSnapshot
                .create("demo", 2, "v1", now.plusSeconds(1), entries.reversed()).sha256());
        assertNotEquals(snapshot.sha256(),
                DeploymentConfigurationSnapshot.create("demo", 3, "v1", now, entries).sha256());
        assertNotEquals(snapshot.sha256(),
                DeploymentConfigurationSnapshot.create("other", 2, "v1", now, entries).sha256());
        assertNotEquals(snapshot.sha256(),
                DeploymentConfigurationSnapshot.create("demo", 2, "v2", now, entries).sha256());
    }

    @Test
    void valuesAreTypedAndScopeControlsLookup() {
        var entries = DeploymentConfigurationInput.parse("PORT=8080;BUILD:OPTIMIZE=true;LABEL=  production  ");
        var snapshot = DeploymentConfigurationSnapshot.create("demo", 1, "v1", Instant.EPOCH, entries);
        assertEquals(new DeploymentConfigurationValue.Number(8080),
                snapshot.requireValue("PORT", DeploymentConfigurationScopeType.RUNTIME));
        assertEquals(new DeploymentConfigurationValue.Flag(true),
                snapshot.requireValue("OPTIMIZE", DeploymentConfigurationScopeType.BUILD));
        assertEquals(new DeploymentConfigurationValue.Text("production"),
                snapshot.requireValue("LABEL", DeploymentConfigurationScopeType.RUNTIME));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot.requireValue("OPTIMIZE", DeploymentConfigurationScopeType.RUNTIME));
    }

    @Test
    void mutableInputAndTamperedDigestCannotChangeAcceptedSnapshot() {
        var entries = new ArrayList<>(DeploymentConfigurationInput.parse("PORT=80"));
        var snapshot = DeploymentConfigurationSnapshot.create("demo", 1, "v1", Instant.EPOCH, entries);
        entries.clear();
        assertEquals(1, snapshot.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
        var failure = assertThrows(DeploymentConfigurationException.class,
                () -> new DeploymentConfigurationSnapshot("demo", 1, "v1", Instant.EPOCH, snapshot.entries(),
                        "a".repeat(64)));
        assertEquals(ProblemCode.INVALID_INPUT, failure.problem().code());
        assertEquals("configuration.snapshot.integrity-failed", failure.problem().eventCode());
    }

    @Test
    void duplicateKeysAndSecretsAreRejectedWithoutEchoingSubmittedValues() {
        var duplicate = DeploymentConfigurationInput.parse("PORT=80;BUILD:PORT=81");
        assertThrows(DeploymentConfigurationException.class,
                () -> DeploymentConfigurationSnapshot.create("demo", 1, "v1", Instant.EPOCH, duplicate));
        var failure = assertThrows(DeploymentConfigurationException.class,
                () -> DeploymentConfigurationInput.parse("API_TOKEN=do-not-echo-this"));
        assertFalse(failure.problem().toString().contains("do-not-echo-this"));
        assertThrows(DeploymentConfigurationException.class, () -> DeploymentConfigurationInput.parse("PORT=70000"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentConfigurationInput.parse("UNKNOWN:PORT=80"));
        assertThrows(DeploymentConfigurationException.class, () -> new DeploymentConfigurationValue.Text("bad\nvalue"));
    }

    @Test
    void secretReferencesRemainOpaqueExactPositiveRevisions() {
        assertEquals(List.of(new DeploymentSecretReference("database-password", 2)),
                DeploymentConfigurationInput.secrets("database-password:2"));
        assertThrows(DeploymentConfigurationException.class,
                () -> DeploymentConfigurationInput.secrets("database-password:0"));
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentConfigurationInput.secrets("database-password:invalid"));
        assertThrows(DeploymentConfigurationException.class,
                () -> DeploymentConfigurationSnapshot.create("../demo", 1, "v1", Instant.EPOCH, List.of()));
    }
}
