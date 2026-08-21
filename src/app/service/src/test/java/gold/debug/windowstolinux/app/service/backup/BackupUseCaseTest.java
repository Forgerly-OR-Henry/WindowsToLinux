package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchivePolicy;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupProvenanceStatus;
import gold.debug.windowstolinux.shared.backup.format.BackupArchiveContent;
import gold.debug.windowstolinux.shared.backup.format.BackupArchiveWriter;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponent;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponentRuntime;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;
import gold.debug.windowstolinux.shared.backup.manifest.BackupHealthCheck;
import gold.debug.windowstolinux.shared.backup.manifest.BackupIdentity;
import gold.debug.windowstolinux.shared.backup.manifest.BackupInventory;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.backup.manifest.BackupRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupUseCaseTest {
    @TempDir Path temporary;

    @Test
    void inspectsAndPreparesLocalCandidateWithoutRemoteMutation() throws Exception {
        byte[] content = "validated backup content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path archive = archive(content);
        BackupUseCase useCase = new BackupUseCase(temporary.resolve("work"));

        BackupArchiveInspection inspection = useCase.inspect(archive);
        PreparedBackupCandidate prepared = useCase.prepare(archive);

        assertEquals("sample", inspection.applicationId());
        assertEquals("3", inspection.schemaVersion());
        assertEquals(1, inspection.componentCount());
        assertEquals(3, inspection.memberCount());
        assertEquals(content.length * 3L, inspection.verifiedBytes());
        assertEquals(BackupProvenanceStatus.NOT_PRESENT, inspection.provenanceStatus());
        assertEquals(inspection, prepared.inspection());
        assertEquals(content.length * 3L, prepared.extractedBytes());
        assertTrue(prepared.candidateRoot().startsWith(temporary.resolve("work").toAbsolutePath()));
        assertArrayEquals(content, Files.readAllBytes(prepared.candidateRoot().resolve("config/sample.json")));
        assertFalse(Files.exists(prepared.candidateRoot().resolve("current")));
    }

    private Path archive(byte[] content) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        List<BackupMember> members = List.of(
                new BackupMember("releases/sample.json", content.length, digest, BackupMemberKind.RELEASE),
                new BackupMember("config/sample.json", content.length, digest, BackupMemberKind.CONFIGURATION),
                new BackupMember("runtime/sample.service", content.length, digest, BackupMemberKind.RUNTIME));
        BackupHealthCheck health = BackupHealthCheck.tcp(8080, 30, 5);
        BackupComponent component = new BackupComponent("sample", "sample", "a".repeat(64),
                "releases/sample.json", "config/sample.json", "runtime/sample.service", List.of(),
                new BackupComponentRuntime.NodeService(22, health));
        BackupInventory inventory = new BackupInventory(
                List.of("releases/sample.json"), List.of("config/sample.json"), List.of(), List.of(), List.of(),
                BackupDatabase.none(),
                new BackupIdentity("sample", "server-1", "/var/lib/windowstolinux/apps/sample", "release-1"),
                List.of("runtime/sample.service"), List.of(component), "sample", health,
                new BackupRuntime("ubuntu", "24.04", "systemd", "255", "x86_64", List.of("systemd")),
                List.of());
        BackupManifest manifest = BackupManifest.create(
                Instant.parse("2026-08-22T00:00:00Z"), "sample", inventory, members);
        List<BackupArchiveContent> streams = members.stream()
                .map(member -> new BackupArchiveContent(member, () -> new ByteArrayInputStream(content))).toList();
        Path archive = temporary.resolve("sample.wtl-backup.zip");
        try (OutputStream output = Files.newOutputStream(archive)) {
            new BackupArchiveWriter(BackupArchivePolicy.defaults()).write(manifest, streams, output);
        }
        return archive;
    }
}
