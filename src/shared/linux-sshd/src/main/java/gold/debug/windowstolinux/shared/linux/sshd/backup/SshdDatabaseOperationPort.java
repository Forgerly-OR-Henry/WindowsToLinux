package gold.debug.windowstolinux.shared.linux.sshd.backup;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;
import gold.debug.windowstolinux.shared.linux.sshd.backup.execution.protocol.DatabaseProtocolParser;
import gold.debug.windowstolinux.shared.linux.sshd.backup.generation.script.DatabaseCommandRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/** Apache SSHD database port backed only by fixed root-owned helper verbs. / 仅由固定 root 持有 helper 动词支持的 Apache SSHD 数据库端口。 */
public final class SshdDatabaseOperationPort implements RemoteDatabasePort {
    private static final Duration INSPECTION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DATABASE_TIMEOUT = Duration.ofMinutes(30);
    private final SshCommandExecutor commands;
    private final DatabaseCommandRenderer renderer;
    private final DatabaseProtocolParser parser;

    /** Creates the database port for one authenticated SSH session. / 为一个已认证 SSH 会话创建数据库端口。 */
    public SshdDatabaseOperationPort(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.renderer = new DatabaseCommandRenderer();
        this.parser = new DatabaseProtocolParser();
    }

    @Override
    public CompatibilityEvidence inspect(BackupRequest request) throws LinuxOperationException {
        SshCommandExecutor.CommandResult result;
        try {
            result = commands.execProtocol(renderer.inspect(request), INSPECTION_TIMEOUT, true);
        } catch (LinuxOperationException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_PREFLIGHT_FAILED,
                    "database inspection transport failed", exception);
        }
        requireSuccess(result, LinuxOperationFailureType.DATABASE_PREFLIGHT_FAILED, "database inspection failed");
        return parser.compatibility(result.output());
    }

    @Override
    public BackupArtifact export(BackupRequest request, DatabaseConsistencyMode consistencyMode)
            throws LinuxOperationException {
        SshCommandExecutor.CommandResult result;
        try {
            result = commands.execProtocol(renderer.export(request, consistencyMode), DATABASE_TIMEOUT, true);
        } catch (LinuxOperationException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_BACKUP_FAILED,
                    "database export transport failed", exception);
        }
        requireSuccess(result, LinuxOperationFailureType.DATABASE_BACKUP_FAILED, "controlled database export failed");
        return parser.artifact(result.output(), reference(request.connection()));
    }

    @Override
    public RestoreEvidence restoreCandidate(RestoreRequest request) throws LinuxOperationException {
        SshCommandExecutor.CommandResult result;
        try {
            result = commands.execProtocol(renderer.restore(request), DATABASE_TIMEOUT, true);
        } catch (LinuxOperationException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_RESTORE_FAILED,
                    "database restore transport failed", exception);
        }
        requireSuccess(result, LinuxOperationFailureType.DATABASE_RESTORE_FAILED,
                "controlled database candidate restore failed");
        return parser.restore(result.output());
    }

    @Override
    public CommitEvidence commitCandidate(RestoreRequest request) throws LinuxOperationException {
        try {
            var result = commands.execProtocol(renderer.commitCandidate(request), DATABASE_TIMEOUT, true);
            requireSuccess(result, LinuxOperationFailureType.DATABASE_RESTORE_FAILED,
                    "controlled database candidate activation failed");
            return parser.commit(result.output());
        } catch (LinuxOperationException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_RESTORE_FAILED,
                    "database candidate activation transport failed", exception);
        }
    }

    @Override
    public RecoveryEvidence recoverCandidate(RestoreRequest request) throws LinuxOperationException {
        try {
            var result = commands.execProtocol(renderer.recoverCandidate(request), DATABASE_TIMEOUT, true);
            requireSuccess(result, LinuxOperationFailureType.DATABASE_RESTORE_FAILED,
                    "controlled database recovery failed");
            return parser.recovery(result.output());
        } catch (LinuxOperationException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_RESTORE_FAILED,
                    "database recovery transport failed", exception);
        }
    }

    @Override
    public void discardCandidate(RestoreRequest request) throws LinuxOperationException {
        try {
            var result = commands.execProtocol(renderer.discardCandidate(request), DATABASE_TIMEOUT, false);
            requireSuccess(result, LinuxOperationFailureType.DATABASE_RESTORE_FAILED,
                    "database candidate cleanup failed");
        } catch (LinuxOperationException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_RESTORE_FAILED,
                    "database candidate cleanup transport failed", exception);
        }
    }

    @Override
    public void copyArtifact(BackupArtifact artifact, OutputStream destination) throws LinuxOperationException {
        Objects.requireNonNull(destination, "destination");
        VerifyingOutput output = new VerifyingOutput(destination, artifact);
        try {
            var result = commands.execProtocolStreaming(renderer.readArtifact(artifact), InputStream.nullInputStream(),
                    output, DATABASE_TIMEOUT);
            requireSuccess(result, LinuxOperationFailureType.DATABASE_BACKUP_FAILED,
                    "database artifact streaming failed");
            output.verify();
        } catch (LinuxOperationException | IOException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_BACKUP_FAILED,
                    "database artifact could not be copied with verified integrity", exception);
        }
    }

    @Override
    public void stageArtifact(BackupArtifact artifact, InputStream source) throws LinuxOperationException {
        Objects.requireNonNull(source, "source");
        VerifyingInput input = new VerifyingInput(source, artifact);
        try {
            var result = commands.execProtocolStreaming(renderer.stageArtifact(artifact), input,
                    OutputStream.nullOutputStream(), DATABASE_TIMEOUT);
            requireSuccess(result, LinuxOperationFailureType.DATABASE_RESTORE_FAILED,
                    "database artifact staging failed");
            input.verify();
        } catch (LinuxOperationException | IOException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_RESTORE_FAILED,
                    "database artifact could not be staged with verified integrity", exception);
        }
    }

    @Override
    public void discardArtifact(BackupArtifact artifact) throws LinuxOperationException {
        try {
            var result = commands.execProtocol(renderer.discardArtifact(artifact), INSPECTION_TIMEOUT, false);
            requireSuccess(result, LinuxOperationFailureType.DATABASE_BACKUP_FAILED, "database artifact cleanup failed");
        } catch (LinuxOperationException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_BACKUP_FAILED,
                    "database artifact cleanup transport failed", exception);
        }
    }

    private static void requireSuccess(
            SshCommandExecutor.CommandResult result, LinuxOperationFailureType type, String operation)
            throws LinuxOperationException {
        if (!result.succeeded()) {
            throw LinuxOperationException.create(type, operation + ": " + result.failureEvidence());
        }
    }

    private static String reference(ConnectionProfile profile) {
        if (profile instanceof ConnectionProfile.Sqlite sqlite) return sqlite.relativePath();
        ConnectionProfile.Server server = (ConnectionProfile.Server) profile;
        return server.host() + ":" + server.port() + "/" + server.database();
    }

    private abstract static class Verifier {
        private final BackupArtifact artifact;
        private final MessageDigest digest = sha256();
        private long count;

        private Verifier(BackupArtifact artifact) {
            this.artifact = Objects.requireNonNull(artifact, "artifact");
        }

        final void update(byte[] bytes, int offset, int length) throws IOException {
            try {
                count = Math.addExact(count, length);
            } catch (ArithmeticException exception) {
                throw new IOException("database artifact length overflow", exception);
            }
            if (count > artifact.byteCount()) throw new IOException("database artifact exceeds expected size");
            digest.update(bytes, offset, length);
        }

        final void verify() throws IOException {
            String actual = HexFormat.of().formatHex(digest.digest());
            if (count != artifact.byteCount() || !actual.equals(artifact.sha256())) {
                throw new IOException("database artifact stream differs from verified metadata");
            }
        }
    }

    private static final class VerifyingOutput extends FilterOutputStream {
        private final Verifier verifier;

        private VerifyingOutput(OutputStream output, BackupArtifact artifact) {
            super(output);
            this.verifier = new Verifier(artifact) { };
        }

        @Override public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            verifier.update(bytes, offset, length);
            out.write(bytes, offset, length);
        }

        @Override public void close() throws IOException { flush(); }
        private void verify() throws IOException { verifier.verify(); }
    }

    private static final class VerifyingInput extends FilterInputStream {
        private final Verifier verifier;

        private VerifyingInput(InputStream input, BackupArtifact artifact) {
            super(input);
            this.verifier = new Verifier(artifact) { };
        }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) verifier.update(new byte[]{(byte) value}, 0, 1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, length);
            if (read > 0) verifier.update(bytes, offset, read);
            return read;
        }

        @Override public void close() { }
        private void verify() throws IOException { verifier.verify(); }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
