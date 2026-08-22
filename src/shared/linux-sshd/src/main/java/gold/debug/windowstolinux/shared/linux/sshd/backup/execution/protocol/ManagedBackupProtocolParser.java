package gold.debug.windowstolinux.shared.linux.sshd.backup.execution.protocol;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifact;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactKind;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict parser for bounded helper backup evidence. / 有界 helper 备份证据的严格解析器。 */
public final class ManagedBackupProtocolParser {
    /** Parses exact artifact identity, kind, size and digest. / 解析精确制品身份、种类、长度和摘要。 */
    public RemoteBackupArtifact artifact(String operationId, RemoteBackupArtifactKind expectedKind, String output)
            throws LinuxOperationException {
        try {
            Map<String, String> values = SshCommandExecutor.lines(Objects.requireNonNull(output, "output"));
            if (!values.keySet().equals(Set.of("ARTIFACT", "KIND", "SIZE", "SHA256"))
                    || !kind(expectedKind).equals(values.get("KIND"))) {
                throw new IllegalArgumentException("backup evidence fields or kind differ");
            }
            long size = Long.parseLong(values.get("SIZE"));
            return new RemoteBackupArtifact(operationId, values.get("ARTIFACT"), expectedKind, size,
                    values.get("SHA256"));
        } catch (RuntimeException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.BACKUP_ARTIFACT_EVIDENCE_INVALID,
                    "managed backup helper evidence is malformed", exception);
        }
    }

    private static String kind(RemoteBackupArtifactKind kind) {
        return switch (kind) {
            case FILE_TREE -> "file";
            case RELEASE_TREE -> "release";
            case VOLUME -> "volume";
            case OCI_IMAGE -> "oci";
        };
    }
}
