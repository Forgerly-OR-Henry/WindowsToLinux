package gold.debug.windowstolinux.app.linux.sftp;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.ResourceLease;

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
import gold.debug.windowstolinux.appapi.task.CancelToken;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.*;
import org.apache.sshd.sftp.common.*;

/** Stages one reviewed regular file and publishes it only after content verification. / 暂存一个已审核普通文件，仅在内容验证后发布。 */
public final class SftpTransfer {
    /** Original destination publication and observed recovery completion. / 原目标发布及已观察恢复收尾。 */
    private final SftpCompletion completion = new SftpCompletion();

    /** Authenticated parent transport retained by its connection. / 由连接保留的已认证父传输。 */
    private final ClientSession session;

    /** Exact transfer endpoints and byte budget. / 精确传输端点及字节预算。 */
    private final TransferRequest request;

    /** Reviewed source digest, checked before destination creation. / 创建目标前核对的已审核源摘要。 */
    private final String digest;

    /** Whether the destination is remote. / 目标是否在远端。 */
    private final boolean upload;

    /** Allowed interval without effective file-byte progress. / 允许无有效文件字节进展的时长。 */
    private final long idleNanos;

    /** Thread-safe physical byte progress and human-wait clock. / 线程安全物理字节进度及人工等待计时。 */
    private final SftpProgress progress;

    /** Host progress publisher observed from the monitoring thread. / 由监控线程调用的宿主进度发布器。 */
    private final TransferProgressListener listener;

    /** Original session properties restored only after the actual SFTP worker closes. / 仅在实际 SFTP 工作线程关闭后恢复的原会话属性。 */
    private final Map<String, Object> originalTimeouts = new HashMap<>();

    /** Original task cancellation source. / 原任务取消来源。 */
    private final CancelToken cancellation;

    /** Actual host-owned same-name choice. / 实际宿主所属同名选择。 */
    private final TransferConflictResolver conflicts;

    /** Durable ownership gate before any staging mutation. / 暂存变更前的持久归属门禁。 */
    private final TransferJournal journal;

    /** File occupancy independent of the live connection. / 独立于活动连接的文件占用。 */
    private ResourceLease fileLease;

    /** Whether publication or staging cleanup was verified. / 发布或暂存清理是否已核验。 */
    private boolean resolved;

    /** Original plan when reconciling a retained transfer. / 核对保留传输时的原计划。 */
    private final TransferPlan recovery;

    /** Explicitly approved discard instead of continuation. / 明确已批准的丢弃而非继续传输。 */
    private final boolean discard;

    /** Current non-secret recovery or transfer phase. / 当前不含秘密的恢复或传输阶段。 */
    private String phase = "local-validation";

    /** Unique temporary-file owner for this transfer. / 本次传输唯一临时文件所有者。 */
    private final UUID transferId;

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

    /** Whether an original or new destination mutation may have taken effect. / 原目标或新目标变更是否可能已生效。 */
    private boolean mutation;

    /**
     * Validates host-reviewed transfer parameters before scheduling any work. / 在调度工作前校验宿主已审核传输参数。
     * @param session authenticated SSH transport / 已认证 SSH 传输
     * @param request exact reviewed file request / 精确已审核文件请求
     * @param digest approved lowercase SHA-256 / 已批准的小写 SHA-256
     * @param upload whether this is an upload / 是否为上传
     * @param timeout no-effective-progress interval / 无有效进展时长
     * @param cancellation original task cancellation / 原任务取消
     * @param conflicts host-owned conflict presenter / 宿主所属冲突呈现器
     * @param journal durable original-transfer ownership / 持久原传输归属
     * @param listener host progress publication / 宿主进度发布
     * @param recovery original plan, or null for a fresh transfer / 原计划，新传输时为 null
     * @param discard whether to discard verified original staging / 是否丢弃经核验原暂存
     * @throws ApiException if an input boundary is invalid / 输入边界无效时
     */
    public SftpTransfer(ClientSession session, TransferRequest request, String digest, boolean upload, Duration timeout,
                 CancelToken cancellation, TransferConflictResolver conflicts, TransferJournal journal,
                 TransferProgressListener listener, TransferPlan recovery, boolean discard) throws ApiException {
        this.session = Objects.requireNonNull(session);
        this.request = Objects.requireNonNull(request);
        this.digest = Objects.requireNonNull(digest);
        this.upload = upload;
        this.cancellation = Objects.requireNonNull(cancellation);
        this.conflicts = recovery == null ? Objects.requireNonNull(conflicts) : conflicts;
        this.journal = Objects.requireNonNull(journal);
        this.listener = Objects.requireNonNull(listener);
        this.recovery = recovery;
        this.discard = discard;
        transferId = recovery == null ? UUID.randomUUID() : recovery.id();
        progress = new SftpProgress(transferId);
        mutation = recovery != null;
        try {
            idleNanos = timeout.toNanos();
            String path = request.remoteAbsolutePath();
            if (idleNanos <= 0 || !digest.matches("[0-9a-f]{64}") || !request.localPath().isAbsolute()
                || !request.localPath().normalize().equals(request.localPath()) || path.length() > 4096
                || !path.startsWith("/") || path.equals("/") || path.indexOf('\\') >= 0
                || path.codePoints().anyMatch(Character::isISOControl)
                || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(path))
                throw new IllegalArgumentException();
            for (String part : path.substring(1).split("/", -1))
                if (part.isEmpty() || part.equals(".") || part.equals(".."))
                    throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "SFTP_INPUT",
                "SFTP request paths, digest or no-progress interval are invalid", Map.of(), Optional.empty()));
        }
    }

    /**
     * Observes one transfer without abandoning blocked provider work. / 观察一次传输，不丢弃阻塞的提供方工作。
     * @return verified completion or explicit uncertainty / 经核验的完成或明确不确定性
     */
    public OperationResult<TransferResult> run() {
        long reported = 0;
        TransferProgress previous = null;
        if (cancellation.requested() || Thread.currentThread().isInterrupted())
            return stopped("SFTP_CANCELLED", ProblemCode.CANCELLED);
        var providerTimeout = Duration.ofNanos(idleNanos).plusSeconds(5);
        for (var property : List.of(org.apache.sshd.core.CoreModuleProperties.IDLE_TIMEOUT,
            org.apache.sshd.sftp.client.impl.AbstractSftpClient.SFTP_CLIENT_CMD_TIMEOUT,
            org.apache.sshd.sftp.SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT)) {
            originalTimeouts.put(property.getName(), session.getProperties().get(property.getName()));
            property.set(session, providerTimeout);
        }
        pending = new FutureTask<>(this::perform);
        worker = Thread.ofVirtual().name("platform-sftp-transfer").start(pending);
        while (true) {
            try {
                var current = progress.snapshot();
                long now = System.nanoTime();
                if (!current.equals(previous) && (pending.isDone() || previous == null
                    || current.stage() != previous.stage() || now - reported >= 200_000_000)) {
                    listener.progress(current);
                    previous = current;
                    reported = now;
                }
                if (pending.isDone())
                    return pending.get();
                if (cancellation.requested() || Thread.currentThread().isInterrupted())
                    return stopped("SFTP_CANCELLED", ProblemCode.CANCELLED);
                long remaining = progress.remaining(idleNanos);
                if (remaining <= 0)
                    return stopped("SFTP_STALLED", ProblemCode.TIMEOUT);
                pending.get(Math.min(remaining, 50_000_000), TimeUnit.NANOSECONDS);
            } catch (TimeoutException retry) {
                // Heartbeats do not enter the file-byte clock. / 心跳不进入文件字节计时。
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return stopped("SFTP_CANCELLED", ProblemCode.CANCELLED);
            } catch (ExecutionException failure) {
                return stopped("SFTP_WORKER_FAILED", ProblemCode.IO_FAILURE);
            } catch (Exception publication) {
                return stopped("SFTP_PROGRESS_FAILED", ProblemCode.IO_FAILURE);
            }
        }
    }

    /**
     * Verifies both source and destination content through bounded streaming. / 通过有界流式处理核验源与目标内容。
     * @return result without provider messages or paths / 不含提供方消息或路径的结果
     */
    private OperationResult<TransferResult> perform() {
        try {
            check();
            if (recovery != null)
                return recover();
            Path path = request.localPath();
            verifyLocalParents(path);
            BasicFileAttributes before = upload ? regularLocal(path) : null;
            String sourceIdentity = upload
                ? gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity.read(path)
                : null;
            if (upload) {
                bounded(before.size());
                local = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                if (local.size() != before.size() || !digest.equals(hashLocal(before.size())))
                    throw new IOException("Source changed");
                verifyUnchanged(before, regularLocal(path));
                if (!sourceIdentity
                    .equals(gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity.read(path)))
                    throw new IOException("Local source was replaced");
            }
            check();
            phase = "sftp-open";
            opening = true;
            sftp = SftpClientFactory.instance().createSftpClient(session);
            phase = "remote-validation";
            verifyRemoteParents();
            var destination = new SftpDestination(sftp, request, upload, transferId, this::stopRequested, progress);
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
                copyToRemote(remote, size, 0);
                verifyUnchanged(before, regularLocal(path));
                if (!sourceIdentity
                    .equals(gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity.read(path)))
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
                copyToLocal(remote, size, 0);
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
            return completion.publishVerified(destination, size);
        } catch (Exception failure) {
            if (failure instanceof ApiException skipped && skipped.problem().eventCode().equals("SFTP_CONFLICT_SKIPPED"))
                return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(skipped.problem()));
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

    /** Reconciles one retained file using the original source and verified current staging extent. / 利用原源及经核验的当前暂存区间核对一个保留文件。
     * @return verified publication or staging cleanup / 经核验发布或暂存清理
     * @throws Exception if any original fact or physical effect cannot be confirmed / 无法确认任一原事实或物理副作用时 */
    private OperationResult<TransferResult> recover() throws Exception {
        phase = "recovery-checkpoint";
        fileLease = Objects.requireNonNull(journal.prepare(recovery));
        verifyLocalParents(request.localPath());
        opening = true;
        sftp = SftpClientFactory.instance().createSftpClient(session);
        verifyRemoteParents();
        var destination = new SftpDestination(sftp, recovery, this::stopRequested, progress);
        phase = "recovery-staging";
        long extent = stagingSize(destination);
        if (extent < 0 && (discard || destination.isPublished(recovery.source()))) {
            resolved = true;
            return recoveredResult(destination);
        }
        if (!discard)
            destination.verifyOriginal();
        long size = recovery.source().size();
        if (extent > size)
            throw new IOException("Staging exceeds the original source");
        phase = "recovery-source";
        verifyRecoverySource();
        String verifiedPrefix;
        if (upload) {
            local = FileChannel.open(request.localPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            if (!digest.equals(hashLocal(size)))
                throw new IOException("Original source content changed");
            verifyRecoverySource();
            boolean create = extent < 0;
            if (create) {
                check();
                extent = 0;
            }
            try (var staged = create
                ? sftp.open(destination.remoteTemporary(), SftpClient.OpenMode.Read, SftpClient.OpenMode.Write,
                SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive)
                : sftp.open(destination.remoteTemporary(),
                discard
                    ? EnumSet.of(SftpClient.OpenMode.Read)
                    : EnumSet.of(SftpClient.OpenMode.Read, SftpClient.OpenMode.Write))) {
                phase = "recovery-prefix";
                regularRemote(sftp.stat(staged), extent);
                verifiedPrefix = hashLocal(extent, false);
                if (!verifiedPrefix.equals(hashRemote(staged, extent, true, TransferProgress.Stage.VERIFYING_PREFIX)))
                    throw new IOException("Staging prefix differs from original source");
                verifyRecoverySource();
                check();
                if (stagingSize(destination) != extent)
                    throw new IOException("Named staging changed before recovery");
                if (!discard) {
                    phase = "recovery-upload";
                    copyToRemote(staged, size, extent);
                    verifyRecoverySource();
                    if (!digest.equals(hashLocal(size)) || !digest.equals(hashRemote(staged, size)))
                        throw new IOException("Resumed content changed");
                }
            }
            local.close();
        } else {
            try (var source = sftp.open(request.remoteAbsolutePath(), SftpClient.OpenMode.Read)) {
                regularRemote(sftp.stat(source), size);
                if (!digest.equals(hashRemote(source, size)))
                    throw new IOException("Original remote source changed");
                verifyRecoverySource();
                String stagingIdentity = extent < 0
                    ? null
                    : gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity
                    .read(destination.localTemporary());
                if (extent < 0) {
                    check();
                    local = FileChannel.open(destination.localTemporary(), StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    extent = 0;
                    stagingIdentity = gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity
                        .read(destination.localTemporary());
                } else {
                    local = FileChannel.open(destination.localTemporary(), discard
                        ? Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                        : Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
                }
                phase = "recovery-prefix";
                verifiedPrefix = hashRemote(source, extent, false);
                if (!hashLocal(extent, true, TransferProgress.Stage.VERIFYING_PREFIX).equals(verifiedPrefix))
                    throw new IOException("Staging prefix differs from original remote source");
                verifyRecoverySource();
                if (!stagingIdentity.equals(gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity
                    .read(destination.localTemporary())))
                    throw new IOException("Local staging was replaced");
                check();
                if (!discard) {
                    phase = "recovery-download";
                    copyToLocal(source, size, extent);
                    local.force(true);
                    verifyRecoverySource();
                    if (!digest.equals(hashLocal(size)) || !digest.equals(hashRemote(source, size)))
                        throw new IOException("Resumed content changed");
                }
                local.close();
                if (!stagingIdentity.equals(gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity
                    .read(destination.localTemporary())))
                    throw new IOException("Local staging was replaced");
            }
        }
        return completion.finishRecovery(destination, extent, verifiedPrefix, size);
    }

    /** Observes an ordinary staging file without trusting a saved offset. / 观察普通暂存文件，不信任已保存偏移。
     * @param destination original selected target / 原选定目标
     * @return current size or minus one for confirmed absence / 当前大小，确认缺失时为负一
     * @throws IOException if the staging object is redirected or nonregular / 暂存对象被重定向或非普通文件时 */
    private long stagingSize(SftpDestination destination) throws IOException {
        check();
        if (upload) {
            try {
                var attrs = sftp.lstat(destination.remoteTemporary());
                regularRemote(attrs, attrs.getSize());
                if (!destination.remoteTemporary().equals(sftp.canonicalPath(destination.remoteTemporary())))
                    throw new IOException("Remote staging is redirected");
                return attrs.getSize();
            } catch (SftpException absent) {
                if (absent.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE)
                    return -1;
                throw absent;
            }
        }
        try {
            return regularLocal(destination.localTemporary()).size();
        } catch (NoSuchFileException absent) {
            return -1;
        }
    }

    /** Rechecks source identity and metadata against the durable original observation. / 根据持久原观测复核源身份及元数据。
     * @throws IOException if the original source has changed / 原源已变化时 */
    private void verifyRecoverySource() throws IOException {
        check();
        var expected = recovery.source();
        if (upload) {
            var attrs = regularLocal(request.localPath());
            if (attrs.size() != expected.size() || !attrs.lastModifiedTime().toString().equals(expected.modified())
                || !gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity.read(request.localPath())
                .equals(expected.identity()))
                throw new IOException("Original local source identity or metadata changed");
        } else {
            var attrs = sftp.lstat(request.remoteAbsolutePath());
            regularRemote(attrs, expected.size());
            if (!attrs.getFlags().contains(SftpClient.Attribute.ModifyTime)
                || !attrs.getModifyTime().toString().equals(expected.modified())
                || !request.remoteAbsolutePath().equals(sftp.canonicalPath(request.remoteAbsolutePath())))
                throw new IOException("Original remote source metadata changed");
        }
    }

    /** Returns only a verified file result or a staging-only cleanup conclusion. / 仅返回已核验文件结果或仅暂存清理结论。
     * @param destination original actual endpoints / 原实际端点
     * @return known recovery result / 已知恢复结果 */
    private OperationResult<TransferResult> recoveredResult(SftpDestination destination) {
        return new OperationResult<>(Outcome.SUCCEEDED,
            discard
                ? Optional.empty()
                : Optional.of(new TransferResult(recovery.source().size(), digest, destination.local(),
                destination.remote())),
            Optional.empty());
    }

    /** Excludes explicit human decision time from transport waiting. / 从传输等待中排除明确人工决定时间。
     * @param conflict actual same-name conflict / 实际同名冲突
     * @return explicit user choice / 明确用户选择
     * @throws Exception if the owned dialog fails / 所属对话框失败时 */
    private TransferConflictChoice choose(TransferConflict conflict) throws Exception {
        progress.human(true);
        try {
            return conflicts.resolve(conflict);
        } finally {
            progress.human(false);
        }
    }

    /**
     * Streams unchanged local bytes to the newly created remote handle. / 向新建远端句柄流式传送未改变的本地字节。
     * @param remote owned remote handle / 所属远端句柄
     * @param size exact reviewed size / 精确已审核大小
     * @param offset verified original staging length / 已核验原暂存长度
     * @throws IOException if the source changes or transport fails / 源变化或传输失败时
     */
    private void copyToRemote(SftpClient.Handle remote, long size, long offset) throws IOException {
        progress.begin(TransferProgress.Stage.TRANSFERRING, size, offset);
        byte[] buffer = new byte[16384];
        while (offset < size) {
            check();
            int count = local.read(ByteBuffer.wrap(buffer, 0, (int) Math.min(buffer.length, size - offset)), offset);
            if (count <= 0)
                throw new IOException("Source ended early");
            sftp.write(remote, offset, buffer, 0, count);
            offset += count;
            progress.advance(count);
        }
    }

    /**
     * Streams the fixed remote extent to a new local file and verifies its digest. / 将固定远端区间流式写入新本地文件并核对摘要。
     * @param remote owned remote handle / 所属远端句柄
     * @param size exact reviewed size / 精确已审核大小
     * @param offset verified original staging length / 已核验原暂存长度
     * @throws IOException if content changes or transport fails / 内容变化或传输失败时
     */
    private void copyToLocal(SftpClient.Handle remote, long size, long offset) throws IOException {
        progress.begin(TransferProgress.Stage.TRANSFERRING, size, offset);
        byte[] buffer = new byte[32768];
        local.position(offset);
        while (offset < size) {
            check();
            int count = sftp.read(remote, offset, buffer, 0, (int) Math.min(buffer.length, size - offset));
            if (count <= 0)
                throw new IOException("Source ended early");
            ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
            while (bytes.hasRemaining()) {
                check();
                local.write(bytes);
            }
            offset += count;
            progress.advance(count);
        }
        if (sftp.read(remote, size, buffer, 0, 1) >= 0)
            throw new IOException("Source changed");
    }

    /**
     * Hashes a bounded local extent and rejects length changes. / 对有界本地区间求摘要并拒绝长度变化。
     * @param size expected file length / 预期文件长度
     * @return lowercase SHA-256 / 小写 SHA-256
     * @throws IOException if the source changes or cannot be read / 源变化或无法读取时
     */
    private String hashLocal(long size) throws IOException {
        return hashLocal(size, true,
            upload ? TransferProgress.Stage.VERIFYING_SOURCE : TransferProgress.Stage.VERIFYING_STAGING);
    }

    /** Hashes a verified prefix or exact local extent. / 对已核验前缀或精确本地区间求摘要。
     * @param size bytes to read / 要读取的字节数
     * @param exact whether EOF must match / 是否须匹配文件末尾
     * @return content digest / 内容摘要
     * @throws IOException if the extent cannot be read / 无法读取区间时 */
    private String hashLocal(long size, boolean exact) throws IOException {
        return hashLocal(size, exact, TransferProgress.Stage.VERIFYING_PREFIX);
    }

    /** Hashes local bytes while reporting the distinct verification stage. / 对本地字节求摘要并报告独立核验阶段。
     * @param size bytes to verify / 要核验的字节数
     * @param exact whether EOF must match / 是否须匹配文件末尾
     * @param stage actual verification purpose / 实际核验用途
     * @return verified digest / 已核验摘要
     * @throws IOException if reading or length verification fails / 读取或长度核验失败时 */
    private String hashLocal(long size, boolean exact, TransferProgress.Stage stage) throws IOException {
        progress.begin(stage, size, 0);
        MessageDigest hash = sha256();
        ByteBuffer bytes = ByteBuffer.allocate(32768);
        for (long offset = 0; offset < size; ) {
            check();
            bytes.clear().limit((int) Math.min(bytes.capacity(), size - offset));
            int count = local.read(bytes, offset);
            if (count <= 0)
                throw new IOException("Source ended early");
            hash.update(bytes.array(), 0, count);
            offset += count;
            progress.advance(count);
        }
        if (exact && local.size() != size)
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
        return hashRemote(remote, size, true,
            upload ? TransferProgress.Stage.VERIFYING_STAGING : TransferProgress.Stage.VERIFYING_SOURCE);
    }

    /** Hashes a verified prefix or exact remote extent. / 对已核验前缀或精确远端区间求摘要。
     * @param remote original file handle / 原文件句柄
     * @param size bytes to read / 要读取的字节数
     * @param exact whether EOF must match / 是否须匹配文件末尾
     * @return content digest / 内容摘要
     * @throws IOException if the extent cannot be read / 无法读取区间时 */
    private String hashRemote(SftpClient.Handle remote, long size, boolean exact) throws IOException {
        return hashRemote(remote, size, exact, TransferProgress.Stage.VERIFYING_PREFIX);
    }

    /** Hashes remote bytes while reporting the distinct verification stage. / 对远端字节求摘要并报告独立核验阶段。
     * @param remote owned file handle / 所属文件句柄
     * @param size bytes to verify / 要核验的字节数
     * @param exact whether EOF must match / 是否须匹配文件末尾
     * @param stage actual verification purpose / 实际核验用途
     * @return verified digest / 已核验摘要
     * @throws IOException if reading or length verification fails / 读取或长度核验失败时 */
    private String hashRemote(SftpClient.Handle remote, long size, boolean exact, TransferProgress.Stage stage)
        throws IOException {
        progress.begin(stage, size, 0);
        MessageDigest hash = sha256();
        byte[] bytes = new byte[32768];
        for (long offset = 0; offset < size; ) {
            check();
            int count = sftp.read(remote, offset, bytes, 0, (int) Math.min(bytes.length, size - offset));
            if (count <= 0)
                throw new IOException("Remote file ended early");
            hash.update(bytes, 0, count);
            offset += count;
            progress.advance(count);
        }
        if (exact && sftp.read(remote, size, bytes, 0, 1) >= 0)
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
    public synchronized OperationResult<TransferResult> problem(String event, ProblemCode code) {
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

    /** Keeps verified completion when the worker finishes during physical shutdown. / 工作线程在物理停止期间完成时保留已核验完成结果。
     * @param observed monitor result before shutdown / 停止前监控结果
     * @return verified worker result for a late completion, otherwise the observation / 延迟完成的已核验工作结果，否则原观测 */
    public OperationResult<TransferResult> afterClose(OperationResult<TransferResult> observed) {
        if (resolved && pending != null && pending.state() == Future.State.SUCCESS
            && observed.problem().map(problem -> problem.eventCode().equals("SFTP_STALLED")).orElse(false))
            return pending.resultNow();
        return observed;
    }

    /** Exposes the same stop fence to destination verification and local publication. / 向目标核验及本地发布暴露相同停止栅栏。
     * @return whether this physical attempt must stop / 此物理尝试是否必须停止 */
    private synchronized boolean stopRequested() {
        return stopped || cancellation.requested();
    }

    /**
     * Releases actual channels and waits for the worker before closing its local file. / 释放实际通道，并等待工作线程后关闭其本地文件。
     * @return whether every local resource is actually closed / 每个本地资源是否实际关闭
     */
    public boolean close() {
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
            if (fileLease != null && (!mutation || resolved)) {
                fileLease.close();
                fileLease = null;
            }
            originalTimeouts.forEach((key, value) -> {
                if (value == null)
                    session.getProperties().remove(key);
                else
                    session.getProperties().put(key, value);
            });
            originalTimeouts.clear();
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

    /** Completes verified destination publication without changing original worker or cancellation ownership. / 完成已核实目标发布，不改变原工作线程或取消归属。 */
    private final class SftpCompletion {
        /** Creates this ledger/runtime collaborator. / 创建此账本或运行时职责协作者。 */
        private SftpCompletion() {
        }

        /** Publishes only after the original handles closed, then hashes the actual named result. / 仅原句柄关闭后发布，随后散列实际命名结果。
         * @param destination exact original destination / 精确原目标
         * @param size verified original byte count / 已核实原字节数
         * @return verified original transfer receipt / 已核实原传输凭据
         * @throws Exception if publication or its observed bytes are unconfirmed / 发布或其观测字节未确认时 */
        private OperationResult<TransferResult> publishVerified(SftpDestination destination, long size) throws Exception {
            phase = "publish";
            destination.publish(size, digest);
            regularRemote(sftp.lstat(destination.remote()), size);
            regularLocal(destination.local());
            phase = "published-verification";
            if (upload) {
                try (var published = sftp.open(destination.remote(), SftpClient.OpenMode.Read)) {
                    if (!digest.equals(hashRemote(published, size, true, TransferProgress.Stage.VERIFYING_RESULT)))
                        throw new IOException("Published remote content changed");
                }
            } else {
                local = FileChannel.open(destination.local(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                if (!digest.equals(hashLocal(size, true, TransferProgress.Stage.VERIFYING_RESULT)))
                    throw new IOException("Published local content changed");
                local.close();
            }
            resolved = true;
            return new OperationResult<>(Outcome.SUCCEEDED,
                Optional.of(new TransferResult(size, digest, destination.local(), destination.remote())),
                Optional.empty());
        }

        /** Concludes the original retained staging through approved discard or verified publication. / 通过已批准丢弃或已核实发布结束原保留暂存。
         * @param destination exact retained destination / 精确保留目标
         * @param extent observed staging byte extent / 已观察暂存字节区间
         * @param verifiedPrefix measured original prefix digest / 实测原前缀摘要
         * @param size original source byte count / 原来源字节数
         * @return verified original publication or staging cleanup / 已核实原发布或暂存清理
         * @throws Exception if original boundaries, cleanup or publication are unconfirmed / 原边界、清理或发布未确认时 */
        private OperationResult<TransferResult> finishRecovery(SftpDestination destination, long extent, String verifiedPrefix, long size)
            throws Exception {
            verifyLocalParents(request.localPath());
            verifyRemoteParents();
            check();
            if (discard) {
                phase = "recovery-discard";
                destination.verifyStaged(extent, verifiedPrefix);
                progress.begin(TransferProgress.Stage.CLEANING, -1, 0);
                if (upload)
                    sftp.remove(destination.remoteTemporary());
                else
                    Files.delete(destination.localTemporary());
                if (stagingSize(destination) >= 0)
                    throw new IOException("Staging cleanup was not observed");
            } else {
                phase = "recovery-publish";
                destination.publish(size, digest);
                phase = "recovery-published-verification";
                if (!destination.isPublished(recovery.source()))
                    throw new IOException("Resumed publication content differs");
            }
            resolved = true;
            return recoveredResult(destination);
        }
    }
}
