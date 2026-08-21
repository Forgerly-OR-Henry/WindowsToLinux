package gold.debug.windowstolinux.shared.backup.contract.validation;

import gold.debug.windowstolinux.shared.backup.format.BackupArchiveContent;
import gold.debug.windowstolinux.shared.backup.format.BackupArchiveWriter;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupIdentity;
import gold.debug.windowstolinux.shared.backup.manifest.BackupInventory;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifestSigner;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.backup.manifest.BackupProvenance;
import gold.debug.windowstolinux.shared.backup.manifest.BackupRuntime;
import gold.debug.windowstolinux.shared.backup.restore.BackupArchiveExtractor;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreCandidate;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackupArchiveSecurityTest {
    @TempDir Path temporaryDirectory;

    @Test
    void validatesAndExtractsExactArchiveIntoNewCandidate() throws Exception {
        byte[] content = "portable configuration".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path archive = writeArchive("valid.zip", sampleManifest(content), content, BackupArchivePolicy.defaults());

        BackupArchiveValidation validation = new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(archive);
        BackupRestoreCandidate candidate = new BackupArchiveExtractor().extract(
                archive, temporaryDirectory.resolve("candidate"), validation);

        assertEquals(BackupProvenanceStatus.NOT_PRESENT, validation.provenanceStatus());
        assertEquals(content.length, candidate.extractedBytes());
        assertArrayEquals(content, Files.readAllBytes(candidate.root().resolve("config/application.json")));
    }

    @Test
    void rejectsTraversalAndSymbolicLinkEntriesBeforeExtraction() throws Exception {
        Path traversal = rawArchive("traversal.zip", "../escape", 0, new byte[]{1});
        Path link = rawArchive("link.zip", "data/link", UnixStat.LINK_FLAG | 0777, "target".getBytes());

        BackupException traversalFailure = assertThrows(BackupException.class,
                () -> new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(traversal));
        BackupException linkFailure = assertThrows(BackupException.class,
                () -> new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(link));

        assertEquals(BackupFailureType.MEMBER_REJECTED.code(), traversalFailure.failure().code());
        assertEquals(BackupFailureType.MEMBER_REJECTED.code(), linkFailure.failure().code());
        assertFalse(Files.exists(temporaryDirectory.resolve("escape")));
    }

    @Test
    void rejectsStreamThatDiffersFromManifestBeforeClaimingSuccess() throws Exception {
        byte[] expected = "expected".getBytes();
        BackupManifest manifest = sampleManifest(expected);
        BackupArchiveContent wrong = new BackupArchiveContent(manifest.members().getFirst(),
                () -> new ByteArrayInputStream("tampered".getBytes()));

        BackupException failure = assertThrows(BackupException.class, () -> {
            try (OutputStream output = Files.newOutputStream(temporaryDirectory.resolve("wrong.zip"))) {
                new BackupArchiveWriter(BackupArchivePolicy.defaults()).write(manifest, List.of(wrong), output);
            }
        });

        assertEquals(BackupFailureType.INTEGRITY_FAILED.code(), failure.failure().code());
    }

    @Test
    void reportsVerifiedProvenanceSeparatelyFromIntegrity() throws Exception {
        byte[] content = "signed".getBytes();
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        BackupManifest signed = new BackupManifestSigner().sign(sampleManifest(content), "backup-key", keyPair.getPrivate());
        Path archive = writeArchive("signed.zip", signed, content, BackupArchivePolicy.defaults());

        BackupArchiveValidation validation = new BackupArchiveValidator(
                BackupArchivePolicy.defaults(), keyId -> keyPair.getPublic()).validate(archive);

        assertEquals(BackupProvenanceStatus.VERIFIED, validation.provenanceStatus());
    }

    @Test
    void rejectsInvalidSignatureWhenTrustIsConfigured() throws Exception {
        byte[] content = "signed".getBytes();
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        BackupManifest forged = sampleManifest(content).withProvenance(new BackupProvenance(
                "Ed25519", "backup-key", Base64.getEncoder().encodeToString(new byte[64])));
        Path archive = writeArchive("forged.zip", forged, content, BackupArchivePolicy.defaults());

        BackupException failure = assertThrows(BackupException.class, () -> new BackupArchiveValidator(
                BackupArchivePolicy.defaults(), keyId -> keyPair.getPublic()).validate(archive));

        assertEquals(BackupFailureType.PROVENANCE_FAILED.code(), failure.failure().code());
    }

    @Test
    void rejectsCompressionRatioBeyondExplicitPolicy() throws Exception {
        byte[] content = new byte[32 * 1024];
        BackupArchivePolicy writePolicy = BackupArchivePolicy.defaults();
        Path archive = writeArchive("compressed.zip", sampleManifest(content), content, writePolicy);
        BackupArchivePolicy strict = new BackupArchivePolicy(10, 64 * 1024, 128 * 1024,
                512, 1024 * 1024, 2.0d);

        BackupException failure = assertThrows(BackupException.class,
                () -> new BackupArchiveValidator(strict).validate(archive));

        assertEquals(BackupFailureType.LIMIT_EXCEEDED.code(), failure.failure().code());
    }

    private Path writeArchive(String fileName, BackupManifest manifest, byte[] content, BackupArchivePolicy policy)
            throws Exception {
        Path archive = temporaryDirectory.resolve(fileName);
        BackupArchiveContent source = new BackupArchiveContent(manifest.members().getFirst(),
                () -> new ByteArrayInputStream(content));
        try (OutputStream output = Files.newOutputStream(archive)) {
            new BackupArchiveWriter(policy).write(manifest, List.of(source), output);
        }
        return archive;
    }

    private Path rawArchive(String fileName, String entryName, int unixMode, byte[] content) throws Exception {
        Path archive = temporaryDirectory.resolve(fileName);
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)) {
            ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
            if (unixMode != 0) entry.setUnixMode(unixMode);
            output.putArchiveEntry(entry);
            output.write(content);
            output.closeArchiveEntry();
        }
        return archive;
    }

    private static BackupManifest sampleManifest(byte[] content) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        BackupMember member = new BackupMember(
                "config/application.json", content.length, hash, BackupMemberKind.CONFIGURATION);
        BackupDatabase database = new BackupDatabase(BackupDatabaseType.SQLITE, "application.db", "3.46",
                "sqlite3 3.46", BackupConsistencyMode.SQLITE_ONLINE_BACKUP, List.of());
        BackupInventory inventory = new BackupInventory(
                List.of("releases/release-1.json"), List.of("config/application.json"), List.of("db.password"),
                List.of("/srv/sample/content"), List.of("sample-content"), database,
                new BackupIdentity("sample", "server-1", "/opt/windowstolinux/apps/sample", "release-1"),
                List.of("runtime/sample.service"),
                new BackupRuntime("systemd", "255", "x86_64", List.of("systemd")),
                List.of("restore requires SQLite 3.46 or a compatible reader"));
        return BackupManifest.create(Instant.parse("2026-08-21T00:00:00Z"), "sample", inventory, List.of(member));
    }
}
