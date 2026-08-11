package gold.debug.windowstolinux.shared.config.revision;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigurationSnapshotTest {
    @Test
    void computesAStableDigestIndependentOfEntryOrder() {
        List<ConfigurationEntry> original = List.of(
                new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(8080)),
                new ConfigurationEntry("SPRING_PROFILES_ACTIVE", ConfigurationScope.RUNTIME, new ConfigurationValue.Text("prod"))
        );
        List<ConfigurationEntry> reversed = List.of(original.get(1), original.get(0));

        ConfigurationSnapshot first = ConfigurationSnapshot.create("demo", 2, "v1", Instant.parse("2026-08-12T00:00:00Z"), original);
        ConfigurationSnapshot second = ConfigurationSnapshot.create("demo", 2, "v1", Instant.parse("2026-08-12T01:00:00Z"), reversed);

        assertEquals(first.sha256(), second.sha256());
        assertEquals("prod", ((ConfigurationValue.Text) first.requireValue("SPRING_PROFILES_ACTIVE", ConfigurationScope.RUNTIME)).value());
    }

    @Test
    void rejectsSecretLikeKeysAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationEntry(
                "API_TOKEN", ConfigurationScope.RUNTIME, new ConfigurationValue.Text("not-allowed")
        ));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationEntry(
                "PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(70000)
        ));
        assertThrows(IllegalArgumentException.class, () -> new SecretReference("database-password", 0));
    }
}
