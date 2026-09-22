package gold.debug.windowstolinux.shared.linux.sshd.execution.transfer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

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

/**
 * Streams reviewed source archives into controlled remote staging and verifies transfer evidence.
 * <p>将经审阅源码归档流式传入受控远端暂存区并验证传输证据。
 */
public final class SshdSourceTransport {
    /**
     * Session used for the current scoped operation.
     * <p>当前限定作用域操作使用的会话。
     */
    private final ClientSession session;

    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Bound candidate workspace executor collaborator for candidates.
     * <p>处理候选集合的候选工作区执行器协作对象。
     */
    private final CandidateWorkspaceExecutor candidates;

    /**
     * Validates and binds the inputs required by sshd source transport.
     * <p>校验并绑定Sshd源码传输所需输入。
     *
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param candidates candidates / 候选集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SshdSourceTransport(ClientSession session, SshCommandExecutor commands,
            CandidateWorkspaceExecutor candidates) {
        this.session = Objects.requireNonNull(session, "session");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    /**
     * Transfers the reviewed source archive to owned remote staging and verifies the resulting upload evidence.
     * <p>将已审阅源码归档传输到自有远端暂存区，并验证所得上传证据。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param maxWorkspaceBytes max workspace bytes / 最大工作区字节
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public SourceUploadResult upload(SourceArchiveDescriptor archive, RemoteWorkspace workspace, long maxWorkspaceBytes)
            throws LinuxOperationException {
        LocalArchivePolicy.verify(archive);
        RemoteStepResult prepared = candidates.create(workspace, maxWorkspaceBytes);
        if (!prepared.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.CANDIDATE_PREPARATION_FAILED,
                    prepared.evidence());
        }
        String remoteArchive = workspace.candidateRoot() + "/mutable/source.tar.gz";
        try (SftpFileSystem fileSystem = SftpClientFactory.instance().createSftpFileSystem(session)) {
            Path destination = fileSystem.getPath(remoteArchive);
            Files.copy(archive.localArchive(), destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            cleanupAfterFailure(workspace);
            StackTraceElement[] frames = exception.getStackTrace();
            throw LinuxOperationException
                    .create(LinuxOperationFailureType.SOURCE_UPLOAD_FAILED,
                            "SFTP source archive upload failed (" + exception.getClass().getSimpleName()
                                    + (frames.length == 0
                                            ? ""
                                            : " at " + java.util.Arrays.stream(frames).limit(5)
                                                    .map(StackTraceElement::toString)
                                                    .collect(java.util.stream.Collectors.joining(" <- ")))
                                    + ")",
                            exception);
        }
        String verificationScript = """
                printf 'DIGEST='; sha256sum %s | awk '{print $1}'
                printf 'BYTES='; stat -c %%s %s
                """.formatted(gold.debug.windowstolinux.shared.linux.command.CommandText.quote(remoteArchive),
                gold.debug.windowstolinux.shared.linux.command.CommandText.quote(remoteArchive));
        var verified = commands.exec(
                "/bin/bash -lc " + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(verificationScript),
                Duration.ofSeconds(20), true);
        Map<String, String> uploaded = gold.debug.windowstolinux.shared.linux.command.CommandText
                .lines(verified.output());
        if (!verified.succeeded() || !archive.contentSha256().equals(uploaded.get("DIGEST"))
                || archive.byteCount() != gold.debug.windowstolinux.shared.linux.command.CommandText
                        .parseLong(uploaded.get("BYTES"))) {
            cleanupAfterFailure(workspace);
            throw LinuxOperationException.create(LinuxOperationFailureType.UPLOAD_VERIFICATION_FAILED,
                    "Remote archive digest or size verification failed");
        }
        return new SourceUploadResult(remoteArchive, archive.byteCount(), archive.contentSha256(),
                "SFTP upload and SHA-256 verification completed");
    }

    /**
     * Cleans up after failure.
     * <p>清理之后失败。
     *
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     */
    private void cleanupAfterFailure(RemoteWorkspace workspace) {
        try {
            candidates.cleanup(workspace);
        } catch (LinuxOperationException ignored) {
            // The upload failure remains authoritative; later reconciliation reports an orphan if one exists. / 上传失败仍是权威结果；后续协调会报告存在的孤立项。
        }
    }
}
