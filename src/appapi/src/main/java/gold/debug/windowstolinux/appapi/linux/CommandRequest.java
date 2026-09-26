package gold.debug.windowstolinux.appapi.linux;

import java.time.Duration;

/**
 * Exact remote script with copied input and bounded output. / 带独立输入及输出上限的精确远端脚本。
 * @param script exact remote script covered by the request / 请求覆盖的精确远端脚本
 * @param stdin copied standard-input bytes / 标准输入字节副本
 * @param timeout strictly positive operation deadline / 严格为正的操作期限
 * @param outputLimitBytes nonnegative captured-output byte limit / 非负的捕获输出字节上限
 */
public record CommandRequest(String script, byte[] stdin, Duration timeout, long outputLimitBytes) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param script exact remote script covered by the request / 请求覆盖的精确远端脚本
     * @param stdin copied standard-input bytes / 标准输入字节副本
     * @param timeout strictly positive operation deadline / 严格为正的操作期限
     * @param outputLimitBytes nonnegative captured-output byte limit / 非负的捕获输出字节上限
     */
    public CommandRequest {
        java.util.Objects.requireNonNull(script, "script");
        stdin = java.util.Objects.requireNonNull(stdin, "stdin").clone();
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");
        if (outputLimitBytes < 0)
            throw new IllegalArgumentException("outputLimitBytes is below its minimum");
    }

    /**
     * Returns an independent byte copy. / 返回独立字节副本。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    @Override
    public byte[] stdin() {
        return stdin.clone();
    }
}
