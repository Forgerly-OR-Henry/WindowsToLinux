package gold.debug.windowstolinux.app.main.start;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One-use startup acknowledgement shared only by an original parent and its new process. / 仅由原父进程与其新进程共享的一次性启动确认。 */
final class ApplicationRestartReceipt {
    /** Internal non-secret directory argument. / 内部非秘密目录参数。 */
    static final String DIRECTORY = "--wtl-restart-directory=";

    /** Internal non-secret correlation argument. / 内部非秘密关联参数。 */
    static final String TOKEN = "--wtl-restart-token=";

    /** Exact original temporary directory. / 精确原临时目录。 */
    private final Path directory;

    /** Original correlation identity. / 原关联身份。 */
    private final UUID token;

    /** Parent process identity including start time. / 包含启动时间的父进程身份。 */
    private final long parent;

    /** Original parent start instant, preventing PID reuse. / 原父进程启动时刻，防止 PID 复用。 */
    private final Instant parentStart;

    /** Whether this child has published a terminal acknowledgement. / 此子进程是否发布过终态确认。 */
    private boolean signalled;

    /** Captures the exact verified temporary ownership. / 捕获精确已验证临时归属。
     * @param directory exact directory / 精确目录
     * @param token correlation identity / 关联身份
     * @param parent original PID / 原 PID
     * @param parentStart original process start / 原进程启动时刻 */
    private ApplicationRestartReceipt(Path directory, UUID token, long parent, Instant parentStart) {
        this.directory = directory;
        this.token = token;
        this.parent = parent;
        this.parentStart = parentStart;
    }

    /** Creates an original request without credentials or arbitrary launch arguments. / 创建原请求，不含凭据或任意启动参数。
     * @param root temporary root / 临时根
     * @return owned request / 所属请求
     * @throws IOException if the original request cannot be created / 无法创建原请求时 */
    static ApplicationRestartReceipt create(Path root) throws IOException {
        return create(root, (request, content) -> Files.writeString(request, content, StandardOpenOption.CREATE_NEW));
    }

    /** Creates a request through the original write boundary. / 经原写入边界创建请求。
     * @param root temporary root / 临时根
     * @param writer original request writer / 原请求写入器
     * @return owned request / 所属请求
     * @throws IOException if writing or cleanup cannot be completed / 无法完成写入或清理时 */
    static ApplicationRestartReceipt create(Path root, RequestWriter writer) throws IOException {
        var current = ProcessHandle.current();
        var start = current.info().startInstant().orElseThrow(() -> new IOException("Parent identity is unavailable"));
        var token = UUID.randomUUID();
        Path directory = Files.createDirectory(root.toRealPath().resolve("wtl-restart-" + token));
        var result = new ApplicationRestartReceipt(directory, token, current.pid(), start);
        try {
            writer.write(directory.resolve("request"), token + "\n" + current.pid() + "\n" + start + "\n");
            return result;
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(directory.resolve("request"));
                Files.deleteIfExists(directory);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Writes one newly owned request, including partial-write failures. / 写入一个新持有请求，包含部分写入失败。 */
    @FunctionalInterface
    interface RequestWriter {
        /** Writes the exact request without replacing existing files. / 写入精确请求，不替换现有文件。
         * @param request original request path / 原请求路径
         * @param content non-secret ownership facts / 非秘密归属事实
         * @throws IOException if the original write fails / 原写入失败时 */
        void write(Path request, String content) throws IOException;
    }

    /** Accepts only the exact existing parent request. / 仅接纳精确现存父请求。
     * @param arguments fixed entry arguments / 固定入口参数
     * @return child acknowledgement, absent for normal startup / 子确认，正常启动时缺省
     * @throws IOException if internal arguments or ownership are invalid / 内部参数或归属无效时 */
    static Optional<ApplicationRestartReceipt> accept(String[] arguments) throws IOException {
        String path = null, id = null;
        for (String value : arguments) {
            if (value.startsWith(DIRECTORY)) {
                if (path != null) throw new IOException("Duplicate restart acknowledgement");
                path = value.substring(DIRECTORY.length());
            } else if (value.startsWith(TOKEN)) {
                if (id != null) throw new IOException("Duplicate restart acknowledgement");
                id = value.substring(TOKEN.length());
            }
        }
        if (path == null && id == null) return Optional.empty();
        try {
            var token = UUID.fromString(id);
            Path directory = Path.of(path).toAbsolutePath().normalize();
            if (!directory.getFileName().toString().equals("wtl-restart-" + token)
                || Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.equals(directory.toRealPath()) || Files.size(directory.resolve("request")) > 512
                || Files.isSymbolicLink(directory.resolve("request")))
                throw new IOException("Restart acknowledgement ownership is invalid");
            var values = Files.readAllLines(directory.resolve("request"));
            if (values.size() != 3 || !values.getFirst().equals(token.toString()))
                throw new IOException("Restart acknowledgement ownership is invalid");
            long parent = Long.parseLong(values.get(1));
            var start = Instant.parse(values.get(2));
            var process = ProcessHandle.of(parent);
            if (process.isEmpty() || !process.get().isAlive()
                || !process.get().info().startInstant().equals(Optional.of(start)))
                throw new IOException("Restart parent is no longer available");
            return Optional.of(new ApplicationRestartReceipt(directory, token, parent, start));
        } catch (RuntimeException invalid) {
            throw new IOException("Restart acknowledgement ownership is invalid");
        }
    }

    /** Returns only the two internal non-secret entry inputs. / 仅返回两个内部非秘密入口输入。
     * @return literal argument list / 字面参数清单 */
    List<String> arguments() {
        return List.of(DIRECTORY + directory, TOKEN + token);
    }

    /** Returns the owned argument-file location. / 返回所属参数文件位置。
     * @return original file / 原文件 */
    Path argumentFile() {
        return directory.resolve("launch.args");
    }

    /** Reads a bounded terminal result without assuming process creation means readiness. / 读取有界终态结果，不将创建进程当作就绪。
     * @return exact result, absent while startup remains incomplete / 精确结果，启动未完成时缺省
     * @throws IOException if the original result is malformed / 原结果格式错误时 */
    Optional<String> result() throws IOException {
        Path result = directory.resolve("result");
        if (!Files.exists(result, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (Files.isSymbolicLink(result) || Files.size(result) > 128)
            throw new IOException("Restart acknowledgement is invalid");
        var values = Files.readAllLines(result);
        if (values.size() != 2 || !values.get(1).equals(token.toString())
            || !List.of("READY", "FAILED").contains(values.getFirst()))
            throw new IOException("Restart acknowledgement is invalid");
        return Optional.of(values.getFirst());
    }

    /** Publishes once, then removes owned temporary files if its parent has exited. / 发布一次，并在父退出后删除所属临时文件。
     * @param state READY or FAILED / READY 或 FAILED
     * @throws IOException if the original acknowledgement cannot be published / 无法发布原确认时 */
    synchronized void signal(String state) throws IOException {
        if (signalled) return;
        if (!List.of("READY", "FAILED").contains(state)) throw new IOException("Invalid restart acknowledgement");
        Files.writeString(directory.resolve("result.tmp"), state + "\n" + token + "\n",
            StandardOpenOption.CREATE_NEW);
        Files.move(directory.resolve("result.tmp"), directory.resolve("result"), StandardCopyOption.ATOMIC_MOVE);
        signalled = true;
        Thread.ofVirtual().name("platform-restart-receipt").start(() -> {
            try {
                while (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    var original = ProcessHandle.of(parent);
                    if (original.isEmpty() || !original.get().isAlive()
                        || !original.get().info().startInstant().equals(Optional.of(parentStart))) {
                        cleanup();
                        return;
                    }
                    Thread.sleep(200);
                }
            } catch (IOException | InterruptedException ignored) {
                // Parent retains the original files if cleanup cannot be confirmed. / 无法确认清理时，父进程仍保留原文件。
            }
        });
    }

    /** Removes only known files from the original non-redirected directory. / 仅从原非重定向目录删除已知文件。
     * @throws IOException if ownership or cleanup cannot be confirmed / 无法确认归属或清理时 */
    void cleanup() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(directory) || !directory.equals(directory.toRealPath()))
            throw new IOException("Restart temporary directory changed");
        for (String name : List.of("launch.args", "result.tmp", "result", "request"))
            Files.deleteIfExists(directory.resolve(name));
        Files.deleteIfExists(directory);
    }
}
