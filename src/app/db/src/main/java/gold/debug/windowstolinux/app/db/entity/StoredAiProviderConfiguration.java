package gold.debug.windowstolinux.app.db.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Ordered enablement and verification metadata, separate from credentials. / 与凭据分离的有序启用及验证元数据。 */
public record StoredAiProviderConfiguration(StoredAiProviderProfile profile, String name, boolean enabled, int priority, Optional<Instant> verifiedAt) {
    /** Bounds configuration labels and ordering. / 限制配置名称及顺序。 */
    public StoredAiProviderConfiguration {
        Objects.requireNonNull(profile); name = Objects.requireNonNull(name).trim(); Objects.requireNonNull(verifiedAt);
        if (name.isEmpty() || name.length() > 120 || priority < 0) throw new IllegalArgumentException("invalid AI configuration metadata");
    }
}
