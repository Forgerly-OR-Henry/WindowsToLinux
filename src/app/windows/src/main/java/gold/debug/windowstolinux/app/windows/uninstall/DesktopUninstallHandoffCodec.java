package gold.debug.windowstolinux.app.windows.uninstall;

import gold.debug.windowstolinux.app.windows.workspace.DesktopHandoffEnvelopeCodec;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceFailureType;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Strict authenticated transport codec for an uninstall handoff crossing the process boundary. / 卸载交接跨进程边界使用的严格认证传输编解码器。 */
public final class DesktopUninstallHandoffCodec {
    private static final int PAYLOAD_VERSION = 1;
    private static final int MAXIMUM_EVENTS = 64;
    private final DesktopHandoffEnvelopeCodec envelope = new DesktopHandoffEnvelopeCodec();

    /** Encodes the explicit uninstall decision and successful task-stop evidence. / 编码显式卸载决定及成功停收任务证据。 */
    public byte[] write(DesktopUninstallHandoff handoff, SecretKey authenticationKey)
            throws WindowsWorkspaceException {
        Objects.requireNonNull(handoff, "handoff");
        byte[] payload = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(PAYLOAD_VERSION);
                output.writeUTF(handoff.operationIdentity().toString());
                DesktopUninstallRequest request = handoff.request();
                output.writeUTF(request.decision().orElseThrow().name());
                output.writeUTF(request.installRoot().toString());
                output.writeUTF(request.dataRoot().toString());
                output.writeUTF(request.credentialNamespace());
                writeEvents(output, handoff.preparationEvents());
            }
            payload = bytes.toByteArray();
            return envelope.write(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL, payload, authenticationKey);
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("uninstall handoff could not be encoded", exception);
        } finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
        }
    }

    /** Authenticates before reconstructing any uninstall decision or path. / 在重建任何卸载决定或路径前完成认证。 */
    public DesktopUninstallHandoff read(byte[] document, SecretKey authenticationKey)
            throws WindowsWorkspaceException {
        byte[] payload = envelope.read(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL,
                document, authenticationKey);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != PAYLOAD_VERSION) {
                throw invalid("uninstall handoff payload version is unsupported", null);
            }
            OperationIdentity operation = OperationIdentity.from(input.readUTF());
            DesktopUninstallRequest request = new DesktopUninstallRequest(
                    Optional.of(DesktopUninstallDecisionType.valueOf(input.readUTF())),
                    Path.of(input.readUTF()), Path.of(input.readUTF()), input.readUTF());
            List<DesktopUninstallEvent> events = readEvents(input);
            if (input.available() != 0) throw invalid("uninstall handoff payload has trailing bytes", null);
            return new DesktopUninstallHandoff(operation, request, events);
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("authenticated uninstall handoff payload is malformed", exception);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private static void writeEvents(DataOutputStream output, List<DesktopUninstallEvent> events) throws IOException {
        output.writeInt(events.size());
        for (DesktopUninstallEvent event : events) {
            output.writeUTF(event.state().name());
            output.writeBoolean(event.succeeded());
            output.writeUTF(event.evidence());
        }
    }

    private static List<DesktopUninstallEvent> readEvents(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 1 || count > MAXIMUM_EVENTS) throw new IOException("uninstall event count is invalid");
        List<DesktopUninstallEvent> events = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            events.add(new DesktopUninstallEvent(DesktopUninstallState.valueOf(input.readUTF()),
                    input.readBoolean(), input.readUTF()));
        }
        return List.copyOf(events);
    }

    private static WindowsWorkspaceException invalid(String diagnostic, Throwable cause) {
        return WindowsWorkspaceException.create(WindowsWorkspaceFailureType.HANDOFF_INVALID, diagnostic, cause);
    }
}
