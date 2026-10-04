package gold.debug.windowstolinux.mod.deployment.file.git;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.work.FileWorkspace;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.mod.model.ModIdentity;
import gold.debug.windowstolinux.appapi.task.process.ManagedProcess;
import gold.debug.windowstolinux.appapi.task.process.ProcessMessage;
import gold.debug.windowstolinux.appapi.task.process.ProcessService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.file.*;

/** Prepares pinned Git material through original platform processes and source ownership. / 经原平台进程及来源归属准备固定 Git 素材。 */
public final class DeploymentGitSetup {
    /** Immutable identities persisted before the first physical effect. / 首个物理效果前持久化的不可变身份。
     * @param job original Git operation / 原 Git 操作
     * @param checkout original checkout workspace / 原检出工作目录
     * @param control original receipt workspace / 原回执工作目录
     * @param process original worker process / 原 worker 进程 */
    public record Planned(UUID job, UUID checkout, UUID control, UUID process) {
        /** Requires all four distinct, non-null identities. / 要求四个非空且不同的身份。
         * @param job original Git operation / 原 Git 操作
         * @param checkout original checkout workspace / 原检出工作目录
         * @param control original receipt workspace / 原回执工作目录
         * @param process original worker process / 原 worker 进程 */
        public Planned {
            if (job == null || checkout == null || control == null || process == null
                || Set.of(job, checkout, control, process).size() != 4)
                throw new IllegalArgumentException("Git attempt identities differ");
        }
    }

    /** Safe result proven after the real worker, native descendants and pipes exited. / 真实 worker、原生后代及管道退出后证明的安全结果。
     * @param outcome actual worker result / 实际 worker 结果
     * @param commit exact successful commit / 精确成功提交
     * @param problemCode bounded failure category / 有界失败类别
     * @param eventCode bounded diagnostic event / 有界诊断事件 */
    public record Receipt(TaskResult outcome, Optional<String> commit, Optional<ProblemCode> problemCode,
                          Optional<String> eventCode) {
        /** Checks complete result shape. / 校验完整结果形态。
         * @param outcome actual worker result / 实际 worker 结果
         * @param commit exact successful commit / 精确成功提交
         * @param problemCode bounded failure category / 有界失败类别
         * @param eventCode bounded diagnostic event / 有界诊断事件 */
        public Receipt {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(commit);
            Objects.requireNonNull(problemCode);
            Objects.requireNonNull(eventCode);
            if (outcome == TaskResult.UNKNOWN || outcome == TaskResult.SUCCEEDED
                ? outcome == TaskResult.UNKNOWN || commit.isEmpty() || problemCode.isPresent() || eventCode.isPresent()
                : commit.isPresent() || problemCode.isEmpty() || eventCode.isEmpty()
                || problemCode.orElseThrow() == ProblemCode.UNKNOWN
                || !eventCode.orElseThrow().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
                throw new IllegalArgumentException("Git receipt result is incomplete");
            if (commit.isPresent() && !commit.orElseThrow().matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException("Git receipt commit is invalid");
        }
    }

    /** Durable original-task facts supplied by the production Git flow. / 生产 Git 流提供的原任务持久依据。 */
    public interface Observer {
        /** Returns identities already durable before any physical creation. / 返回任何物理创建前已持久化的身份。
         * @return original plan / 原计划 */
        Planned planned();

        /** Persists that a request might be delivered before sending it. / 发送前持久化请求可能被投递的事实。
         * @throws ApiException if persistence is unconfirmed / 持久化未确认时 */
        void beforeRequest() throws ApiException;

        /** Persists the verified complete physical result before control deletion. / 删除控制目录前持久化已验证的完整物理结果。
         * @param receipt verified receipt / 已核实回执
         * @throws ApiException if persistence is unconfirmed / 持久化未确认时 */
        void receipt(Receipt receipt) throws ApiException;
    }

    /** Original source capability. / 原来源能力。 */
    private final FileService sources;

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
    public DeploymentGitSetup(ModContext context, Path executable) {
        Objects.requireNonNull(context);
        sources = context.file();
        processes = context.processes();
        identity = context.identity();
        this.executable = Objects.requireNonNull(executable).toAbsolutePath().normalize();
        if (!executable.isAbsolute())
            throw new IllegalArgumentException("Git executable must be absolute");
    }

    /** Executes one previously journaled production attempt without generating replacement identities. / 用预先记账的身份执行一次生产尝试，不生成替代身份。
     * @param request original reviewed request / 原已审阅请求
     * @param maximumSourceBytes bounded source bytes / 有界源码字节
     * @param maximumEntries bounded entry count / 有界条目数
     * @param task original task / 原任务
     * @param observer durable original journal / 原持久日志
     * @return original frozen Git material / 原冻结 Git 素材
     * @throws ApiException if evidence or cleanup is unconfirmed / 依据或清理未确认时 */
    public DeploymentGitMaterial prepare(DeploymentGitRequest request, long maximumSourceBytes, int maximumEntries,
                                         TaskContext task, Observer observer) throws ApiException {
        Objects.requireNonNull(request);
        if (!Files.isRegularFile(executable) || maximumSourceBytes < 0 || maximumSourceBytes > 2L * 1024 * 1024 * 1024
            || maximumEntries < 1 || maximumEntries > 100000)
            throw DeploymentGitCommands.problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_GIT_INPUT");
        var attempt = new Attempt(task, observer);
        synchronized (this) {
            pending.add(attempt);
        }
        DeploymentSourceMaterial material = null;
        DeploymentSourceArchive archive = null;
        boolean published = false;
        try {
            attempt.checkout = sources.workspaces().workspace(task, attempt.plan.checkout());
            attempt.control = sources.workspaces().workspace(task, attempt.plan.control());
            Path checkout = attempt.checkout.directory();
            attempt.controlPath = attempt.control.directory();
            var spec = DeploymentGitLaunch.spec(identity, executable, checkout, attempt.controlPath, attempt.id);
            attempt.process = processes.start(spec, task, attempt.plan.process());
            observer.beforeRequest();
            attempt.requested = true;
            var response = attempt.process
                .request(new ProcessMessage(DeploymentGitMessages.TYPE, DeploymentGitMessages.encode(request)),
                    Duration.ofMinutes(12))
                .toCompletableFuture().get();
            attempt.stopWorker();
            if (response.outcome() != TaskResult.SUCCEEDED)
                throw new ApiException(response.problem().orElseGet(() -> DeploymentGitCommands
                    .problem(ProblemCode.CANCELLED, "DEPLOYMENT_GIT_CANCELLED").problem()));
            String commit = DeploymentGitMessages.receipt(response.value().orElseThrow(), attempt.id).orElseThrow();
            if (!attempt.commit.orElseThrow().equals(commit))
                throw new IOException("Git result differs from original receipt");
            material = new DeploymentSourceSetup(sources).prepare(attempt.checkout.selection(), maximumSourceBytes,
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

    /** Verifies a copied original receipt without reopening a source grant or Git request. / 不重新打开来源授权或 Git 请求，仅核验复制的原回执。
     * @param bytes bounded original control file / 有界原控制文件
     * @param task original task identity / 原任务身份
     * @param process original worker identity / 原 worker 身份
     * @param job original Git operation / 原 Git 操作
     * @return complete physical result / 完整物理结果
     * @throws IOException if any frame, identity or native cleanup fact differs / 帧、身份或原生清理依据不符时 */
    public static Receipt readReceipt(byte[] bytes, UUID task, UUID process, UUID job) throws IOException {
        try (var input = new ByteArrayInputStream(bytes)) {
            var frame = DeploymentGitProtocol.read(input).orElseThrow();
            if (input.read() != -1 || !frame.process().equals(process) || !frame.task().equals(task))
                throw new IOException("Foreign Git receipt");
            var result = DeploymentGitProtocol.result(frame);
            var commit = DeploymentGitMessages.receipt(result.value().orElseThrow(), job);
            if ((result.outcome() == TaskResult.SUCCEEDED) != commit.isPresent())
                throw new IOException("Git result and commit disagree");
            return new Receipt(result.outcome(), commit, result.problem().map(Problem::code),
                result.problem().map(Problem::eventCode));
        } catch (RuntimeException invalid) {
            throw new IOException("Original Git receipt is invalid", invalid);
        }
    }

    /** Retains the complete operation until worker and native cleanup are independently proved. / 保留完整操作，直至分别证明 worker 及原生清理完成。 */
    private final class Attempt {
        /** Original task context. / 原任务上下文。 */
        private final TaskContext task;

        /** Original operation identity used by receipt and custom lease. / 凭据及自定义租约使用的原操作身份。 */
        private final UUID id;

        /** Pre-persisted production identities. / 预持久化的生产身份。 */
        private final Planned plan;

        /** Production durability observer. / 生产持久观察器。 */
        private final Observer observer;

        /** Original writable source files. / 原可写来源文件。 */
        private FileWorkspace checkout;

        /** Original control files. / 原控制文件。 */
        private FileWorkspace control;

        /** Original control coordinates retained for cleanup checks after revocation. / 为撤销后清理核对保留的原控制坐标。 */
        private Path controlPath;

        /** Original managed worker. / 原受管 worker。 */
        private ManagedProcess process;

        /** Whether one request may have been delivered. / 一个请求是否可能已经投递。 */
        private boolean requested;

        /** Whether worker and native exit evidence were confirmed. / 是否已确认 worker 及原生退出证据。 */
        private boolean stopped;

        /** Commit retained in the original physical receipt. / 原物理凭据保留的提交。 */
        private Optional<String> commit = Optional.empty();

        /** Whether all physical and durable cleanup completed. / 全部物理及持久清理是否完成。 */
        private boolean closed;

        /** Binds the original task before allocating resources. / 分配资源前绑定原任务。
         * @param task original task / 原任务
         * @param observer original durable facts / 原持久依据 */
        private Attempt(TaskContext task, Observer observer) {
            this.task = Objects.requireNonNull(task);
            this.observer = Objects.requireNonNull(observer);
            this.plan = Objects.requireNonNull(observer.planned());
            this.id = plan.job();
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
                    if (Files.size(file) > DeploymentGitProtocol.MAX_BYTES)
                        throw new IOException("Original Git receipt exceeds its bound");
                    var receipt = readReceipt(Files.readAllBytes(file), task.id(), process.id(), id);
                    commit = receipt.commit();
                    observer.receipt(receipt);
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
            closed = true;
            synchronized (DeploymentGitSetup.this) {
                pending.remove(this);
            }
        }
    }
}
