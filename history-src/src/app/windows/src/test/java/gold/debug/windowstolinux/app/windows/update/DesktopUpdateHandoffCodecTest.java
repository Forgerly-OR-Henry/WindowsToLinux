package gold.debug.windowstolinux.app.windows.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import gold.debug.windowstolinux.app.windows.workspace.DesktopHandoffEnvelopeCodec;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopUpdateHandoffCodecTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsTheExactVerifiedPackageBackupAndPreparationEvidence() throws Exception {
        DesktopUpdateHandoff handoff = handoff();
        DesktopUpdateHandoffCodec codec = new DesktopUpdateHandoffCodec();

        byte[] document = codec.write(handoff, key(1));

        assertEquals(handoff, codec.read(document, key(1)));
        WindowsWorkspaceException failure = assertThrows(WindowsWorkspaceException.class,
                () -> codec.read(document, key(2)));
        assertEquals("windows.workspace.handoff-invalid", failure.failure().code());
    }

    @Test
    void rejectsAuthenticatedButMalformedUpdatePayloads() throws Exception {
        byte[] document = new DesktopHandoffEnvelopeCodec().write(DesktopHandoffEnvelopeCodec.PurposeType.UPDATE,
                new byte[]{0, 0, 0, 2}, key(1));

        WindowsWorkspaceException failure = assertThrows(WindowsWorkspaceException.class,
                () -> new DesktopUpdateHandoffCodec().read(document, key(1)));

        assertEquals("windows.workspace.handoff-invalid", failure.failure().code());
    }

    @Test
    void rejectsUpdateEvidenceThatCannotFitTheTransportBoundary() {
        Path packageFile = temporary.resolve("WindowsToLinux-2.0.0.msi");

        assertThrows(IllegalArgumentException.class,
                () -> new DesktopUpdateVerification(packageFile, "release-2", DesktopReleaseVersion.parse("2.0.0"),
                        DesktopArchitectureType.X86_64, 8, "a".repeat(64), Instant.parse("2026-08-22T00:00:00Z"),
                        List.of("e".repeat(513))));
    }

    private DesktopUpdateHandoff handoff() throws Exception {
        Path packageFile = Files.writeString(temporary.resolve("WindowsToLinux-2.0.0.msi"), "verified");
        DesktopUpdateVerification verification = new DesktopUpdateVerification(packageFile, "release-2",
                DesktopReleaseVersion.parse("2.0.0"), DesktopArchitectureType.X86_64, 8, "a".repeat(64),
                Instant.parse("2026-08-22T00:00:00.123456789Z"), List.of("signature and digest verified"));
        DesktopUpdatePort.BackupEvidence backup = new DesktopUpdatePort.BackupEvidence("backup-1", true, true, true,
                true, List.of("program and SQLite backed up"));
        return new DesktopUpdateHandoff(OperationIdentity.from("11111111-1111-1111-1111-111111111111"), verification,
                backup, List.of(new DesktopUpdateEvent(DesktopUpdateState.TASKS_QUIESCED, true, "tasks quiesced"),
                        new DesktopUpdateEvent(DesktopUpdateState.BACKUP_CREATED, true, "paired backup created")));
    }

    private static SecretKey key(int marker) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) marker);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
