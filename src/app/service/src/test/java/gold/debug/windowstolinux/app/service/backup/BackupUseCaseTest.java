package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.secret.crypto.BackupSecretCryptoService;
import gold.debug.windowstolinux.app.secret.crypto.BackupSecretException;
import gold.debug.windowstolinux.app.secret.crypto.BackupSecretFailureType;
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
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void preparesOnlyACompleteManifestBoundSecretRevisionSet() throws Exception {
        byte[] content = "validated backup content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        char[] encryptionPassword = "independent backup password".toCharArray();
        byte[] envelope;
        try (ResolvedSecretRevision revision = new ResolvedSecretRevision(
                new SecretReference("database-password", 4), "private-database-value".toCharArray())) {
            envelope = new BackupSecretCryptoService().encryptRevisions(encryptionPassword, List.of(revision));
        }
        Path archive = archive(content, envelope, List.of("database-password"), "with-secret");
        char[] restorePassword = "independent backup password".toCharArray();

        try (PreparedBackupSecrets prepared = new BackupUseCase(temporary.resolve("secret-work"))
                .prepareWithSecrets(archive, restorePassword)) {
            assertEquals(List.of(new SecretReference("database-password", 4)),
                    prepared.secrets().revisions().stream().map(ResolvedSecretRevision::reference).toList());
            assertTrue(Files.isRegularFile(prepared.candidate().candidateRoot().resolve("secrets.enc")));
        }
        assertTrue(allCleared(restorePassword));
    }

    @Test
    void mismatchedAuthenticatedSecretSetReturnsNothingAndCleansItsAttempt() throws Exception {
        byte[] content = "validated backup content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        char[] encryptionPassword = "independent backup password".toCharArray();
        byte[] envelope;
        try (ResolvedSecretRevision revision = new ResolvedSecretRevision(
                new SecretReference("other-password", 1), "private-value".toCharArray())) {
            envelope = new BackupSecretCryptoService().encryptRevisions(encryptionPassword, List.of(revision));
        }
        Path archive = archive(content, envelope, List.of("database-password"), "mismatched-secret");
        Path work = temporary.resolve("mismatch-work");
        char[] restorePassword = "independent backup password".toCharArray();

        BackupSecretException failure = assertThrows(BackupSecretException.class,
                () -> new BackupUseCase(work).prepareWithSecrets(archive, restorePassword));

        assertEquals(BackupSecretFailureType.PAYLOAD_INVALID.code(), failure.failure().code());
        assertTrue(allCleared(restorePassword));
        try (var attempts = Files.list(work.resolve("restore-candidates"))) {
            assertEquals(0, attempts.count());
        }
    }

    private Path archive(byte[] content) throws Exception {
        return archive(content, null, List.of(), "sample");
    }

    private Path archive(byte[] content, byte[] envelope, List<String> secretReferences, String fileName)
            throws Exception {
        Map<String, byte[]> values = new LinkedHashMap<>();
        values.put("releases/sample.json", content);
        values.put("config/sample.json", content);
        values.put("runtime/sample.service", content);
        if (envelope != null) values.put("secrets.enc", envelope);
        List<BackupMember> members = values.entrySet().stream().map(entry -> new BackupMember(
                entry.getKey(), entry.getValue().length, digest(entry.getValue()), kind(entry.getKey()))).toList();
        BackupHealthCheck health = BackupHealthCheck.tcp(8080, 30, 5);
        BackupComponent component = new BackupComponent("sample", "sample", "a".repeat(64),
                "releases/sample.json", "config/sample.json", "runtime/sample.service", List.of(),
                new BackupComponentRuntime.NodeService(22, health));
        BackupInventory inventory = new BackupInventory(
                List.of("releases/sample.json"), List.of("config/sample.json"), secretReferences, List.of(), List.of(),
                BackupDatabase.none(),
                new BackupIdentity("sample", "server-1", "/var/lib/windowstolinux/apps/sample", "release-1"),
                List.of("runtime/sample.service"), List.of(component), "sample", health,
                new BackupRuntime("ubuntu", "24.04", "systemd", "255", "x86_64", List.of("systemd")),
                List.of());
        BackupManifest manifest = BackupManifest.create(
                Instant.parse("2026-08-22T00:00:00Z"), "sample", inventory, members);
        List<BackupArchiveContent> streams = members.stream()
                .map(member -> new BackupArchiveContent(member,
                        () -> new ByteArrayInputStream(values.get(member.path())))).toList();
        Path archive = temporary.resolve(fileName + ".wtl-backup.zip");
        try (OutputStream output = Files.newOutputStream(archive)) {
            new BackupArchiveWriter(BackupArchivePolicy.defaults()).write(manifest, streams, output);
        }
        return archive;
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BackupMemberKind kind(String path) {
        if (path.startsWith("releases/")) return BackupMemberKind.RELEASE;
        if (path.startsWith("config/")) return BackupMemberKind.CONFIGURATION;
        if (path.startsWith("runtime/")) return BackupMemberKind.RUNTIME;
        return BackupMemberKind.ENCRYPTED_SECRETS;
    }

    private static boolean allCleared(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }
}
