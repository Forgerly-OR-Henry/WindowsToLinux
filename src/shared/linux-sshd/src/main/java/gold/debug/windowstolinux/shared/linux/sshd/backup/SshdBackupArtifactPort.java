package gold.debug.windowstolinux.shared.linux.sshd.backup;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifact;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactRequest;
import gold.debug.windowstolinux.shared.linux.sshd.backup.execution.protocol.ManagedBackupProtocolParser;
import gold.debug.windowstolinux.shared.linux.sshd.backup.generation.script.ManagedBackupCommandRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/** Apache SSHD managed backup artifacts backed only by fixed helper verbs. / 仅由固定 helper 动词支持的 Apache SSHD 受管备份制品端口。 */
public final class SshdBackupArtifactPort implements RemoteBackupArtifactPort {
    private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration STREAM_TIMEOUT = Duration.ofHours(2);
    private final SshCommandExecutor commands;
    private final ManagedBackupCommandRenderer renderer = new ManagedBackupCommandRenderer();
    private final ManagedBackupProtocolParser parser = new ManagedBackupProtocolParser();

    /** Creates the port for one authenticated SSH session. / 为一个已认证 SSH 会话创建端口。 */
    public SshdBackupArtifactPort(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @Override
    public RemoteBackupArtifact createBackupArtifact(RemoteBackupArtifactRequest request)
            throws LinuxOperationException {
        var result = commands.execProtocol(renderer.create(request), CREATE_TIMEOUT, true);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.BACKUP_ARTIFACT_CREATION_FAILED,
                    "controlled managed backup creation failed: " + result.failureEvidence());
        }
        RemoteBackupArtifact artifact = parser.artifact(request.operationId(), request.kind(), result.output());
        if (artifact.byteCount() > request.maximumBytes()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.BACKUP_ARTIFACT_EVIDENCE_INVALID,
                    "managed backup artifact exceeds its reviewed bound");
        }
        return artifact;
    }

    @Override
    public void copyBackupArtifact(RemoteBackupArtifact artifact, OutputStream destination)
            throws LinuxOperationException {
        Objects.requireNonNull(destination, "destination");
        VerifyingOutput output = new VerifyingOutput(destination, artifact);
        try {
            var result = commands.execProtocolStreaming(renderer.read(artifact), InputStream.nullInputStream(), output,
                    STREAM_TIMEOUT);
            if (!result.succeeded()) throw new IOException(result.failureEvidence());
            output.verify();
        } catch (IOException | LinuxOperationException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.BACKUP_ARTIFACT_TRANSFER_FAILED,
                    "managed backup artifact stream differs from helper evidence", exception);
        }
    }

    @Override
    public RemoteStepResult discardBackupOperation(String operationId) throws LinuxOperationException {
        var result = commands.execProtocol(renderer.discard(operationId), CREATE_TIMEOUT, false);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.BACKUP_OPERATION_CLEANUP_FAILED,
                    "managed backup operation cleanup failed: " + result.failureEvidence());
        }
        return new RemoteStepResult(true, false, "Controlled helper removed the exact backup operation");
    }

    private static final class VerifyingOutput extends FilterOutputStream {
        private final RemoteBackupArtifact artifact;
        private final MessageDigest digest = sha256();
        private long count;

        private VerifyingOutput(OutputStream output, RemoteBackupArtifact artifact) {
            super(output); this.artifact = artifact;
        }

        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            count = Math.addExact(count, length);
            if (count > artifact.byteCount()) throw new IOException("backup stream exceeds evidence");
            digest.update(bytes, offset, length); out.write(bytes, offset, length);
        }
        @Override public void close() throws IOException { flush(); }
        private void verify() throws IOException {
            if (count != artifact.byteCount()
                    || !HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256())) {
                throw new IOException("backup stream digest or size differs");
            }
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
