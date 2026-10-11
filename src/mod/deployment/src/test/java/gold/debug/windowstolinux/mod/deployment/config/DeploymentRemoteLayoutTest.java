package gold.debug.windowstolinux.mod.deployment.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DeploymentRemoteLayoutTest {
    private static final String ORIGINAL = "{\"formatVersion\":1,\"remote\":{\"helperDirectory\":\"/usr/local/lib/windowstolinux\","
        + "\"applicationsRoot\":\"/opt/windowstolinux/apps\",\"stateRoot\":\"/var/lib/windowstolinux\","
        + "\"configurationRoot\":\"/etc/opt/windowstolinux/apps\",\"dataRoot\":\"/var/opt/windowstolinux/apps\"}}";

    @Test
    void defaultEncodingAndDigestArePinned() {
        var layout = DeploymentRemoteLayout.original();
        assertEquals(1, layout.version());
        assertEquals(DeploymentConfig.RemotePaths.original(), layout.paths());
        assertEquals(ORIGINAL, layout.canonical());
        assertEquals("d332b2fba4c6080e3a00239cc51f6867dd230fe198a3720a6d28a5172cfd21d0", layout.digest());
        assertEquals(layout, DeploymentRemoteLayout.decode(ORIGINAL));
        assertEquals("/var/lib/windowstolinux/work/demo-0123456789abcdef", layout.candidateRoot("demo-0123456789abcdef"));
    }

    @Test
    void nonDefaultPathsAndEvidenceDirectoriesStayWithinTheirOwnRoots() {
        var paths = new DeploymentConfig.RemotePaths("/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state",
            "/srv/wtl/config", "/srv/wtl/data");
        var layout = new DeploymentRemoteLayout(paths);
        assertSame(paths, layout.paths());
        assertEquals("/srv/wtl/helper/managed-helper", layout.helperPath());
        assertEquals("/srv/wtl/helper/build-entry", layout.buildEntry());
        assertEquals("/srv/wtl/helper/toolchains", layout.toolchains());
        assertEquals("/srv/wtl/state/work", layout.workRoot());
        assertEquals("/srv/wtl/state/work/web-api-0123456789abcdef", layout.candidateRoot("web-api-0123456789abcdef"));
        assertEquals("/srv/wtl/apps/web-api", layout.applicationRoot("web-api"));
        assertEquals("/srv/wtl/config/web-api", layout.configurationRoot("web-api"));
        assertEquals("/srv/wtl/data/web-api", layout.dataRoot("web-api"));
        assertEquals("/srv/wtl/state/snapshots", layout.snapshotsRoot());
        UUID task = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertEquals("/srv/wtl/state/snapshots/web-api-" + task, layout.snapshotRoot("web-api", task));
        assertEquals("/srv/wtl/state/backups", layout.backupsRoot());
        assertEquals("/srv/wtl/state/application-locks/web-api", layout.applicationLockRoot("web-api"));
        assertEquals("/srv/wtl/state/helper-install-operations", layout.helperInstallOperationsRoot());
        assertEquals("/srv/wtl/state/model-command-operations", layout.modelCommandOperationsRoot());
        assertEquals("/srv/wtl/state/environment-operations", layout.environmentOperationsRoot());
        assertEquals(layout, DeploymentRemoteLayout.decode(layout.canonical()));
        assertNotEquals(DeploymentRemoteLayout.original().digest(), layout.digest());
    }

    @Test
    void strictDecodingRejectsVersionSchemaSyntaxAndRootDrift() {
        for (String invalid : List.of("", "null", "[]", "{}", "{\"formatVersion\":1,\"remote\":null}",
            "{\"formatVersion\":1,\"remote\":[]}", "{\"formatVersion\":1,\"remote\":\"default\"}", ORIGINAL + " {}", ORIGINAL + "\n",
            ORIGINAL.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            ORIGINAL.replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
            ORIGINAL.replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
            ORIGINAL.replace("\"formatVersion\":1", "\"formatVersion\":2147483648"),
            ORIGINAL.replace("\"formatVersion\":1", "\"formatVersion\":null"),
            ORIGINAL.replace("\"formatVersion\":1,", ""),
            ORIGINAL.replace("\"remote\":{", "\"unknown\":0,\"remote\":{"),
            ORIGINAL.replace("\"remote\":{", "\"remote\":{\"extra\":\"/srv/extra\","),
            ORIGINAL.replace("\"stateRoot\":\"/var/lib/windowstolinux\",", ""),
            ORIGINAL.replace("\"stateRoot\":\"/var/lib/windowstolinux\"", "\"stateRoot\":12"),
            ORIGINAL.replace("\"stateRoot\":\"/var/lib/windowstolinux\"", "\"stateRoot\":null"),
            ORIGINAL.replace("\"stateRoot\":\"/var/lib/windowstolinux\"", "\"stateRoot\":{}"),
            ORIGINAL.replace("/var/lib/windowstolinux", "/usr/local/lib/windowstolinux"),
            ORIGINAL.replace("/var/lib/windowstolinux", "/run/wtl"),
            ORIGINAL.replace("/var/lib/windowstolinux", "/srv//wtl"),
            ORIGINAL.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            ORIGINAL.replace("\"stateRoot\":\"/var/lib/windowstolinux\"", "\"stateRoot\":\"/var/lib/windowstolinux\",\"stateRoot\":\"/var/lib/windowstolinux\""),
            ORIGINAL.replace("\"remote\":", "\"remote\" :"), ORIGINAL + " ".repeat(4096)))
            assertThrows(IllegalArgumentException.class, () -> DeploymentRemoteLayout.decode(invalid), invalid);
        assertThrows(IllegalArgumentException.class, () -> DeploymentRemoteLayout.decode(null));
        var multibyte = assertThrows(IllegalArgumentException.class,
            () -> DeploymentRemoteLayout.decode("{\"padding\":\"" + "界".repeat(1600) + "\"}"));
        assertTrue(multibyte.getMessage().contains("exceeds limits"));
    }

    @Test
    void derivedIdentitiesRejectNormalizationTraversalAndMalformedCandidates() {
        var layout = DeploymentRemoteLayout.original();
        for (String app : List.of("Demo", "../demo", "demo/other", "demo;id", "", "a".repeat(64)))
            assertThrows(IllegalArgumentException.class, () -> layout.applicationRoot(app), app);
        for (String candidate : List.of("demo-0123456789abcde", "demo-0123456789abcdeg", "demo-0123456789ABCDEF",
            "Demo-0123456789abcdef", "../demo-0123456789abcdef", "demo-0123456789abcdef/other"))
            assertThrows(IllegalArgumentException.class, () -> layout.candidateRoot(candidate), candidate);
        assertThrows(NullPointerException.class, () -> new DeploymentRemoteLayout(null));
        assertThrows(NullPointerException.class, () -> layout.snapshotRoot("demo", null));
    }
}
