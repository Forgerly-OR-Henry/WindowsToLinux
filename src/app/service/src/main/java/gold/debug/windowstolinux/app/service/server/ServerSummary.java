package gold.debug.windowstolinux.app.service.server;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Credential-free server card and dated connectivity evidence. / 服务器卡片及带时间的连接证据，不含凭据。
 *
 * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
 * @param checkedAt checked at / 已检查时刻
 * @param connected connected / 已连接
 * @param operatingSystem operating system / 操作系统
 */
public record ServerSummary(ServerProfile profile, Optional<Instant> checkedAt, boolean connected, String operatingSystem) {
    /**
     * Matches a display name or endpoint substring. / 按显示名称或端点子串匹配。
     *
     * @param query query / 查询
     * @return true when matches a display name or endpoint substring, false otherwise / 按显示名称或端点子串匹配时为 true，否则为 false
     */
    public boolean matches(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return profile.displayName().toLowerCase(Locale.ROOT).contains(needle)
                || profile.host().toLowerCase(Locale.ROOT).contains(needle);
    }
}
