package gold.debug.windowstolinux.app.service.execution.lifecycle;

import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

/**
 * Unified inventory metadata with explicit deployment, adoption and observation dates. / 明确区分部署、接管与观测时间的统一清单元数据。
 *
 * @param key lookup key within the current contract / 当前契约内的查找键
 * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
 * @param category category / 类别
 * @param serverId persisted server identifier / 持久化服务器标识
 * @param serverName server name / 服务器名称
 * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
 * @param deployedAt deployed at / 已部署时刻
 * @param adoptedAt adopted at / 已接管时刻
 * @param lastState last state / 上次状态
 * @param observedAt observed at / 已观测时刻
 * @param accessUrl access url / 访问URL
 * @param external external / 外部
 * @param canStart can start / 能够启动
 * @param canStop can stop / 能够停止
 * @param needsMasterPassword needs master password / 需要主密码
 * @param usage usage / 用法
 */
public record ApplicationSummary(String key, String name, String category, String serverId, String serverName, String host,
                                 Optional<Instant> deployedAt, Optional<Instant> adoptedAt, RuntimeState lastState,
                                 Optional<Instant> observedAt, Optional<UserAccessUrl> accessUrl, boolean external,
                                 boolean canStart, boolean canStop, boolean needsMasterPassword,
                                 Optional<gold.debug.windowstolinux.shared.model.managed.ApplicationUsage> usage) {
    /**
     * Initializes application summary through its shared constructor contract.
     * <p>通过共享构造契约初始化应用摘要。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param category category / 类别
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param serverName server name / 服务器名称
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param deployedAt deployed at / 已部署时刻
     * @param adoptedAt adopted at / 已接管时刻
     * @param lastState last state / 上次状态
     * @param observedAt observed at / 已观测时刻
     * @param accessUrl access url / 访问URL
     * @param external external / 外部
     * @param canStart can start / 能够启动
     * @param canStop can stop / 能够停止
     * @param needsMasterPassword needs master password / 需要主密码
     */
    public ApplicationSummary(String key, String name, String category, String serverId, String serverName, String host,
            Optional<Instant> deployedAt, Optional<Instant> adoptedAt, RuntimeState lastState, Optional<Instant> observedAt,
            Optional<UserAccessUrl> accessUrl, boolean external, boolean canStart, boolean canStop, boolean needsMasterPassword) {
        this(key, name, category, serverId, serverName, host, deployedAt, adoptedAt, lastState, observedAt,
                accessUrl, external, canStart, canStop, needsMasterPassword, Optional.empty());
    }
    /**
     * Newest successful deployment or explicitly labeled adoption first, then stable ID. / 成功部署或明确标记的接管时间倒序，同时间按稳定 ID 排序。
     *
     * @return constructed or resolved comparator / 构造或解析得到的Comparator
     */
    public static Comparator<ApplicationSummary> newestFirst() {
        return Comparator.comparing((ApplicationSummary value) -> value.deployedAt.or(() -> value.adoptedAt).orElse(Instant.MIN))
                .reversed().thenComparing(ApplicationSummary::key);
    }
    /**
     * Combines type and server filtering. / 组合类型与服务器筛选。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @return true when combines type and server filtering, false otherwise / 组合类型与服务器筛选时为 true，否则为 false
     */
    public boolean matches(String type, String server) {
        return (type.isEmpty() || category.equals(type)) && (server.isEmpty() || serverId.equals(server));
    }
}
