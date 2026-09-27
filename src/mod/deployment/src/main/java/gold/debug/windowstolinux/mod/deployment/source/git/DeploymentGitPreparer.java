package gold.debug.windowstolinux.mod.deployment.source.git;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.source.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.source.*;

/** Prepares pinned Git material through original platform processes and source ownership. / 经原平台进程及来源归属准备固定 Git 素材。 */
public final class DeploymentGitPreparer {
    /** Original source capability. / 原来源能力。 */
    private final SourceService sources;

    /** Original process capability. / 原进程能力。 */
    private final ProcessService processes;

    /** Original activating identity. / 原启用身份。 */
    private final ModIdentity identity;

    /** Explicit native Git executable. / 明确原生 Git 可执行文件。 */
    private final Path executable;

    /** Attempts whose physical or durable cleanup has not completed. / 物理或持久清理尚未完成的尝试。 */
    private final Set<Attempt> pending = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Binds an actual Mod context and explicit local Git executable. / 绑定实际 Mod 上下文及明确本机 Git 可执行文件。
     * @param context original public Mod context / 原公开 Mod 上下文
     * @param executable absolute native Git path / 绝对原生 Git 路径 */
    public DeploymentGitPreparer(ModContext context, Path executable) {
        Objects.requireNonNull(context);
        sources = context.source();
        processes = context.processes();
        identity = context.identity();
        this.executable = Objects.requireNonNull(executable).toAbsolutePath().normalize();
        if (!executable.isAbsolute())
            throw new IllegalArgumentException("Git executable must be absolute");
    }

    /** Resolves one ref, drains native processes, then freezes and archives exactly the admitted source. / 解析一个引用、排空原生进程，再冻结并归档精确准入来源。
     * @param request original reviewed Git request / 原已审阅 Git 请求
     * @param maximumSourceBytes maximum selected source bytes / 最大选定源码字节
     * @param maximumEntries maximum discovered entries / 最大发现子项
     * @param task original task / 原任务
     * @return independently retained source and archive / 独立保留的源码及归档
     * @throws ApiException if input, process evidence, source capture or cleanup fails / 输入、进程证据、来源捕获或清理失败时 */
    public DeploymentGitMaterial prepare(DeploymentGitRequest request, long maximumSourceBytes, int maximumEntries,
            TaskContext task) throws ApiException {
        Objects.requireNonNull(request);
        if (!Files.isRegularFile(executable) || maximumSourceBytes < 0 || maximumSourceBytes > 2L * 1024 * 1024 * 1024
                || maximumEntries < 1 || maximumEntries > 100000)
            throw DeploymentGitCommands.problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_GIT_INPUT");
        var attempt = new Attempt(task);
        synchronized (this) {
            pending.add(attempt);
        }
        DeploymentSourceMaterial material = null;
        DeploymentSourceArchive archive = null;
        boolean published = false;
        try {
            attempt.checkout = sources.workspace(task);
            attempt.control = sources.workspace(task);
            Path checkout = attempt.checkout.directory();
            attempt.controlPath = attempt.control.directory();
            var spec = DeploymentGitLaunch.spec(identity, executable, checkout, attempt.controlPath, attempt.id);
            attempt.process = processes.start(spec, task);
            attempt.group = task.retain("git_processes", attempt.id.toString(), "Git native process cleanup");
            attempt.requested = true;
            var response = attempt.process
                    .request(new ProcessMessage(DeploymentGitMessages.TYPE, DeploymentGitMessages.encode(request)),
                            Duration.ofMinutes(12))
                    .toCompletableFuture().get();
            attempt.stopWorker();
            if (response.outcome() != Outcome.SUCCEEDED)
                throw new ApiException(response.problem().orElseGet(() -> DeploymentGitCommands
                        .problem(ProblemCode.CANCELLED, "DEPLOYMENT_GIT_CANCELLED").problem()));
            String commit = DeploymentGitMessages.receipt(response.value().orElseThrow(), attempt.id).orElseThrow();
            if (!attempt.commit.orElseThrow().equals(commit))
                throw new IOException("Git result differs from original receipt");
            material = new DeploymentSourcePreparer(sources).prepare(attempt.checkout.selection(), maximumSourceBytes,
                    maximumEntries, task);
            archive = new DeploymentSourceArchiver(sources).prepare(material, request.maximumArchiveBytes(), task);
            attempt.close();
            var result = new DeploymentGitMaterial(request.remote(), commit, material, archive);
            published = true;
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw DeploymentGitCommands.problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GIT_WAIT_INTERRUPTED");
        } catch (IOException | ExecutionException | IllegalStateException failure) {
            throw DeploymentGitCommands.problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GIT_RESULT_UNCONFIRMED");
        } finally {
            try {
                attempt.close();
            } finally {
                if (!published) {
                    try {
                        if (archive != null)
                            archive.close();
                    } finally {
                        if (material != null)
                            material.close();
                    }
                }
            }
        }
    }

    /** Retries only the original in-memory attempts without sending another Git request. / 仅重试原内存尝试，不再发送 Git 请求。
     * @param task exact original task object / 精确原任务对象
     * @throws ApiException if original native work or cleanup evidence remains unresolved / 原原生工作或清理证据仍未决时 */
    public void closeTaskResources(TaskContext task) throws ApiException {
        List<Attempt> attempts;
        synchronized (this) {
            attempts = List.copyOf(pending);
        }
        for (var attempt : attempts)
            if (attempt.task == task)
                attempt.close();
    }

    /** Retains the complete operation until worker and native cleanup are independently proved. / 保留完整操作，直至分别证明 worker 及原生清理完成。 */
    private final class Attempt {
        /** Original task context. / 原任务上下文。 */
        private final TaskContext task;

        /** Original operation identity used by receipt and custom lease. / 凭据及自定义租约使用的原操作身份。 */
        private final UUID id = UUID.randomUUID();

        /** Original writable source files. / 原可写来源文件。 */
        private SourceWorkspace checkout;

        /** Original control files. / 原控制文件。 */
        private SourceWorkspace control;

        /** Original control coordinates retained for cleanup checks after revocation. / 为撤销后清理核对保留的原控制坐标。 */
        private Path controlPath;

        /** Original managed worker. / 原受管 worker。 */
        private ManagedProcess process;

        /** Independent descendant-process uncertainty occupancy. / 独立后代进程不确定占用。 */
        private ResourceLease group;

        /** Whether one request may have been delivered. / 一个请求是否可能已经投递。 */
        private boolean requested;

        /** Whether worker and native exit evidence were confirmed. / 是否已确认 worker 及原生退出证据。 */
        private boolean stopped;

        /** Commit retained in the original physical receipt. / 原物理凭据保留的提交。 */
        private Optional<String> commit = Optional.empty();

        /** Whether all physical and durable cleanup completed. / 全部物理及持久清理是否完成。 */
        private boolean closed;

        /** Binds the original task before allocating resources. / 分配资源前绑定原任务。
         * @param task original task / 原任务 */
        private Attempt(TaskContext task) {
            this.task = task;
        }

        /** Requires actual worker exit and a matching positive native cleanup receipt. / 要求 worker 实际退出及匹配的肯定原生清理凭据。
         * @throws ApiException if either fact cannot be proved / 任一事实无法证明时 */
        private synchronized void stopWorker() throws ApiException {
            if (stopped)
                return;
            if (process != null)
                process.close();
            if (requested) {
                try {
                    if (process.exitCode().toCompletableFuture().join() != 0)
                        throw new IOException("Git worker did not exit normally");
                    var file = controlPath.resolve("git-result.json");
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Missing original Git receipt");
                    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        var frame = DeploymentGitProtocol.read(input).orElseThrow();
                        if (input.read() != -1 || !frame.process().equals(process.id())
                                || !frame.task().equals(task.id()))
                            throw new IOException("Foreign Git receipt");
                        var result = DeploymentGitProtocol.result(frame);
                        commit = DeploymentGitMessages.receipt(result.value().orElseThrow(), id);
                        if ((result.outcome() == Outcome.SUCCEEDED) != commit.isPresent())
                            throw new IOException("Git result and commit disagree");
                    }
                } catch (IOException | RuntimeException failure) {
                    throw DeploymentGitCommands.problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GIT_NATIVE_EXIT_UNCONFIRMED");
                }
            }
            stopped = true;
        }

        /** Releases actual workspaces before the final original group lease. / 在最后的原进程组租约前释放实际工作目录。
         * @throws ApiException if any original physical or durable resource remains / 任一原物理或持久资源仍存在时 */
        private synchronized void close() throws ApiException {
            if (closed)
                return;
            stopWorker();
            if (checkout != null)
                checkout.close();
            if (control != null)
                control.close();
            if (group != null)
                group.close();
            closed = true;
            synchronized (DeploymentGitPreparer.this) {
                pending.remove(this);
            }
        }
    }
}
