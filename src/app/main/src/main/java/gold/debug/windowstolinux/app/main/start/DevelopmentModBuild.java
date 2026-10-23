package gold.debug.windowstolinux.app.main.start;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Rebuilds the source reactor before CLASS Mod discovery. / 在 CLASS Mod 发现前重新构建源码 reactor。 */
final class DevelopmentModBuild {
    /** Prevents creating a stateless build coordinator. / 防止创建无状态构建协调器。 */
    private DevelopmentModBuild() {
    }

    /** Runs the existing Maven development lifecycle without executing tests. / 运行现有 Maven 开发生命周期，不执行测试。
     * @param workspace validated original workspace / 已验证的原工作区
     * @throws Exception if compilation or its process cannot complete / 编译或进程无法完成时 */
    static void rebuild(Path workspace) throws Exception {
        rebuild(workspace, () -> false);
    }

    /** Cancels this build if its startup window closes. / 启动窗口关闭时取消本次构建。
     * @param workspace validated original workspace / 已验证的原工作区
     * @param cancelled whether startup has ended / 启动是否已结束
     * @throws Exception if compilation cannot complete / 编译无法完成时 */
    static void rebuild(Path workspace, BooleanSupplier cancelled) throws Exception {
        Path log = workspace.resolve("target/mod-startup-build.log");
        try {
            var command = command(workspace, Path.of(System.getProperty("java.home")),
                mavenHome(System.getProperty("maven.home"), System.getenv()));
            Files.createDirectories(log.getParent());
            try (var channel = FileChannel.open(log.resolveSibling("mod-startup-build.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                if (lock == null)
                    throw failure("Another CLASS startup is compiling this workspace; retry after it finishes", log);
                execute(new ProcessBuilder(command).directory(workspace.toFile()), log, cancelled);
            }
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception failure) {
            var reported = failure("Development Mod compilation could not start; check the JDK and Maven configuration", log);
            reported.initCause(failure);
            throw reported;
        }
    }

    /** Finds an installed Maven without relying on a shell or the working directory. / 不依赖 shell 或工作目录查找已安装 Maven。
     * @param configured Maven home system property / Maven 主目录系统属性
     * @param environment process environment / 进程环境
     * @return verified Maven home / 已验证的 Maven 主目录
     * @throws IOException if no installation is available / 没有可用安装时 */
    static Path mavenHome(String configured, Map<String, String> environment) throws IOException {
        var variables = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        variables.putAll(environment);
        for (String value : List.of(configured == null ? "" : configured,
            variables.getOrDefault("MAVEN_HOME", ""), variables.getOrDefault("M2_HOME", ""))) {
            if (!value.isBlank()) return verifyMaven(Path.of(value));
        }
        for (String value : variables.getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (value.isBlank()) continue;
            Path bin = Path.of(value.replace("\"", "")).toAbsolutePath().normalize();
            if (Files.isRegularFile(bin.resolve("mvn.cmd")) || Files.isRegularFile(bin.resolve("mvn")))
                return verifyMaven(bin.getParent());
        }
        throw new IOException("Maven requires maven.home, MAVEN_HOME, M2_HOME or mvn on PATH");
    }

    /** Validates Maven's actual launcher files. / 校验 Maven 的实际启动文件。
     * @param home proposed installation / 待校验安装
     * @return canonical installation / 规范安装
     * @throws IOException if incomplete / 安装不完整时 */
    private static Path verifyMaven(Path home) throws IOException {
        Path result = home.toRealPath();
        if (!Files.isRegularFile(result.resolve("bin/m2.conf")))
            throw new IOException("Maven configuration is missing");
        bootstrap(result);
        return result;
    }

    /** Requires one unambiguous Maven bootstrap. / 要求唯一明确的 Maven 引导器。
     * @param home verified installation / 已验证安装
     * @return actual bootstrap JAR / 实际引导 JAR
     * @throws IOException if missing or ambiguous / 缺失或歧义时 */
    private static Path bootstrap(Path home) throws IOException {
        try (var files = Files.list(home.resolve("boot"))) {
            var jars = files.filter(path -> path.getFileName().toString().matches("plexus-classworlds-.+\\.jar"))
                .filter(Files::isRegularFile).toList();
            if (jars.size() != 1) throw new IOException("Maven bootstrap is missing or ambiguous");
            return jars.getFirst();
        }
    }

    /** Uses literal Java arguments so spaces and shell metacharacters remain paths. / 使用 Java 字面参数，使空格与 shell 元字符保持路径语义。
     * @param workspace original source root / 原源码根
     * @param javaHome current application JDK / 当前应用 JDK
     * @param maven installed Maven / 已安装 Maven
     * @return complete child command / 完整子进程命令
     * @throws IOException if the compiler or launcher is unavailable / 编译器或启动器不可用时 */
    static List<String> command(Path workspace, Path javaHome, Path maven) throws IOException {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        Path java = javaHome.resolve("bin/java" + suffix);
        if (!Files.isRegularFile(java) || !Files.isRegularFile(javaHome.resolve("bin/javac" + suffix)))
            throw new IOException("CLASS Mod compilation requires a JDK");
        return List.of(java.toString(), "-Dmaven.home=" + maven, "-Dclassworlds.conf=" + maven.resolve("bin/m2.conf"),
            "-Dmaven.multiModuleProjectDirectory=" + workspace, "-classpath", bootstrap(maven).toString(),
            "org.codehaus.plexus.classworlds.launcher.Launcher", "-B", "-ntp", "-f", workspace.resolve("pom.xml").toString(),
            "-DskipTests", "process-test-classes");
    }

    /** Waits off the EDT and retains compiler output for diagnosis. / 在 EDT 外等待并保留编译输出供诊断。
     * @param builder literal child process / 字面子进程
     * @param log build output location / 构建输出位置
     * @throws Exception if failed, interrupted or timed out / 失败、中断或超时时 */
    static void execute(ProcessBuilder builder, Path log) throws Exception {
        execute(builder, log, () -> false);
    }

    /** Observes closing while waiting for the original child. / 等待原子进程时观察关闭状态。
     * @param builder literal child process / 字面子进程
     * @param log build output location / 构建输出位置
     * @param cancelled whether startup has ended / 启动是否已结束
     * @throws Exception if failed, cancelled, interrupted or timed out / 失败、取消、中断或超时时 */
    static void execute(ProcessBuilder builder, Path log, BooleanSupplier cancelled) throws Exception {
        PlatformBuildIdentity.requireWorker();
        System.out.println("Rebuilding development Mods: " + log);
        var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var shutdown = new Thread(() -> stop(process), "development-mod-build-shutdown");
        boolean attached = false;
        try {
            Runtime.getRuntime().addShutdownHook(shutdown);
            attached = true;
            process.getOutputStream().close();
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            while (true) {
                if (cancelled.getAsBoolean())
                    throw failure("Development Mod compilation was cancelled because startup closed", log);
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() >= deadline)
                    throw failure("Development Mod compilation timed out; no development Mods were loaded", log);
            }
            if (process.exitValue() != 0)
                throw failure("Development Mod compilation failed (exit " + process.exitValue()
                    + "); no development Mods were loaded; see the build log", log);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("Development Mod compilation was interrupted; no development Mods were loaded", log);
        } finally {
            stop(process);
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            if (attached) try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException closing) {
                // The registered hook still owns cleanup during JVM shutdown. / JVM 关闭时已注册钩子仍持有清理职责。
            }
        }
    }

    /** Terminates only this build and its descendants. / 仅终止本次构建及其后代。
     * @param process original build / 原构建 */
    private static void stop(Process process) {
        if (!process.isAlive()) return;
        var owned = new ArrayList<>(process.descendants().toList());
        owned.add(process.toHandle());
        for (var child : owned.reversed()) child.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Creates safe startup evidence with an exact build-log location. / 创建附精确构建日志位置的安全启动证据。
     * @param message safe explanation / 安全说明
     * @param log original build log / 原构建日志
     * @return structured rejection / 结构化拒绝 */
    private static ApiException failure(String message, Path log) {
        return new ApiException(new Problem(ProblemCode.UNAVAILABLE, "MOD_DEVELOPMENT_BUILD_FAILED", message,
            Map.of("log", log.toString()), Optional.of(UUID.randomUUID())));
    }

    /** Internal startup injection point for already compiled lifecycle fixtures. / 已编译生命周期夹具的内部启动注入点。 */
    @FunctionalInterface
    interface Compiler {
        /** Rebuilds before discovery is allowed. / 在允许发现前重新构建。
         * @param workspace verified workspace / 已验证工作区
         * @throws Exception if compilation fails / 编译失败时 */
        void rebuild(Path workspace) throws Exception;
    }
}
