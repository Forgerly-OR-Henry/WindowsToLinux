package gold.debug.windowstolinux.app.windows.uninstall;

import gold.debug.windowstolinux.app.windows.workspace.DesktopHandoffEnvelopeCodec;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopUninstallHandoffCodecTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsTheExactDecisionPathsNamespaceAndPreparationEvidence() throws Exception {
        DesktopUninstallHandoff handoff = handoff();
        DesktopUninstallHandoffCodec codec = new DesktopUninstallHandoffCodec();

        byte[] document = codec.write(handoff, key(4));

        assertEquals(handoff, codec.read(document, key(4)));
        WindowsWorkspaceException failure = assertThrows(WindowsWorkspaceException.class,
                () -> codec.read(document, key(5)));
        assertEquals("windows.workspace.handoff-invalid", failure.failure().code());
    }

    @Test
    void rejectsAuthenticatedButMalformedUninstallPayloads() throws Exception {
        byte[] document = new DesktopHandoffEnvelopeCodec().write(
                DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL, new byte[]{0, 0, 0, 2}, key(4));

        WindowsWorkspaceException failure = assertThrows(WindowsWorkspaceException.class,
                () -> new DesktopUninstallHandoffCodec().read(document, key(4)));

        assertEquals("windows.workspace.handoff-invalid", failure.failure().code());
    }

    @Test
    void rejectsUninstallPathsThatCannotFitTheTransportBoundary() {
        Path install = temporary.resolve("a".repeat(4096));

        assertThrows(IllegalArgumentException.class, () -> new DesktopUninstallRequest(
                Optional.of(DesktopUninstallDecisionType.KEEP_DATA_AND_CREDENTIALS), install, install.resolve("data"),
                "WindowsToLinux/*"));
    }

    private DesktopUninstallHandoff handoff() {
        Path install = temporary.resolve("WindowsToLinux");
        DesktopUninstallRequest request = new DesktopUninstallRequest(
                Optional.of(DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS),
                install, install.resolve("data"), "WindowsToLinux/*");
        return new DesktopUninstallHandoff(OperationIdentity.from("22222222-2222-2222-2222-222222222222"),
                request, List.of(
                new DesktopUninstallEvent(DesktopUninstallState.DECISION_VALIDATED, true, "delete explicitly selected"),
                new DesktopUninstallEvent(DesktopUninstallState.TASKS_STOPPED, true, "owned tasks stopped")));
    }

    private static SecretKey key(int marker) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) marker);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
