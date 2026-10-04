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

    /** Physical SSH channel retained until actual closure. / 实际关闭前保留的物理 SSH 通道。 */
    private ClientChannel channel;

    /** Task-owned input sender, never abandoned on timeout. / 任务持有的输入发送线程，超时时不丢弃。 */
    private Thread sender;

    /** Whether a command-open request may have reached the peer. / 命令打开请求是否可能到达对端。 */
    private boolean dispatched;

    /** Whether channel and sender have actually closed. / 通道及发送线程是否已实际关闭。 */
    private boolean closed;

    /**
     * Prepares and bounds the exact immutable request without sending bytes. / 准备并限制精确不可变请求，不发送字节。
     * @param session authenticated transport / 已认证传输
     * @param request exact approved command request / 精确已批准命令请求
     * @throws ApiException if request validation fails / 请求校验失败时
     */
    SshCommandExecution(ClientSession session, CommandRequest request) throws ApiException {
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
        long started = System.nanoTime();
        try {
            if (cancellation.requested() || Thread.currentThread().isInterrupted())
                return result(TaskResult.CANCELLED, ProblemCode.CANCELLED, "SSH_COMMAND_CANCELLED",
                    "SSH command was cancelled before dispatch", null);
            channel = session.createExecChannel(SshCommandFrame.COMMAND);
            channel.setOut(capture.stdout());
            channel.setErr(capture.stderr());
            dispatched = true;
            var opening = channel.open();
            while (!opening.await(remainingMillis(started))) {
                if (cancellation.requested() || Thread.currentThread().isInterrupted())
                    return unknown("SSH_COMMAND_CANCELLED",
                        "SSH command cancellation does not confirm its remote effects");
            }
            opening.verify(Duration.ofMillis(1));
            sender = Thread.ofVirtual().name("platform-ssh-input").unstarted(() -> {
                try (OutputStream input = channel.getInvertedIn()) {
                    frame.writeTo(input);
                } catch (Exception failure) {
                    // Completion is determined from peer exit evidence or the deadline, never a raw stream exception. / 完成由对端退出证据或期限判定，不使用原始流异常。
                }
            });
            sender.start();
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
            return unknown("SSH_COMMAND_TIMEOUT", "SSH command deadline expired before its effects could be confirmed");
        } catch (Exception failure) {
            if (failure instanceof InterruptedIOException || failure instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return dispatched
                ? unknown("SSH_COMMAND_TRANSPORT",
                "SSH command effects could not be confirmed after transport failure")
                : result(TaskResult.FAILED, ProblemCode.IO_FAILURE, "SSH_COMMAND_NOT_SENT",
                "SSH command could not be dispatched", null);
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
        return Math.max(1, Math.min(50, remaining / 1_000_000));
    }

    /**
     * Reports uncertainty without upgrading channel close into business completion. / 报告不确定性，不把通道关闭提升为业务完成。
     * @param event stable reason code / 稳定原因代码
     * @param message safe reason text / 安全原因文本
     * @return uncertain result with observed bounded output / 附带已观测有界输出的不确定结果
     */
    private OperationResult<CommandResult> unknown(String event, String message) {
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
        return new OperationResult<>(outcome, Optional.of(capture.snapshot(exit)),
            Optional.of(new Problem(code, event, message, Map.of(), Optional.empty())));
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
            closed = true;
            return true;
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }
}
