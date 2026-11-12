package gold.debug.windowstolinux.app.linux.ssh.connect;

import java.io.*;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import org.apache.sshd.client.channel.*;
import org.apache.sshd.client.session.ClientSession;

/** Owns one dispatched SSH channel, bounded output and actual input-sender lifetime. / 持有一次已发送 SSH 通道、有界输出及输入发送线程的实际生命周期。 */
final class SshCommandExecution {
    /** Authenticated session owned by the enclosing connection. / 外层连接持有的已认证会话。 */
    private final ClientSession session;

    /** Copied request frame wiped only after the sender exits. / 仅在发送线程退出后清零的请求帧副本。 */
    private final SshCommandFrame frame;

    /** Shared output-retention boundary. / 共享输出保留边界。 */
    private final SshOutputCapture capture;

    /** Original operation deadline budget. / 原操作期限预算。 */
    private final long budget;

    /** Sanitized transport observations for this one dispatch. / 本次发送的脱敏传输观测。 */
    private final SshCommandDiagnostics diagnostics = new SshCommandDiagnostics();

    /** Physical SSH channel retained until actual closure. / 实际关闭前保留的物理 SSH 通道。 */
    private ClientChannel channel;
    private ChannelOpenMetadata channelOpen;
    private final ClientIoLifecycle io;
    private Map<String, String> deadlineWorkers = Map.of("channelOpenWorkerSnapshot", "NOT_OBSERVED");
    private boolean workerSnapshotTaken;

    /** Task-owned input sender, never abandoned on timeout. / 任务持有的输入发送线程，超时时不丢弃。 */
    private Thread sender;

    /** Whether a command-open request may have reached the peer. / 命令打开请求是否可能到达对端。 */
    private boolean dispatched;

    /** Whether channel and sender have actually closed. / 通道及发送线程是否已实际关闭。 */
    private boolean closed;

    /** Last locally observed command phase. / 最近一次本机观察的命令阶段。 */
    private DispatchState phase = DispatchState.BEFORE_DISPATCH;

    /** Monotonic execution start, excluding approval time. / 单调执行启动时刻，不包含审批等待。 */
    private long started;

    /** Whether channel opening was confirmed by the peer. / 对端是否已确认打开通道。 */
    private boolean openConfirmed;

    /** Window evidence captured before starting the input sender. / 启动输入发送线程前抓取的窗口依据。 */
    private Map<String, String> openedWindow = Map.of();

    /** Input progress without retaining private stream errors. / 输入进展，不保留私密流错误内容。 */
    private volatile InputState inputState = InputState.NOT_STARTED;

    /** Locally observed SSH dispatch states; names remain the existing diagnostic values. / 本机观察的 SSH 发送状态，名称保留既有诊断值。 */
    private enum DispatchState {
        /** No dispatch has started. / 尚未开始发送。 */
        BEFORE_DISPATCH,
        /** The local channel is being created. / 正在创建本机通道。 */
        CREATING_CHANNEL,
        /** Channel opening may already have reached the peer. / 通道打开请求可能已到达对端。 */
        CHANNEL_OPENING,
        /** The open channel awaits confirmed remote exit. / 已打开通道等待确认远端退出。 */
        WAITING_FOR_EXIT
    }

    /** Tracks the owned input sender without retaining stream error content. / 跟踪所属输入发送线程，不保留流错误内容。 */
    private enum InputState {
        /** The sender has not started. / 发送线程尚未启动。 */
        NOT_STARTED,
        /** The sender may still own request bytes. / 发送线程可能仍持有请求字节。 */
        SENDING,
        /** All input was sent and the owned stream closed. / 输入已发完且所属流已关闭。 */
        COMPLETED,
        /** Input sending failed; peer evidence still determines completion. / 输入发送失败，完成状态仍由对端依据决定。 */
        FAILED
    }

    /**
     * Prepares and bounds the exact immutable request without sending bytes. / 准备并限制精确不可变请求，不发送字节。
     * @param session authenticated transport / 已认证传输
     * @param request exact approved command request / 精确已批准命令请求
     * @throws ApiException if request validation fails / 请求校验失败时
     */
    SshCommandExecution(ClientSession session, CommandRequest request) throws ApiException {
        this(session, request, null);
    }

    SshCommandExecution(ClientSession session, CommandRequest request, ClientIoLifecycle io) throws ApiException {
        this.io = io;
        this.session = Objects.requireNonNull(session);
        frame = new SshCommandFrame(request);
        capture = new SshOutputCapture(request.outputLimitBytes());
        budget = request.timeout().toNanos();
    }

    /**
     * Dispatches once and distinguishes observed completion from unconfirmed effects. / 仅发送一次，区分已观测完成及未确认副作用。
     * @param cancellation original task cancellation / 原任务取消请求
     * @return known command result or explicit uncertainty / 已知命令结果或明确不确定性
     */
    OperationResult<CommandResult> run(CancelToken cancellation) {
        return run(cancellation, System.nanoTime());
    }

    /** Runs within the original command deadline, including any fixed pre-dispatch admission. / 在包括固定发送前准入的原命令时限内执行。
     * @param cancellation original task cancellation / 原任务取消请求
     * @param admittedAt monotonic start of this request before pre-dispatch checks / 发送前校验开始前记录的本请求单调时钟起点
     * @return known command result or explicit uncertainty / 已知命令结果或明确不确定性 */
    OperationResult<CommandResult> run(CancelToken cancellation, long admittedAt) {
        started = admittedAt;
        try {
            if (cancellation.requested() || Thread.currentThread().isInterrupted())
                return result(TaskResult.CANCELLED, ProblemCode.CANCELLED, "SSH_COMMAND_CANCELLED",
                    "SSH command was cancelled before dispatch", null);
            remainingMillis(started);
            phase = DispatchState.CREATING_CHANNEL;
            diagnostics.attach(session);
            channel = session.createExecChannel(SshCommandFrame.COMMAND);
            channel.setOut(capture.stdout());
            channel.setErr(capture.stderr());
            dispatched = true;
            phase = DispatchState.CHANNEL_OPENING;
            channelOpen = new ChannelOpenMetadata(channel.getChannelId(), started);
            if (session instanceof ChannelOpenMetadata.Session observed) observed.bind(channelOpen);
            var opening = channel.open();
            channelOpen.opening(opening);
            while (!opening.await(remainingMillis(started))) {
                if (cancellation.requested() || Thread.currentThread().isInterrupted())
                    return unknown("SSH_COMMAND_CANCELLED",
                        "SSH command cancellation does not confirm its remote effects");
            }
            opening.verify(Duration.ofMillis(1));
            openConfirmed = true;
            openedWindow = windowFacts("remoteWindowOpen");
            sender = Thread.ofVirtual().name("platform-ssh-input").unstarted(() -> {
                try (OutputStream input = channel.getInvertedIn()) {
                    frame.writeTo(input);
                } catch (Exception failure) {
                    inputState = InputState.FAILED;
                    // Completion is determined from peer exit evidence or the deadline, never a raw stream exception. / 完成由对端退出证据或期限判定，不使用原始流异常。
                    return;
                }
                inputState = InputState.COMPLETED;
            });
            inputState = InputState.SENDING;
            sender.start();
            phase = DispatchState.WAITING_FOR_EXIT;
            while (true) {
                if (channel.isClosed()) {
                    Integer exit = channel.getExitStatus();
                    if (exit == null || channel.getExitSignal() != null)
                        return unknown("SSH_COMMAND_EXIT_UNKNOWN",
                            "SSH channel closed without a confirmed command exit");
                    return exit == 0
                        ? new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(capture.snapshot(exit)),
                        Optional.empty())
                        : result(TaskResult.FAILED, ProblemCode.IO_FAILURE, "SSH_COMMAND_NONZERO",
                        "SSH command reported a nonzero exit status", exit);
                }
                if (cancellation.requested() || Thread.currentThread().isInterrupted())
                    return unknown("SSH_COMMAND_CANCELLED",
                        "SSH command cancellation does not confirm its remote effects");
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), remainingMillis(started));
            }
        } catch (java.util.concurrent.TimeoutException failure) {
            if (cancellation.requested() || Thread.currentThread().isInterrupted())
                return unknown("SSH_COMMAND_CANCELLED",
                    "SSH command cancellation does not confirm its remote effects");
            if (!dispatched)
                return result(TaskResult.FAILED, ProblemCode.TIMEOUT, "SSH_COMMAND_TIMEOUT",
                    "SSH command expired before dispatch", null);
            if (phase == DispatchState.CHANNEL_OPENING && dispatched && !openConfirmed
                && sender == null && inputState == InputState.NOT_STARTED)
                return result(TaskResult.FAILED, ProblemCode.IO_FAILURE, "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT",
                    "SSH bootstrap opening deadline expired before the author script and input sender were created", null);
            return unknown("SSH_COMMAND_TIMEOUT", "SSH command deadline expired before its effects could be confirmed");
        } catch (Exception failure) {
            diagnostics.failure(failure);
            if (failure instanceof InterruptedIOException || failure instanceof InterruptedException)
                Thread.currentThread().interrupt();
            if (cancellation.requested() || Thread.currentThread().isInterrupted())
                return unknown("SSH_COMMAND_CANCELLED",
                    "SSH command cancellation does not confirm its remote effects");
            return dispatched
                ? unknown("SSH_COMMAND_TRANSPORT",
                "SSH command effects could not be confirmed after transport failure")
                : result(TaskResult.FAILED, ProblemCode.IO_FAILURE, "SSH_COMMAND_NOT_SENT",
                "SSH command could not be dispatched", null);
        } finally {
            if (session instanceof ChannelOpenMetadata.Session observed) observed.unbind(channelOpen);
        }
    }

    /**
     * Computes one short wait within the unchanged original budget. / 在不变的原预算内计算一次短等待。
     * @param started monotonic start time / 单调时钟启动时间
     * @return positive bounded wait in milliseconds / 正数的有界毫秒等待
     * @throws java.util.concurrent.TimeoutException when the total deadline has elapsed / 总期限已届满时
     */
    private long remainingMillis(long started) throws java.util.concurrent.TimeoutException {
        long remaining = budget - (System.nanoTime() - started);
        if (remaining <= 0)
            throw new java.util.concurrent.TimeoutException();
        if ((phase == DispatchState.CHANNEL_OPENING || phase == DispatchState.WAITING_FOR_EXIT)
            && !workerSnapshotTaken && remaining <= 100_000_000L) {
            workerSnapshotTaken = true;
            if (io != null) {
                long sampleStarted = System.nanoTime() - started;
                try {
                    var snapshot = io.workerSnapshot();
                    long completed = System.nanoTime() - started;
                    remaining = budget - completed;
                    var facts = new LinkedHashMap<String, String>(remaining > 0 ? snapshot
                        : Map.of("channelOpenWorkerSnapshot", "NOT_OBSERVED"));
                    facts.put("channelOpenWorkerSnapshotStartedMillis", Long.toString(sampleStarted / 1_000_000));
                    facts.put("channelOpenWorkerSnapshotCompletedMillis", Long.toString(completed / 1_000_000));
                    facts.put("channelOpenWorkerSnapshotRemainingMillis", Long.toString(Math.max(0, remaining) / 1_000_000));
                    facts.put("channelOpenWorkerSnapshotCommandPhase", phase.name());
                    deadlineWorkers = Map.copyOf(facts);
                } catch (RuntimeException unavailable) {
                    deadlineWorkers = Map.of("channelOpenWorkerSnapshot", "UNAVAILABLE");
                }
            }
            remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
        }
        return Math.max(1, Math.min(50, remaining / 1_000_000));
    }

    /**
     * Reports uncertainty without upgrading channel close into business completion. / 报告不确定性，不把通道关闭提升为业务完成。
     * @param event stable reason code / 稳定原因代码
     * @param message safe reason text / 安全原因文本
     * @return uncertain result with observed bounded output / 附带已观测有界输出的不确定结果
     */
    private OperationResult<CommandResult> unknown(String event, String message) {
        if (event.equals("SSH_COMMAND_TRANSPORT") && dispatched && sender == null && inputState == InputState.NOT_STARTED)
            return result(TaskResult.FAILED, ProblemCode.IO_FAILURE, "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT",
                "SSH bootstrap opening may have started, but the author script and input sender were never created", null);
        return result(TaskResult.UNKNOWN, ProblemCode.UNKNOWN, event, message,
            channel == null ? null : channel.getExitStatus());
    }

    /**
     * Builds a result without retaining implementation exception text. / 构造结果，不保留实现异常文本。
     * @param outcome observed result category / 已观测结果分类
     * @param code public problem category / 公开问题分类
     * @param event stable event identity / 稳定事件身份
     * @param message safe diagnostic / 安全诊断
     * @param exit actually observed exit status / 实际观测退出状态
     * @return bounded result and diagnostic / 有界结果及诊断
     */
    private OperationResult<CommandResult> result(TaskResult outcome, ProblemCode code, String event, String message,
                                                  Integer exit) {
        var output = capture.snapshot(exit);
        var diagnostic = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("commandPhase", phase.name()),
            Map.entry("elapsedMillis", Long.toString(Math.max(0, System.nanoTime() - started) / 1_000_000)),
            Map.entry("timeoutMillis", Long.toString(budget / 1_000_000)),
            Map.entry("commandOpenRequested", Boolean.toString(dispatched)),
            Map.entry("channelOpenConfirmed", Boolean.toString(openConfirmed)),
            Map.entry("channelClosed", Boolean.toString(channel != null && channel.isClosed())),
            Map.entry("sessionOpen", Boolean.toString(session.isOpen())),
            Map.entry("sessionAuthenticated", Boolean.toString(session.isAuthenticated())),
            Map.entry("inputState", inputState.name()),
            Map.entry("scriptBytes", Integer.toString(frame.scriptBytes())),
            Map.entry("stdinBytes", Integer.toString(frame.stdinBytes())),
            Map.entry("frameWritePhase", frame.writePhase().name()),
            Map.entry("inputSenderCreated", Boolean.toString(sender != null)),
            Map.entry("authorPayloadState", sender == null && inputState == InputState.NOT_STARTED ? "NOT_SENT" : "MAY_HAVE_BEEN_SENT"),
            Map.entry("outputObserved", Boolean.toString(!output.stdout().isEmpty() || !output.stderr().isEmpty())),
            Map.entry("exitObserved", Boolean.toString(exit != null))));
        diagnostic.putAll(openedWindow);
        diagnostic.putAll(windowFacts("remoteWindowFailure"));
        if (sender != null && inputState == InputState.SENDING) {
            diagnostic.put("inputThreadState", sender.getState().name());
            int index = 0;
            for (var entry : sender.getStackTrace()) {
                if (entry.getClassName().matches(
                    "(?:java|jdk|sun\\.nio|org\\.apache\\.sshd|gold\\.debug\\.windowstolinux\\.app\\.linux\\.ssh\\.connect)\\.[A-Za-z0-9_.$]{1,160}")
                    && entry.getMethodName().matches("[A-Za-z0-9_$<>]{1,128}")) {
                    diagnostic.put("inputStack[" + index++ + "]",
                        entry.getClassName() + "#" + entry.getMethodName() + ":" + entry.getLineNumber());
                    if (index == 6) break;
                }
            }
        }
        diagnostics.appendTo(diagnostic);
        if (channelOpen != null) {
            try { channelOpen.appendTo(diagnostic); }
            catch (RuntimeException unavailable) { diagnostic.put("channelOpenObservation", "UNAVAILABLE"); }
        }
        diagnostic.putAll(deadlineWorkers);
        diagnostic.put("channelPeerAssigned", Boolean.toString(channel != null && channel.getRecipient() >= 0));
        return new OperationResult<>(outcome, Optional.of(output),
            Optional.of(new Problem(code, event, message, Map.copyOf(diagnostic), Optional.empty())));
    }

    /** Observes only an already initialized remote window; missing diagnostics never change execution. / 仅观察已初始化远端窗口；缺失诊断不改变执行。 */
    private Map<String, String> windowFacts(String prefix) {
        if (!openConfirmed || channel == null)
            return Map.of();
        try {
            var window = channel.getRemoteWindow();
            return Map.of(prefix + "MaxSize", Long.toString(window.getMaxSize()),
                prefix + "Size", Long.toString(window.getSize()),
                prefix + "PacketSize", Long.toString(window.getPacketSize()));
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    /**
     * Confirms actual channel and sender termination before wiping input copies. / 确认通道和发送线程实际终止后才清零输入副本。
     * @return whether all local resources have actually released / 所有本地资源是否实际释放
     */
    boolean close() {
        if (closed)
            return true;
        if (channel != null
            && (!channel.close(true).awaitUninterruptibly(Duration.ofSeconds(5)) || !channel.isClosed()))
            return false;
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (sender != null && sender.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    return false;
                try {
                    sender.join(Duration.ofNanos(remaining));
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            frame.close();
            diagnostics.close();
            closed = true;
            return true;
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }
}
