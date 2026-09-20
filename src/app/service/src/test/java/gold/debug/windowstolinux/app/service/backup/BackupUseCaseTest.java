package gold.debug.windowstolinux.app.service.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretCryptoService;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretFailureType;
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
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifestCodec;
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
        assertEquals("6", inspection.schemaVersion());
        assertEquals(1, inspection.componentCount());
        assertEquals(3, inspection.memberCount());
        assertEquals(content.length * 3L, inspection.verifiedBytes());
        assertEquals(BackupProvenanceStatus.NOT_PRESENT, inspection.provenanceStatus());
        assertEquals(inspection, prepared.inspection());
        assertEquals(content.length * 3L, prepared.extractedBytes());
        assertTrue(prepared.candidateRoot().startsWith(temporary.resolve("work").toAbsolutePath()));
        assertArrayEquals(content, Files.readAllBytes(prepared.candidateRoot().resolve("config/sample.json")));
        assertFalse(Files.exists(prepared.candidateRoot().resolve("current")));

        Path attemptParent = prepared.candidateRoot().getParent();
        useCase.discard(prepared);
        useCase.discard(prepared);

        assertFalse(Files.exists(attemptParent));
    }

    @Test
    void refusesAReconstructedCandidateWithoutPlatformCleanupAuthority() throws Exception {
        byte[] content = "validated backup content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        BackupUseCase useCase = new BackupUseCase(temporary.resolve("work"));
        PreparedBackupCandidate prepared = useCase.prepare(archive(content));
        Path retained = prepared.candidateRoot().resolve("config/sample.json");
        PreparedBackupCandidate reconstructed = new PreparedBackupCandidate(
                prepared.inspection(), prepared.candidateRoot(), prepared.extractedBytes());

        assertThrows(gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException.class,
                () -> useCase.discard(reconstructed));

        assertTrue(Files.isRegularFile(retained));
        useCase.discard(prepared);
        assertFalse(Files.exists(prepared.candidateRoot().getParent()));
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
        Path archive = archive(content, envelope,
                List.of(new SecretReference("database-password", 4)), "with-secret");
        char[] restorePassword = "independent backup password".toCharArray();

        ResolvedSecretRevision restored;
        try (PreparedBackupSecrets prepared = new BackupUseCase(temporary.resolve("secret-work"))
                .prepareWithSecrets(archive, restorePassword)) {
            assertEquals(List.of(new SecretReference("database-password", 4)),
                    prepared.secrets().revisions().stream().map(ResolvedSecretRevision::reference).toList());
            assertTrue(Files.isRegularFile(prepared.candidate().candidateRoot().resolve("secrets.enc")));
            restored = prepared.secrets().revisions().getFirst();
        }
        assertTrue(allCleared(restorePassword));
        assertTrue(allCleared(restored.copyValue()));
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
        Path archive = archive(content, envelope,
                List.of(new SecretReference("database-password", 4)), "mismatched-secret");
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

    @Test
    void rejectsOldArchiveBeforeExtractingOrAuthenticatingSecrets() throws Exception {
        Path archive = archive(new byte[]{1},null,List.of(),"old-format",true);
        BackupUseCase useCase=new BackupUseCase(temporary.resolve("old-work"));
        assertThrows(Exception.class,()->useCase.inspect(archive));
        assertFalse(Files.exists(temporary.resolve("old-work/restore-candidates")));
    }

    private Path archive(byte[] content) throws Exception {
        return archive(content, null, List.of(), "sample");
    }

    private Path archive(byte[] content, byte[] envelope, List<SecretReference> secretReferences, String fileName)
            throws Exception {
        return archive(content, envelope, secretReferences, fileName, false);
    }

    private Path archive(
            byte[] content,
            byte[] envelope,
            List<SecretReference> secretReferences,
            String fileName,
            boolean legacy
    ) throws Exception {
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
                new BackupComponentRuntime.NodeService(22, health), "b".repeat(64), secretReferences);
        BackupInventory inventory = new BackupInventory(
                List.of("releases/sample.json"), List.of("config/sample.json"), secretReferences, List.of(), List.of(),
                BackupDatabase.none(),
                new BackupIdentity("sample", "server-1", "/opt/windowstolinux/apps/sample",
                        BackupInventory.computeReleaseSetSha256(List.of(component))),
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
            if (legacy) {
                try (var zip=new java.util.zip.ZipOutputStream(output)) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
                    zip.write(legacy(manifest)); zip.closeEntry();
                    for (var entry:values.entrySet()) { zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
                }
            } else new BackupArchiveWriter(BackupArchivePolicy.defaults()).write(manifest, streams, output);
        }
        return archive;
    }

    private static byte[] legacy(BackupManifest current) throws Exception {
        BackupManifestCodec codec = new BackupManifestCodec();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(codec.write(current));
        root.put("schemaVersion", "3");
        ObjectNode inventory = (ObjectNode) root.get("inventory");
        ObjectNode identity = (ObjectNode) inventory.get("identity");
        identity.remove("releaseSetSha256");
        identity.put("releaseIdentity", "release-1");
        ArrayNode legacySecrets = mapper.createArrayNode();
        for (JsonNode reference : inventory.withArray("secretReferences")) {
            legacySecrets.add(reference.get("identifier").asText());
        }
        inventory.set("secretReferences", legacySecrets);
        for (JsonNode value : inventory.withArray("components")) {
            ObjectNode component = (ObjectNode) value;
            component.remove("releaseSha256");
            component.remove("secretReferences");
        }
        return mapper.writeValueAsBytes(root);
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

    private static boolean allCleared(byte[] value) {
        for (byte current : value) if (current != 0) return false;
        return true;
    }
}
