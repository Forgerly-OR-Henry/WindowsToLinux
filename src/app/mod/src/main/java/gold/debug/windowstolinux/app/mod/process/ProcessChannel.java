package gold.debug.windowstolinux.app.mod.process;

import static gold.debug.windowstolinux.app.mod.process.ProcessProtocol.Kind;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.appapi.task.*;

/** Owns one real child's bounded protocol transport; the host owns admission and durable leases. / 持有一个真实子进程的有界协议传输；宿主负责准入和持久占用。 */
public final class ProcessChannel implements ManagedProcess {
    /** Maximum pending requests and queued frames. / 待完成请求及排队帧上限。 */
    private static final int CAPACITY = 32;

    /** Original Java process object, never reconstructed from a PID. / 原 Java 进程对象，从不按 PID 重建。 */
    private final Process child;

    /** Immutable protocol ownership. / 不可变协议归属。 */
    private final UUID id, task, helloId = UUID.randomUUID(), stopId = UUID.randomUUID();

    /** Non-blocking host feedback that must not expose unfiltered stderr. / 不得暴露未过滤 stderr 的非阻塞宿主反馈。 */
    private final Observer observer;

    /** Sole writer's bounded queue. / 唯一写入线程的有界队列。 */
    private final BlockingQueue<byte[]> writes = new ArrayBlockingQueue<>(CAPACITY);

    /** Unsettled original requests, including timed-out requests awaiting evidence. / 未核实原请求，含超时后仍待证据的请求。 */
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    /** Bounded exact-response cache for duplicate verification. / 用于重复核验的有界精确响应缓存。 */
    private final LinkedHashMap<UUID, ProcessProtocol.Frame> completed = new LinkedHashMap<>();

    /** Actual handshake and OS exit observations. / 实际握手及操作系统退出观察。 */
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    /** Completes on OS exit independently of pipe release. / 独立于管道释放，在操作系统退出时完成。 */
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();

    /** Physical stream and monitoring workers. / 实际流及监控工作线程。 */
    private final List<Thread> workers = new ArrayList<>();

    /** Bounded stderr excerpt, drained independently of log storage. / 有界 stderr 摘要，消费独立于日志存储。 */
    private final ByteArrayOutputStream diagnostic = new ByteArrayOutputStream();

    /** Whether the current stderr batch exceeded its retained bytes. / 当前 stderr 批次是否超过保留字节数。 */
    private boolean diagnosticTruncated;

    /** Protocol and actual release state. / 协议与实际释放状态。 */
    private boolean helloSent, stopping, stopSent, stopped, failed, released;

    /** Actual completion of individual stream workers. / 各流工作线程的实际完成状态。 */
    private volatile boolean writerDone, stdoutDone, stderrDone;

    /** Last reported stop-wait timestamp. / 最近停止等待报告时间。 */
    private long stopReported;

    /** Binds an already admitted child; callers must persist its identity before HELLO. / 绑定已准入子进程；调用方须在 HELLO 前持久化身份。
     * @param child actual newly started process / 实际新启动进程
     * @param processId persisted process UUID / 持久进程 UUID
     * @param taskId original task UUID / 原任务 UUID
     * @param observer bounded host feedback / 有界宿主反馈 */
    public ProcessChannel(Process child, UUID processId, UUID taskId, Observer observer) {
        this.child = Objects.requireNonNull(child);
        id = Objects.requireNonNull(processId);
        task = Objects.requireNonNull(taskId);
        this.observer = Objects.requireNonNull(observer);
        workers.add(Thread.ofVirtual().name("wtl-process-stdin-" + id).start(this::writeLoop));
        workers.add(Thread.ofVirtual().name("wtl-process-stdout-" + id).start(this::readLoop));
        workers.add(Thread.ofVirtual().name("wtl-process-stderr-" + id).start(this::diagnosticLoop));
        workers.add(Thread.ofVirtual().name("wtl-process-exit-" + id).start(this::waitLoop));
        workers.add(Thread.ofVirtual().name("wtl-process-monitor-" + id).start(this::monitorLoop));
    }

    /** Performs one identity-bound handshake without any business request. / 执行一次身份绑定握手，不发送业务请求。
     * @param owner actual caller / 实际调用方
     * @param version loaded release / 加载发布版本
     * @param timeout one to sixty seconds / 一至六十秒
     * @throws ApiException if readiness cannot be confirmed / 无法确认就绪时 */
    public void handshake(String owner, String version, Duration timeout) throws ApiException {
        if (owner == null || owner.isBlank() || version == null || version.isBlank() || timeout == null
                || timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw error(ProblemCode.INVALID_INPUT, "PROCESS_HELLO", "Invalid process handshake");
        synchronized (this) {
            if (helloSent || stopping || failed)
                throw error(ProblemCode.UNAVAILABLE, "PROCESS_HELLO", "Process handshake is unavailable");
            try {
                String payload = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(Map.of("ownerId", owner, "ownerVersion", version));
                enqueue(new ProcessProtocol.Frame(id, task, helloId, Kind.HELLO, "wtl.process", payload));
                helloSent = true;
            } catch (IOException invalid) {
                throw error(ProblemCode.INVALID_INPUT, "PROCESS_HELLO", "Invalid handshake payload");
            }
        }
        try {
            ready.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("PROCESS_HANDSHAKE_INTERRUPTED", false);
            throw error(ProblemCode.UNKNOWN, "PROCESS_HANDSHAKE_INTERRUPTED", "Process readiness remains unconfirmed");
        } catch (ExecutionException | TimeoutException unavailable) {
            fail("PROCESS_HANDSHAKE_UNCONFIRMED", false);
            throw error(ProblemCode.UNAVAILABLE, "PROCESS_HANDSHAKE_UNCONFIRMED",
                    "Process readiness remains unconfirmed");
        }
    }

    /** Returns the original durable protocol identity. / 返回原持久协议身份。
     * @return original UUID / 原 UUID */
    @Override
    public UUID id() {
        return id;
    }

    /** Queues one bounded request without replaying it on timeout. / 排队一个有界请求，超时不重放。
     * @param message explicit object payload / 明确对象载荷
     * @param timeout positive observation deadline / 正的观察期限
     * @return immutable observation stage / 不可篡改的观察阶段
     * @throws ApiException if admission, payload or capacity is invalid / 准入、载荷或容量无效时 */
    @Override
    public synchronized CompletionStage<OperationResult<ProcessMessage>> request(ProcessMessage message,
            Duration timeout) throws ApiException {
        if (!ready.isDone() || ready.isCompletedExceptionally() || stopping || failed || !child.isAlive())
            throw error(ProblemCode.UNAVAILABLE, "PROCESS_NOT_READY", "Process is not accepting requests");
        long nanos;
        try {
            nanos = timeout.toNanos();
            if (nanos <= 0)
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw error(ProblemCode.INVALID_INPUT, "PROCESS_TIMEOUT", "Invalid request deadline");
        }
        if (pending.size() == CAPACITY)
            throw error(ProblemCode.BUSY, "PROCESS_REQUEST_LIMIT", "Process has 32 unsettled requests");
        var requestId = UUID.randomUUID();
        ProcessProtocol.Frame frame;
        try {
            frame = new ProcessProtocol.Frame(id, task, requestId, Kind.REQUEST, message.type(), message.jsonObject());
        } catch (RuntimeException invalid) {
            throw error(ProblemCode.INVALID_INPUT, "PROCESS_MESSAGE", "Invalid process message");
        }
        var entry = new Pending(frame, nanos);
        enqueue(frame);
        pending.put(requestId, entry);
        return entry.result.minimalCompletionStage();
    }

    /** Requests cancellation once per original unsettled request. / 对每个原未核实请求仅请求一次取消。
     * @throws ApiException if the bounded writer cannot accept cancellation / 有界写入队列无法接纳取消时 */
    @Override
    public synchronized void requestCancel() throws ApiException {
        for (var entry : List.copyOf(pending.values())) {
            cancel(entry);
            unknown(entry, "PROCESS_CANCEL_UNCONFIRMED");
        }
    }

    /** Exposes actual process exit without implying that pipe workers have returned. / 暴露实际进程退出，不暗示管道工作线程已返回。
     * @return read-only exit observation / 只读退出观察 */
    @Override
    public CompletionStage<Integer> exitCode() {
        return exit.minimalCompletionStage();
    }

    /** Seals requests and sends one cooperative STOP, then waits at most thirty seconds. / 封闭请求并发送一次协作 STOP，随后最多等待三十秒。
     * @throws ApiException while actual process or stream cleanup remains / 实际进程或流清理仍存在时 */
    @Override
    public void close() throws ApiException {
        synchronized (this) {
            if (released)
                return;
            stopping = true;
            if (child.isAlive() && !stopSent) {
                enqueue(new ProcessProtocol.Frame(id, task, stopId, Kind.STOP, "wtl.process", "{}"));
                stopSent = true;
                stopReported = System.nanoTime();
            }
        }
        long start = System.nanoTime(), limit = Duration.ofSeconds(30).toNanos();
        try {
            if (!child.waitFor(30, TimeUnit.SECONDS))
                throw error(ProblemCode.BUSY, "PROCESS_EXIT_PENDING", "Child process has not actually exited");
            for (var worker : workers) {
                long remaining = limit - (System.nanoTime() - start);
                if (remaining <= 0 || worker == Thread.currentThread())
                    break;
                worker.join(Duration.ofNanos(remaining));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw error(ProblemCode.BUSY, "PROCESS_CLOSE_INTERRUPTED", "Process cleanup remains pending");
        }
        synchronized (this) {
            if (workers.stream().anyMatch(Thread::isAlive))
                throw error(ProblemCode.BUSY, "PROCESS_PIPES_PENDING", "Original process pipe workers remain active");
            try {
                child.getOutputStream().close();
                child.getInputStream().close();
                child.getErrorStream().close();
            } catch (IOException failure) {
                throw error(ProblemCode.IO_FAILURE, "PROCESS_PIPE_CLOSE",
                        "Process pipe release could not be confirmed");
            }
            released = true;
        }
    }

    /** Offers an already validated complete frame without blocking a producer. / 提供已校验完整帧，不阻塞生产方。
     * @param frame original frame / 原帧
     * @throws ApiException if encoding or queue admission fails / 编码或队列准入失败时 */
    private void enqueue(ProcessProtocol.Frame frame) throws ApiException {
        byte[] bytes;
        try {
            bytes = ProcessProtocol.encode(frame);
        } catch (IOException invalid) {
            throw error(ProblemCode.INVALID_INPUT, "PROCESS_FRAME_LIMIT",
                    "Process frame cannot be encoded within its byte limit");
        }
        if (writerDone || !writes.offer(bytes))
            throw error(ProblemCode.BUSY, "PROCESS_WRITE_LIMIT", "Process writer is unavailable or full");
    }

    /** Sole stdin writer for this process; transport failure never resends a frame. / 此进程唯一标准输入写入方；传输失败从不重发帧。 */
    private void writeLoop() {
        try {
            while (child.isAlive()) {
                var bytes = writes.poll(100, TimeUnit.MILLISECONDS);
                if (bytes != null) {
                    child.getOutputStream().write(bytes);
                    child.getOutputStream().flush();
                }
            }
        } catch (IOException | InterruptedException failure) {
            fail("PROCESS_STDIN_FAILED", true);
        } finally {
            writerDone = true;
            writes.clear();
        }
    }

    /** Validates each stdout frame; after failure drains without interpreting further data. / 校验每条 stdout 帧；失败后继续消费但不再解释数据。 */
    private void readLoop() {
        try {
            while (true) {
                var frame = ProcessProtocol.read(child.getInputStream());
                if (frame.isEmpty())
                    break;
                receive(frame.get());
            }
        } catch (IOException | RuntimeException invalid) {
            fail("PROCESS_PROTOCOL", true);
            try {
                child.getInputStream().transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                /* The original protocol failure remains visible. / 原协议失败继续可见。 */ }
        } finally {
            stdoutDone = true;
            synchronized (this) {
                if (!(stopping && stopped && pending.isEmpty()))
                    fail("PROCESS_STDOUT_EOF", true);
            }
        }
    }

    /** Applies correlation, direction and duplicate checks before exposing a result. / 暴露结果前应用关联、方向及重复检查。
     * @param frame complete decoded frame / 完整解码帧
     * @throws IOException if the child violates the active protocol / 子进程违反当前协议时 */
    private synchronized void receive(ProcessProtocol.Frame frame) throws IOException {
        if (failed || !frame.process().equals(id) || !frame.task().equals(task))
            throw new IOException("Process identity mismatch");
        if (frame.kind() == Kind.READY) {
            if (!helloSent || !frame.request().equals(helloId) || !frame.type().equals("wtl.process")
                    || !frame.payload().equals("{}"))
                throw new IOException("Invalid readiness");
            ready.complete(null);
            return;
        }
        if (!ready.isDone() || ready.isCompletedExceptionally())
            throw new IOException("Business frame before readiness");
        if (frame.kind() == Kind.STOPPED) {
            if (!stopSent || !frame.request().equals(stopId) || !frame.type().equals("wtl.process")
                    || !frame.payload().equals("{}"))
                throw new IOException("Invalid stop acknowledgement");
            stopped = true;
            return;
        }
        var entry = pending.get(frame.request());
        var prior = completed.get(frame.request());
        if (frame.kind() == Kind.EVENT) {
            if (entry == null && prior == null)
                throw new IOException("Unknown event request");
            return;
        }
        if (frame.kind() != Kind.RESULT && frame.kind() != Kind.ERROR)
            throw new IOException("Invalid child message direction");
        if (prior != null) {
            if (!ProcessProtocol.equivalent(prior, frame))
                throw new IOException("Conflicting duplicate response");
            return;
        }
        if (entry == null || !entry.frame.type().equals(frame.type()))
            throw new IOException("Unknown response request or type");
        var result = ProcessProtocol.result(frame);
        pending.remove(frame.request());
        completed.put(frame.request(), frame);
        if (completed.size() > CAPACITY)
            completed.remove(completed.keySet().iterator().next());
        if (result.outcome() == Outcome.UNKNOWN)
            notifyFailure(problem(ProblemCode.UNKNOWN, "PROCESS_RESULT_UNKNOWN", "Child reported an unknown outcome"),
                    true);
        entry.result.complete(result);
    }

    /** Consumes stderr continuously while retaining only a bounded aggregate. / 持续消费 stderr，仅保留有界聚合。 */
    private void diagnosticLoop() {
        byte[] bytes = new byte[8192];
        try {
            int size;
            while ((size = child.getErrorStream().read(bytes)) != -1) {
                synchronized (diagnostic) {
                    int retained = Math.min(size, 8192 - diagnostic.size());
                    diagnostic.write(bytes, 0, retained);
                    diagnosticTruncated |= retained < size;
                }
            }
        } catch (IOException failure) {
            feedback("PROCESS_STDERR_FAILED", false);
        } finally {
            stderrDone = true;
        }
    }

    /** Observes actual OS exit; stdout remains responsible for already buffered results. / 观察实际操作系统退出；stdout 继续负责已缓冲结果。 */
    private void waitLoop() {
        try {
            int code = child.waitFor();
            exit.complete(code);
            if (code != 0)
                notifyFailure(problem(ProblemCode.IO_FAILURE, "PROCESS_EXIT_NONZERO",
                        "Auxiliary process exited with code " + code), false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            feedback("PROCESS_EXIT_OBSERVATION", false);
        }
    }

    /** Checks deadlines and stop blockers independently of pipe reads. / 独立于管道读取检查期限及停止阻塞。 */
    private void monitorLoop() {
        long lastDiagnostic = System.nanoTime();
        try {
            while (child.isAlive() || !writerDone || !stdoutDone || !stderrDone) {
                synchronized (this) {
                    if (observer.cancellationRequested()) {
                        try {
                            requestCancel();
                        } catch (ApiException full) {
                            /* Unsent cancellation remains pending. / 尚未发送的取消保持待处理。 */ }
                    }
                    for (var entry : List.copyOf(pending.values()))
                        if (System.nanoTime() - entry.started >= entry.timeout) {
                            try {
                                cancel(entry);
                            } catch (ApiException full) {
                                /* Retry only the unsent CANCEL, never the request. / 仅重试尚未发送的 CANCEL，从不重发请求。 */ }
                            unknown(entry, "PROCESS_REQUEST_TIMEOUT");
                        }
                    if (stopping && System.nanoTime() - stopReported >= Duration.ofSeconds(30).toNanos()) {
                        stopReported = System.nanoTime();
                        try {
                            observer.waiting(stopped);
                        } catch (RuntimeException failure) {
                            feedback("PROCESS_FEEDBACK_FAILED", false);
                        }
                    }
                }
                if (System.nanoTime() - lastDiagnostic >= Duration.ofSeconds(1).toNanos()) {
                    emitDiagnostic();
                    lastDiagnostic = System.nanoTime();
                }
                Thread.sleep(25);
            }
            emitDiagnostic();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            feedback("PROCESS_MONITOR_INTERRUPTED", true);
        } catch (RuntimeException feedbackFailure) {
            fail("PROCESS_FEEDBACK_FAILED", true);
        }
    }

    /** Publishes at most one bounded stderr aggregate per observation interval. / 每个观察间隔最多发布一个有界 stderr 聚合。 */
    private void emitDiagnostic() {
        String text;
        boolean truncated;
        synchronized (diagnostic) {
            if (diagnostic.size() == 0)
                return;
            text = diagnostic.toString(StandardCharsets.UTF_8);
            truncated = diagnosticTruncated;
            diagnostic.reset();
            diagnosticTruncated = false;
        }
        try {
            observer.diagnostic(text, truncated);
        } catch (RuntimeException failure) {
            feedback("PROCESS_DIAGNOSTIC_FAILED", false);
        }
    }

    /** Sends a single cancellation for the exact original request. / 对精确原请求发送单次取消。
     * @param entry original pending request / 原待完成请求
     * @throws ApiException if cancellation has not entered the writer queue / 取消尚未进入写入队列时 */
    private void cancel(Pending entry) throws ApiException {
        if (entry.cancelSent)
            return;
        enqueue(new ProcessProtocol.Frame(id, task, entry.frame.request(), Kind.CANCEL, entry.frame.type(), "{}"));
        entry.cancelSent = true;
    }

    /** Completes a caller observation as unknown without declaring the original request ended. / 将调用方观察完成为未知，不声明原请求已结束。
     * @param entry original unresolved request / 原未核实请求
     * @param event stable event code / 稳定事件码 */
    private void unknown(Pending entry, String event) {
        if (entry.result.isDone())
            return;
        var problem = problem(ProblemCode.UNKNOWN, event, "The original process request requires reconciliation");
        notifyFailure(problem, true);
        entry.result.complete(new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(problem)));
    }

    /** Seals a failed channel while retaining trusted completed outcomes and actual pipes. / 封闭失败通道，保留已可信完成的结果及实际管道。
     * @param event stable failure code / 稳定失败码
     * @param requestUncertainty whether pending business could be affected / 是否可能影响待完成业务 */
    private synchronized void fail(String event, boolean requestUncertainty) {
        if (failed)
            return;
        failed = true;
        ready.completeExceptionally(new IOException("Process handshake or transport failed"));
        boolean unresolved = !pending.isEmpty();
        for (var entry : List.copyOf(pending.values()))
            unknown(entry, event);
        feedback(event, requestUncertainty && unresolved);
    }

    /** Isolates host diagnostic exceptions from physical draining. / 将宿主诊断异常与实际管道消费隔离。
     * @param event stable event code / 稳定事件码
     * @param uncertain whether original requests remain unconfirmed / 原请求是否仍未确认 */
    private void feedback(String event, boolean uncertain) {
        notifyFailure(problem(ProblemCode.IO_FAILURE, event, "Auxiliary process transport requires attention"),
                uncertain);
    }

    /** Preserves transport progress even if the host feedback callback fails. / 即使宿主反馈回调失败，仍保留传输进展。
     * @param problem safe evidence / 安全证据
     * @param uncertain whether original business remains unknown / 原业务是否仍未知 */
    private void notifyFailure(Problem problem, boolean uncertain) {
        try {
            observer.failure(problem, uncertain);
        } catch (RuntimeException ignored) {
            // Feedback failure must not stop pipe draining or erase the retained protocol failure. / 反馈失败不得停止管道排空或抹去保留的协议失败。
            System.err.println("PROCESS_FEEDBACK_FAILED");
        }
    }

    /** Original request and immutable outward observation. / 原请求及不可变对外观察。 */
    private static final class Pending {
        /** Original frame used only once. / 仅使用一次的原帧。 */
        private final ProcessProtocol.Frame frame;

        /** Monotonic observation start and budget. / 单调观察起点及预算。 */
        private final long started = System.nanoTime(), timeout;

        /** Outward observation, never used as physical release proof. / 对外观察，从不作为物理释放证明。 */
        private final CompletableFuture<OperationResult<ProcessMessage>> result = new CompletableFuture<>();

        /** Whether its single CANCEL entered the queue. / 单次 CANCEL 是否进入队列。 */
        private boolean cancelSent;
        /** Captures original request identity and deadline. / 捕获原请求身份及期限。
         * @param frame original frame / 原帧
         * @param timeout positive nanoseconds / 正的纳秒数 */
        private Pending(ProcessProtocol.Frame frame, long timeout) {
            this.frame = frame;
            this.timeout = timeout;
        }
    }

    /** Host integration callbacks; diagnostic text still requires platform redaction. / 宿主集成回调；诊断文字仍须平台脱敏。 */
    public interface Observer {
        /** Observes original-task cancellation without granting new work. / 观察原任务取消，不授予新工作。
         * @return current cancellation signal / 当前取消信号 */
        boolean cancellationRequested();

        /** Receives a bounded raw stderr excerpt for the unified log service. / 为统一日志服务接收有界原始 stderr 摘要。
         * @param excerpt bounded excerpt / 有界摘要
         * @param truncated whether bytes were omitted / 是否省略字节 */
        void diagnostic(String excerpt, boolean truncated);

        /** Reports safe protocol evidence separately from business results. / 独立于业务结果报告安全协议证据。
         * @param problem fixed safe diagnostic / 固定安全诊断
         * @param uncertain whether pending business remains unknown / 待完成业务是否仍未知 */
        void failure(Problem problem, boolean uncertain);

        /** Refreshes the actual stop blocker without killing the child. / 刷新实际停止阻塞，不终止子进程。
         * @param acknowledged whether STOPPED was observed / 是否观察到 STOPPED */
        void waiting(boolean acknowledged);
    }

    /** Creates a fixed non-secret protocol problem. / 创建固定且不含秘密的协议问题。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param message safe text / 安全文字
     * @return structured problem / 结构化问题 */
    private static Problem problem(ProblemCode code, String event, String message) {
        return new Problem(code, event, message, Map.of(), Optional.empty());
    }

    /** Creates a public boundary exception. / 创建公开边界异常。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param message safe text / 安全文字
     * @return public exception / 公开异常 */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(problem(code, event, message));
    }
}
