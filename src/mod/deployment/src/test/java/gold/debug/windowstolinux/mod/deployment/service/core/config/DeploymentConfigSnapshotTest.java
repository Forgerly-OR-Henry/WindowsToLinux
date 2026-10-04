package gold.debug.windowstolinux.mod.deployment.service.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentConfigException;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRef;
import org.junit.jupiter.api.Test;

class DeploymentConfigSnapshotTest {
    @Test
    void migratedDigestMatchesOriginalAlgorithmAndIgnoresOnlyOrderingAndTimestamp() {
        var entries = DeploymentConfigInput.parse("PORT=8080;BUILD:OPTIMIZE=true;LABEL=production");
        var now = Instant.parse("2026-09-27T00:00:00.123456Z");
        var snapshot = DeploymentConfigSnapshot.create("demo", 2, "v1", now, entries);
        // Frozen original digest; original-format-provenance.md records the archived writer. / 冻结的原摘要；出处见旧格式样本记录。
        assertEquals("572a20bfd4263bfa7bc11b9a0ef0ce46b46710ea01439566982ce882ef2a3134", snapshot.sha256());
        assertEquals(snapshot.sha256(), DeploymentConfigSnapshot
            .create("demo", 2, "v1", now.plusSeconds(1), entries.reversed()).sha256());
        assertNotEquals(snapshot.sha256(),
            DeploymentConfigSnapshot.create("demo", 3, "v1", now, entries).sha256());
        assertNotEquals(snapshot.sha256(),
            DeploymentConfigSnapshot.create("other", 2, "v1", now, entries).sha256());
        assertNotEquals(snapshot.sha256(),
            DeploymentConfigSnapshot.create("demo", 2, "v2", now, entries).sha256());
    }

    @Test
    void valuesAreTypedAndScopeControlsLookup() {
        var entries = DeploymentConfigInput.parse("PORT=8080;BUILD:OPTIMIZE=true;LABEL=  production  ");
        var snapshot = DeploymentConfigSnapshot.create("demo", 1, "v1", Instant.EPOCH, entries);
        assertEquals(new DeploymentConfigValue.Number(8080),
            snapshot.requireValue("PORT", DeploymentConfigScopeType.RUNTIME));
        assertEquals(new DeploymentConfigValue.Flag(true),
            snapshot.requireValue("OPTIMIZE", DeploymentConfigScopeType.BUILD));
        assertEquals(new DeploymentConfigValue.Text("production"),
            snapshot.requireValue("LABEL", DeploymentConfigScopeType.RUNTIME));
        assertThrows(IllegalArgumentException.class,
            () -> snapshot.requireValue("OPTIMIZE", DeploymentConfigScopeType.RUNTIME));
    }

    @Test
    void mutableInputAndTamperedDigestCannotChangeAcceptedSnapshot() {
        var entries = new ArrayList<>(DeploymentConfigInput.parse("PORT=80"));
        var snapshot = DeploymentConfigSnapshot.create("demo", 1, "v1", Instant.EPOCH, entries);
        entries.clear();
        assertEquals(1, snapshot.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
        var failure = assertThrows(DeploymentConfigException.class,
            () -> new DeploymentConfigSnapshot("demo", 1, "v1", Instant.EPOCH, snapshot.entries(),
                "a".repeat(64)));
        assertEquals(ProblemCode.INVALID_INPUT, failure.problem().code());
        assertEquals("configuration.snapshot.integrity-failed", failure.problem().eventCode());
    }

    @Test
    void duplicateKeysAndSecretsAreRejectedWithoutEchoingSubmittedValues() {
        var duplicate = DeploymentConfigInput.parse("PORT=80;BUILD:PORT=81");
        assertThrows(DeploymentConfigException.class,
            () -> DeploymentConfigSnapshot.create("demo", 1, "v1", Instant.EPOCH, duplicate));
        var failure = assertThrows(DeploymentConfigException.class,
            () -> DeploymentConfigInput.parse("API_TOKEN=do-not-echo-this"));
        assertFalse(failure.problem().toString().contains("do-not-echo-this"));
        assertThrows(DeploymentConfigException.class, () -> DeploymentConfigInput.parse("PORT=70000"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentConfigInput.parse("UNKNOWN:PORT=80"));
        assertThrows(DeploymentConfigException.class, () -> new DeploymentConfigValue.Text("bad\nvalue"));
    }

    @Test
    void secretReferencesRemainOpaqueExactPositiveRevisions() {
        assertEquals(List.of(new DeploymentSecretRef("database-password", 2)),
            DeploymentConfigInput.secrets("database-password:2"));
        assertThrows(DeploymentConfigException.class,
            () -> DeploymentConfigInput.secrets("database-password:0"));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentConfigInput.secrets("database-password:invalid"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentConfigInput.secrets("missing-revision"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentConfigInput.secrets("database-password:"));
        assertThrows(DeploymentConfigException.class,
            () -> DeploymentConfigInput.secrets("database-password:-1"));
        assertThrows(DeploymentConfigException.class,
            () -> DeploymentConfigSnapshot.create("../demo", 1, "v1", Instant.EPOCH, List.of()));
    }
}
