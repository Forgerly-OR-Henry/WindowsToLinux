package gold.debug.windowstolinux.shared.linux.sshd.execution.transfer;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.CandidateWorkspaceExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.transfer.SourceUploadResult;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Provides the {@code SshdSourceTransport} implementation.
 *
 * <p>提供 {@code SshdSourceTransport} 实现。
 */
public final class SshdSourceTransport {
    private final ClientSession session;
    private final SshCommandExecutor commands;
    private final CandidateWorkspaceExecutor candidates;

    /**
     * Creates a {@code SshdSourceTransport} instance.
     *
     * <p>创建 {@code SshdSourceTransport} 实例。
     *
     * @param session the {@code session} value / {@code session} 值
     * @param commands the {@code commands} value / {@code commands} 值
     * @param candidates the {@code candidates} value / {@code candidates} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SshdSourceTransport(ClientSession session, SshCommandExecutor commands,
                              CandidateWorkspaceExecutor candidates) {
        this.session = Objects.requireNonNull(session, "session");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    /**
     * Performs the {@code upload} operation.
     *
     * <p>执行 {@code upload} 操作。
     *
     * @param archive the {@code archive} value / {@code archive} 值
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public SourceUploadResult upload(SourceArchiveDescriptor archive, RemoteWorkspace workspace, long maxWorkspaceBytes)
            throws LinuxOperationException {
        LocalArchivePolicy.verify(archive);
        RemoteStepResult prepared = candidates.create(workspace, maxWorkspaceBytes);
        if (!prepared.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.CANDIDATE_PREPARATION_FAILED, prepared.evidence());
        }
        String remoteArchive = workspace.candidateRoot() + "/mutable/source.tar.gz";
        try (SftpFileSystem fileSystem = SftpClientFactory.instance().createSftpFileSystem(session)) {
            Path destination = fileSystem.getPath(remoteArchive);
            Files.copy(archive.localArchive(), destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            cleanupAfterFailure(workspace);
            StackTraceElement[] frames = exception.getStackTrace();
            throw LinuxOperationException.create(LinuxOperationFailureType.SOURCE_UPLOAD_FAILED,
                    "SFTP source archive upload failed (" + exception.getClass().getSimpleName()
                            + (frames.length == 0 ? "" : " at " + java.util.Arrays.stream(frames).limit(5)
                                    .map(StackTraceElement::toString).collect(java.util.stream.Collectors.joining(" <- ")))
                            + ")", exception);
        }
        String verificationScript = """
                printf 'DIGEST='; sha256sum %s | awk '{print $1}'
                printf 'BYTES='; stat -c %%s %s
                """.formatted(SshCommandExecutor.quote(remoteArchive), SshCommandExecutor.quote(remoteArchive));
        var verified = commands.exec("/bin/bash -lc " + SshCommandExecutor.quote(verificationScript),
                Duration.ofSeconds(20), true);
        Map<String, String> uploaded = SshCommandExecutor.lines(verified.output());
        if (!verified.succeeded() || !archive.contentSha256().equals(uploaded.get("DIGEST"))
                || archive.byteCount() != SshCommandExecutor.parseLong(uploaded.get("BYTES"))) {
            cleanupAfterFailure(workspace);
            throw LinuxOperationException.create(LinuxOperationFailureType.UPLOAD_VERIFICATION_FAILED,
                    "Remote archive digest or size verification failed");
        }
        return new SourceUploadResult(remoteArchive, archive.byteCount(), archive.contentSha256(),
                "SFTP upload and SHA-256 verification completed");
    }

    private void cleanupAfterFailure(RemoteWorkspace workspace) {
        try {
            candidates.cleanup(workspace);
        } catch (LinuxOperationException ignored) {
            // The upload failure remains authoritative; later reconciliation reports an orphan if one exists. / 上传失败仍是权威结果；后续协调会报告存在的孤立项。
        }
    }
}
