package gold.debug.windowstolinux.app.service.setting;

import java.time.Duration;

/** Non-secret platform thresholds captured once per logical network operation. / 每个逻辑网络操作只捕获一次的非秘密平台阈值。
 * @param connectSeconds maximum connection and authentication waiting / 连接及认证最长等待秒数
 * @param idleSeconds maximum interval without effective file-byte progress / 无有效文件字节进展的最长秒数 */
public record TransferConfig(int connectSeconds, int idleSeconds) {
    /** Default independent connection and stall thresholds. / 默认独立连接及停滞阈值。 */
    public static final TransferConfig DEFAULTS = new TransferConfig(30, 300);

    /** Largest supported whole-second threshold, one day. / 支持的最大整数秒阈值，一天。 */
    public static final int MAX_SECONDS = 86400;

    /** Requires positive bounded whole-second settings. / 要求正值有界整数秒设置。
     * @param connectSeconds connection threshold / 连接阈值
     * @param idleSeconds no-progress threshold / 无进展阈值 */
    public TransferConfig {
        if (connectSeconds < 1 || connectSeconds > MAX_SECONDS || idleSeconds < 1 || idleSeconds > MAX_SECONDS)
            throw new IllegalArgumentException("Transfer thresholds must be between 1 and 86400 seconds");
    }

    /** Returns the independent connection deadline. / 返回独立连接期限。
     * @return connection duration / 连接时长 */
    public Duration connectTimeout() {
        return Duration.ofSeconds(connectSeconds);
    }

    /** Returns the effective-byte stall threshold without a total transfer deadline. / 返回有效字节停滞阈值，不设置传输总期限。
     * @return no-progress duration / 无进展时长 */
    public Duration idleTimeout() {
        return Duration.ofSeconds(idleSeconds);
    }
}
