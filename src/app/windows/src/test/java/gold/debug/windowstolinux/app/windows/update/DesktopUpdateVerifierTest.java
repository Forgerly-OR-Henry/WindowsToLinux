package gold.debug.windowstolinux.app.windows.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopUpdateVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void acceptsOnlyPackageBoundToPinnedSignatureVersionAndArchitecture() throws Exception {
        KeyPair key = keys();
        Path update = packageFile("signed desktop package");
        DesktopUpdateManifest manifest = signedManifest(key, update, "release-2", "2.0.0",
                DesktopArchitectureType.X86_64, false);

        DesktopUpdateVerification verified = new DesktopUpdateVerifier().verify(update, manifest,
                policy(key, "1.0.0", Set.of(), false));

        assertEquals(DesktopReleaseVersion.parse("2.0.0"), verified.version());
        assertEquals(4, verified.evidence().size());
    }

    @Test
    void rejectsPackageContentChangedAfterMetadataWasSigned() throws Exception {
        KeyPair key = keys();
        Path update = packageFile("original package");
        DesktopUpdateManifest manifest = signedManifest(key, update, "release-2", "2.0.0",
                DesktopArchitectureType.X86_64, false);
        Files.writeString(update, "changed package");

        DesktopUpdateException failure = assertThrows(DesktopUpdateException.class,
                () -> new DesktopUpdateVerifier().verify(update, manifest, policy(key, "1.0.0", Set.of(), false)));

        assertEquals("windows.update.package-invalid", failure.failure().code());
    }

    @Test
    void rejectsUnknownSigningKeyAndRevokedRelease() throws Exception {
        KeyPair trusted = keys();
        KeyPair unknown = keys();
        Path update = packageFile("signed package");
        DesktopUpdateManifest unknownSignature = signedManifest(unknown, update, "release-2", "2.0.0",
                DesktopArchitectureType.X86_64, false);

        DesktopUpdateException signatureFailure = assertThrows(DesktopUpdateException.class,
                () -> new DesktopUpdateVerifier().verify(update, unknownSignature,
                        policy(trusted, "1.0.0", Set.of(), false)));
        assertEquals("windows.update.signature-invalid", signatureFailure.failure().code());

        DesktopUpdateManifest trustedManifest = signedManifest(trusted, update, "release-2", "2.0.0",
                DesktopArchitectureType.X86_64, false);
        DesktopUpdateException revoked = assertThrows(DesktopUpdateException.class, () -> new DesktopUpdateVerifier()
                .verify(update, trustedManifest, policy(trusted, "1.0.0", Set.of("release-2"), false)));
        assertEquals("windows.update.version-rejected", revoked.failure().code());
    }

    @Test
    void downgradeRequiresSignedEmergencyMarkerAndExplicitApproval() throws Exception {
        KeyPair key = keys();
        Path update = packageFile("emergency rollback package");
        DesktopUpdateManifest rollback = signedManifest(key, update, "release-1", "1.0.0",
                DesktopArchitectureType.X86_64, true);

        assertThrows(DesktopUpdateException.class,
                () -> new DesktopUpdateVerifier().verify(update, rollback, policy(key, "2.0.0", Set.of(), false)));

        DesktopUpdateVerification approved = new DesktopUpdateVerifier().verify(update, rollback,
                policy(key, "2.0.0", Set.of(), true));
        assertEquals(DesktopReleaseVersion.parse("1.0.0"), approved.version());
    }

    @Test
    void architectureMismatchIsRejectedBeforeReplacement() throws Exception {
        KeyPair key = keys();
        Path update = packageFile("arm package");
        DesktopUpdateManifest manifest = signedManifest(key, update, "release-arm", "2.0.0",
                DesktopArchitectureType.ARM64, false);

        DesktopUpdateException failure = assertThrows(DesktopUpdateException.class,
                () -> new DesktopUpdateVerifier().verify(update, manifest, policy(key, "1.0.0", Set.of(), false)));
        assertEquals("windows.update.architecture-rejected", failure.failure().code());
    }

    private Path packageFile(String content) throws Exception {
        Path file = temporary.resolve("WindowsToLinux-update.bin");
        Files.writeString(file, content);
        return file;
    }

    private DesktopUpdateManifest signedManifest(KeyPair key, Path packageFile, String releaseId, String version,
            DesktopArchitectureType architecture, boolean emergencyRollback) throws Exception {
        byte[] bytes = Files.readAllBytes(packageFile);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String placeholder = Base64.getEncoder().encodeToString(new byte[64]);
        DesktopUpdateManifest unsigned = new DesktopUpdateManifest(releaseId, DesktopReleaseVersion.parse(version),
                architecture, bytes.length, digest, NOW.minusSeconds(60), NOW.plusSeconds(3600), "release-key",
                emergencyRollback, placeholder);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(unsigned.signedPayload());
        return new DesktopUpdateManifest(releaseId, unsigned.version(), architecture, bytes.length, digest,
                unsigned.issuedAt(), unsigned.expiresAt(), unsigned.keyId(), emergencyRollback,
                Base64.getEncoder().encodeToString(signer.sign()));
    }

    private DesktopUpdateTrustPolicy policy(KeyPair key, String currentVersion, Set<String> revoked,
            boolean rollbackApproved) {
        return new DesktopUpdateTrustPolicy(DesktopReleaseVersion.parse(currentVersion), DesktopArchitectureType.X86_64,
                "release-key", key.getPublic(), revoked, NOW, rollbackApproved);
    }

    private KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
