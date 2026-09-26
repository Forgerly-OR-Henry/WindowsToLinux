package gold.debug.windowstolinux.app.linux.ssh;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.*;
import org.apache.sshd.sftp.common.*;

/** Stages one reviewed regular file and publishes it only after content verification. / 暂存一个已审核普通文件，仅在内容验证后发布。 */
final class SftpTransfer {
    /** Authenticated parent transport retained by its connection. / 由连接保留的已认证父传输。 */
    private final ClientSession session;

    /** Exact transfer endpoints and byte budget. / 精确传输端点及字节预算。 */
    private final TransferRequest request;

    /** Reviewed source digest, checked before destination creation. / 创建目标前核对的已审核源摘要。 */
    private final String digest;

    /** Whether the destination is remote. / 目标是否在远端。 */
    private final boolean upload;

    /** Original total nanosecond budget. / 原始总纳秒预算。 */
    private final long budget;

    /** Original task cancellation source. / 原任务取消来源。 */
    private final CancellationToken cancellation;

    /** Actual host-owned same-name choice. / 实际宿主所属同名选择。 */
    private final TransferConflictResolver conflicts;

    /** Durable ownership gate before any staging mutation. / 暂存变更前的持久归属门禁。 */
    private final TransferJournal journal;

    /** File occupancy independent of the live connection. / 独立于活动连接的文件占用。 */
    private ResourceLease fileLease;

    /** Whether publication and final content were both verified. / 发布及最终内容是否均已核验。 */
    private boolean published;

    /** Unique temporary-file owner for this transfer. / 本次传输唯一临时文件所有者。 */
    private final UUID transferId = UUID.randomUUID();

    /** Whether the physical worker is waiting for the user's conflict decision. / 物理工作线程是否正在等待用户冲突决定。 */
    private volatile long humanStarted;

    /** Time spent waiting for conflict decisions. / 等待冲突决定花费的时间。 */
    private volatile long humanElapsed;

    /** Actual worker, retained until it terminates. / 终止前保留的实际工作线程。 */
    private Thread worker;

    /** Completed observation, independent from resource release. / 独立于资源释放的完成观测。 */
    private FutureTask<OperationResult<TransferResult>> pending;

    /** Actual SFTP channel, also observed by cancellation cleanup. / 取消清理同时观察的实际 SFTP 通道。 */
    private volatile SftpClient sftp;

    /** Actual local file, closed only after the worker returns. / 仅在工作线程返回后关闭的实际本地文件。 */
    private FileChannel local;

    /** Whether client initialization may have allocated an unreturned channel. / 客户端初始化是否可能分配尚未返回的通道。 */
    private volatile boolean opening;

    /** Cancellation fence checked before every destination effect. / 每次目标副作用前核对的取消栅栏。 */
    private boolean stopped;

    /** Whether a destination-create request may have taken effect. / 目标创建请求是否可能已生效。 */
    private boolean mutation;

    /**
     * Validates host-reviewed transfer parameters before scheduling any work. / 在调度工作前校验宿主已审核传输参数。
     * @param session authenticated SSH transport / 已认证 SSH 传输
     * @param request exact reviewed file request / 精确已审核文件请求
     * @param digest approved lowercase SHA-256 / 已批准的小写 SHA-256
     * @param upload whether this is an upload / 是否为上传
     * @param timeout total host operation budget / 宿主操作总预算
     * @param cancellation original task cancellation / 原任务取消
     * @param conflicts host-owned conflict presenter / 宿主所属冲突呈现器
     * @param journal durable original-transfer ownership / 持久原传输归属
     * @throws ApiException if an input boundary is invalid / 输入边界无效时
     */
    SftpTransfer(ClientSession session, TransferRequest request, String digest, boolean upload, Duration timeout,
            CancellationToken cancellation, TransferConflictResolver conflicts, TransferJournal journal)
            throws ApiException {
        this.session = Objects.requireNonNull(session);
        this.request = Objects.requireNonNull(request);
        this.digest = Objects.requireNonNull(digest);
        this.upload = upload;
        this.cancellation = Objects.requireNonNull(cancellation);
        this.conflicts = Objects.requireNonNull(conflicts);
        this.journal = Objects.requireNonNull(journal);
        try {
            budget = timeout.toNanos();
            String path = request.remoteAbsolutePath();
            if (budget <= 0 || !digest.matches("[0-9a-f]{64}") || !request.localPath().isAbsolute()
                    || !request.localPath().normalize().equals(request.localPath()) || path.length() > 4096
                    || !path.startsWith("/") || path.equals("/") || path.indexOf('\\') >= 0
                    || path.codePoints().anyMatch(Character::isISOControl)
                    || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(path))
                throw new IllegalArgumentException();
            for (String part : path.substring(1).split("/", -1))
                if (part.isEmpty() || part.equals(".") || part.equals(".."))
                    throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw SshConnector.failure(ProblemCode.INVALID_INPUT, "SFTP_INPUT",
                    "SFTP request paths, digest or deadline are invalid");
        }
    }

    /**
     * Observes one transfer without abandoning blocked provider work. / 观察一次传输，不丢弃阻塞的提供方工作。
     * @return verified completion or explicit uncertainty / 经核验的完成或明确不确定性
     */
    OperationResult<TransferResult> run() {
        long started = System.nanoTime();
        if (cancellation.requested() || Thread.currentThread().isInterrupted())
            return stopped("SFTP_CANCELLED", ProblemCode.CANCELLED);
        pending = new FutureTask<>(this::perform);
        worker = Thread.ofVirtual().name("platform-sftp-transfer").start(pending);
        while (true) {
            try {
                if (pending.isDone())
                    return pending.get();
                if (cancellation.requested() || Thread.currentThread().isInterrupted())
                    return stopped("SFTP_CANCELLED", ProblemCode.CANCELLED);
                long now = System.nanoTime(), prompt = humanStarted;
                long remaining = budget - (now - started - humanElapsed - (prompt == 0 ? 0 : now - prompt));
                if (remaining <= 0)
                    return stopped("SFTP_TIMEOUT", ProblemCode.TIMEOUT);
                return pending.get(Math.min(remaining, 50_000_000), TimeUnit.NANOSECONDS);
            } catch (TimeoutException retry) {
                // Recheck the original total budget and cancellation. / 复核原始总预算与取消。
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return stopped("SFTP_CANCELLED", ProblemCode.CANCELLED);
            } catch (ExecutionException failure) {
                return stopped("SFTP_WORKER_FAILED", ProblemCode.IO_FAILURE);
            }
        }
    }

    /**
     * Verifies both source and destination content through bounded streaming. / 通过有界流式处理核验源与目标内容。
     * @return result without provider messages or paths / 不含提供方消息或路径的结果
     */
    private OperationResult<TransferResult> perform() {
        String phase = "local-validation";
        try {
            check();
            Path path = request.localPath();
            verifyLocalParents(path);
            BasicFileAttributes before = upload ? regularLocal(path) : null;
            String sourceIdentity = upload
                    ? gold.debug.windowstolinux.app.windows.filesystem.WindowsFileIdentity.read(path)
                    : null;
            if (upload) {
                bounded(before.size());
                local = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                if (local.size() != before.size() || !digest.equals(hashLocal(before.size())))
                    throw new IOException("Source changed");
                verifyUnchanged(before, regularLocal(path));
                if (!sourceIdentity
                        .equals(gold.debug.windowstolinux.app.windows.filesystem.WindowsFileIdentity.read(path)))
                    throw new IOException("Local source was replaced");
            }
            check();
            phase = "sftp-open";
            opening = true;
            sftp = SftpClientFactory.instance().createSftpClient(session);
            phase = "remote-validation";
            verifyRemoteParents();
            var destination = new SftpDestination(sftp, request, upload, transferId, cancellation);
            SftpClient.CloseableHandle remote;
            long size;
            if (upload) {
                size = before.size();
                destination.select(size, this::choose);
                phase = "checkpoint";
                fileLease = Objects
                        .requireNonNull(journal.prepare(destination.plan(new TransferPlan.FileStamp(sourceIdentity,
                                size, before.lastModifiedTime().toString(), digest))));
                phase = "upload";
                beginMutation();
                remote = sftp.open(destination.remoteTemporary(), SftpClient.OpenMode.Read, SftpClient.OpenMode.Write,
                        SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive);
                copyToRemote(remote, size);
                verifyUnchanged(before, regularLocal(path));
                if (!sourceIdentity
                        .equals(gold.debug.windowstolinux.app.windows.filesystem.WindowsFileIdentity.read(path)))
                    throw new IOException("Local source was replaced");
                if (local.size() != size || !digest.equals(hashLocal(size)))
                    throw new IOException("Source changed");
                regularRemote(sftp.stat(remote), size);
                if (!digest.equals(hashRemote(remote, size)))
                    throw new IOException("Destination digest mismatch");
            } else {
                var attributes = sftp.lstat(request.remoteAbsolutePath());
                regularRemote(attributes, attributes.getSize());
                if (!request.remoteAbsolutePath().equals(sftp.canonicalPath(request.remoteAbsolutePath())))
                    throw new IOException("Remote file is redirected");
                size = attributes.getSize();
                bounded(size);
                phase = "download";
                remote = sftp.open(request.remoteAbsolutePath(), SftpClient.OpenMode.Read);
                regularRemote(sftp.stat(remote), size);
                if (!digest.equals(hashRemote(remote, size)))
                    throw new IOException("Source digest mismatch");
                destination.select(size, this::choose);
                check();
                verifyLocalParents(path);
                phase = "checkpoint";
                if (!attributes.getFlags().contains(SftpClient.Attribute.ModifyTime))
                    throw new IOException("Remote source modification time is unavailable");
                fileLease = Objects.requireNonNull(journal.prepare(destination.plan(
                        new TransferPlan.FileStamp("remote", size, attributes.getModifyTime().toString(), digest))));
                phase = "download";
                beginMutation();
                local = FileChannel.open(destination.localTemporary(), StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                copyToLocal(remote, size);
                local.force(true);
                if (local.size() != size || !digest.equals(hashLocal(size)))
                    throw new IOException("Destination digest mismatch");
                regularRemote(sftp.stat(remote), size);
            }
            verifyLocalParents(path);
            verifyRemoteParents();
            check();
            remote.close();
            local.close();
            phase = "publish";
            destination.publish();
            regularRemote(sftp.lstat(destination.remote()), size);
            regularLocal(destination.local());
            phase = "published-verification";
            if (upload) {
                try (var published = sftp.open(destination.remote(), SftpClient.OpenMode.Read)) {
                    if (!digest.equals(hashRemote(published, size)))
                        throw new IOException("Published remote content changed");
                }
            } else {
                local = FileChannel.open(destination.local(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                if (!digest.equals(hashLocal(size)))
                    throw new IOException("Published local content changed");
                local.close();
            }
            published = true;
            return new OperationResult<>(Outcome.SUCCEEDED,
                    Optional.of(new TransferResult(size, digest, destination.local(), destination.remote())),
                    Optional.empty());
        } catch (Exception failure) {
            var result = problem("SFTP_NOT_VERIFIED",
                    cancellation.requested() || failure instanceof CancellationException
                            ? ProblemCode.CANCELLED
                            : ProblemCode.IO_FAILURE);
            var problem = result.problem().orElseThrow();
            return new OperationResult<>(result.outcome(), result.value(),
                    Optional.of(new Problem(problem.code(), problem.eventCode(), problem.message(),
                            Map.of("transferId", transferId.toString(), "phase", phase, "providerType",
                                    failure.getClass().getSimpleName(), "status",
                                    failure instanceof SftpException sftpFailure
                                            ? Integer.toString(sftpFailure.getStatus())
                                            : "none"),
                            Optional.empty())));
        }
    }

    /** Excludes explicit human decision time from transport waiting. / 从传输等待中排除明确人工决定时间。
     * @param conflict actual same-name conflict / 实际同名冲突
     * @return explicit user choice / 明确用户选择
     * @throws Exception if the owned dialog fails / 所属对话框失败时 */
    private TransferConflictChoice choose(TransferConflict conflict) throws Exception {
        long started = System.nanoTime();
        humanStarted = started;
        try {
            return conflicts.resolve(conflict);
        } finally {
            humanElapsed += System.nanoTime() - started;
            humanStarted = 0;
        }
    }

    /**
     * Streams unchanged local bytes to the newly created remote handle. / 向新建远端句柄流式传送未改变的本地字节。
     * @param remote owned remote handle / 所属远端句柄
     * @param size exact reviewed size / 精确已审核大小
     * @throws IOException if the source changes or transport fails / 源变化或传输失败时
     */
    private void copyToRemote(SftpClient.Handle remote, long size) throws IOException {
        byte[] buffer = new byte[16384];
        for (long offset = 0; offset < size;) {
            check();
            int count = local.read(ByteBuffer.wrap(buffer, 0, (int) Math.min(buffer.length, size - offset)), offset);
            if (count <= 0)
                throw new IOException("Source ended early");
            sftp.write(remote, offset, buffer, 0, count);
            offset += count;
        }
    }

    /**
     * Streams the fixed remote extent to a new local file and verifies its digest. / 将固定远端区间流式写入新本地文件并核对摘要。
     * @param remote owned remote handle / 所属远端句柄
     * @param size exact reviewed size / 精确已审核大小
     * @throws IOException if content changes or transport fails / 内容变化或传输失败时
     */
    private void copyToLocal(SftpClient.Handle remote, long size) throws IOException {
        byte[] buffer = new byte[32768];
        MessageDigest hash = sha256();
        for (long offset = 0; offset < size;) {
            check();
            int count = sftp.read(remote, offset, buffer, 0, (int) Math.min(buffer.length, size - offset));
            if (count <= 0)
                throw new IOException("Source ended early");
            hash.update(buffer, 0, count);
            ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
            while (bytes.hasRemaining()) {
                check();
                local.write(bytes);
            }
            offset += count;
        }
        if (!digest.equals(HexFormat.of().formatHex(hash.digest())) || sftp.read(remote, size, buffer, 0, 1) >= 0)
            throw new IOException("Source changed");
    }

    /**
     * Hashes a bounded local extent and rejects length changes. / 对有界本地区间求摘要并拒绝长度变化。
     * @param size expected file length / 预期文件长度
     * @return lowercase SHA-256 / 小写 SHA-256
     * @throws IOException if the source changes or cannot be read / 源变化或无法读取时
     */
    private String hashLocal(long size) throws IOException {
        MessageDigest hash = sha256();
        ByteBuffer bytes = ByteBuffer.allocate(32768);
        for (long offset = 0; offset < size;) {
            check();
            bytes.clear().limit((int) Math.min(bytes.capacity(), size - offset));
            int count = local.read(bytes, offset);
            if (count <= 0)
                throw new IOException("Source ended early");
            hash.update(bytes.array(), 0, count);
            offset += count;
        }
        if (local.size() != size)
            throw new IOException("File length changed");
        return HexFormat.of().formatHex(hash.digest());
    }

    /**
     * Reads back the exact bounded remote extent and rejects appended data. / 回读精确有界远端区间并拒绝追加内容。
     * @param remote owned handle / 所属句柄
     * @param size expected length / 预期长度
     * @return lowercase SHA-256 / 小写 SHA-256
     * @throws IOException if verification fails / 核验失败时
     */
    private String hashRemote(SftpClient.Handle remote, long size) throws IOException {
        MessageDigest hash = sha256();
        byte[] bytes = new byte[32768];
        for (long offset = 0; offset < size;) {
            check();
            int count = sftp.read(remote, offset, bytes, 0, (int) Math.min(bytes.length, size - offset));
            if (count <= 0)
                throw new IOException("Remote file ended early");
            hash.update(bytes, 0, count);
            offset += count;
        }
        if (sftp.read(remote, size, bytes, 0, 1) >= 0)
            throw new IOException("Remote file grew");
        return HexFormat.of().formatHex(hash.digest());
    }

    /**
     * Checks every existing local parent without following links or junctions. / 不跟随链接或联接，核对每个已有本地父目录。
     * @param path selected absolute local path / 选定本地绝对路径
     * @throws IOException if an ancestor is not an ordinary directory / 祖先不是普通目录时
     */
    private static void verifyLocalParents(Path path) throws IOException {
        for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
            var attributes = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("Local parent is not an ordinary directory");
        }
    }

    /**
     * Requires an existing ordinary local file. / 要求已有本地普通文件。
     * @param path exact selected file / 精确选定文件
     * @return non-following attributes / 不跟随链接的属性
     * @throws IOException if the selected object is not regular / 选定对象不是普通文件时
     */
    private static BasicFileAttributes regularLocal(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
            throw new IOException("Local file is not regular");
        return attributes;
    }

    /**
     * Rejects observed source replacement, length or modification changes. / 拒绝观测到的源替换、长度或修改时间变化。
     * @param before original attributes / 原始属性
     * @param after current attributes / 当前属性
     * @throws IOException if source identity or metadata changed / 源身份或元数据变化时
     */
    private static void verifyUnchanged(BasicFileAttributes before, BasicFileAttributes after) throws IOException {
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()))
            throw new IOException("Local source changed");
    }

    /**
     * Rejects linked or non-directory remote ancestors. / 拒绝链接或非目录远端祖先。
     * @throws IOException if a parent does not meet the boundary / 父目录不满足边界时
     */
    private void verifyRemoteParents() throws IOException {
        String path = request.remoteAbsolutePath();
        for (int end = path.indexOf('/', 1); end >= 0; end = path.indexOf('/', end + 1)) {
            check();
            String parent = path.substring(0, end);
            var attributes = sftp.lstat(parent);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || !parent.equals(sftp.canonicalPath(parent)))
                throw new IOException("Remote parent is not regular");
        }
    }

    /**
     * Requires an explicitly typed ordinary remote file with its exact length. / 要求明确类型的远端普通文件及其精确长度。
     * @param attributes observed remote attributes / 已观测远端属性
     * @param size exact expected length / 精确预期长度
     * @throws IOException if type or size is unconfirmed / 类型或大小未确认时
     */
    private static void regularRemote(SftpClient.Attributes attributes, long size) throws IOException {
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || !attributes.getFlags().contains(SftpClient.Attribute.Size) || attributes.getSize() != size
                || size < 0)
            throw new IOException("Remote file is not regular or changed");
    }

    /**
     * Enforces the caller's exact file byte budget. / 执行调用方精确文件字节预算。
     * @param size observed file size / 已观测文件大小
     * @throws IOException if the file exceeds the boundary / 文件超出边界时
     */
    private void bounded(long size) throws IOException {
        if (size < 0 || size > request.maxBytes())
            throw new IOException("Transfer byte limit exceeded");
    }

    /**
     * Seals cancellation before the first possible destination mutation. / 在首次可能的目标变更前核对取消栅栏。
     * @throws IOException if cancellation won admission / 取消先于准入时
     */
    private synchronized void beginMutation() throws IOException {
        check();
        mutation = true;
    }

    /**
     * Observes cancellation without interrupting arbitrary file code. / 观察取消，不中断任意文件代码。
     * @throws IOException if the original operation was stopped / 原操作已停止时
     */
    private synchronized void check() throws IOException {
        if (stopped || cancellation.requested())
            throw new IOException("Transfer stopped");
    }

    /**
     * Builds a safe result and retains uncertainty after mutation admission. / 构造安全结果，变更准入后保留不确定性。
     * @param event stable event code / 稳定事件码
     * @param code failure category before mutation / 变更前失败类别
     * @return result without file contents or provider exceptions / 不含文件内容或提供方异常的结果
     */
    synchronized OperationResult<TransferResult> problem(String event, ProblemCode code) {
        Outcome outcome = mutation
                ? Outcome.UNKNOWN
                : code == ProblemCode.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED;
        return new OperationResult<>(outcome, Optional.empty(),
                Optional.of(new Problem(mutation ? ProblemCode.UNKNOWN : code, event,
                        mutation
                                ? "File transfer was not verified; destination effects require reconciliation"
                                : "File transfer was rejected before destination creation",
                        Map.of("transferId", transferId.toString()), Optional.empty())));
    }

    /**
     * Stops admission while leaving actual cleanup to the owning connection. / 停止准入，将实际清理留给所属连接。
     * @param event stable cancellation reason / 稳定取消原因
     * @param code failure category before mutation / 变更前失败类别
     * @return cancellation or uncertainty result / 取消或不确定结果
     */
    private synchronized OperationResult<TransferResult> stopped(String event, ProblemCode code) {
        stopped = true;
        return problem(event, code);
    }

    /**
     * Releases actual channels and waits for the worker before closing its local file. / 释放实际通道，并等待工作线程后关闭其本地文件。
     * @return whether every local resource is actually closed / 每个本地资源是否实际关闭
     */
    boolean close() {
        synchronized (this) {
            stopped = true;
        }
        boolean interrupted = Thread.interrupted();
        try {
            if (sftp != null) {
                if (!sftp.getClientChannel().close(true).awaitUninterruptibly(Duration.ofSeconds(5)))
                    return false;
            } else if (opening && !session.close(true).awaitUninterruptibly(Duration.ofSeconds(5)))
                return false;
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (worker != null && worker.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    return false;
                try {
                    worker.join(Duration.ofNanos(remaining));
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            // Initialization can finish after the first cleanup observation. / 初始化可能在首次清理观测后完成。
            if (sftp != null && !sftp.getClientChannel().close(true).awaitUninterruptibly(Duration.ofSeconds(5)))
                return false;
            if (opening && sftp == null && !session.close(true).awaitUninterruptibly(Duration.ofSeconds(5)))
                return false;
            if (local != null)
                local.close();
            if ((local != null && local.isOpen()) || (sftp != null && !sftp.getClientChannel().isClosed()))
                return false;
            if (fileLease != null && (!mutation || published)) {
                fileLease.close();
                fileLease = null;
            }
            return true;
        } catch (IOException | ApiException | RuntimeException failure) {
            return false;
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /** Returns the mandatory JDK SHA-256 implementation. / 返回 JDK 必备的 SHA-256 实现。
     * @return fresh hash accumulator / 新摘要累加器 */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
