package gold.debug.windowstolinux.shared.backup.manifest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackupManifestCodecTest {
    @Test
    void roundTripsCurrentManifestDeterministically() throws Exception {
        BackupManifest manifest = sampleManifest(new byte[]{1, 2, 3});
        BackupManifestCodec codec = new BackupManifestCodec();

        byte[] first = codec.write(manifest);
        BackupManifest restored = codec.read(first);

        assertEquals(manifest, restored);
        assertEquals(new String(first, StandardCharsets.UTF_8),
                new String(codec.write(restored), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnknownAndDuplicateJsonFields() throws Exception {
        BackupManifestCodec codec = new BackupManifestCodec();
        String json = new String(codec.write(sampleManifest(new byte[]{1})), StandardCharsets.UTF_8);
        String unknown = json.replaceFirst("\\{", "{\\\"unknown\\\":true,");
        String duplicate = json.replaceFirst("\\{", "{\\\"format\\\":\\\"windowstolinux-backup\\\",");

        assertThrows(Exception.class, () -> codec.read(unknown.getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> codec.read(duplicate.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void databaseEvidenceCannotClaimUnsupportedConsistency() {
        assertThrows(IllegalArgumentException.class, () -> new BackupDatabase(
                BackupDatabaseType.POSTGRESQL, "main", "16", "pg_dump 16",
                BackupConsistencyMode.MYSQL_TRANSACTION_SNAPSHOT, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BackupDatabase(
                BackupDatabaseType.SQLITE, "main.db", "3.46", "copy",
                BackupConsistencyMode.NOT_APPLICABLE, List.of()));
    }

    private static BackupManifest sampleManifest(byte[] content) throws Exception {
        String digest = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        List<BackupMember> members = List.of(
                new BackupMember("releases/release-1.json", content.length, digest, BackupMemberKind.RELEASE),
                new BackupMember("config/application.json", content.length, digest, BackupMemberKind.CONFIGURATION),
                new BackupMember("runtime/sample.service", content.length, digest, BackupMemberKind.RUNTIME));
        BackupHealthCheck health = BackupHealthCheck.tcp(8080, 30, 5);
        BackupComponent component = new BackupComponent("sample", "sample", "a".repeat(64),
                "releases/release-1.json", "config/application.json", "runtime/sample.service", List.of(),
                new BackupComponentRuntime.SpringBoot(health));
        BackupInventory inventory = new BackupInventory(
                List.of("releases/release-1.json"), List.of("config/application.json"), List.of("db.password"),
                List.of("/srv/sample/content"), List.of("sample-content"), BackupDatabase.none(),
                new BackupIdentity("sample", "server-1", "/opt/windowstolinux/apps/sample", "release-1"),
                List.of("runtime/sample.service"), List.of(component), "sample", health,
                new BackupRuntime("ubuntu", "24.04", "systemd", "255", "x86_64", List.of("systemd")),
                List.of("restore requires the managed root to be empty"));
        return BackupManifest.create(Instant.parse("2026-08-21T00:00:00Z"), "sample", inventory, members);
    }
}
