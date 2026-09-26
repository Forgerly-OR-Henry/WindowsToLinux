package gold.debug.windowstolinux.appapi.linux;

import java.time.Duration;
import java.util.Objects;

/** Original file operation stopped after its configured interval without effective byte progress. / 原文件操作在配置时长内无有效字节进展后已停止。
 * @param progress last physical stage and verified progress / 最后物理阶段及已核验进度
 * @param request exact current selected endpoints / 精确当前选定端点
 * @param upload original transfer direction / 原传输方向
 * @param idleTimeout configured no-progress interval / 配置的无进展时长 */
public record TransferStall(TransferProgress progress, TransferRequest request, boolean upload, Duration idleTimeout) {
    /** Requires the original facts and positive configured threshold. / 要求原事实及配置的正阈值。
     * @param progress last observed progress / 最后已观察进度
     * @param request selected endpoints / 选定端点
     * @param upload direction / 方向
     * @param idleTimeout no-progress threshold / 无进展阈值 */
    public TransferStall {
        Objects.requireNonNull(progress);
        Objects.requireNonNull(request);
        if (Objects.requireNonNull(idleTimeout).isNegative() || idleTimeout.isZero())
            throw new IllegalArgumentException("No-progress threshold must be positive");
    }
}
