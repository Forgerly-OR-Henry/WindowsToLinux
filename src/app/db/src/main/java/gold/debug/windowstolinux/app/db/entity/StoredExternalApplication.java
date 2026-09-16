package gold.debug.windowstolinux.app.db.entity;

import gold.debug.windowstolinux.shared.model.lifecycle.*;
import java.time.Instant;
import java.util.Objects;

/** External lifecycle registration, separate from the strict managed deployment contract. / 独立于严格受管部署契约的外部生命周期登记。 */
public record StoredExternalApplication(String id, String serverId, String host, int sshPort, String username,
                                        DiscoveredApplication application, Instant adoptedAt, Instant observedAt) {
    /** Validates durable registration fields. / 校验持久登记字段。 */
    public StoredExternalApplication {
        if (!Objects.requireNonNull(id).matches("external:[a-f0-9-]{36}")) throw new IllegalArgumentException("invalid external registration id");
        Objects.requireNonNull(serverId); Objects.requireNonNull(host); Objects.requireNonNull(username);
        Objects.requireNonNull(application); Objects.requireNonNull(adoptedAt); Objects.requireNonNull(observedAt);
        if (application.managed()) throw new IllegalArgumentException("managed ownership must not be bypassed by external registration");
    }
}
