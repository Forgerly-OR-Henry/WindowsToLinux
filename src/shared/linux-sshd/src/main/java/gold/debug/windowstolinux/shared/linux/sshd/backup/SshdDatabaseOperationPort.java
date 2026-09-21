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

/**
 * Apache SSHD database port backed only by fixed root-owned helper verbs. / 仅由固定 root 持有 helper 动词支持的 Apache SSHD 数据库端口。
 */
public final class SshdDatabaseOperationPort implements RemoteDatabasePort {
    /**
     * INSPECTION TIMEOUT.
     * <p>检查超时。
     */
    private static final Duration INSPECTION_TIMEOUT = Duration.ofSeconds(30);
    /**
     * DATABASE TIMEOUT.
     * <p>数据库超时。
     */
    private static final Duration DATABASE_TIMEOUT = Duration.ofMinutes(30);
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;
    /**
     * Renderer.
     * <p>渲染器。
     */
    private final DatabaseCommandRenderer renderer;
    /**
     * Parser.
     * <p>解析器。
     */
    private final DatabaseProtocolParser parser;

    /**
     * Creates the database port for one authenticated SSH session. / 为一个已认证 SSH 会话创建数据库端口。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SshdDatabaseOperationPort(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.renderer = new DatabaseCommandRenderer();
        this.parser = new DatabaseProtocolParser();
    }

    /**
     * Inspects compatibility evidence.
     * <p>检查兼容性证据。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved compatibility evidence / 构造或解析得到的兼容性证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Exports the selected database using the admitted consistency strategy.
     * <p>使用已准入一致性策略导出所选数据库。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param consistencyMode consistency mode / 一致性模式
     * @return constructed or resolved backup artifact / 构造或解析得到的备份制品
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Restores candidate.
     * <p>恢复候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved restore evidence / 构造或解析得到的恢复证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Commits candidate.
     * <p>提交候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved commit evidence / 构造或解析得到的提交证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Recovers candidate.
     * <p>恢复候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Discards candidate.
     * <p>清理候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Copies verified build or backup artifact metadata.
     * <p>复制已验证构建或备份制品元数据。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Stages verified build or backup artifact metadata.
     * <p>暂存已验证构建或备份制品元数据。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Discards verified build or backup artifact metadata.
     * <p>清理已验证构建或备份制品元数据。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Requires success.
     * <p>要求成功。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param operation operation / 操作
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static void requireSuccess(
            SshCommandExecutor.CommandResult result, LinuxOperationFailureType type, String operation)
            throws LinuxOperationException {
        if (!result.succeeded()) {
            throw LinuxOperationException.create(type, operation + ": " + result.failureEvidence());
        }
    }

    /**
     * Builds a non-secret database reference using the SQLite binding or server host, port and database.
     * <p>使用 SQLite 绑定或服务器主机、端口及数据库构建非秘密数据库引用。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @return a non-secret database reference using the SQLite binding or server host, port and database / 使用 SQLite 绑定或服务器主机、端口及数据库构建非秘密数据库引用
     */
    private static String reference(ConnectionProfile profile) {
        if (profile instanceof ConnectionProfile.Sqlite sqlite) return sqlite.bindingId();
        ConnectionProfile.Server server = (ConnectionProfile.Server) profile;
        return server.host() + ":" + server.port() + "/" + server.database();
    }

    /**
     * Checks transferred database byte counts and SHA-256 evidence.
     * <p>检查数据库传输字节数及 SHA-256 证据。
     */
    private abstract static class Verifier {
        /**
         * Verified build or backup artifact metadata.
         * <p>已验证构建或备份制品元数据。
         */
        private final BackupArtifact artifact;
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
         * Validates and binds the inputs required by verifier.
         * <p>校验并绑定验证器所需输入。
         *
         * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        private Verifier(BackupArtifact artifact) {
            this.artifact = Objects.requireNonNull(artifact, "artifact");
        }

        /**
         * Updates verifier.
         * <p>更新验证器。
         *
         * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
         * @param offset offset / 偏移量
         * @param length length / 长度
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        final void update(byte[] bytes, int offset, int length) throws IOException {
            try {
                count = Math.addExact(count, length);
            } catch (ArithmeticException exception) {
                throw new IOException("database artifact length overflow", exception);
            }
            if (count > artifact.byteCount()) throw new IOException("database artifact exceeds expected size");
            digest.update(bytes, offset, length);
        }

        /**
         * Verifies verifier.
         * <p>验证验证器。
         *
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        final void verify() throws IOException {
            String actual = HexFormat.of().formatHex(digest.digest());
            if (count != artifact.byteCount() || !actual.equals(artifact.sha256())) {
                throw new IOException("database artifact stream differs from verified metadata");
            }
        }
    }

    /**
     * Counts and hashes streamed bytes while forwarding them to the caller's destination.
     * <p>向调用方目标转发流数据时累计字节数及摘要。
     */
    private static final class VerifyingOutput extends FilterOutputStream {
        /**
         * Verifier.
         * <p>验证器。
         */
        private final Verifier verifier;

        /**
         * Prevents instantiation of this static contract helper.
         * <p>防止实例化当前静态契约辅助类。
         *
         * @param output destination receiving the produced content / 接收所生成内容的目标
         * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
         */
        private VerifyingOutput(OutputStream output, BackupArtifact artifact) {
            super(output);
            this.verifier = new Verifier(artifact) { };
        }

        /**
         * Writes verifying output.
         * <p>写入Verifying输出。
         *
         * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        @Override public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
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
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            verifier.update(bytes, offset, length);
            out.write(bytes, offset, length);
        }

        /**
         * Closes the resources owned by this instance and completes its cleanup boundary.
         * <p>关闭当前实例持有的资源并完成其清理边界。
         *
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        @Override public void close() throws IOException { flush(); }
        /**
         * Verifies verifying output.
         * <p>验证Verifying输出。
         *
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        private void verify() throws IOException { verifier.verify(); }
    }

    /**
     * Counts and hashes consumed bytes while reading a verified transfer source.
     * <p>读取已验证传输源时累计已消费字节数及摘要。
     */
    private static final class VerifyingInput extends FilterInputStream {
        /**
         * Verifier.
         * <p>验证器。
         */
        private final Verifier verifier;

        /**
         * Prevents instantiation of this static contract helper.
         * <p>防止实例化当前静态契约辅助类。
         *
         * @param input source content consumed by this operation / 当前操作消费的源内容
         * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
         */
        private VerifyingInput(InputStream input, BackupArtifact artifact) {
            super(input);
            this.verifier = new Verifier(artifact) { };
        }

        /**
         * Reads verifying input.
         * <p>读取Verifying输入。
         *
         * @return verifying input / Verifying输入
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) verifier.update(new byte[]{(byte) value}, 0, 1);
            return value;
        }

        /**
         * Reads verifying input.
         * <p>读取Verifying输入。
         *
         * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
         * @param offset offset / 偏移量
         * @param length length / 长度
         * @return verifying input / Verifying输入
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, length);
            if (read > 0) verifier.update(bytes, offset, read);
            return read;
        }

        /**
         * Accepts the callback without side effects because this adapter needs no additional action.
         * <p>接受回调且不产生副作用，因为当前适配器无需额外动作。
         */
        @Override public void close() { }
        /**
         * Verifies verifying input.
         * <p>验证Verifying输入。
         *
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        private void verify() throws IOException { verifier.verify(); }
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
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
