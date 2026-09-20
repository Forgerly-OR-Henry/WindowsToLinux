package gold.debug.windowstolinux.app.service.execution.lifecycle;

import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

/** Unified inventory metadata with explicit deployment, adoption and observation dates. / 明确区分部署、接管与观测时间的统一清单元数据。 */
public record ApplicationSummary(String key, String name, String category, String serverId, String serverName, String host,
                                 Optional<Instant> deployedAt, Optional<Instant> adoptedAt, RuntimeState lastState,
                                 Optional<Instant> observedAt, Optional<UserAccessUrl> accessUrl, boolean external,
                                 boolean canStart, boolean canStop, boolean needsMasterPassword,
                                 Optional<gold.debug.windowstolinux.shared.model.managed.ApplicationUsage> usage) {
    public ApplicationSummary(String key, String name, String category, String serverId, String serverName, String host,
            Optional<Instant> deployedAt, Optional<Instant> adoptedAt, RuntimeState lastState, Optional<Instant> observedAt,
            Optional<UserAccessUrl> accessUrl, boolean external, boolean canStart, boolean canStop, boolean needsMasterPassword) {
        this(key, name, category, serverId, serverName, host, deployedAt, adoptedAt, lastState, observedAt,
                accessUrl, external, canStart, canStop, needsMasterPassword, Optional.empty());
    }
    /** Newest successful deployment or explicitly labeled adoption first, then stable ID. / 成功部署或明确标记的接管时间倒序，同时间按稳定 ID 排序。 */
    public static Comparator<ApplicationSummary> newestFirst() {
        return Comparator.comparing((ApplicationSummary value) -> value.deployedAt.or(() -> value.adoptedAt).orElse(Instant.MIN))
                .reversed().thenComparing(ApplicationSummary::key);
    }
    /** Combines type and server filtering. / 组合类型与服务器筛选。 */
    public boolean matches(String type, String server) {
        return (type.isEmpty() || category.equals(type)) && (server.isEmpty() || serverId.equals(server));
    }
}
