package gold.debug.windowstolinux.shared.linux.sshd.backup;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifact;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactRequest;
import gold.debug.windowstolinux.shared.linux.sshd.backup.execution.protocol.ManagedBackupProtocolParser;
import gold.debug.windowstolinux.shared.linux.sshd.backup.generation.script.ManagedBackupCommandRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;

/**
 * Apache SSHD managed backup artifacts backed only by fixed helper verbs. / 仅由固定 helper 动词支持的 Apache SSHD 受管备份制品端口。
 */
public final class SshdBackupArtifactPort implements RemoteBackupArtifactPort {
    /**
     * CREATE TIMEOUT.
     * <p>创建超时。
     */
    private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(30);

    /**
     * STREAM TIMEOUT.
     * <p>流超时。
     */
    private static final Duration STREAM_TIMEOUT = Duration.ofHours(2);

    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Renderer.
     * <p>渲染器。
     */
    private final ManagedBackupCommandRenderer renderer = new ManagedBackupCommandRenderer();

    /**
     * Parser.
     * <p>解析器。
     */
    private final ManagedBackupProtocolParser parser = new ManagedBackupProtocolParser();

    /**
     * Creates the port for one authenticated SSH session. / 为一个已认证 SSH 会话创建端口。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SshdBackupArtifactPort(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * Begins maintenance.
     * <p>开始维护。
     *
     * @param app app / 应用
     * @param token token / 令牌
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public void beginMaintenance(gold.debug.windowstolinux.shared.model.managed.ManagedApplication app, String token)
            throws LinuxOperationException {
        maintenance(app, token, "begin");
    }

    /**
     * Releases only the maintenance marker belonging to the supplied operation.
     * <p>仅释放属于所提供操作的维护标记。
     *
     * @param app app / 应用
     * @param token token / 令牌
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public void endMaintenance(gold.debug.windowstolinux.shared.model.managed.ManagedApplication app, String token)
            throws LinuxOperationException {
        maintenance(app, token, "end");
    }

    /**
     * Checks maintenance syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查维护语法及边界。
     *
     * @param app app / 应用
     * @param token token / 令牌
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private void maintenance(gold.debug.windowstolinux.shared.model.managed.ManagedApplication app, String token,
            String action) throws LinuxOperationException {
        if (!token.matches("(?:(?:backup|restore)-[0-9a-f]{32}|deployment-[0-9a-f]{64})"))
            throw new IllegalArgumentException("invalid maintenance token");
        var result = commands.execProtocol("sudo -n "
                + gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle.PATH
                + " application-maintenance " + action + " " + app.id() + " " + app.ownershipManifestSha256() + " "
                + token, Duration.ofSeconds(40), true);
        if (!result.succeeded())
            throw LinuxOperationException.create(LinuxOperationFailureType.BACKUP_ARTIFACT_CREATION_FAILED,
                    "Application maintenance could not be verified: " + result.failureEvidence());
    }

    /**
     * Creates backup artifact.
     * <p>创建备份制品。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return backup artifact / 备份制品
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Copies backup artifact.
     * <p>复制备份制品。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    @Override
    public void copyBackupArtifact(RemoteBackupArtifact artifact, OutputStream destination)
            throws LinuxOperationException {
        Objects.requireNonNull(destination, "destination");
        VerifyingOutput output = new VerifyingOutput(destination, artifact);
        try {
            var result = commands.execProtocolStreaming(renderer.read(artifact), InputStream.nullInputStream(), output,
                    STREAM_TIMEOUT, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 0,
                    Math.max(1, artifact.byteCount()));
            if (!result.succeeded())
                throw new IOException(result.failureEvidence());
            output.verify();
        } catch (IOException | LinuxOperationException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.BACKUP_ARTIFACT_TRANSFER_FAILED,
                    "managed backup artifact stream differs from helper evidence", exception);
        }
    }

    /**
     * Discards backup operation.
     * <p>清理备份操作。
     *
     * @param operationId identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public RemoteStepResult discardBackupOperation(String operationId) throws LinuxOperationException {
        var result = commands.execProtocol(renderer.discard(operationId), CREATE_TIMEOUT, false);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.BACKUP_OPERATION_CLEANUP_FAILED,
                    "managed backup operation cleanup failed: " + result.failureEvidence());
        }
        return new RemoteStepResult(true, false, "Controlled helper removed the exact backup operation");
    }

    /**
     * Counts and hashes streamed bytes while forwarding them to the caller's destination.
     * <p>向调用方目标转发流数据时累计字节数及摘要。
     */
    private static final class VerifyingOutput extends FilterOutputStream {
        /**
         * Verified build or backup artifact metadata.
         * <p>已验证构建或备份制品元数据。
         */
        private final RemoteBackupArtifact artifact;

        /**
         * Content identity used for independent verification.
         * <p>独立验证所用的内容身份。
         */
        private final MessageDigest digest = sha256();

        /**
         * Count.
         * <p>数量。
         */
        private long count;

        /**
         * Binds the supplied dependencies and state for verifying output.
         * <p>为Verifying输出绑定传入的依赖及状态。
         *
         * @param output destination receiving the produced content / 接收所生成内容的目标
         * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
         */
        private VerifyingOutput(OutputStream output, RemoteBackupArtifact artifact) {
            super(output);
            this.artifact = artifact;
        }

        /**
         * Writes verifying output.
         * <p>写入Verifying输出。
         *
         * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        /**
         * Writes verifying output.
         * <p>写入Verifying输出。
         *
         * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
         * @param offset offset / 偏移量
         * @param length length / 长度
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            count = Math.addExact(count, length);
            if (count > artifact.byteCount())
                throw new IOException("backup stream exceeds evidence");
            digest.update(bytes, offset, length);
            out.write(bytes, offset, length);
        }

        /**
         * Closes the resources owned by this instance and completes its cleanup boundary.
         * <p>关闭当前实例持有的资源并完成其清理边界。
         *
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        @Override
        public void close() throws IOException {
            flush();
        }

        /**
         * Verifies verifying output.
         * <p>验证Verifying输出。
         *
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        private void verify() throws IOException {
            if (count != artifact.byteCount() || !HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256())) {
                throw new IOException("backup stream digest or size differs");
            }
        }
    }

    /**
     * Creates a SHA-256 accumulator for independent content evidence.
     * <p>创建用于独立内容证据的 SHA-256 累加器。
     *
     * @return new SHA-256 digest accumulator / 新的 SHA-256 摘要累加器
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
