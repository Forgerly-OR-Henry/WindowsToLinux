package gold.debug.windowstolinux.appapi.linux;

import java.util.Optional;

/**
 * Observed remote output without inferring an unobserved exit. / 不推断未观察退出状态的远端输出。
 * @param exitCode observed process exit code when available / 可用时报告已观察的进程退出码
 * @param stdout captured standard output / 捕获的标准输出
 * @param stderr captured standard error / 捕获的标准错误输出
 * @param truncated whether output exceeded its retained limit / 输出是否超过保留上限
 */
public record CommandResult(Optional<Integer> exitCode, String stdout, String stderr, boolean truncated) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param exitCode observed process exit code when available / 可用时报告已观察的进程退出码
     * @param stdout captured standard output / 捕获的标准输出
     * @param stderr captured standard error / 捕获的标准错误输出
     * @param truncated whether output exceeded its retained limit / 输出是否超过保留上限
     */
    public CommandResult {
        java.util.Objects.requireNonNull(exitCode, "exitCode");
        java.util.Objects.requireNonNull(stdout, "stdout");
        java.util.Objects.requireNonNull(stderr, "stderr");
    }
}
