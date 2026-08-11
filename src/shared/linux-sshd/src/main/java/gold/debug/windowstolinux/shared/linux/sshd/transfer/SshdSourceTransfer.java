package gold.debug.windowstolinux.shared.linux.sshd.transfer;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.PhaseOneProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.transfer.UploadReceipt;
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
 * Provides the {@code SshdSourceTransfer} implementation.
 *
 * <p>提供 {@code SshdSourceTransfer} 实现。
 */
public final class SshdSourceTransfer {
    private final ClientSession session;
    private final SshCommandExecutor commands;
    private final PhaseOneProtocolExecutor protocol;

    /**
     * Creates a {@code SshdSourceTransfer} instance.
     *
     * <p>创建 {@code SshdSourceTransfer} 实例。
     *
     * @param session the {@code session} value / {@code session} 值
     * @param commands the {@code commands} value / {@code commands} 值
     * @param protocol the {@code protocol} value / {@code protocol} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SshdSourceTransfer(ClientSession session, SshCommandExecutor commands,
                              PhaseOneProtocolExecutor protocol) {
        this.session = Objects.requireNonNull(session, "session");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
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
    public UploadReceipt upload(SourceArchiveDescriptor archive, RemoteWorkspace workspace)
            throws LinuxOperationException {
        LocalArchivePolicy.verify(archive);
        RemoteStepResult prepared = protocol.createCandidate(workspace);
        if (!prepared.succeeded()) {
            throw LinuxOperationException.localized("linux.error.candidatePreparationFailed", prepared.evidence());
        }
        String remoteArchive = workspace.candidateRoot() + "/mutable/source.tar.gz";
        try (SftpFileSystem fileSystem = SftpClientFactory.instance().createSftpFileSystem(session)) {
            Path destination = fileSystem.getPath(remoteArchive);
            Files.copy(archive.localArchive(), destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            cleanupAfterFailure(workspace);
            throw LinuxOperationException.localized("linux.error.sourceUploadFailed",
                    "SFTP source archive upload failed", exception);
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
            throw LinuxOperationException.localized("linux.error.uploadVerificationFailed",
                    "Remote archive digest or size verification failed");
        }
        return new UploadReceipt(remoteArchive, archive.byteCount(), archive.contentSha256(),
                "SFTP upload and SHA-256 verification completed");
    }

    private void cleanupAfterFailure(RemoteWorkspace workspace) {
        try {
            protocol.cleanupCandidate(workspace);
        } catch (LinuxOperationException ignored) {
            // The upload failure remains authoritative; later reconciliation reports an orphan if one exists. / 上传失败仍是权威结果；后续协调会报告存在的孤立项。
        }
    }
}
