package gold.debug.windowstolinux.app.windows.update;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Strict authenticated transport codec for an update handoff crossing the process boundary. / 更新交接跨进程边界使用的严格认证传输编解码器。 */
public final class DesktopUpdateHandoffCodec {
    private static final int PAYLOAD_VERSION = 1;
    private static final int MAXIMUM_ITEMS = 64;
    private final DesktopHandoffEnvelopeCodec envelope = new DesktopHandoffEnvelopeCodec();

    /** Encodes the complete verified update and paired backup evidence. / 编码完整的已验证更新及成对备份证据。 */
    public byte[] write(DesktopUpdateHandoff handoff, SecretKey authenticationKey)
            throws WindowsWorkspaceException {
        Objects.requireNonNull(handoff, "handoff");
        byte[] payload = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(PAYLOAD_VERSION);
                output.writeUTF(handoff.operationIdentity().toString());
                writeVerification(output, handoff.update());
                writeBackup(output, handoff.backup());
                writeEvents(output, handoff.preparationEvents());
            }
            payload = bytes.toByteArray();
            return envelope.write(DesktopHandoffEnvelopeCodec.PurposeType.UPDATE, payload, authenticationKey);
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("update handoff could not be encoded", exception);
        } finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
        }
    }

    /** Authenticates before reconstructing any update handoff state. / 在重建任何更新交接状态前完成认证。 */
    public DesktopUpdateHandoff read(byte[] document, SecretKey authenticationKey)
            throws WindowsWorkspaceException {
        byte[] payload = envelope.read(DesktopHandoffEnvelopeCodec.PurposeType.UPDATE,
                document, authenticationKey);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != PAYLOAD_VERSION) {
                throw invalid("update handoff payload version is unsupported", null);
            }
            OperationIdentity operation = OperationIdentity.from(input.readUTF());
            DesktopUpdateVerification update = readVerification(input);
            DesktopUpdatePort.BackupEvidence backup = readBackup(input);
            List<DesktopUpdateEvent> events = readEvents(input);
            if (input.available() != 0) throw invalid("update handoff payload has trailing bytes", null);
            return new DesktopUpdateHandoff(operation, update, backup, events);
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("authenticated update handoff payload is malformed", exception);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private static void writeVerification(DataOutputStream output, DesktopUpdateVerification update)
            throws IOException {
        output.writeUTF(update.packageFile().toString());
        output.writeUTF(update.releaseId());
        output.writeUTF(update.version().toString());
        output.writeUTF(update.architecture().name());
        output.writeLong(update.packageBytes());
        output.writeUTF(update.packageSha256());
        output.writeLong(update.verifiedAt().getEpochSecond());
        output.writeInt(update.verifiedAt().getNano());
        writeTexts(output, update.evidence());
    }

    private static DesktopUpdateVerification readVerification(DataInputStream input) throws IOException {
        Path packageFile = Path.of(input.readUTF());
        String releaseId = input.readUTF();
        DesktopReleaseVersion version = DesktopReleaseVersion.parse(input.readUTF());
        DesktopArchitectureType architecture = DesktopArchitectureType.valueOf(input.readUTF());
        long packageBytes = input.readLong();
        String packageSha256 = input.readUTF();
        Instant verifiedAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
        return new DesktopUpdateVerification(packageFile, releaseId, version, architecture, packageBytes,
                packageSha256, verifiedAt, readTexts(input));
    }

    private static void writeBackup(DataOutputStream output, DesktopUpdatePort.BackupEvidence backup)
            throws IOException {
        output.writeUTF(backup.backupToken());
        output.writeBoolean(backup.programBackedUp());
        output.writeBoolean(backup.databaseBackedUp());
        output.writeBoolean(backup.dataLocationPreserved());
        output.writeBoolean(backup.credentialModePreserved());
        writeTexts(output, backup.evidence());
    }

    private static DesktopUpdatePort.BackupEvidence readBackup(DataInputStream input) throws IOException {
        return new DesktopUpdatePort.BackupEvidence(input.readUTF(), input.readBoolean(), input.readBoolean(),
                input.readBoolean(), input.readBoolean(), readTexts(input));
    }

    private static void writeEvents(DataOutputStream output, List<DesktopUpdateEvent> events) throws IOException {
        output.writeInt(events.size());
        for (DesktopUpdateEvent event : events) {
            output.writeUTF(event.state().name());
            output.writeBoolean(event.succeeded());
            output.writeUTF(event.evidence());
        }
    }

    private static List<DesktopUpdateEvent> readEvents(DataInputStream input) throws IOException {
        int count = count(input.readInt(), "update event");
        List<DesktopUpdateEvent> events = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            events.add(new DesktopUpdateEvent(DesktopUpdateState.valueOf(input.readUTF()),
                    input.readBoolean(), input.readUTF()));
        }
        return List.copyOf(events);
    }

    private static void writeTexts(DataOutputStream output, List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) output.writeUTF(value);
    }

    private static List<String> readTexts(DataInputStream input) throws IOException {
        int count = count(input.readInt(), "update evidence");
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(input.readUTF());
        return List.copyOf(values);
    }

    private static int count(int value, String field) throws IOException {
        if (value < 1 || value > MAXIMUM_ITEMS) throw new IOException(field + " count is invalid");
        return value;
    }

    private static WindowsWorkspaceException invalid(String diagnostic, Throwable cause) {
        return WindowsWorkspaceException.create(WindowsWorkspaceFailureType.HANDOFF_INVALID, diagnostic, cause);
    }
}
