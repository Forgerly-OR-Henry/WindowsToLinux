package gold.debug.windowstolinux.app.main.start;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.main.AppMain;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;

/** Exact non-secret launch inputs for a fresh desktop process. / 新桌面进程的精确非秘密启动输入。
 * @param executable original Java runtime or application launcher / 原 Java 运行时或应用启动器
 * @param arguments explicit Java inputs, without inherited VM flags / 明确 Java 输入，不继承 VM 标志
 * @param directory verified program directory / 已验证程序目录
 * @param javaLauncher whether an argument file is required / 是否使用 Java 参数文件 */
record ApplicationRestartLaunch(Path executable, List<String> arguments, Path directory, boolean javaLauncher) {
    /** Copies the bounded launch inputs. / 复制有界启动输入。
     * @param executable verified executable / 已验证可执行文件
     * @param arguments explicit arguments / 明确参数
     * @param directory verified directory / 已验证目录
     * @param javaLauncher Java argument-file mode / Java 参数文件模式 */
    ApplicationRestartLaunch {
        arguments = List.copyOf(arguments);
    }

    /** Captures actual source and classpath without copying process arguments. / 捕获实际代码来源及类路径，不复制进程参数。
     * @param layout current verified runtime layout / 当前已验证运行布局
     * @return restart inputs / 重启输入
     * @throws Exception if a safe exact entry cannot be established / 无法确认安全精确入口时 */
    static ApplicationRestartLaunch capture(RunModeResolver.RuntimeLayout layout) throws Exception {
        return fromEvidence(layout, Path.of(System.getProperty("java.home")),
            ProcessHandle.current().info().command().map(Path::of),
            Path.of(AppMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
            System.getProperty("java.class.path", ""), Path.of("").toAbsolutePath());
    }

    /** Resolves the three admitted modes from explicit deterministic evidence. / 按明确确定性证据解析三种已准入模式。
     * @param layout current layout / 当前布局
     * @param javaHome actual Java home / 实际 Java 目录
     * @param command actual process executable / 实际进程可执行文件
     * @param source actual main code source / 实际主入口代码来源
     * @param classpath actual runtime classpath / 实际运行类路径
     * @param workingDirectory original classpath base / 原类路径基准
     * @return validated immutable description / 已验证不可变描述
     * @throws IOException if an input is missing or inconsistent / 输入缺失或不一致时 */
    static ApplicationRestartLaunch fromEvidence(RunModeResolver.RuntimeLayout layout, Path javaHome,
                                                 Optional<Path> command, Path source, String classpath,
                                                 Path workingDirectory) throws IOException {
        Path home = layout.applicationHome().toRealPath();
        Path main = source.toRealPath();
        if (layout.mode() == RunModeResolver.RunMode.RUN_APP) {
            Path executable = command.orElseThrow(() -> invalid("Application launcher is unavailable")).toRealPath();
            if (!Files.isRegularFile(executable) || !executable.getParent().equals(home)
                || !executable.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                || !Files.isDirectory(home.resolve("app")) || !Files.isDirectory(home.resolve("runtime"))
                || !Files.isRegularFile(main) || !main.getParent().equals(home.resolve("app")))
                throw invalid("Application launcher does not match the running image");
            return new ApplicationRestartLaunch(executable, List.of(), home, false);
        }
        if (layout.mode() == RunModeResolver.RunMode.RUN_CLASS) {
            if (!Files.isDirectory(main) || !main.startsWith(home)
                || !Files.isRegularFile(home.resolve("pom.xml"))
                || !Files.isRegularFile(home.resolve("src/app/main/pom.xml"))
                || !Files.isRegularFile(home.resolve("src/app/db/pom.xml")))
                throw invalid("Development entry does not match the running workspace");
        } else if (layout.mode() == RunModeResolver.RunMode.RUN_JAR) {
            if (!Files.isRegularFile(main) || !main.getParent().equals(home)
                || !main.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                throw invalid("Main JAR does not match the running program");
        } else {
            throw invalid("The running mode cannot be restarted");
        }
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path java = javaHome.toRealPath().resolve("bin").resolve(windows ? "javaw.exe" : "java");
        if (!Files.isRegularFile(java))
            throw invalid("The running Java launcher is unavailable");
        var entries = new ArrayList<String>();
        boolean includesMain = false;
        for (String value : classpath.split(Pattern.quote(File.pathSeparator), -1)) {
            if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
                throw invalid("The actual classpath cannot be reproduced");
            boolean wildcard = value.endsWith("*");
            Path entry = workingDirectory.resolve(wildcard ? value.substring(0, value.length() - 1) : value)
                .toAbsolutePath().normalize().toRealPath();
            if (wildcard && !Files.isDirectory(entry))
                throw invalid("The actual classpath cannot be reproduced");
            includesMain |= wildcard ? Files.isRegularFile(main) && main.getParent().equals(entry) : main.equals(entry);
            entries.add(wildcard ? entry + File.separator + "*" : entry.toString());
        }
        if (!includesMain)
            throw invalid("The actual classpath does not contain the running main entry");
        var arguments = new ArrayList<String>();
        arguments.add("-cp");
        arguments.add(String.join(File.pathSeparator, entries));
        arguments.add(AppMain.class.getName());
        return new ApplicationRestartLaunch(java, arguments, home, true);
    }

    /** Quotes one complete Java argument-file token. / 转义一个完整 Java 参数文件令牌。
     * @param value literal argument / 字面参数
     * @return quoted token / 已引用令牌 */
    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    /** Produces a bounded failure without repeating paths or arguments. / 生成有界失败，不重复路径或参数。
     * @param message safe fixed reason / 安全固定原因
     * @return launch validation failure / 启动校验失败 */
    private static IOException invalid(String message) {
        return new IOException(message);
    }
}
