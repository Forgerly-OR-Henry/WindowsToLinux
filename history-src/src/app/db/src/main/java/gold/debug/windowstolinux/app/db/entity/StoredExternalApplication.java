package gold.debug.windowstolinux.app.db.entity;

import java.time.Instant;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.lifecycle.*;

/**
 * External lifecycle registration, separate from the strict managed deployment contract. / 独立于严格受管部署契约的外部生命周期登记。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param serverId persisted server identifier / 持久化服务器标识
 * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
 * @param sshPort ssh port / SSH端口
 * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
 * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
 * @param adoptedAt adopted at / 已接管时刻
 * @param observedAt observed at / 已观测时刻
 */
public record StoredExternalApplication(String id, String serverId, String host, int sshPort, String username,
        DiscoveredApplication application, Instant adoptedAt, Instant observedAt) {
    /**
     * Validates durable registration fields. / 校验持久登记字段。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param sshPort ssh port / SSH端口
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param adoptedAt adopted at / 已接管时刻
     * @param observedAt observed at / 已观测时刻
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public StoredExternalApplication {
        if (!Objects.requireNonNull(id).matches("external:[a-f0-9-]{36}"))
            throw new IllegalArgumentException("invalid external registration id");
        Objects.requireNonNull(serverId);
        Objects.requireNonNull(host);
        Objects.requireNonNull(username);
        Objects.requireNonNull(application);
        Objects.requireNonNull(adoptedAt);
        Objects.requireNonNull(observedAt);
        if (application.managed())
            throw new IllegalArgumentException("managed ownership must not be bypassed by external registration");
    }
}
