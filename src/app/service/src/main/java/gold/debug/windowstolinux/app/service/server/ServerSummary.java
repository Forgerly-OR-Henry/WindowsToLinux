package gold.debug.windowstolinux.app.service.server;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/** Credential-free server card and dated connectivity evidence. / 服务器卡片及带时间的连接证据，不含凭据。 */
public record ServerSummary(ServerProfile profile, Optional<Instant> checkedAt, boolean connected, String operatingSystem) {
    /** Matches a display name or endpoint substring. / 按显示名称或端点子串匹配。 */
    public boolean matches(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return profile.displayName().toLowerCase(Locale.ROOT).contains(needle)
                || profile.host().toLowerCase(Locale.ROOT).contains(needle);
    }
}
