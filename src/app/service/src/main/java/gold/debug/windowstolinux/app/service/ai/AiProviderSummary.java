package gold.debug.windowstolinux.app.service.ai;

import java.time.Instant;
import java.util.Optional;

/** Credential-free ordered model card. / 不含凭据的有序模型卡片。 */
public record AiProviderSummary(AiProviderProfile profile, String name, boolean enabled, int priority, Optional<Instant> verifiedAt) { }
