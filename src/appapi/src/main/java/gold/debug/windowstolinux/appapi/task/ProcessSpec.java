package gold.debug.windowstolinux.appapi.task;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Literal process arguments and bounded handshake settings. / 字面进程参数及有界握手配置。
 * @param executable absolute executable path / 可执行文件的绝对路径
 * @param arguments copied process argument list / 独立的进程参数列表
 * @param environment environment supplied to the owned component / 提供给所属组件的环境
 * @param workingDirectory absolute child-process working directory / 子进程的绝对工作目录
 * @param handshakeTimeout handshake deadline from one to sixty seconds / 一到六十秒的握手期限
 */
public record ProcessSpec(Path executable, List<String> arguments, Map<String, String> environment,
        Path workingDirectory, Duration handshakeTimeout) {
    /** Uses the documented ten-second default handshake deadline. / 使用文档规定的十秒默认握手期限。
     * @param executable absolute executable path / 可执行文件绝对路径
     * @param arguments literal argument list / 字面参数列表
     * @param environment explicitly supplied child environment / 明确提供的子进程环境
     * @param workingDirectory absolute working directory / 绝对工作目录 */
    public ProcessSpec(Path executable, List<String> arguments, Map<String, String> environment,
            Path workingDirectory) {
        this(executable, arguments, environment, workingDirectory, Duration.ofSeconds(10));
    }

    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param executable absolute executable path / 可执行文件的绝对路径
     * @param arguments copied process argument list / 独立的进程参数列表
     * @param environment environment supplied to the owned component / 提供给所属组件的环境
     * @param workingDirectory absolute child-process working directory / 子进程的绝对工作目录
     * @param handshakeTimeout handshake deadline from one to sixty seconds / 一到六十秒的握手期限
     */
    public ProcessSpec {
        java.util.Objects.requireNonNull(executable, "executable");
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
        java.util.Objects.requireNonNull(workingDirectory, "workingDirectory");
        java.util.Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        if (handshakeTimeout.isZero() || handshakeTimeout.isNegative())
            throw new IllegalArgumentException("handshakeTimeout must be positive");
        if (!executable.isAbsolute() || !workingDirectory.isAbsolute())
            throw new IllegalArgumentException("Process paths must be absolute");
        if (handshakeTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || handshakeTimeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Handshake timeout must be 1 to 60 seconds");
    }
}
