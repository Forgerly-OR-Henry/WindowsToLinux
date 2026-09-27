package gold.debug.windowstolinux.app.service.task;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.mod.process.ProcessChannel;
import gold.debug.windowstolinux.app.service.log.PlatformLogService;
import gold.debug.windowstolinux.appapi.task.*;

/** Task-bound real auxiliary processes with durable creation facts and proven physical release. / 具有持久创建事实及已证明物理释放的任务绑定真实辅助进程。 */
public final class PlatformProcessService implements ProcessService, AutoCloseable {
    /** Host-owned process resource namespace. / 宿主持有进程资源命名空间。 */
    private static final String PREFIX = "process-v1:";

    /** Actual engine and task authority. / 实际引擎及任务授权方。 */
    private final PlatformDatabase database;

    /** Central admission and resource accounting. / 中央准入及资源记账。 */
    private final PlatformTaskRuntime tasks;

    /** Activation-bound diagnostic sink. / 启用绑定诊断目标。 */
    private final PlatformLogService.Binding logs;

    /** Exact actual release ownership. / 精确实际发布归属。 */
    private final String owner, version;

    /** Actual children and failed releases retained through cleanup. / 保留至清理的实际子进程及失败释放。 */
    private final Set<Owned> children = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Original category handle, registered before any process is created. / 在创建任何进程前登记的原类别句柄。 */
    private LogTarget target;

    /** Activation shutdown and in-flight creation count. / 启用关闭及在途创建计数。 */
    private boolean closed;

    /** Creation operations that have not returned. / 尚未返回的创建操作。 */
    private int creating;

    /** Binds host authority without starting processes or registering categories on the EDT. / 绑定宿主授权，不在 EDT 启动进程或登记类别。
     * @param database actual engine / 实际引擎
     * @param tasks central runtime / 中央运行时
     * @param logs original caller logger / 原调用方日志器
     * @param owner loaded Mod identity / 加载 Mod 身份
     * @param version actual release / 实际发布 */
    public PlatformProcessService(PlatformDatabase database, PlatformTaskRuntime tasks, PlatformLogService.Binding logs,
            String owner, String version) {
        this.database = Objects.requireNonNull(database);
        this.tasks = Objects.requireNonNull(tasks);
        this.logs = Objects.requireNonNull(logs);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
    }

    /** Registers a process claim before creation, checkpoints PID/start time, then handshakes. / 创建前登记进程申请，记录 PID/启动时间后再握手。
     * @param spec literal executable and explicit environment / 字面可执行文件及明确环境
     * @param task original admitted task / 原准入任务
     * @return original owned process handle / 原所属进程句柄
     * @throws ApiException if admission, creation, handshake or cleanup is unconfirmed / 准入、创建、握手或清理未确认时 */
    @Override
    public ManagedProcess start(ProcessSpec spec, TaskContext task) throws ApiException {
        worker();
        tasks.requireExecuting(task, owner, version);
        Objects.requireNonNull(spec);
        if (!Files.isRegularFile(spec.executable()) || !Files.isDirectory(spec.workingDirectory()))
            throw error(ProblemCode.INVALID_INPUT, "PROCESS_PATH",
                    "Process executable or working directory is unavailable");
        synchronized (this) {
            if (closed)
                throw error(ProblemCode.UNAVAILABLE, "PROCESSES_CLOSED", "Process capability is closing");
            creating++;
        }
        Owned owned = null;
        try {
            synchronized (this) {
                if (target == null)
                    target = logs.register(new LogCategory("runtime", "Runtime", Optional.empty()));
            }
            var id = UUID.randomUUID();
            var resource = new TaskResource("PROCESS", PREFIX + id, owner, false);
            var before = new ProcessRecovery(database.bootId(), id, OptionalLong.empty(), Optional.empty());
            var lease = tasks.retain(task, resource, before.encode());
            owned = new Owned(task, id, lease);
            synchronized (this) {
                children.add(owned);
            }
            var retained = owned;
            tasks.authorized(task, owner, version, () -> {
                var command = new ArrayList<String>();
                command.add(spec.executable().toString());
                command.addAll(spec.arguments());
                var builder = new ProcessBuilder(command).directory(spec.workingDirectory().toFile());
                builder.environment().clear();
                builder.environment().putAll(spec.environment());
                retained.child = builder.start();
                retained.channel = new ProcessChannel(retained.child, id, task.id(), retained);
                var started = retained.child.info().startInstant()
                        .orElseThrow(() -> new java.io.IOException("OS start identity unavailable"));
                try {
                    new TaskLedger(database).checkpointProcess(task.id(), resource, before, new ProcessRecovery(
                            database.bootId(), id, OptionalLong.of(retained.child.pid()), Optional.of(started)));
                } catch (Exception uncertain) {
                    tasks.reportUncertainEffect(task);
                    throw uncertain;
                }
                retained.channel.handshake(owner, version, spec.handshakeTimeout());
                tasks.requireExecuting(task, owner, version);
                return null;
            });
            return owned;
        } catch (Exception failure) {
            if (owned != null) {
                owned.building = false;
                try {
                    owned.close();
                } catch (ApiException cleanup) {
                    tasks.reportUncertainEffect(task);
                    throw cleanup;
                }
            }
            if (failure instanceof ApiException api)
                throw api;
            throw error(ProblemCode.IO_FAILURE, "PROCESS_START", "Auxiliary process startup did not complete");
        } finally {
            if (owned != null)
                owned.building = false;
            synchronized (this) {
                creating--;
            }
        }
    }

    /** Releases only original-task children or previous-boot identities proved to be gone. / 仅释放原任务子进程或已证明消失的前次启动身份。
     * @param task authentic original recovery context / 真实原恢复上下文
     * @throws ApiException if an original process, unknown identity or physical resource remains / 原进程、未知身份或物理资源仍存在时 */
    public void closeTaskResources(TaskContext task) throws ApiException {
        worker();
        tasks.requireCleanup(task, owner, version);
        List<Owned> originals;
        synchronized (this) {
            originals = List.copyOf(children);
        }
        for (var original : originals)
            if (original.task == task)
                original.close();
        for (var entry : tasks.recoveries(task, owner, version, "PROCESS", PREFIX).entrySet()) {
            ProcessRecovery checkpoint;
            try {
                checkpoint = ProcessRecovery.decode(entry.getValue());
                if (!entry.getKey().id().equals(PREFIX + checkpoint.process()) || entry.getKey().exclusive())
                    throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                throw error(ProblemCode.UNKNOWN, "PROCESS_RECOVERY_IDENTITY",
                        "Original process identity cannot be verified");
            }
            if (checkpoint.boot().equals(database.bootId()))
                throw error(ProblemCode.BUSY, "PROCESS_CURRENT_OWNER",
                        "Current-boot process cleanup belongs to its original capability");
            if (checkpoint.pid().isEmpty())
                throw error(ProblemCode.UNKNOWN, "PROCESS_CREATION_UNCONFIRMED",
                        "Interrupted process creation has no confirmed OS identity");
            var current = ProcessHandle.of(checkpoint.pid().getAsLong());
            if (current.isPresent() && current.get().isAlive()) {
                var observed = current.get().info().startInstant();
                if (observed.isEmpty())
                    throw error(ProblemCode.UNKNOWN, "PROCESS_START_UNAVAILABLE",
                            "Existing PID start time cannot be verified");
                if (observed.equals(checkpoint.started()))
                    throw error(ProblemCode.BUSY, "PROCESS_PREVIOUS_ALIVE",
                            "The original previous-boot child is still alive");
            }
            tasks.retain(task, entry.getKey(), entry.getValue()).close();
        }
    }

    /** Seals new process creation and cooperatively closes actual retained children. / 封闭新进程创建并协作关闭实际保留子进程。
     * @throws ApiException if creation or physical cleanup is still active / 创建或物理清理仍活动时 */
    @Override
    public void close() throws ApiException {
        worker();
        List<Owned> originals;
        synchronized (this) {
            closed = true;
            if (creating != 0)
                throw error(ProblemCode.BUSY, "PROCESS_CREATION_ACTIVE", "Process creation is still active");
            originals = List.copyOf(children);
        }
        for (var original : originals)
            original.close();
        logs.close();
    }

    /** Exact original task capability retained through physical and durable release. / 保留至物理及持久释放的精确原任务能力。 */
    private final class Owned implements ManagedProcess, ProcessChannel.Observer {
        /** Authentic owning context. / 真实所属上下文。 */
        private final TaskContext task;

        /** Original persisted UUID. / 原持久 UUID。 */
        private final UUID id;

        /** Actual resource claim. / 实际资源申请。 */
        private final ResourceLease lease;

        /** Original physical process object. / 原物理进程对象。 */
        private volatile Process child;

        /** Real protocol transport, absent only before construction. / 真实协议传输，仅构造前为空。 */
        private volatile ProcessChannel channel;

        /** Creation and durable release state. / 创建及持久释放状态。 */
        private volatile boolean building = true;

        /** Whether all actual resources and the claim were released. / 所有实际资源及申请是否已释放。 */
        private boolean released;
        /** Captures the durable pre-creation claim. / 捕获持久创建前申请。
         * @param task original task / 原任务
         * @param id original process ID / 原进程 ID
         * @param lease pre-creation claim / 创建前申请 */
        private Owned(TaskContext task, UUID id, ResourceLease lease) {
            this.task = task;
            this.id = id;
            this.lease = lease;
        }

        /** Returns original process identity. / 返回原进程身份。
         * @return durable UUID / 持久 UUID */
        @Override
        public UUID id() {
            return id;
        }

        /** Submits only within the original executing or explicit recovery context. / 仅在原执行或明确恢复上下文提交。
         * @param message explicit request / 明确请求
         * @param timeout observation deadline / 观察期限
         * @return original observation / 原观察
         * @throws ApiException if the caller cannot continue business / 调用方不能继续业务时 */
        @Override
        public CompletionStage<OperationResult<ProcessMessage>> request(ProcessMessage message, Duration timeout)
                throws ApiException {
            worker();
            tasks.requireExecuting(task, owner, version);
            synchronized (PlatformProcessService.this) {
                if (closed)
                    throw error(ProblemCode.UNAVAILABLE, "PROCESSES_CLOSED", "Process capability is closing");
            }
            return channel.request(message, timeout);
        }

        /** Requests only cooperative cancellation of already admitted work. / 仅请求已准入工作的协作取消。
         * @throws ApiException if bounded delivery cannot be confirmed / 无法确认有界投递时 */
        @Override
        public void requestCancel() throws ApiException {
            worker();
            channel.requestCancel();
        }

        /** Observes actual OS exit independently of the durable lease. / 独立于持久占用观察实际系统退出。
         * @return read-only actual exit / 只读实际退出 */
        @Override
        public CompletionStage<Integer> exitCode() {
            return channel.exitCode();
        }

        /** Releases the claim only after original process and all pipe workers are gone. / 原进程及所有管道工作线程消失后才释放申请。
         * @throws ApiException if actual or persistent cleanup remains / 实际或持久清理仍存在时 */
        @Override
        public synchronized void close() throws ApiException {
            worker();
            if (released)
                return;
            if (building)
                throw error(ProblemCode.BUSY, "PROCESS_CREATION_ACTIVE", "Original process creation is still active");
            if (channel != null)
                channel.close();
            else if (child != null)
                throw error(ProblemCode.UNKNOWN, "PROCESS_TRANSPORT_UNAVAILABLE",
                        "Created process transport was not established");
            try {
                lease.close();
            } catch (ApiException uncertain) {
                tasks.reportUncertainEffect(task);
                throw uncertain;
            }
            released = true;
            synchronized (PlatformProcessService.this) {
                children.remove(this);
            }
        }

        /** Routes bounded stderr through the original redacting log capability. / 将有界 stderr 送入原脱敏日志能力。
         * @param excerpt raw bounded text / 原始有界文字
         * @param truncated whether bytes were omitted / 是否省略字节 */
        @Override
        public void diagnostic(String excerpt, boolean truncated) {
            logs.writeExcerpt(
                    target, new LogEvent(LogLevel.WARN, "PROCESS_STDERR", "Auxiliary process stderr",
                            Optional.of(excerpt), Optional.of(task.id()), Optional.empty(), Optional.empty()),
                    truncated);
        }

        /** Preserves uncertainty even when author code returns success. / 即使作者代码返回成功，也保留不确定性。
         * @param problem safe protocol evidence / 安全协议证据
         * @param uncertain whether a business request remains unknown / 业务请求是否仍未知 */
        @Override
        public void failure(Problem problem, boolean uncertain) {
            if (uncertain)
                try {
                    tasks.reportUncertainEffect(task);
                } catch (ApiException ignored) {
                    // The process lease remains held; report the failed ledger update without throwing from the reader. / 进程占用仍保留；报告账本更新失败，不从读取线程抛出。
                    System.err.println("PROCESS_TASK_UNCERTAINTY_UNCONFIRMED");
                }
            logs.write(target, new LogEvent(LogLevel.WARN, problem.eventCode(), problem.message(), Optional.empty(),
                    Optional.of(task.id()), Optional.empty(), Optional.empty()));
        }

        /** Refreshes actual blockers while leaving the original child untouched. / 刷新实际阻塞并保持原有子进程不受强制处理。
         * @param acknowledged whether STOPPED was seen / 是否看到 STOPPED */
        @Override
        public void waiting(boolean acknowledged) {
            try {
                task.progress(acknowledged
                        ? "Helper acknowledged the stop request; waiting for actual exit"
                        : "Waiting for cooperative helper shutdown and actual exit", Optional.empty());
            } catch (ApiException ignored) {
                /* Durable process occupancy still exposes the blocker. / 持久进程占用仍披露阻塞。 */ }
        }

        /** Observes the original task's current cancellation state. / 观察原任务当前取消状态。
         * @return current cooperative cancellation / 当前协作取消 */
        @Override
        public boolean cancellationRequested() {
            return tasks.operationCancellation(task).requested();
        }
    }

    /** Rejects process I/O on the EDT. / 拒绝在 EDT 执行进程 I/O。
     * @throws ApiException if invoked on the UI thread / 在界面线程调用时 */
    private static void worker() throws ApiException {
        if (SwingUtilities.isEventDispatchThread())
            throw error(ProblemCode.CONTRACT_VIOLATION, "PROCESS_WORKER", "Process operations require a worker thread");
    }

    /** Creates a fixed non-secret capability error. / 创建固定且不含秘密的能力错误。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param message safe text / 安全文字
     * @return public exception / 公开异常 */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }
}
