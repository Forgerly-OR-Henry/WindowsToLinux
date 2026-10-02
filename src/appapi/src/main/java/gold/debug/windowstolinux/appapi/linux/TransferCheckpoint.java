package gold.debug.windowstolinux.appapi.linux;

import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.server.ServerRef;

/** Durable original-transfer description; the host reloads its facts before recovery. / 持久原传输描述；恢复前由宿主重新加载其事实。
 * @param id original transfer identity / 原传输身份
 * @param server original saved server / 原已保存服务器
 * @param upload original direction / 原方向
 * @param request actual selected endpoints and original limit / 实际选定端点及原限额
 * @param sha256 original source digest / 原源摘要
 * @param size original source byte length / 原源字节长度
 * @param overwrite whether the original choice replaces an observed target / 原选择是否替换已观察目标 */
public record TransferCheckpoint(UUID id, ServerRef server, boolean upload, TransferRequest request, String sha256,
                                 long size, boolean overwrite) {
    /** Requires complete recovery review facts. / 要求完整恢复审核事实。
     * @param id original identity / 原身份
     * @param server saved server / 已保存服务器
     * @param upload direction / 方向
     * @param request selected endpoints / 选定端点
     * @param sha256 source digest / 源摘要
     * @param size source length / 源长度
     * @param overwrite original replacement choice / 原替换选择 */
    public TransferCheckpoint {
        Objects.requireNonNull(id);
        Objects.requireNonNull(server);
        Objects.requireNonNull(request);
        if (!Objects.requireNonNull(sha256).matches("[0-9a-f]{64}") || size < 0 || size > request.maxBytes())
            throw new IllegalArgumentException("Invalid transfer checkpoint facts");
    }
}
