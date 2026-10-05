package gold.debug.windowstolinux.mod.git.file;

import static gold.debug.windowstolinux.mod.git.file.GitProtocol.KindType;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Single-operation Git worker using only the public standard-stream process protocol. / 仅使用公开标准流进程协议的单操作 Git worker。 */
public final class GitWorker {
    /** Expected original owner. / 预期原所有者。 */
    private final String owner;

    /** Expected original version. / 预期原版本。 */
    private final String version;

    /** Exact operation identity. / 精确操作身份。 */
    private final UUID job;

    /** Fixed native Git executable. / 固定原生 Git 可执行文件。 */
    private final Path executable;

    /** Owned source directory. / 所属来源目录。 */
    private final Path checkout;

    /** Owned control directory. / 所属控制目录。 */
    private final Path control;

    /** Cooperative cancellation state. / 协作取消状态。 */
    private volatile boolean cancelled;

    /** Original platform introduction. / 原平台介绍。 */
    private GitProtocol.Frame hello;

    /** Original business request. / 原业务请求。 */
    private GitProtocol.Frame request;

    /** Original stop request. / 原停止请求。 */
    private GitProtocol.Frame stop;

    /** Native operation thread retained until actual completion. / 保留至实际完成的原生操作线程。 */
    private Thread operation;

    /** Binds only explicit non-secret launch facts. / 仅绑定明确非秘密启动事实。
     * @param arguments exact owner/version/tool/control/job tuple / 精确所有者、版本、工具、控制目录、操作元组
     * @throws IOException if original paths are unavailable / 原路径不可用时 */
    private GitWorker(String[] arguments) throws IOException {
        if (arguments.length != 5)
            throw new IllegalArgumentException("Invalid Git worker launch");
        owner = arguments[0];
        version = arguments[1];
        executable = Path.of(arguments[2]);
        control = Path.of(arguments[3]);
        job = UUID.fromString(arguments[4]);
        checkout = Path.of("").toAbsolutePath().normalize();
        if (!executable.isAbsolute() || !Files.isRegularFile(executable) || !control.isAbsolute()
            || !control.toRealPath().equals(control) || !checkout.toRealPath().equals(checkout)
            || checkout.startsWith(control) || control.startsWith(checkout))
            throw new IOException("Git worker paths are unavailable");
    }

    /** Enters the owned worker without printing protocol failures to stdout. / 进入所属 worker，不向 stdout 输出协议失败文本。
     * @param arguments explicit non-secret launch arguments / 明确非秘密启动参数 */
    public static void main(String[] arguments) {
        try {
            new GitWorker(arguments).run();
        } catch (Exception failure) {
            System.err.println("Git worker stopped before confirmed completion");
        }
    }

    /** Processes one request and cooperative control frames until all native work returns. / 处理一个请求及协作控制帧，直至全部原生工作返回。
     * @throws Exception if handshake, framing or physical output fails / 握手、帧或物理输出失败时 */
    private void run() throws Exception {
        hello = GitProtocol.read(System.in).orElseThrow(() -> new IOException("Missing introduction"));
        var identity = GitProtocol.object(hello.payload());
        GitProtocol.fields(identity, Set.of("ownerId", "ownerVersion"));
        if (hello.kind() != KindType.HELLO || !hello.type().equals("wtl.process")
            || !owner.equals(GitProtocol.text(identity, "ownerId"))
            || !version.equals(GitProtocol.text(identity, "ownerVersion")))
            throw new IOException("Git worker introduction differs");
        send(new GitProtocol.Frame(hello.process(), hello.task(), hello.request(), KindType.READY,
            "wtl.process", "{}"));
        try {
            while (true) {
                var next = GitProtocol.read(System.in);
                if (next.isEmpty())
                    break;
                var frame = next.orElseThrow();
                if (!frame.process().equals(hello.process()) || !frame.task().equals(hello.task()))
                    throw new IOException("Foreign Git control frame");
                if (frame.kind() == KindType.REQUEST && request == null
                    && frame.type().equals(GitMessages.TYPE)) {
                    request = frame;
                    operation = Thread.ofPlatform().name("git-source-operation").start(() -> execute(frame));
                } else if (frame.kind() == KindType.CANCEL && request != null
                    && frame.request().equals(request.request())) {
                    if (!frame.payload().equals("{}"))
                        throw new IOException("Invalid cancellation");
                    cancelled = true;
                } else if (frame.kind() == KindType.STOP && frame.type().equals("wtl.process")
                    && frame.payload().equals("{}")) {
                    stop = frame;
                    break;
                } else
                    throw new IOException("Unsupported Git control frame");
            }
        } finally {
            cancelled = true;
            if (operation != null)
                while (operation.isAlive()) {
                    try {
                        operation.join(100);
                    } catch (InterruptedException signal) {
                        cancelled = true;
                    }
                }
        }
        if (stop != null) {
            if (request == null)
                receipt(new GitProtocol.Frame(hello.process(), hello.task(), stop.request(), KindType.RESULT,
                    GitMessages.TYPE, GitMessages.result(job, null, GitCommands
                    .problem(ProblemCode.CANCELLED, "GIT_SOURCE_NOT_STARTED").problem())));
            send(new GitProtocol.Frame(hello.process(), hello.task(), stop.request(), KindType.STOPPED,
                "wtl.process", "{}"));
        }
    }

    /** Performs one fixed checkout and saves original cleanup evidence before responding. / 执行一次固定检出，在响应前保存原清理证据。
     * @param frame original business request / 原业务请求 */
    private void execute(GitProtocol.Frame frame) {
        String commit = null;
        Problem problem = null;
        try {
            var request = GitMessages.decode(frame.payload());
            commit = GitCheckout.prepare(request, executable, checkout, control, () -> cancelled);
            if (cancelled)
                throw GitCommands.problem(ProblemCode.CANCELLED, "GIT_SOURCE_CANCELLED");
        } catch (ApiException failure) {
            problem = failure.problem();
        } catch (IOException | IllegalArgumentException failure) {
            problem = GitCommands.problem(ProblemCode.INVALID_INPUT, "GIT_SOURCE_SOURCE").problem();
        } catch (RuntimeException failure) {
            problem = GitCommands.problem(ProblemCode.UNKNOWN, "GIT_SOURCE_UNCONFIRMED").problem();
        }
        var result = new GitProtocol.Frame(frame.process(), frame.task(), frame.request(), KindType.RESULT,
            frame.type(), GitMessages.result(job, problem == null ? commit : null, problem));
        try {
            receipt(result);
            send(result);
        } catch (IOException failure) {
            System.err.println("Git worker result could not be confirmed");
        }
    }

    /** Persists a complete bounded result without replacing an existing receipt. / 持久化完整有界结果，不替换已有凭据。
     * @param result original result and physical cleanup evidence / 原结果及物理清理证据
     * @throws IOException if persistence cannot be confirmed / 无法确认持久化时 */
    private void receipt(GitProtocol.Frame result) throws IOException {
        byte[] bytes = GitProtocol.encode(result);
        try (var output = FileChannel.open(control.resolve("git-result.json"), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining())
                output.write(buffer);
            output.force(true);
        }
    }

    /** Serializes one complete frame on the sole protocol output. / 在唯一协议输出上串行写入一帧。
     * @param frame original correlated frame / 原关联帧
     * @throws IOException if the platform pipe is unavailable / 平台管道不可用时 */
    private synchronized void send(GitProtocol.Frame frame) throws IOException {
        System.out.write(GitProtocol.encode(frame));
        System.out.flush();
        if (System.out.checkError())
            throw new IOException("Git protocol output unavailable");
    }
}
